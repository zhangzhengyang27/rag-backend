package org.example.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RuntimeConfigStore;
import org.example.entity.KbEvalReportEntity;
import org.example.repository.KbEvalReportRepository;
import org.example.service.KbAlertService;
import org.example.service.KbEvalService;
import org.example.vo.KbEvalResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 评测定时任务：每晚对公共知识库（default）跑全量评测并落库趋势报告，
 * 供管理页观察检索质量回归；命中率低于运行时配置阈值时产生运营告警。
 * 评测线程无登录态，空间必须显式传入。
 */
@Slf4j
@Component
public class KbEvalScheduler {

    /** 评测目标：公共知识库空间（个人空间内容由用户自查，不纳入质量基线） */
    private static final String EVAL_LIBRARY = "default";

    private final KbEvalService kbEvalService;
    private final KbEvalReportRepository reportRepository;
    private final KbAlertService kbAlertService;
    private final RuntimeConfigStore runtimeConfig;
    private final ObjectMapper objectMapper;

    public KbEvalScheduler(KbEvalService kbEvalService,
                           KbEvalReportRepository reportRepository,
                           KbAlertService kbAlertService,
                           RuntimeConfigStore runtimeConfig,
                           ObjectMapper objectMapper) {
        this.kbEvalService = kbEvalService;
        this.reportRepository = reportRepository;
        this.kbAlertService = kbAlertService;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 每晚 02:00 跑全量评测并落库；当天已有报告则跳过（幂等，避免重复执行覆盖/堆积）。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runDailyEval() {
        String today = LocalDate.now().toString();
        log.info("定时评测开始 - date: {}, library: {}", today, EVAL_LIBRARY);
        try {
            if (reportRepository.findByDateAndLibraryId(today, EVAL_LIBRARY).isPresent()) {
                log.info("今日评测报告已存在，跳过 - date: {}", today);
                return;
            }
            KbEvalResult result = kbEvalService.run(EVAL_LIBRARY, null, 5);
            KbEvalReportEntity report = new KbEvalReportEntity();
            report.setDate(today);
            report.setLibraryId(EVAL_LIBRARY);
            report.setCaseCount(result.getTotal());
            report.setHitRate(result.getHitRate());
            report.setMrr(result.getMrr());
            report.setNdcg(result.getNdcg());
            report.setSnapshot(toSnapshotJson(result));
            reportRepository.save(report);
            log.info("定时评测完成并落库 - date: {}, caseCount: {}, 命中率: {}%, MRR: {}, NDCG: {}",
                    today, result.getTotal(), Math.round(result.getHitRate() * 1000) / 10.0,
                    result.getMrr(), result.getNdcg());

            // 质量告警：命中率低于运行时配置阈值（阈值可在管理端配置中心在线调整）
            double threshold = runtimeConfig.getDouble("eval.hitrate-threshold");
            if (result.getTotal() > 0 && result.getHitRate() < threshold) {
                kbAlertService.raise(EVAL_LIBRARY, "EVAL_LOW_HITRATE", "WARN",
                        String.format("评测命中率 %.1f%% 低于阈值 %.1f%%",
                                result.getHitRate() * 100, threshold * 100),
                        toSnapshotJson(result));
            }
        } catch (Exception e) {
            log.warn("定时评测失败 - date: {}, err: {}", today, e.getMessage());
        }
    }

    /** 评测快照：未命中用例的 question/missingKeywords/topTitles，便于事后回溯归因。 */
    private String toSnapshotJson(KbEvalResult result) {
        try {
            List<Map<String, Object>> misses = result.getCases().stream()
                    .filter(c -> !c.isHit())
                    .map(c -> Map.of(
                            "question", c.getQuestion(),
                            "missingKeywords", c.getMissingKeywords(),
                            "topTitles", c.getTopTitles()))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(Map.of("missCount", misses.size(), "misses", misses));
        } catch (Exception e) {
            log.warn("评测快照序列化失败 - err: {}", e.getMessage());
            return "{}";
        }
    }
}

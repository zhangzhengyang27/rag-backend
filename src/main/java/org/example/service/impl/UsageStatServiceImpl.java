package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.ChatUsageEntity;
import org.example.repository.ChatUsageRepository;
import org.example.service.UsageStatService;
import org.example.vo.ChatUsageView;
import org.example.vo.UsageSummaryView;
import org.example.vo.UsageTrendPoint;
import org.example.vo.UserUsageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天用量统计实现：记录走轻量同步旁路写（低频率、失败仅记日志）；
 * 汇总/趋势因数据量小，在 Java 侧按日聚合，避免 JPQL 方言函数兼容问题。
 */
@Slf4j
@Service
public class UsageStatServiceImpl implements UsageStatService {

    // SimpleDateFormat 非线程安全，管理端并发汇总时会日期错乱；DateTimeFormatter 不可变可安全共享
    private static final java.time.format.DateTimeFormatter DAY_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault());

    private final ChatUsageRepository usageRepository;

    public UsageStatServiceImpl(ChatUsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @Override
    public void record(String libraryId, String userId, String conversationId, String question, String answer,
                       int inputTokens, int outputTokens, long elapsedMs, int hitCount) {
        try {
            ChatUsageEntity e = new ChatUsageEntity();
            e.setLibraryId(libraryId);
            e.setUserId(userId);
            e.setConversationId(conversationId);
            e.setQuestion(question);
            e.setAnswerCharCount(answer == null ? 0 : answer.length());
            e.setInputTokens(inputTokens);
            e.setOutputTokens(outputTokens);
            e.setTotalTokens(inputTokens + outputTokens);
            e.setElapsedMs(elapsedMs);
            e.setHitCount(hitCount);
            usageRepository.save(e);
        } catch (Exception ex) {
            log.warn("记录聊天用量失败 - userId: {}, err: {}", userId, ex.getMessage());
        }
    }

    @Override
    public UsageSummaryView summary(String libraryId, int days) {
        int n = Math.max(1, Math.min(days, 365));
        Date from = daysAgo(n);
        List<ChatUsageEntity> records = usageRepository
                .findByLibraryIdAndCreatedDateAfterOrderByIdAsc(libraryId, from);
        long questions = records.size();
        long input = records.stream().mapToLong(e -> intOf(e.getInputTokens())).sum();
        long output = records.stream().mapToLong(e -> intOf(e.getOutputTokens())).sum();
        double avgLatency = questions == 0 ? 0 : records.stream()
                .mapToDouble(e -> e.getElapsedMs() == null ? 0 : e.getElapsedMs()).average().orElse(0);
        double avgHit = questions == 0 ? 0 : records.stream()
                .mapToDouble(e -> intOf(e.getHitCount())).average().orElse(0);

        // 按日聚合趋势（LinkedHashMap 保序，最后按日期倒序返回）
        Map<String, TrendAgg> byDay = new LinkedHashMap<>();
        for (ChatUsageEntity e : records) {
            String day = DAY_FMT.format(e.getCreatedDate().toInstant());
            byDay.computeIfAbsent(day, k -> new TrendAgg()).add(e);
        }
        List<UsageTrendPoint> trend = new ArrayList<>(byDay.size());
        for (Map.Entry<String, TrendAgg> entry : byDay.entrySet()) {
            TrendAgg agg = entry.getValue();
            trend.add(UsageTrendPoint.builder()
                    .date(entry.getKey())
                    .questions(agg.count)
                    .tokens(agg.tokens)
                    .avgLatencyMs(round1(agg.count == 0 ? 0 : (double) agg.latency / agg.count))
                    .avgHitCount(round1(agg.count == 0 ? 0 : (double) agg.hit / agg.count))
                    .build());
        }
        trend.sort((a, b) -> b.getDate().compareTo(a.getDate()));

        return UsageSummaryView.builder()
                .totalQuestions(questions)
                .totalInputTokens(input)
                .totalOutputTokens(output)
                .totalTokens(input + output)
                .avgLatencyMs(round1(avgLatency))
                .avgHitCount(round1(avgHit))
                .trend(trend)
                .build();
    }

    @Override
    public Page<ChatUsageView> recent(String libraryId, Pageable page) {
        Page<ChatUsageEntity> entityPage = usageRepository.findByLibraryIdOrderByIdDesc(libraryId, page);
        List<ChatUsageView> views = entityPage.getContent().stream().map(this::toView).toList();
        return new PageImpl<>(views, entityPage.getPageable(), entityPage.getTotalElements());
    }

    @Override
    public List<UserUsageView> topUsers(String libraryId, int days, int limit) {
        int n = Math.max(1, Math.min(days, 365));
        int max = Math.max(1, Math.min(limit, 100));
        Date from = daysAgo(n);
        List<Object[]> rows = usageRepository.aggregateByUser(libraryId, from);
        List<UserUsageView> result = new ArrayList<>(Math.min(rows.size(), max));
        for (Object[] row : rows) {
            if (result.size() >= max) {
                break;
            }
            long tokens = row[2] == null ? 0 : ((Number) row[2]).longValue();
            double avgLatency = row[3] == null ? 0 : ((Number) row[3]).doubleValue();
            double avgHit = row[4] == null ? 0 : ((Number) row[4]).doubleValue();
            result.add(UserUsageView.builder()
                    .userId((String) row[0])
                    .questions(row[1] == null ? 0 : ((Number) row[1]).longValue())
                    .totalTokens(tokens)
                    .avgLatencyMs(round1(avgLatency))
                    .avgHitCount(round1(avgHit))
                    .lastActiveAt(row[5] == null ? null : (Date) row[5])
                    .build());
        }
        return result;
    }

    private ChatUsageView toView(ChatUsageEntity e) {
        return ChatUsageView.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .conversationId(e.getConversationId())
                .question(e.getQuestion())
                .answerCharCount(e.getAnswerCharCount())
                .inputTokens(e.getInputTokens())
                .outputTokens(e.getOutputTokens())
                .totalTokens(e.getTotalTokens())
                .elapsedMs(e.getElapsedMs())
                .hitCount(e.getHitCount())
                .createdDate(e.getCreatedDate())
                .build();
    }

    private int intOf(Integer v) {
        return v == null ? 0 : v;
    }

    private Date daysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        return c.getTime();
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 按日聚合临时结构。 */
    private static final class TrendAgg {
        long count;
        long tokens;
        long latency;
        long hit;

        void add(ChatUsageEntity e) {
            count++;
            tokens += (e.getInputTokens() == null ? 0 : e.getInputTokens())
                    + (e.getOutputTokens() == null ? 0 : e.getOutputTokens());
            latency += e.getElapsedMs() == null ? 0 : e.getElapsedMs();
            hit += e.getHitCount() == null ? 0 : e.getHitCount();
        }
    }
}

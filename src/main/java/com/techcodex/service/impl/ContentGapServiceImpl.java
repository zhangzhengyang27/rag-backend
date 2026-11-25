package com.techcodex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.entity.KbContentGapClusterEntity;
import com.techcodex.entity.KbContentGapEntity;
import com.techcodex.entity.KbContentGapEventEntity;
import com.techcodex.repository.KbContentGapClusterRepository;
import com.techcodex.repository.KbContentGapEventRepository;
import com.techcodex.repository.KbContentGapRepository;
import com.techcodex.service.ContentGapService;
import com.techcodex.service.KbEvalService;
import com.techcodex.vo.ContentGapClusterView;
import com.techcodex.vo.ContentGapDetailView;
import com.techcodex.vo.ContentGapEventView;
import com.techcodex.vo.ContentGapSummaryView;
import com.techcodex.vo.ContentGapView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内容缺口实现：聚合表 + 事件表 + 在线质心聚类。
 *
 * <p>边界：聚类依赖本地 embedding 服务；embedding 失败时缺口仍落库，只是 clusterKey 为空，
 * 不阻塞信号采集。所有写入均为旁路，异常不向问答链路抛出。</p>
 */
@Slf4j
@Service
public class ContentGapServiceImpl implements ContentGapService {

    private static final Set<String> SIGNAL_TYPES = Set.of("NO_ANSWER", "ZERO_CITATION", "THUMB_DOWN", "FOLLOW_UP");
    private static final Set<String> STATUSES = Set.of("OPEN", "TRIAGED", "PLANNED", "RESOLVED", "IGNORED");

    @Autowired
    private KbContentGapRepository gapRepository;

    @Autowired
    private KbContentGapEventRepository eventRepository;

    @Autowired
    private KbContentGapClusterRepository clusterRepository;

    @Autowired
    private KbEvalService evalService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ObjectMapper objectMapper;

    /** 归并阈值：低于该余弦相似度就新建簇；0.82 适合 512 维本地向量，可在配置中调。 */
    @Value("${app.kb.gap.cluster-threshold:0.82}")
    private double clusterThreshold;

    @Value("${app.kb.gap.cluster-max-clusters:200}")
    private int maxClusters;

    @Override
    public void recordSignal(String libraryId, String userId, String conversationId, String question,
                             String signalType, List<Long> relatedDocIds, Long feedbackId, String detail) {
        try {
            if (question == null || question.isBlank() || signalType == null
                    || !SIGNAL_TYPES.contains(signalType.trim().toUpperCase(Locale.ROOT))) {
                return;
            }
            String lib = blankToDefault(libraryId);
            String type = signalType.trim().toUpperCase(Locale.ROOT);
            String raw = truncate(question.trim(), 2000);
            String normalized = normalize(raw);
            String hash = sha256(normalized);
            Date now = new Date();

            KbContentGapEntity gap = gapRepository.findByLibraryIdAndNormalizedHash(lib, hash).orElse(null);
            boolean isNew = gap == null;
            if (isNew) {
                gap = new KbContentGapEntity();
                gap.setLibraryId(lib);
                gap.setQuestion(raw);
                gap.setNormalized(truncate(normalized, 512));
                gap.setNormalizedHash(hash);
                gap.setStatus("OPEN");
                gap.setFrequency(1);
                gap.setFirstSeen(now);
                gap.setSampleConversationId(truncate(conversationId, 64));
                gap.setSampleUserId(truncate(userId, 64));
            } else {
                gap.setFrequency(gap.getFrequency() == null ? 1 : gap.getFrequency() + 1);
                // 已关闭的缺口再次出现，重新打开，避免“标记已解决就永远消失”
                if ("RESOLVED".equals(gap.getStatus()) || "IGNORED".equals(gap.getStatus())) {
                    gap.setStatus("OPEN");
                }
                if (gap.getSampleConversationId() == null || gap.getSampleConversationId().isBlank()) {
                    gap.setSampleConversationId(truncate(conversationId, 64));
                }
            }
            gap.setLastSeen(now);
            gap.setSignalSummary(mergeSignalSummary(gap.getSignalSummary(), type));
            gap.setRelatedDocIds(mergeDocIds(gap.getRelatedDocIds(), relatedDocIds));
            gap = gapRepository.save(gap);

            KbContentGapEventEntity event = new KbContentGapEventEntity();
            event.setGapId(gap.getId());
            event.setLibraryId(lib);
            event.setSignalType(type);
            event.setConversationId(truncate(conversationId, 64));
            event.setFeedbackId(feedbackId);
            event.setDetail(truncate(detail, 1000));
            eventRepository.save(event);

            if (isNew || gap.getClusterKey() == null || gap.getClusterKey().isBlank()) {
                assignCluster(gap, normalized, now);
            }
        } catch (Exception e) {
            log.warn("内容缺口信号写入失败 - type: {}, question: {}, err: {}",
                    signalType, truncateForLog(question), e.getMessage());
        }
    }

    @Override
    public Page<ContentGapView> page(String libraryId, String status, String keyword, Pageable pageable) {
        String lib = blankToDefault(libraryId);
        String st = blankToNull(status);
        String kw = blankToNull(keyword);
        Map<String, String> labels = clusterLabels(lib);
        return gapRepository.search(lib, st, kw, pageable).map(g -> toView(g, labels));
    }

    @Override
    public List<ContentGapView> top(String libraryId, int limit) {
        String lib = blankToDefault(libraryId);
        int n = Math.max(1, Math.min(limit, 50));
        Map<String, String> labels = clusterLabels(lib);
        return gapRepository.findTopOpen(lib, PageRequest.of(0, n)).stream()
                .map(g -> toView(g, labels)).toList();
    }

    @Override
    public ContentGapSummaryView summary(String libraryId) {
        String lib = blankToDefault(libraryId);
        Date sevenDaysAgo = new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        return ContentGapSummaryView.builder()
                .total(gapRepository.countByLibraryId(lib))
                .open(gapRepository.countByLibraryIdAndStatus(lib, "OPEN"))
                .triaged(gapRepository.countByLibraryIdAndStatus(lib, "TRIAGED"))
                .planned(gapRepository.countByLibraryIdAndStatus(lib, "PLANNED"))
                .resolved(gapRepository.countByLibraryIdAndStatus(lib, "RESOLVED"))
                .last7Days(gapRepository.countSince(lib, sevenDaysAgo))
                .build();
    }

    @Override
    public List<ContentGapClusterView> clusters(String libraryId) {
        String lib = blankToDefault(libraryId);
        return clusterRepository.findByLibraryIdOrderByLastSeenDesc(lib, PageRequest.of(0, maxClusters))
                .stream()
                .map(c -> ContentGapClusterView.builder()
                        .key(clusterKey(c.getId()))
                        .label(c.getLabel())
                        .size(c.getSize())
                        .lastSeen(c.getLastSeen())
                        .build())
                .toList();
    }

    @Override
    public ContentGapDetailView detail(Long id, String libraryId) {
        String lib = blankToDefault(libraryId);
        KbContentGapEntity gap = gapRepository.findById(id)
                .filter(g -> lib.equals(g.getLibraryId()))
                .orElseThrow(() -> new IllegalArgumentException("缺口不存在"));
        Map<String, String> labels = clusterLabels(lib);
        List<ContentGapEventView> events = eventRepository
                .findByGapIdOrderByIdDesc(id, PageRequest.of(0, 30))
                .stream()
                .map(e -> ContentGapEventView.builder()
                        .id(e.getId())
                        .signalType(e.getSignalType())
                        .conversationId(e.getConversationId())
                        .feedbackId(e.getFeedbackId())
                        .detail(e.getDetail())
                        .createdDate(e.getCreatedDate())
                        .build())
                .toList();
        return ContentGapDetailView.builder().gap(toView(gap, labels)).events(events).build();
    }

    @Override
    public ContentGapView updateStatus(Long id, String libraryId, String status, String note) {
        String lib = blankToDefault(libraryId);
        String st = normalizeStatus(status);
        KbContentGapEntity gap = gapRepository.findById(id)
                .filter(g -> lib.equals(g.getLibraryId()))
                .orElseThrow(() -> new IllegalArgumentException("缺口不存在"));
        gap.setStatus(st);
        if (note != null) {
            gap.setNote(truncate(note.trim(), 1000));
        }
        gap = gapRepository.save(gap);
        return toView(gap, clusterLabels(lib));
    }

    @Override
    public void batchStatus(List<Long> ids, String libraryId, String status) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择至少一个缺口");
        }
        String lib = blankToDefault(libraryId);
        String st = normalizeStatus(status);
        List<KbContentGapEntity> gaps = gapRepository.findAllById(ids).stream()
                .filter(g -> lib.equals(g.getLibraryId())).toList();
        for (KbContentGapEntity gap : gaps) {
            gap.setStatus(st);
        }
        gapRepository.saveAll(gaps);
        log.info("内容缺口批量流转 - library: {}, count: {}, status: {}", lib, gaps.size(), st);
    }

    @Override
    public Long convertToEval(Long id, String libraryId, String expectedKeywords, String note, String operator) {
        if (expectedKeywords == null || expectedKeywords.isBlank()) {
            throw new IllegalArgumentException("期望命中关键字不能为空");
        }
        String lib = blankToDefault(libraryId);
        KbContentGapEntity gap = gapRepository.findById(id)
                .filter(g -> lib.equals(g.getLibraryId()))
                .orElseThrow(() -> new IllegalArgumentException("缺口不存在"));
        if (gap.getEvalCaseId() != null) {
            throw new IllegalArgumentException("该缺口已转为评测用例 #" + gap.getEvalCaseId());
        }
        String resolvedNote = (note == null || note.isBlank())
                ? "内容缺口回流 #" + gap.getId() + "，频次 " + gap.getFrequency()
                : note.trim() + "（内容缺口回流 #" + gap.getId() + "）";
        Long caseId = evalService.addCase(gap.getQuestion(), expectedKeywords.trim(), "", resolvedNote, operator);
        gap.setEvalCaseId(caseId);
        gap.setStatus("PLANNED");
        gap.setNote(truncate(resolvedNote, 1000));
        gapRepository.save(gap);
        log.info("内容缺口转评测用例 - gapId: {}, caseId: {}, operator: {}", id, caseId, operator);
        return caseId;
    }

    @Override
    public String exportCsv(String libraryId, String status, String keyword) {
        String lib = blankToDefault(libraryId);
        Page<ContentGapView> page = page(lib, status, keyword,
                PageRequest.of(0, 5000, Sort.by(Sort.Direction.DESC, "frequency")));
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("id,question,frequency,status,signals,cluster,relatedDocIds,conversationId,lastSeen,note\n");
        for (ContentGapView g : page.getContent()) {
            sb.append(g.getId()).append(',')
                    .append(csv(g.getQuestion())).append(',')
                    .append(g.getFrequency()).append(',')
                    .append(csv(g.getStatus())).append(',')
                    .append(csv(g.getSignalSummary())).append(',')
                    .append(csv(g.getClusterLabel())).append(',')
                    .append(csv(g.getRelatedDocIds())).append(',')
                    .append(csv(g.getSampleConversationId())).append(',')
                    .append(g.getLastSeen() == null ? "" : fmt.format(g.getLastSeen())).append(',')
                    .append(csv(g.getNote())).append('\n');
        }
        return sb.toString();
    }

    /** 在线归并：与最近 200 个质心比较余弦，命中则加权更新，否则新建簇。 */
    private void assignCluster(KbContentGapEntity gap, String text, Date now) {
        try {
            float[] vector = embeddingModel.embed(text).content().vector();
            List<KbContentGapClusterEntity> clusters = clusterRepository
                    .findByLibraryIdOrderByLastSeenDesc(gap.getLibraryId(), PageRequest.of(0, maxClusters));
            KbContentGapClusterEntity best = null;
            double bestSimilarity = -1;
            for (KbContentGapClusterEntity c : clusters) {
                float[] centroid = parseCentroid(c.getCentroidJson());
                if (centroid.length != vector.length) continue;
                double sim = cosine(vector, centroid);
                if (sim > bestSimilarity) {
                    bestSimilarity = sim;
                    best = c;
                }
            }
            if (best != null && bestSimilarity >= clusterThreshold) {
                float[] centroid = parseCentroid(best.getCentroidJson());
                int size = best.getSize() == null ? 1 : best.getSize();
                float[] merged = new float[vector.length];
                // 加权平均：旧质心按已归并缺口数加权，避免新样本把簇中心拉偏
                for (int i = 0; i < vector.length; i++) {
                    merged[i] = (centroid[i] * size + vector[i]) / (size + 1);
                }
                best.setCentroidJson(objectMapper.writeValueAsString(merged));
                best.setSize(size + 1);
                best.setLastSeen(now);
                clusterRepository.save(best);
                gap.setClusterKey(clusterKey(best.getId()));
            } else {
                KbContentGapClusterEntity cluster = new KbContentGapClusterEntity();
                cluster.setLibraryId(gap.getLibraryId());
                cluster.setLabel(truncate(gap.getQuestion(), 255));
                cluster.setCentroidJson(objectMapper.writeValueAsString(vector));
                cluster.setSize(1);
                cluster.setLastSeen(now);
                cluster = clusterRepository.save(cluster);
                gap.setClusterKey(clusterKey(cluster.getId()));
            }
            gapRepository.save(gap);
        } catch (Exception e) {
            log.warn("内容缺口聚类失败，保留未聚类缺口 - gapId: {}, err: {}", gap.getId(), e.getMessage());
        }
    }

    private Map<String, String> clusterLabels(String libraryId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (KbContentGapClusterEntity c : clusterRepository.findByLibraryIdOrderByLastSeenDesc(
                libraryId, PageRequest.of(0, maxClusters))) {
            map.put(clusterKey(c.getId()), c.getLabel());
        }
        return map;
    }

    private ContentGapView toView(KbContentGapEntity g, Map<String, String> labels) {
        return ContentGapView.builder()
                .id(g.getId())
                .question(g.getQuestion())
                .normalized(g.getNormalized())
                .frequency(g.getFrequency())
                .status(g.getStatus())
                .signalSummary(g.getSignalSummary())
                .clusterKey(g.getClusterKey())
                .clusterLabel(g.getClusterKey() == null ? null : labels.get(g.getClusterKey()))
                .relatedDocIds(g.getRelatedDocIds())
                .sampleConversationId(g.getSampleConversationId())
                .evalCaseId(g.getEvalCaseId())
                .note(g.getNote())
                .firstSeen(g.getFirstSeen())
                .lastSeen(g.getLastSeen())
                .createdDate(g.getCreatedDate())
                .build();
    }

    private String normalizeStatus(String status) {
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(st)) {
            throw new IllegalArgumentException("状态仅支持 OPEN/TRIAGED/PLANNED/RESOLVED/IGNORED");
        }
        return st;
    }

    private String mergeSignalSummary(String old, String type) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (old != null && !old.isBlank()) {
            for (String part : old.split(",")) {
                int idx = part.lastIndexOf(':');
                if (idx <= 0) continue;
                try {
                    counts.put(part.substring(0, idx), Integer.parseInt(part.substring(idx + 1)));
                } catch (NumberFormatException ignored) {
                    // 旧数据格式异常时忽略该段，避免一处脏值影响整条聚合
                }
            }
        }
        counts.merge(type, 1, Integer::sum);
        String joined = counts.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse(type + ":1");
        return truncate(joined, 255);
    }

    private String mergeDocIds(String old, List<Long> ids) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (old != null && !old.isBlank()) {
            for (String s : old.split(",")) {
                if (!s.isBlank()) set.add(s.trim());
            }
        }
        if (ids != null) {
            for (Long id : ids) {
                if (id != null) set.add(String.valueOf(id));
            }
        }
        String joined = String.join(",", set);
        return truncate(joined, 1000);
    }

    private String normalize(String question) {
        String n = Normalizer.normalize(question, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[?？!！。.;；]+$", "");
        return n.length() > 512 ? n.substring(0, 512) : n;
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private float[] parseCentroid(String json) throws Exception {
        if (json == null || json.isBlank()) return new float[0];
        return objectMapper.readValue(json, float[].class);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String clusterKey(Long id) {
        return id == null ? null : "c" + id;
    }

    private String blankToDefault(String value) {
        return value == null || value.isBlank() ? com.techcodex.library.KbLibrary.DEFAULT_LIBRARY : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String truncateForLog(String value) {
        return truncate(value, 80);
    }

    private String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}

package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.service.KbGovernanceService;
import org.example.vo.KbGovernanceIssueView;
import org.example.vo.KbGovernanceSummaryView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识治理实现：4,500 篇公共文档一次性扫描即可，不引入新中间件。
 *
 * <p>重复判定同时看内容哈希（完全重复）与规范化标题（近似重复）；过期看 reviewDueDate 或
 * updatedDate 超过 staleDays；健康分按问题扣分，便于周报/首页做趋势。</p>
 */
@Slf4j
@Service
public class KbGovernanceServiceImpl implements KbGovernanceService {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final KnowledgeDocumentRepository documentRepository;

    public KbGovernanceServiceImpl(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public KbGovernanceSummaryView summary(String libraryId, int staleDays) {
        List<KnowledgeDocumentEntity> docs = documentRepository.findAllByLibraryId(libraryId);
        ScanResult scan = scan(docs, staleDays);
        List<KbGovernanceSummaryView.DuplicateGroup> groups = new ArrayList<>();
        scan.duplicateGroups.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().size(), a.getValue().size()))
                .limit(20)
                .forEach(e -> groups.add(KbGovernanceSummaryView.DuplicateGroup.builder()
                        .normalizedTitle(e.getKey())
                        .count(e.getValue().size())
                        .samplePath(e.getValue().get(0).getFilePath())
                        .build()));
        return KbGovernanceSummaryView.builder()
                .totalDocuments(docs.size())
                .avgHealthScore(round1(scan.healthSum / Math.max(1, docs.size())))
                .duplicateDocuments(scan.duplicateDocs)
                .staleDocuments(scan.staleDocs)
                .missingOwnerDocuments(scan.missingOwnerDocs)
                .emptyChunkDocuments(scan.emptyChunkDocs)
                .duplicateGroups(groups)
                .build();
    }

    @Override
    public List<KbGovernanceIssueView> issues(String libraryId, String type, int staleDays, int limit) {
        List<KnowledgeDocumentEntity> docs = documentRepository.findAllByLibraryId(libraryId);
        ScanResult scan = scan(docs, staleDays);
        String filter = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return scan.issues.stream()
                .filter(i -> filter.isBlank() || filter.equals(i.getType()))
                .sorted(Comparator.comparing(KbGovernanceIssueView::getHealthScore)
                        .thenComparing(KbGovernanceIssueView::getDocId))
                .limit(Math.max(1, Math.min(limit, 500)))
                .toList();
    }

    @Override
    @Transactional
    public void update(String libraryId, Long docId, String owner, Date reviewDueDate, String operator) {
        KnowledgeDocumentEntity doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!libraryId.equals(doc.getLibraryId())) {
            throw new IllegalArgumentException("文档不属于当前知识库空间");
        }
        doc.setOwner(owner == null || owner.isBlank() ? null : owner.trim());
        doc.setReviewDueDate(reviewDueDate);
        doc.setLastReviewedAt(new Date());
        documentRepository.save(doc);
        log.info("知识治理更新 - docId: {}, owner: {}, reviewDueDate: {}, operator: {}",
                docId, doc.getOwner(), reviewDueDate, operator);
    }

    private ScanResult scan(List<KnowledgeDocumentEntity> docs, int staleDays) {
        int days = Math.max(7, Math.min(staleDays, 730));
        Date staleBefore = new Date(System.currentTimeMillis() - days * DAY_MS);
        Map<String, List<KnowledgeDocumentEntity>> byHash = new HashMap<>();
        Map<String, List<KnowledgeDocumentEntity>> byTitle = new LinkedHashMap<>();
        ScanResult result = new ScanResult();
        for (KnowledgeDocumentEntity doc : docs) {
            if (doc.getContentHash() != null && !doc.getContentHash().isBlank()) {
                byHash.computeIfAbsent(doc.getContentHash(), k -> new ArrayList<>()).add(doc);
            }
            String normalized = normalizeTitle(doc.getTitle());
            if (!normalized.isBlank()) {
                // 标题重复必须限定在同一项目/目录桶里：不同项目的“01 系统架构”
                // 是正常章节名，不应被判成重复内容。
                String bucket = pathBucket(doc);
                byTitle.computeIfAbsent(normalized + "|" + bucket, k -> new ArrayList<>()).add(doc);
            }
        }
        Set<Long> duplicateIds = new HashSet<>();
        for (List<KnowledgeDocumentEntity> group : byHash.values()) {
            if (group.size() > 1) {
                group.forEach(d -> duplicateIds.add(d.getId()));
            }
        }
        for (List<KnowledgeDocumentEntity> group : byTitle.values()) {
            if (group.size() > 1) {
                group.forEach(d -> duplicateIds.add(d.getId()));
                result.duplicateGroups.putIfAbsent(normalizeTitle(group.get(0).getTitle()), group);
            }
        }

        for (KnowledgeDocumentEntity doc : docs) {
            int score = 100;
            boolean duplicate = duplicateIds.contains(doc.getId());
            boolean stale = isStale(doc, staleBefore);
            boolean missingOwner = doc.getOwner() == null || doc.getOwner().isBlank();
            boolean emptyChunks = doc.getChunkCount() == null || doc.getChunkCount() == 0;
            if (duplicate) {
                score -= 20;
                result.duplicateDocs++;
                result.issues.add(issue(doc, KbGovernanceIssueView.DUPLICATE, "MEDIUM",
                        "标题/内容与其他文档重复，建议合并或加区分说明", score));
            }
            if (stale) {
                score -= 25;
                result.staleDocs++;
                result.issues.add(issue(doc, KbGovernanceIssueView.STALE, "HIGH",
                        "超过 " + days + " 天未更新或复核日期已过", score));
            }
            if (missingOwner) {
                score -= 15;
                result.missingOwnerDocs++;
                result.issues.add(issue(doc, KbGovernanceIssueView.MISSING_OWNER, "LOW",
                        "未指定内容责任人，出问题无人认领", score));
            }
            if (emptyChunks) {
                score -= 20;
                result.emptyChunkDocs++;
                result.issues.add(issue(doc, KbGovernanceIssueView.EMPTY_CHUNKS, "HIGH",
                        "分块数为 0，检索无法命中", score));
            }
            result.healthSum += score;
            for (KbGovernanceIssueView issue : result.issues) {
                if (issue.getDocId().equals(doc.getId())) {
                    issue.setHealthScore(score);
                }
            }
        }
        return result;
    }

    private KbGovernanceIssueView issue(KnowledgeDocumentEntity doc, String type, String severity,
                                        String detail, int healthScore) {
        return KbGovernanceIssueView.builder()
                .docId(doc.getId())
                .type(type)
                .severity(severity)
                .title(doc.getTitle())
                .filePath(doc.getFilePath())
                .detail(detail)
                .owner(doc.getOwner())
                .reviewDueDate(doc.getReviewDueDate())
                .updatedDate(doc.getUpdatedDate())
                .healthScore(healthScore)
                .build();
    }

    private boolean isStale(KnowledgeDocumentEntity doc, Date staleBefore) {
        if (doc.getReviewDueDate() != null && doc.getReviewDueDate().before(new Date())) {
            return true;
        }
        Date lastTouch = doc.getLastReviewedAt() != null ? doc.getLastReviewedAt() : doc.getUpdatedDate();
        return lastTouch != null && lastTouch.before(staleBefore);
    }

    /** 取路径前两段作为项目桶（如 13-项目文档/xiaoye-admin-react）；不足两段回退 space。 */
    private String pathBucket(KnowledgeDocumentEntity doc) {
        String path = doc.getFilePath();
        if (path == null || path.isBlank()) {
            return doc.getSpace() == null ? "" : doc.getSpace();
        }
        String[] parts = path.replace('\\', '/').split("/");
        if (parts.length >= 2) {
            return parts[0] + "/" + parts[1];
        }
        return parts[0];
    }

    /** 标题规范化：小写、去掉空白与常见中英文标点，用于近似重复分组。 */
    private String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：（）【】《》“”‘’·]+", "");
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static final class ScanResult {
        long healthSum;
        long duplicateDocs;
        long staleDocs;
        long missingOwnerDocs;
        long emptyChunkDocs;
        List<KbGovernanceIssueView> issues = new ArrayList<>();
        Map<String, List<KnowledgeDocumentEntity>> duplicateGroups = new LinkedHashMap<>();
    }
}

package org.example.service.impl;

import org.example.entity.RetrievalTraceEntity;
import org.example.repository.RetrievalTraceRepository;
import org.example.service.KbTraceService;
import org.example.vo.RetrievalTraceView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

/**
 * 检索 Trace 可观测实现：Specification 组合空间/用户/关键字过滤（Trace 只读，无需事务）。
 */
@Service
public class KbTraceServiceImpl implements KbTraceService {

    private final RetrievalTraceRepository traceRepository;

    public KbTraceServiceImpl(RetrievalTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @Override
    public Page<RetrievalTraceView> page(String libraryId, String userId, String keyword, Pageable pageable) {
        Specification<RetrievalTraceEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (libraryId != null && !libraryId.isBlank()) {
                ps.add(cb.equal(root.get("libraryId"), libraryId.trim()));
            }
            if (userId != null && !userId.isBlank()) {
                ps.add(cb.equal(root.get("userId"), userId.trim()));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(
                        cb.like(root.get("originalQuery"), like),
                        cb.like(root.get("searchQuery"), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return traceRepository.findAll(spec, pageable).map(this::toView);
    }

    private RetrievalTraceView toView(RetrievalTraceEntity e) {
        return RetrievalTraceView.builder()
                .id(e.getId())
                .libraryId(e.getLibraryId())
                .userId(e.getUserId())
                .originalQuery(e.getOriginalQuery())
                .searchQuery(e.getSearchQuery())
                .keywordUsed(e.getKeywordUsed())
                .vectorCount(e.getVectorCount())
                .keywordCount(e.getKeywordCount())
                .fusedCount(e.getFusedCount())
                .reranked(e.getReranked())
                .rerankModelAvailable(e.getRerankModelAvailable())
                .resultCount(e.getResultCount())
                .elapsedMs(e.getElapsedMs())
                .hitSummary(e.getHitSummary())
                .pathFilter(e.getPathFilter())
                .createdDate(e.getCreatedDate())
                .build();
    }
}

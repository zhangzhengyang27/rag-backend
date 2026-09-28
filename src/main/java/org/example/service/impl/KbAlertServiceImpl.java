package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbAlertEntity;
import org.example.repository.KbAlertRepository;
import org.example.service.KbAlertService;
import org.example.vo.KbAlertView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 运营告警实现：同类未处理告警去重，处理后留痕（不删除，可追溯）。
 */
@Slf4j
@Service
public class KbAlertServiceImpl implements KbAlertService {

    private final KbAlertRepository alertRepository;

    public KbAlertServiceImpl(KbAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Override
    public boolean raise(String libraryId, String type, String severity, String message, String detail) {
        // 预检（快速路径）+ dedupeKey 唯一约束兜底：并发/跨实例同时 raise 同类告警时，
        // 后插入者触发唯一键冲突被捕获为"已存在"，彻底收敛 check-then-insert 竞态。
        // 注意：本方法刻意不加 @Transactional——saveAndFlush 以独立事务执行，
        // 唯一键冲突在 flush 时即抛出、可安全捕获；若包进外层事务，冲突只会把
        // 共享事务标记 rollback-only，捕获后方法仍以 UnexpectedRollbackException 告吹，
        // 并把已成功的调用方流程（如 docs 同步任务）误报为失败。
        if (alertRepository.findByTypeAndResolvedFalse(type).isPresent()) {
            return false;
        }
        KbAlertEntity entity = new KbAlertEntity();
        entity.setLibraryId(libraryId);
        entity.setType(type);
        entity.setDedupeKey(type);
        entity.setSeverity(severity);
        entity.setMessage(message);
        entity.setDetail(detail);
        try {
            alertRepository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("运营告警并发去重命中唯一约束 - type: {}", type);
            return false;
        }
        log.warn("运营告警产生 - type: {}, severity: {}, message: {}", type, severity, message);
        return true;
    }

    @Override
    public Page<KbAlertView> page(String libraryId, Boolean resolved, Pageable pageable) {
        Page<KbAlertEntity> entityPage = resolved == null
                ? alertRepository.findByLibraryId(libraryId, pageable)
                : alertRepository.findByLibraryIdAndResolved(libraryId, resolved, pageable);
        List<KbAlertView> views = entityPage.getContent().stream().map(this::toView).toList();
        return new PageImpl<>(views, entityPage.getPageable(), entityPage.getTotalElements());
    }

    @Override
    public List<KbAlertView> openAlerts(String libraryId) {
        return alertRepository.findByResolvedFalseOrderByCreatedDateDesc().stream()
                .filter(a -> libraryId == null || libraryId.equals(a.getLibraryId()))
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public void resolve(Long id, String operator) {
        KbAlertEntity entity = alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        entity.setResolved(true);
        entity.setDedupeKey(null);
        entity.setResolvedBy(operator);
        alertRepository.save(entity);
        log.info("运营告警已处理 - id: {}, type: {}, operator: {}", id, entity.getType(), operator);
    }

    private KbAlertView toView(KbAlertEntity e) {
        return KbAlertView.builder()
                .id(e.getId())
                .libraryId(e.getLibraryId())
                .type(e.getType())
                .severity(e.getSeverity())
                .message(e.getMessage())
                .detail(e.getDetail())
                .resolved(e.isResolved())
                .resolvedBy(e.getResolvedBy())
                .createdDate(e.getCreatedDate())
                .build();
    }
}

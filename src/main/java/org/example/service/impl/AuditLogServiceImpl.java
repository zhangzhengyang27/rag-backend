package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.AuditLogEntity;
import org.example.repository.AuditLogRepository;
import org.example.service.AuditLogService;
import org.example.vo.AuditLogView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计日志实现：记录为同步旁路写（管理操作频率低、失败仅记日志）；查询按空间/操作类型分支过滤。
 */
@Slf4j
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final int DETAIL_MAX_LENGTH = 500;

    private final AuditLogRepository auditRepository;

    public AuditLogServiceImpl(AuditLogRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void record(String libraryId, String operator, String action, String targetType, Long targetId, String detail) {
        try {
            AuditLogEntity e = new AuditLogEntity();
            e.setLibraryId(libraryId);
            e.setOperator(operator);
            e.setAction(action);
            e.setTargetType(targetType);
            e.setTargetId(targetId);
            e.setDetail(truncate(detail, DETAIL_MAX_LENGTH));
            auditRepository.save(e);
        } catch (Exception ex) {
            log.warn("记录审计日志失败 - operator: {}, action: {}, err: {}", operator, action, ex.getMessage());
        }
    }

    @Override
    public Page<AuditLogView> query(String libraryId, String action, Pageable page) {
        boolean hasLibrary = libraryId != null && !libraryId.isBlank();
        boolean hasAction = action != null && !action.isBlank();
        Page<AuditLogEntity> entityPage;
        if (hasLibrary && hasAction) {
            entityPage = auditRepository.findByLibraryIdAndActionOrderByIdDesc(libraryId, action, page);
        } else if (hasLibrary) {
            entityPage = auditRepository.findByLibraryIdOrderByIdDesc(libraryId, page);
        } else if (hasAction) {
            entityPage = auditRepository.findByActionOrderByIdDesc(action, page);
        } else {
            entityPage = auditRepository.findAll(page);
        }
        List<AuditLogView> views = entityPage.getContent().stream().map(this::toView).toList();
        return new PageImpl<>(views, entityPage.getPageable(), entityPage.getTotalElements());
    }

    private AuditLogView toView(AuditLogEntity e) {
        return AuditLogView.builder()
                .id(e.getId())
                .libraryId(e.getLibraryId())
                .operator(e.getOperator())
                .action(e.getAction())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .detail(e.getDetail())
                .createdDate(e.getCreatedDate())
                .build();
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}

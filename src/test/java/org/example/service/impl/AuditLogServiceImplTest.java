package org.example.service.impl;

import org.example.entity.AuditLogEntity;
import org.example.repository.AuditLogRepository;
import org.example.service.AuditLogService;
import org.example.vo.AuditLogView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志服务单测：记录组装与保存、查询分页映射与 action 过滤。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogRepository auditRepository;

    @InjectMocks
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditLogServiceImpl(auditRepository);
    }

    private AuditLogEntity entity(long id, String libraryId, String action) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(id);
        e.setLibraryId(libraryId);
        e.setOperator("admin");
        e.setAction(action);
        e.setTargetType("DOCUMENT");
        e.setTargetId(10L);
        e.setDetail("导入文档");
        e.setCreatedDate(new Date());
        return e;
    }

    @Test
    void recordAssemblesAndSavesEntity() {
        service.record("default", "admin", "UPLOAD", "DOCUMENT", 10L, "导入文档：xxx");

        verify(auditRepository).save(argThat(e ->
                "default".equals(e.getLibraryId())
                        && "admin".equals(e.getOperator())
                        && "UPLOAD".equals(e.getAction())
                        && "DOCUMENT".equals(e.getTargetType())
                        && e.getTargetId() == 10L
                        && "导入文档：xxx".equals(e.getDetail())));
    }

    @Test
    void recordTruncatesOverlongDetail() {
        String longDetail = "d".repeat(600);
        service.record("default", "admin", "UPLOAD", "DOCUMENT", 1L, longDetail);

        verify(auditRepository).save(argThat(e -> e.getDetail().length() == 500));
    }

    @Test
    void recordSwallowsSaveError() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(auditRepository).save(any());
        // 不应抛异常
        service.record("default", "admin", "UPLOAD", "DOCUMENT", 1L, "x");
    }

    @Test
    void queryByLibraryAndActionFiltersAndMaps() {
        when(auditRepository.findByLibraryIdAndActionOrderByIdDesc(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(entity(1, "default", "UPLOAD"))));

        Page<AuditLogView> page = service.query("default", "UPLOAD", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        AuditLogView view = page.getContent().get(0);
        assertThat(view.getId()).isEqualTo(1);
        assertThat(view.getLibraryId()).isEqualTo("default");
        assertThat(view.getAction()).isEqualTo("UPLOAD");
        assertThat(view.getDetail()).isEqualTo("导入文档");
    }

    @Test
    void queryWithoutFiltersFallsBackToAll() {
        when(auditRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entity(2, "t1", "DELETE"))));

        Page<AuditLogView> page = service.query(null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getAction()).isEqualTo("DELETE");
    }
}

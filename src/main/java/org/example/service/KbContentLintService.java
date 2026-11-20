package org.example.service;

import org.example.vo.KbLintReportView;

/**
 * 全站内容 Lint：只产出问题与修复建议，不自动改文档（Phase 4 边界）。
 */
public interface KbContentLintService {

    KbLintReportView lint(String libraryId, int limit);
}

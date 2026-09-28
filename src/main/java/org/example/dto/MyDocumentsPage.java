package org.example.dto;

import java.util.List;

/**
 * 用户侧私有文档分页（/api/kb/my/documents）：扁平结构，与前端 lib/kb.ts 的
 * PagedDocuments 契约对齐（管理端 PagedModel 为嵌套 page 对象，两者不同形）。
 */
public record MyDocumentsPage(
        List<KbDocumentView> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {
}

package org.example.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 把现有 docs/ 目录一次性收编为 CMS 的请求。
 * 分类映射由前端从 docs-config 传入（slug ↔ dir），后端不复制一份分类配置，
 * 避免两处配置漂移；dir 同时用于最长前缀匹配，决定每篇文档的归属分类。
 */
@Data
public class CmsImportRequest {

    private List<Category> categories = new ArrayList<>();

    /** true 时覆盖已有 CMS 文档（用于内容漂移后的显式重建）；默认只收编 SYNC/旧导入文档。 */
    private boolean force;

    /** 新建/内容变化的文档是否立即重建向量；默认 false，迁移完成后统一重建索引更快。 */
    private boolean indexNew;

    @Data
    public static class Category {
        private String slug;
        private String dir;
    }
}

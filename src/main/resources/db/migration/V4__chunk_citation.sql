-- V4 分块引用元数据：为「代码块保留 + 标题路径 + 引用锚点 + 父子分块」打底。
-- 说明：
--   1) 存量分块由列默认值回填，不阻塞已有检索；新索引会写入真实标题路径与类型。
--   2) parent_id 当前留空，Phase 3 做父子分块时再把 section 级分块挂到父块上。
--   3) Redis 向量索引的 metadata schema 不在迁移里改；旧索引缺字段时检索侧按
--      docId + chunkIndex 回查 kb_chunk 补齐，下一次全量重建索引后自然带上。
ALTER TABLE `kb_chunk`
  ADD COLUMN `heading_path` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题路径（如 Java > 集合 > HashMap；首个标题前为空）',
  ADD COLUMN `anchor` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题文本（前端用 extractToc 映射锚点；无标题为空）',
  ADD COLUMN `heading_ordinal` int NOT NULL DEFAULT 0 COMMENT '标题序号（0=首个标题前，标题按文档顺序 1-based 递增）',
  ADD COLUMN `chunk_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'text' COMMENT '分块类型：text=正文，code=代码块',
  ADD COLUMN `code_lang` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '代码语言（chunk_type=code 时记录围栏语言，如 java/bash）',
  ADD COLUMN `parent_id` bigint DEFAULT NULL COMMENT '父分块ID（父子分块；Phase 3 启用）',
  ADD COLUMN `token_count` int NOT NULL DEFAULT 0 COMMENT '估算 token 数（用于语义缓存与上下文预算）';

ALTER TABLE `kb_chunk`
  ADD INDEX `idx_kb_chunk_doc_ordinal` (`doc_id`, `heading_ordinal`);

ALTER TABLE `kb_chat_usage`
  ADD COLUMN `cache_hit` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否命中语义缓存',
  ADD COLUMN `cache_saved_tokens` int NOT NULL DEFAULT 0 COMMENT '缓存命中节省的 token 数';

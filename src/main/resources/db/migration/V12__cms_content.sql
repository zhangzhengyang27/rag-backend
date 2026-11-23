-- CMS 内容源：kb_document 从「文件同步的副本」升级为「站点内容的权威库」。
-- 文件仍是构建/渲染的物化镜像（docs volume），数据库负责编辑、版本与发布状态。
-- 兼容存量数据：新列全部可空或带默认值，旧 SYNC 记录由 admin 的导入动作收编为 CMS。
ALTER TABLE kb_document
    ADD COLUMN category_slug VARCHAR(64) NULL COMMENT '站点分类 slug（如 javascript，对应 /docs/{slug}）',
    ADD COLUMN category_dir VARCHAR(255) NULL COMMENT '站点分类目录（如 04-前端/03-JavaScript，镜像文件父目录）',
    ADD COLUMN doc_slug VARCHAR(512) NULL COMMENT '分类内文档 slug（相对分类目录、不含 .md）',
    ADD COLUMN description VARCHAR(2000) NULL COMMENT '文档摘要（frontmatter.description 的结构化副本）',
    ADD COLUMN publish_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态：PUBLISHED/DRAFT',
    ADD COLUMN published_at DATETIME NULL COMMENT '最近发布时间',
    ADD COLUMN published_by VARCHAR(64) NULL COMMENT '最近发布人',
    ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '当前内容版本号（与乐观锁 version 解耦）';

CREATE INDEX idx_kb_doc_cms_catalog ON kb_document (library_id, source_type, category_dir);
-- 唯一路由键组合的完整长度超过 InnoDB 3072 字节索引上限（utf8mb4 下 category_dir 255 + doc_slug 512
-- 已接近 3KB），这里用前缀索引：列表查询仍走等值条件，前缀只影响极端长路径的区分度，不影响正确性。
CREATE INDEX idx_kb_doc_cms_slug ON kb_document (library_id, source_type, category_dir(128), doc_slug(128));

-- 内容版本表：每次发布追加一条快照，支持查看与回滚；正文存 LONGTEXT，
-- 不依赖 Git，因此 CMS 编辑与文件镜像解耦后仍保留可追溯历史。
CREATE TABLE kb_document_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    doc_id BIGINT NOT NULL COMMENT '文档 id',
    revision INT NOT NULL COMMENT '内容版本号（与 kb_document.revision 对齐）',
    title VARCHAR(500) NULL COMMENT '快照标题',
    description VARCHAR(2000) NULL COMMENT '快照摘要',
    content LONGTEXT NULL COMMENT '快照 Markdown 原文（含 frontmatter）',
    content_hash VARCHAR(64) NULL COMMENT '快照内容哈希',
    change_note VARCHAR(500) NULL COMMENT '变更说明',
    created_by VARCHAR(64) NULL COMMENT '发布人',
    created_date DATETIME(6) NULL COMMENT '创建时间',
    updated_date DATETIME(6) NULL COMMENT '更新时间',
    version BIGINT DEFAULT 0 COMMENT '乐观锁版本号',
    KEY idx_kb_version_doc_revision (doc_id, revision),
    KEY idx_kb_version_created (created_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS 文档版本快照';

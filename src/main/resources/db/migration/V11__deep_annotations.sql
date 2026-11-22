-- GraphRAG 门禁前置数据：文档级实体/关系标注样本。
-- 当前样本基于“文档标题 + 章节标题 + Markdown 出链”生成，属于轻量文档图，
-- 不等同于开放域命名实体识别；status=VERIFIED 表示链接目标已解析、关系可复核。
CREATE TABLE kb_deep_annotation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    doc_id BIGINT NOT NULL COMMENT '文档 id',
    entity_count INT NOT NULL DEFAULT 0 COMMENT '实体数',
    relation_count INT NOT NULL DEFAULT 0 COMMENT '关系数',
    entities_json LONGTEXT NULL COMMENT '实体 JSON：[{name,type}]',
    relations_json LONGTEXT NULL COMMENT '关系 JSON：[{from,to,type}]',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/VERIFIED',
    annotator VARCHAR(64) NULL COMMENT '标注来源',
    note VARCHAR(512) NULL COMMENT '口径说明',
    created_date DATETIME(6) NULL,
    updated_date DATETIME(6) NULL,
    version BIGINT DEFAULT 0,
    UNIQUE KEY uk_deep_annotation_doc (doc_id),
    KEY idx_deep_annotation_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GraphRAG 实体关系标注样本';

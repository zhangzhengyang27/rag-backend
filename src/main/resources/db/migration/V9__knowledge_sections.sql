-- 父子分块的父级：按「文档 + 标题序号」聚合 section 文本，子块用 parent_id 指向 section。
-- 这样不需要给父块再建向量：检索仍返回子块，回答/引用时按 parent_id 取章节上下文。
CREATE TABLE IF NOT EXISTS `kb_section` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version` BIGINT DEFAULT 0 COMMENT '乐观锁版本号',
  `library_id` VARCHAR(64) DEFAULT NULL COMMENT '空间业务键',
  `doc_id` BIGINT NOT NULL COMMENT '所属文档ID',
  `heading_ordinal` INT NOT NULL DEFAULT 0 COMMENT '标题序号（0=首个标题前）',
  `heading_path` VARCHAR(512) DEFAULT NULL COMMENT '标题路径',
  `anchor` VARCHAR(1024) DEFAULT NULL COMMENT '标题锚点',
  `text` LONGTEXT COMMENT '章节全文（父块上下文）',
  `chunk_count` INT DEFAULT 0 COMMENT '子块数量',
  `token_count` INT DEFAULT 0 COMMENT 'token 估算',
  `created_date` DATETIME(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` DATETIME(6) DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_section_doc_ordinal` (`doc_id`, `heading_ordinal`),
  KEY `idx_kb_section_library` (`library_id`),
  KEY `idx_kb_section_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库章节父块（父子分块的父级）';

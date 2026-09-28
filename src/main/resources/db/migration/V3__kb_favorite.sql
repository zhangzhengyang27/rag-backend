-- V3 文档收藏：用户收藏公共/个人知识库文档（阶段 3 收藏功能）。
-- 同一用户对同一文档仅一条记录（唯一键幂等），列表按收藏时间倒序。
CREATE TABLE `kb_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_date` datetime(6) DEFAULT NULL,
  `updated_date` datetime(6) DEFAULT NULL,
  `version` bigint DEFAULT NULL,
  `username` varchar(64) NOT NULL COMMENT '收藏者用户名',
  `doc_id` bigint NOT NULL COMMENT '被收藏文档ID（kb_document.id）',
  `library_id` varchar(64) DEFAULT NULL COMMENT '文档所属空间（default / u-{username}）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_favorite_user_doc` (`username`,`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档收藏';

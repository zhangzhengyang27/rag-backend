-- V5 内容缺口中心：把未答 / 零引用 / 点踩 / 追问四类信号聚合为可运营的缺口清单。
-- 设计边界：
--   1) 频次按 library + normalized_hash 合并，原文仍保留首条问题，避免同义标点/空格造成碎片；
--   2) 事件表保留每次信号与 conversation/feedback 追溯，缺口表只存聚合快照；
--   3) 聚类质心存 JSON，在线增量归并；数据量小时无需引入独立向量库。
CREATE TABLE `kb_content_gap` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '空间业务键',
  `question` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '首次出现的问题原文',
  `normalized` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '归一化问题（展示/检索用）',
  `normalized_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '归一化 SHA-256，聚合唯一键',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/TRIAGED/PLANNED/RESOLVED/IGNORED',
  `frequency` int NOT NULL DEFAULT 1 COMMENT '累计出现次数',
  `first_seen` datetime(6) DEFAULT NULL COMMENT '首次出现时间',
  `last_seen` datetime(6) DEFAULT NULL COMMENT '最近出现时间',
  `related_doc_ids` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近引用/相关文档ID，逗号分隔',
  `signal_summary` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '信号计数，如 NO_ANSWER:2,ZERO_CITATION:1',
  `sample_conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '样本对话ID',
  `sample_user_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '样本用户',
  `cluster_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '聚类键',
  `eval_case_id` bigint DEFAULT NULL COMMENT '转评测用例ID',
  `note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '运营备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gap_library_norm` (`library_id`, `normalized_hash`),
  KEY `idx_gap_library_status_freq` (`library_id`, `status`, `frequency`),
  KEY `idx_gap_library_last_seen` (`library_id`, `last_seen`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容缺口聚合';

CREATE TABLE `kb_content_gap_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `gap_id` bigint DEFAULT NULL COMMENT '缺口ID',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '空间业务键',
  `signal_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'NO_ANSWER/ZERO_CITATION/THUMB_DOWN/FOLLOW_UP',
  `conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对话ID',
  `feedback_id` bigint DEFAULT NULL COMMENT '反馈ID（点踩信号）',
  `detail` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '信号上下文（截断）',
  PRIMARY KEY (`id`),
  KEY `idx_gap_event_gap` (`gap_id`),
  KEY `idx_gap_event_library_time` (`library_id`, `created_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容缺口信号事件';

CREATE TABLE `kb_content_gap_cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '空间业务键',
  `label` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '簇标签（取首个问题截断）',
  `centroid_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '512 维质心 JSON',
  `size` int NOT NULL DEFAULT 0 COMMENT '簇内缺口数',
  `last_seen` datetime(6) DEFAULT NULL COMMENT '最近信号时间',
  PRIMARY KEY (`id`),
  KEY `idx_gap_cluster_library_seen` (`library_id`, `last_seen`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容缺口聚类质心';

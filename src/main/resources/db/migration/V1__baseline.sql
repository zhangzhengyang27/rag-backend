-- Flyway V1 基线:由生产 schema(Hibernate ddl-auto=update 生成的 12 张表)导出。
-- 已有数据的库通过 baseline-on-migrate 跳过本脚本;全新库由本脚本建表,后续 schema 演进一律走 db/migration。

CREATE TABLE `chat_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `content` longtext COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `role` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色：user=用户消息，ai=助手消息',
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID',
  `conversation_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对话ID：同一用户可拥有多个独立对话；历史旧数据为空',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天历史';
CREATE TABLE `kb_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `detail` longtext COLLATE utf8mb4_unicode_ci COMMENT '告警详情（命中情况/失败明细等）',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '空间业务键（default=公共知识库）',
  `message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '告警标题',
  `resolved` bit(1) NOT NULL COMMENT '是否已处理',
  `resolved_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理人',
  `severity` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '告警类型：EVAL_LOW_HITRATE / DOCS_SYNC_FAILED 等',
  `dedupe_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '去重键：未处理时=type（唯一），处理后置 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_alert_dedupe` (`dedupe_key`),
  KEY `idx_kb_alert_resolved` (`library_id`,`resolved`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营告警';
CREATE TABLE `kb_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `action` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作类型：UPLOAD/IMPORT_URL/IMPORT_DIR/DELETE/REBUILD_INDEX/UPDATE_QUOTA 等',
  `detail` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作详情',
  `operator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人',
  `target_id` bigint DEFAULT NULL COMMENT '操作对象ID（可空，如目录导入为 null）',
  `target_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作对象类型：DOCUMENT/DIR/TENANT 等',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（平台管理员可跨租户查；空表示平台级操作）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';
CREATE TABLE `kb_chat_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `answer` longtext COLLATE utf8mb4_unicode_ci COMMENT '反馈时的回答快照',
  `comment` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户评语（可空）',
  `conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对话ID（可空：未随对话提交）',
  `question` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '反馈时的问题快照（截断存储，独立于聊天历史可追溯）',
  `rating` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '评价：UP=点赞，DOWN=点踩',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度）',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '反馈用户',
  PRIMARY KEY (`id`),
  KEY `idx_kb_feedback_library` (`library_id`),
  KEY `idx_kb_feedback_library_rating` (`library_id`,`rating`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问答反馈（点赞/点踩）';
CREATE TABLE `kb_chat_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `answer_char_count` int DEFAULT NULL COMMENT '回答字符数',
  `conversation_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对话ID',
  `elapsed_ms` bigint DEFAULT NULL COMMENT '问答耗时（毫秒）',
  `hit_count` int DEFAULT NULL COMMENT '检索命中片段数',
  `input_tokens` int DEFAULT NULL COMMENT '输入 token 估算',
  `output_tokens` int DEFAULT NULL COMMENT '输出 token 估算',
  `question` longtext COLLATE utf8mb4_unicode_ci COMMENT '用户问题',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  `total_tokens` int DEFAULT NULL COMMENT '总 token 估算（输入+输出）',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户ID',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天用量统计';
CREATE TABLE `kb_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `char_count` int DEFAULT NULL COMMENT '字符数',
  `chunk_index` int NOT NULL COMMENT '分块序号',
  `doc_id` bigint NOT NULL COMMENT '所属文档ID',
  `text` longtext COLLATE utf8mb4_unicode_ci COMMENT '分块正文',
  `vector_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Redis 向量存储的 ID（addAll 返回），用于按 ID 精准删除旧向量',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  PRIMARY KEY (`id`),
  KEY `idx_kb_chunk_doc` (`doc_id`),
  KEY `idx_kb_chunk_library_doc` (`library_id`,`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档分块';
CREATE TABLE `kb_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `chunk_count` int DEFAULT NULL COMMENT '分块数量（已成功向量化的片段数）',
  `content` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Markdown 原文（预览用；分块内容在 kb_chunk）',
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '正文内容哈希（用于去重，避免重复导入）',
  `file_name` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始文件名',
  `file_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '源文件相对路径（溯源与目录导入用）',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小（字节）',
  `imported_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '导入用户',
  `status` enum('FAILED','INDEXED','PENDING') COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败',
  `title` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文档标题（默认取文件名）',
  `space` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库空间/分类（默认取 filePath 顶级目录，如 前端/后端/架构与工程；也可手动指定，用于空间隔离检索）',
  `site_slug` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '站点 slug（from 文档 frontmatter.slug，相对 /docs/ 的跳转标识；无则空）',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  `source_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源类型：UPLOAD=单文件上传，URL=网页导入，DIRECTORY=目录导入，SYNC=docs 目录自动同步（受管，随目录对账增删改）',
  `category` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'frontmatter category（课程级标签，如 JavaScript/Nginx；写入向量 metadata 供分类范围检索）',
  PRIMARY KEY (`id`),
  KEY `idx_kb_doc_library` (`library_id`),
  KEY `idx_kb_doc_library_space` (`library_id`,`space`),
  KEY `idx_kb_doc_library_hash` (`library_id`,`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档元数据';
CREATE TABLE `kb_rag_eval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `created_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `expected_keywords` text COLLATE utf8mb4_unicode_ci COMMENT '期望命中的关键字（逗号分隔，全部命中才算召回成功）',
  `note` text COLLATE utf8mb4_unicode_ci COMMENT '备注/预期答案说明',
  `question` text COLLATE utf8mb4_unicode_ci COMMENT '评测问题',
  `expected_doc_ids` text COLLATE utf8mb4_unicode_ci COMMENT '期望命中的文档 id（逗号分隔，用于 NDCG 排序质量评估；可为空则跳过该用例的 NDCG 计算）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评测用例';
CREATE TABLE `kb_rag_eval_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `case_count` int NOT NULL COMMENT '评测用例数',
  `eval_date` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评测日期（yyyy-MM-dd，每天一条）',
  `hit_rate` double NOT NULL COMMENT '命中率',
  `mrr` double NOT NULL COMMENT 'MRR',
  `ndcg` double NOT NULL COMMENT 'NDCG',
  `snapshot` text COLLATE utf8mb4_unicode_ci COMMENT '评测快照（JSON：未命中用例的 question/missingKeywords/topTitles）',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '所属租户 code',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eval_report_date` (`eval_date`),
  UNIQUE KEY `uk_eval_report_date_tenant` (`eval_date`,`library_id`),
  UNIQUE KEY `uk_eval_report_date_library` (`eval_date`,`library_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 评测趋势报告（每日一条）';
CREATE TABLE `kb_retrieval_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `elapsed_ms` bigint DEFAULT NULL COMMENT '检索耗时（毫秒）',
  `fused_count` int DEFAULT NULL COMMENT 'RRF 融合后候选数',
  `hit_summary` text COLLATE utf8mb4_unicode_ci COMMENT '命中片段摘要（docId#chunkIndex，逗号分隔）',
  `keyword_count` int DEFAULT NULL COMMENT '关键词召回数',
  `keyword_used` bit(1) DEFAULT NULL COMMENT '是否命中关键词通道',
  `original_query` text COLLATE utf8mb4_unicode_ci COMMENT '原始查询',
  `path_filter` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目录过滤前缀',
  `rerank_model_available` bit(1) DEFAULT NULL COMMENT '重排模型是否可用',
  `reranked` bit(1) DEFAULT NULL COMMENT '是否执行语义重排',
  `result_count` int DEFAULT NULL COMMENT '最终返回给 LLM 的片段数',
  `search_query` text COLLATE utf8mb4_unicode_ci COMMENT '检索用查询（可能经改写）',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户ID',
  `vector_count` int DEFAULT NULL COMMENT '向量召回数',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库检索追踪';
CREATE TABLE `kb_runtime_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `cfg_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置键（如 retrieval.final-top-k）',
  `cfg_value` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '覆盖值（字符串形态，按键类型解析）',
  `updated_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后修改人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_cfg_key` (`cfg_key`),
  KEY `idx_kb_cfg_key` (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库运行时配置覆盖';
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `created_date` datetime(6) DEFAULT NULL COMMENT '创建时间',
  `updated_date` datetime(6) DEFAULT NULL COMMENT '更新时间',
  `version` bigint DEFAULT NULL COMMENT '乐观锁版本号，防止并发更新互相覆盖',
  `nickname` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `password` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码（BCrypt 加密）',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色：USER=普通用户，ADMIN=管理员',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名（登录账号）',
  `library_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户业务键（组织隔离维度；存量数据回填 default）',
  `chat_limit` bigint DEFAULT NULL COMMENT '个人知识库每日问答限额覆盖（null=跟随全局配置）',
  `doc_limit` bigint DEFAULT NULL COMMENT '个人知识库文档数限额覆盖（null=跟随全局配置 app.kb.library.user-doc-limit）',
  `retrieval_limit` bigint DEFAULT NULL COMMENT '个人知识库每日检索限额覆盖（null=跟随全局配置）',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：ACTIVE=正常，DISABLED=禁用（登录与鉴权均拒绝）；存量 null 视为 ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

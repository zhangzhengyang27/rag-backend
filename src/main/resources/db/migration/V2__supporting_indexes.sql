-- V2 补充支撑索引：高频查询路径与保留期清理任务（表结构本身仍由 V1 基线承载）
-- chat_history：按会话/对话查询与删除是最高频路径（每次问答读写）
CREATE INDEX idx_chat_history_session_conversation ON chat_history (session_id, conversation_id);
-- kb_chat_usage：用量统计按空间+时间范围聚合
CREATE INDEX idx_kb_chat_usage_library_created ON kb_chat_usage (library_id, created_date);
-- kb_retrieval_trace / kb_audit_log：只增不清的观测数据，保留期清理按 created_date 删除
CREATE INDEX idx_kb_retrieval_trace_created ON kb_retrieval_trace (created_date);
CREATE INDEX idx_kb_audit_log_created ON kb_audit_log (created_date);

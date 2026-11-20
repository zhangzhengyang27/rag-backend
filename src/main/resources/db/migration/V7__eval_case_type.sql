-- Golden Set 分流：检索类用例进入 HitRate，回答格式/引用类不参与检索指标。
ALTER TABLE kb_rag_eval ADD COLUMN case_type VARCHAR(32) NOT NULL DEFAULT 'RETRIEVAL';
UPDATE kb_rag_eval SET case_type = 'ANSWER_FORMAT' WHERE question LIKE '%不要出现任何 [n]%';

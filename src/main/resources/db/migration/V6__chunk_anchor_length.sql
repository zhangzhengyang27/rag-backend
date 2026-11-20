-- anchor 是标题 slug，超长标题会超过 255；放大到 1024，避免回填/重建索引时被截断。
ALTER TABLE kb_chunk MODIFY COLUMN anchor VARCHAR(1024) NULL;

-- 表格语义：把以 | 开头的连续表格行标记为 table，并保留首行表头供检索/展示。
ALTER TABLE kb_chunk
    ADD COLUMN table_header VARCHAR(1024) NULL COMMENT '表格首行表头（chunk_type=table 时）' AFTER code_lang;

UPDATE kb_chunk
SET chunk_type = 'table',
    table_header = SUBSTRING_INDEX(text, '\n', 1)
WHERE chunk_type = 'text'
  AND text LIKE '|%|%';

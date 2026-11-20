-- 知识治理中心：责任人、有效期与最近复核时间。
ALTER TABLE kb_document ADD COLUMN owner VARCHAR(64) NULL;
ALTER TABLE kb_document ADD COLUMN review_due_date DATETIME NULL;
ALTER TABLE kb_document ADD COLUMN last_reviewed_at DATETIME NULL;

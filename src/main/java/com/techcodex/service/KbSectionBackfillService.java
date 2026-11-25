package com.techcodex.service;

/**
 * 父子分块父级回填：把存量 kb_chunk 按「文档 + 标题序号」聚合为 kb_section，
 * 并回填 kb_chunk.parent_id。只重建 section 与关联，不重算向量。
 */
public interface KbSectionBackfillService {

    Result backfill();

    record Result(int sections, int chunks) {
    }
}

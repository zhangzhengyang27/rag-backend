package org.example.dto;

/**
 * 检索参数覆盖快照（A/B 评测用）。
 *
 * <p>用于在不改动全局 {@code KbConfig} 的前提下，对单次检索临时覆盖部分参数，
 * 从而支持"同用例集、不同检索配置"的横向对比。字段为 {@code null} 时表示
 * 沿用 {@code KbConfig} 当前值（不覆盖），避免污染共享配置导致并发互相影响。</p>
 */
public class SearchOptions {

    /** 是否启用语义重排（null 表示沿用配置） */
    private Boolean rerankEnabled;
    /** 是否启用关键词(BM25)召回（null 表示沿用配置） */
    private Boolean bm25Enabled;
    /** 召回并最终返回的片段数 topK（null 表示沿用调用方传入的 topK） */
    private Integer topK;

    public static SearchOptions of() {
        return new SearchOptions();
    }

    public SearchOptions rerankEnabled(Boolean value) {
        this.rerankEnabled = value;
        return this;
    }

    public SearchOptions bm25Enabled(Boolean value) {
        this.bm25Enabled = value;
        return this;
    }

    public SearchOptions topK(Integer value) {
        this.topK = value;
        return this;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public Boolean getBm25Enabled() {
        return bm25Enabled;
    }

    public Integer getTopK() {
        return topK;
    }
}

package org.example.vo;

/**
 * 检索 A/B 对比结果：同用例集、两组检索参数各跑一遍评测，输出对照与差值。
 *
 * <p>差值均为「变体 − 基线」，正值表示变体更优。</p>
 */
public class KbEvalAbResult {

    /** 基线组标签（如 "当前配置"） */
    private String baselineLabel;
    /** 变体组标签（如 "rerank=off"） */
    private String variantLabel;
    /** 基线组评测结果 */
    private KbEvalResult baseline;
    /** 变体组评测结果 */
    private KbEvalResult variant;
    /** 变体相对基线的命中率差值（0~1，正值更优） */
    private double hitRateDiff;
    /** 变体相对基线的 MRR 差值（正值更优） */
    private double mrrDiff;
    /** 变体相对基线的 NDCG 差值（正值更优） */
    private double ndcgDiff;

    public KbEvalAbResult() {
    }

    public KbEvalAbResult(String baselineLabel, String variantLabel,
                          KbEvalResult baseline, KbEvalResult variant) {
        this.baselineLabel = baselineLabel;
        this.variantLabel = variantLabel;
        this.baseline = baseline;
        this.variant = variant;
        this.hitRateDiff = variant.getHitRate() - baseline.getHitRate();
        this.mrrDiff = variant.getMrr() - baseline.getMrr();
        this.ndcgDiff = variant.getNdcg() - baseline.getNdcg();
    }

    public String getBaselineLabel() {
        return baselineLabel;
    }

    public String getVariantLabel() {
        return variantLabel;
    }

    public KbEvalResult getBaseline() {
        return baseline;
    }

    public KbEvalResult getVariant() {
        return variant;
    }

    public double getHitRateDiff() {
        return hitRateDiff;
    }

    public double getMrrDiff() {
        return mrrDiff;
    }

    public double getNdcgDiff() {
        return ndcgDiff;
    }
}

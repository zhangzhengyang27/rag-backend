package org.example.vo;

/**
 * 评测趋势报告视图（供趋势接口返回，避免暴露实体审计/版本字段）。
 */
public class KbEvalReportView {

    private String date;
    private Integer caseCount;
    private Double hitRate;
    private Double mrr;
    private Double ndcg;

    public KbEvalReportView() {
    }

    public KbEvalReportView(String date, Integer caseCount, Double hitRate, Double mrr, Double ndcg) {
        this.date = date;
        this.caseCount = caseCount;
        this.hitRate = hitRate;
        this.mrr = mrr;
        this.ndcg = ndcg;
    }

    public String getDate() {
        return date;
    }

    public Integer getCaseCount() {
        return caseCount;
    }

    public Double getHitRate() {
        return hitRate;
    }

    public Double getMrr() {
        return mrr;
    }

    public Double getNdcg() {
        return ndcg;
    }
}

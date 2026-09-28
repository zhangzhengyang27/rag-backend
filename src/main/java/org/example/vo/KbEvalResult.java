package org.example.vo;

import java.util.List;

/**
 * RAG 评测汇总结果。
 */
public class KbEvalResult {

    /** 总用例数 */
    private int total;
    /** 命中用例数 */
    private int hit;
    /** 未命中用例数 */
    private int miss;
    /** 命中率 */
    private double hitRate;
    /** MRR（平均倒数排名）：首个命中在返回列表中的倒数名次的均值，值越大说明相关结果排得越靠前 */
    private double mrr;
    /** NDCG@K（标准化折扣累积收益）：衡量排序质量；仅对声明了期望命中文档的用例计算，无声明时为 0 */
    private double ndcg;
    /** 逐用例明细 */
    private List<CaseResult> cases;

    public static class CaseResult {
        private Long caseId;
        private String question;
        private String expectedKeywords;
        private boolean hit;
        private int returnedCount;
        private int firstHitRank;
        /** 本次检索返回的片段标题（Top-N，用于失败归因：看召回的是什么） */
        private List<String> topTitles;
        /** 该用例的 NDCG@K（未声明期望文档时为 0） */
        private double ndcg;
        /** 未命中时缺失的期望关键字（在所有返回片段中都不存在；命中时为空） */
        private List<String> missingKeywords;

        public CaseResult() {
        }

        public CaseResult(Long caseId, String question, String expectedKeywords,
                          boolean hit, int returnedCount, int firstHitRank, List<String> topTitles) {
            this(caseId, question, expectedKeywords, hit, returnedCount, firstHitRank, topTitles, 0d);
        }

        public CaseResult(Long caseId, String question, String expectedKeywords,
                          boolean hit, int returnedCount, int firstHitRank, List<String> topTitles, double ndcg) {
            this(caseId, question, expectedKeywords, hit, returnedCount, firstHitRank, topTitles, ndcg, List.of());
        }

        public CaseResult(Long caseId, String question, String expectedKeywords,
                          boolean hit, int returnedCount, int firstHitRank, List<String> topTitles, double ndcg,
                          List<String> missingKeywords) {
            this.caseId = caseId;
            this.question = question;
            this.expectedKeywords = expectedKeywords;
            this.hit = hit;
            this.returnedCount = returnedCount;
            this.firstHitRank = firstHitRank;
            this.topTitles = topTitles;
            this.ndcg = ndcg;
            this.missingKeywords = missingKeywords;
        }

        public Long getCaseId() {
            return caseId;
        }

        public String getQuestion() {
            return question;
        }

        public String getExpectedKeywords() {
            return expectedKeywords;
        }

        public boolean isHit() {
            return hit;
        }

        public int getReturnedCount() {
            return returnedCount;
        }

        public int getFirstHitRank() {
            return firstHitRank;
        }

        public List<String> getTopTitles() {
            return topTitles;
        }

        public double getNdcg() {
            return ndcg;
        }

        public List<String> getMissingKeywords() {
            return missingKeywords;
        }
    }

    public KbEvalResult() {
    }

    public KbEvalResult(int total, int hit, int miss, double hitRate, double mrr, List<CaseResult> cases) {
        this(total, hit, miss, hitRate, mrr, 0d, cases);
    }

    public KbEvalResult(int total, int hit, int miss, double hitRate, double mrr, double ndcg, List<CaseResult> cases) {
        this.total = total;
        this.hit = hit;
        this.miss = miss;
        this.hitRate = hitRate;
        this.mrr = mrr;
        this.ndcg = ndcg;
        this.cases = cases;
    }

    public int getTotal() {
        return total;
    }

    public int getHit() {
        return hit;
    }

    public int getMiss() {
        return miss;
    }

    public double getHitRate() {
        return hitRate;
    }

    public double getMrr() {
        return mrr;
    }

    public double getNdcg() {
        return ndcg;
    }

    public List<CaseResult> getCases() {
        return cases;
    }
}

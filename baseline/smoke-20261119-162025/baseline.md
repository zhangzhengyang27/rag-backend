# RAG 检索基线（P1-01）

- 生成时间：2026-11-19 16:20:27
- 后端：http://127.0.0.1:8082
- 评测 topK：5（全量）
- 评测用例：2
- 命中 / 未命中：2 / 0
- HitRate：1.0
- MRR：1.0
- NDCG：0.0

## 检索延迟（/api/kb/search，20 次采样）
- 成功采样：20
- 平均：0.025s
- P95：0.038s

## 当前检索参数（运行时配置）
- retrieval.vector-top-n：20
- retrieval.bm25-top-n：20
- retrieval.rrf-k：60
- retrieval.final-top-k：6
- retrieval.min-score：0.4
- retrieval.bm25-enabled：true
- retrieval.rerank-enabled：false
- retrieval.relevance-check-enabled：true

## 规模
- totalDocuments：1
- totalChunks：3
- indexedDocuments：1
- pendingDocuments：0
- failedDocuments：0
- totalVectorCount：3

## 产物
- eval.json
- config.json
- stats.json
- trend.json
- redis.txt
- latency.txt

> 说明：HitRate/MRR/NDCG 来自现有 KbEvalServiceImpl（检索级离线指标）；生成级指标（faithfulness/context precision/context recall）在 M2.2 评测 2.0 补齐。

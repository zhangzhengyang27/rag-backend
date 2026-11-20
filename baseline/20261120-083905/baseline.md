# RAG 全量检索基线（rerank 开启，P1-01 补测）

- 生成时间：2026-11-20 08:39:32
- 后端：http://127.0.0.1:8084
- 索引范围：全量 4,557 篇 / 273,394 块
- 说明：修复 ScoringModel 注入后补跑的 rerank-on 基线，用于与 rerank-off 对比
- 评测 topK：5，limit=30
- 评测用例：20
- 命中 / 未命中：10 / 10
- HitRate：0.5
- MRR：0.9166666666666666
- NDCG：0.6858127539751069

## rerank 说明
- 模型：bge-reranker-base INT8 ONNX（本地推理，`rerankScoringModel` setter 注入已生效）
- `kb_retrieval_trace`：`reranked=1`、`rerank_model_available=1`
- 对比 rerank-off：MRR 0.8825→0.9167，NDCG 0.5069→0.6858；但 P95 0.212s→0.838s

## 检索延迟（/api/kb/search，20 次采样）
- 成功采样：20
- 平均：0.525s
- P95：0.838s

## 当前检索参数（运行时配置）
- retrieval.vector-top-n：20
- retrieval.bm25-top-n：20
- retrieval.rrf-k：60
- retrieval.final-top-k：6
- retrieval.min-score：0.4
- retrieval.bm25-enabled：true
- retrieval.rerank-enabled：true
- retrieval.relevance-check-enabled：true

## 规模
- totalDocuments：4557
- totalChunks：273394
- indexedDocuments：4557
- pendingDocuments：0
- failedDocuments：0
- totalVectorCount：273394

## 产物
- eval.json
- config.json
- stats.json
- trend.json
- redis.txt
- latency.txt

> 说明：HitRate/MRR/NDCG 来自现有 KbEvalServiceImpl（检索级离线指标）；生成级指标（faithfulness/context precision/context recall）在 M2.2 评测 2.0 补齐。

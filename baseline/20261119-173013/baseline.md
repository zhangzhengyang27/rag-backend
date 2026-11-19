# RAG 检索基线（P1-01）

- 生成时间：2026-11-19 17:30:23
- 后端：http://127.0.0.1:8083
- 评测 topK：5，limit=30
- 评测用例：20
- 命中 / 未命中：10 / 10
- HitRate：0.5
- MRR：0.885
- NDCG：0.5091234979777017

## 检索延迟（/api/kb/search，20 次采样）
- 成功采样：20
- 平均：0.224s
- P95：0.377s

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
- totalDocuments：1713
- totalChunks：134942
- indexedDocuments：1713
- pendingDocuments：0
- failedDocuments：0
- totalVectorCount：134942

## 产物
- eval.json
- config.json
- stats.json
- trend.json
- redis.txt
- latency.txt

> 说明：HitRate/MRR/NDCG 来自现有 KbEvalServiceImpl（检索级离线指标）；生成级指标（faithfulness/context precision/context recall）在 M2.2 评测 2.0 补齐。

## 样本范围与环境（重要）

- 这是**四个大类的代表样本**，不是 4,557 篇全站基线：已索引 `01-Java` 343 篇、`02-Node` 253 篇、`03-Python` 251 篇、`04-前端` 866 篇，共 1,713 篇 / 134,942 块。
- 环境：本机 MySQL 8.4.7（127.0.0.1:3306，隔离库 `langchain4j_rag`，未改动原有 `langchain4j` 库）+ 独立 Redis Stack（127.0.0.1:6381）。
- `retrieval.rerank-enabled=false`：为控制本机内存关闭 bge-reranker，P95 只代表「向量 + BM25 + RRF」链路；生产基线需在 rerank 开启环境复测。
- 全量索引按当前 500 字分块估算约 6–7GB Redis；本机 16GB 内存不足，需独立 Redis 或更大内存机器补跑。
- 评测用例：20 条，覆盖 Java / Node / Python / 前端；`expected_doc_ids` 已按样本文档回填。

# RAG 检索全量基线（P1-01）

- 生成时间：2026-11-19 18:44:24
- 后端：http://127.0.0.1:8084
- 索引范围：全量 4,557 篇 Markdown（01-Java / 02-Node / 03-Python / 04-前端 / 其他）
- 评测 topK：5，limit=30
- 评测用例：20
- 命中 / 未命中：10 / 10
- HitRate：0.5
- MRR：0.8825
- NDCG：0.5069323104357591

## 检索延迟（/api/kb/search，20 次采样）
- 成功采样：20
- 平均：0.071s
- P95：0.212s

## 100 次延迟复测（同一索引，热态）
- 成功采样：100
- 平均：0.109s
- P50：0.050s
- P95：0.455s
- P99：0.648s
- 最大：0.711s

> 20 次采样与 100 次复测存在波动，两个数值都保留；全量索引刚完成时 Redis 仍有写入/缓存冷热差异，正式对外 SLA 建议在稳定运行后复测。

## 全量范围与环境（重要）

- 全量 docs：4,557 篇 / 273,394 块；DB 全部 INDEXED，0 pending / 0 failed。
- 独立 Redis Stack 6382：5.19GB / maxmemory 8GB；从 1,713 篇样本 RDB 续跑，新增 2,844 篇、幂等跳过 1,713 篇、失败 0。
- MySQL 8.4.7（127.0.0.1:3306，隔离库 `langchain4j_rag`）；样本 `langchain4j` 库未改动。
- `retrieval.rerank-enabled=false`：本基线是「向量 + BM25 + RRF」链路；rerank 开启的注入问题另记，不能把本次 P95 当作 rerank 生产延迟。

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

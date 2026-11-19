# RAG 检索基线

## 怎么跑

```bash
cd rag-backend
ADMIN_USER=admin ADMIN_PASS=*** BASE_URL=http://localhost:8081 ./scripts/eval-baseline.sh
```

- 后端必须已启动，且 `kb_rag_eval` 里已有 Golden Set 用例。
- 8081 被占用时用 `BASE_URL=http://localhost:8082`。
- 只跑部分用例：`LIMIT=40`。
- 采样检索 P95 会消耗个人检索配额，默认 20 次；配额紧张时把脚本里的 `round` 改小。
- 产物落在 `baseline/<时间戳>/`，包含 eval/config/stats/trend/redis/latency 和 `baseline.md`。

## 2026-11-19 冒烟记录（非生产基线）

环境：临时 MySQL 8.4（127.0.0.1:3309）+ Redis Stack（127.0.0.1:6380），
1 篇测试 Markdown、2 条评测用例。目的是验证 V4 迁移、代码块索引和检索字段透传，
不代表 4,500 篇真实文档的基线。

结果：

- 文档 1 / 分块 3 / 向量 3
- HitRate 1.0，MRR 1.0，NDCG 0（用例未填 expectedDocIds）
- `/api/kb/search` 20 次采样：平均 0.025s，P95 0.038s
- 代码块命中 `chunkType=code`、`codeLang=java`、`headingPath=HashMap > 扩容机制`

## 2026-11-19 本地 MySQL 样本基线

用户提供本机 MySQL root 密码后执行；原有 `langchain4j` 库是老 schema（6 篇文档、无 Flyway 记录），
为不破坏原数据，新建隔离库 `langchain4j_rag` + 独立 Redis Stack 6381。

- 环境：MySQL 8.4.7（127.0.0.1:3306，`langchain4j_rag`）+ Redis Stack（127.0.0.1:6381）
- 样本：`01-Java` 343 + `02-Node` 253 + `03-Python` 251 + `04-前端` 866 = 1,713 篇
- 规模：134,942 个分块 / 134,942 个向量
- 20 条评测用例：HitRate 0.5，MRR 0.885，NDCG 0.509
- 检索 P95：0.377s（rerank 关闭；向量 + BM25 + RRF）
- 报告：`baseline/20261119-173013/baseline.md`

这是**四类代表样本**，不是 4,557 篇全站基线；全量已于 2026-11-19 在独立 Redis 6382 上补跑（见下节）。

## 2026-11-19 全量基线（独立 Redis 6382）

样本基线之后，按要求在独立 Redis 上补全全量。用 6381 的 RDB 快照启动 6382，
保留前 1,713 篇样本并续跑剩余 2,844 篇；没有重跑样本，也没有改动原 `langchain4j` 库。

- 环境：MySQL 8.4.7（`langchain4j_rag`）+ 独立 Redis Stack 6382（maxmemory 8GB）
- 全量：4,557 篇 / 273,394 块；全部 INDEXED，0 pending / 0 failed
- Redis：5.19GB（实际低于此前 6–7GB 估算）
- 20 条评测用例：HitRate 0.5，MRR 0.8825，NDCG 0.5069
- 检索 P95：0.212s（20 次采样）/ 0.455s（100 次复测）；rerank 关闭
- 报告：`baseline/20261119-184414-full/baseline.md`
- 同步结果：新增 2,844、跳过 1,713、更新 0、改名 0、删除 0、失败 0

## 后续补测

1. **rerank 开启基线尚未完成**：本次把 `retrieval.rerank-enabled=true` 后，模型在启动时加载成功，但 `kb_retrieval_trace` 显示 `rerank_model_available=0`、`reranked=0`，指标与关闭时完全一致。需要先排查 `ScoringModel` 未注入 `KbSearchServiceImpl` 的原因，再补 rerank-on 基线；不要把当前 P95 当作 rerank 生产延迟。
2. 全量基线已落盘并提交，已打 `baseline-20261119-full` tag；原 `baseline-20261119` 仍指样本基线。
3. 生产环境复测时，`BASE_URL` 指向线上后端，`KB_REDIS_HOST/PORT/PASSWORD` 指向线上索引。

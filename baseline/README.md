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

这是**四类代表样本**，不是 4,557 篇全站基线。全量索引按当前 500 字分块估算约 6–7GB Redis，
本机 16GB 内存不足，需独立 Redis 或更大内存机器补跑。

## 全量基线还缺什么

1. 全量 4,557 篇需要约 6–7GB Redis 内存；本机需独立 Redis 实例或换更大内存机器。
2. `rerank-enabled=false` 的 P95 不能代表生产；补跑时开启 bge-reranker。
3. 全量完成后，重跑本脚本并 `TAG_BASELINE=1` 打 `baseline-YYYYMMDD` tag。
4. 生产环境直接跑时，`BASE_URL` 指向线上后端，`REDIS_HOST/PORT/PASSWORD` 指向线上索引。

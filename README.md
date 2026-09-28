# rag-backend

面向 [tech-codex](../tech-codex) 技术文档站的 RAG 知识库问答后端。

Spring Boot 3.4.4（Java 17）+ LangChain4j 1.19.0-beta29 + MySQL + Redis Stack（RediSearch 向量/BM25 索引）。

## 功能模块

| 模块 | 端点前缀 | 说明 |
|---|---|---|
| 认证 | `/auth` | 注册/登录，JWT 签发，登录防爆破锁定 |
| 知识库问答 | `/api/kb` | SSE 流式问答（多轮会话）、混合检索、点赞点踩、历史管理 |
| 个人知识库 | `/api/kb/my` | 个人空间（`u-{username}`）文档上传/管理、配额余量 |
| 管理后台 | `/admin/kb` | 文档全生命周期、目录导入/docs 同步、检索评测（Golden Set/A-B/趋势）、用量/审计/告警、用户与配额管理、运行时配置中心 |

## RAG 流水线

Tika 解析（PDF/Word/Excel/PPT/HTML → Markdown）→ 标题+段落切分 → 本地 ONNX 向量化（bge-small-zh-v15，512 维）
→ Redis 向量库 + RediSearch 中文 BM25 双路召回 → RRF 融合 → 本地 ONNX 重排（bge-reranker-base INT8）
→ DeepSeek 流式生成（查询改写、意图路由、相关度拒答、敏感词双向审核）。

本地向量化与重排模型离线运行，无需 API Key；仅聊天模型走 DeepSeek。

## 本地开发

要求 JDK 17（Maven Enforcer 强制）、MySQL 8、Redis Stack（含 RediSearch 模块）。

```bash
cp .env.example .env          # 填入 DEEPSEEK_API_KEY / JWT_SECRET / DB_PASSWORD
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
# 本机示例：export DB_PASSWORD=xxx && export JWT_SECRET=xxx && export DEEPSEEK_API_KEY=xxx
mvn spring-boot:run           # 端口 8081
```

前端（tech-codex）通过 Next.js rewrites 把 `/kb-auth` `/kb-api` `/kb-admin` 代理到 `http://localhost:8081`。
启动约 20s 后自动对账 `../tech-codex/docs`（约 4000 篇 Markdown）入库并向量化。

重排模型默认读取项目内 `models/bge-reranker-base-int8/`（.gitignore 排除），
首次获取：从 HuggingFace 下载 `keisuke-miyako/bge-reranker-base-onnx-int8` 的
`model_quantized.onnx` 与 `tokenizer.json` 放入该目录；缺失时检索自动降级为纯 RRF，不阻断启动。

## 测试

```bash
mvn test        # 单元测试（不依赖外部服务）
mvn test -Dtest=KbIntegrationSmokeTest   # 集成冒烟（需真实 MySQL/Redis：itest profile 独立库与前缀）
```

集成测试需要环境变量：`DB_PASSWORD`（本机 MySQL 密码）、`JWT_SECRET`、`DEEPSEEK_API_KEY`（假值即可，流式模型已被 mock）。

## 关键配置

全部集中在 `application.properties`（注释齐全），密钥/凭据一律环境变量注入（见 `.env.example`）。
生产部署见 [DEPLOY.md](DEPLOY.md)。

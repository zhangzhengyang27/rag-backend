# 部署说明（NAS / Docker）

生产形态：NAS 上 Docker Compose 运行两个容器，经 `kbnet` 网络互通，公网入口为
`https://docs.zhangzhengyang.com`（frp/VPS 转发 → tech-codex 容器:4200）。

```
用户 → docs.zhangzhengyang.com → frp/VPS → tech-codex (Next.js:4200)
                                              ├─ 文档内容：SSR/SSG 直接读容器内 docs/
                                              └─ /kb-auth /kb-api /kb-admin ──kbnet──▶ rag-backend:8081
rag-backend ──▶ kb-redis-stack（向量+BM25 索引，同 kbnet）
            ──▶ dev-stack_devnet 的 dev-mysql（MySQL，langchain4j 库）
            ──▶ 只读挂载 /docs（tech-codex docs 对账同步）与 /models（重排模型）
```

## 构建与启动

```bash
cd rag-backend
docker compose up -d --build
```

- 多阶段构建（maven:3.9-temurin-17 → temurin-17-jre），阿里云镜像源（`settings-aliyun.xml`）。
- 凭据来自同目录 `.env`（`env_file` 引入），模板见 `.env.example`。
- 仅绑定 `127.0.0.1:18080` 供 NAS 本机调试；服务间通信走内网，不对外暴露 8081。

## 关键环境变量（docker-compose.yml 已固定）

| 变量 | 值 | 说明 |
|---|---|---|
| `KB_REDIS_HOST` | `kb-redis-stack` | 向量/BM25 索引所在 Redis Stack |
| `KB_DOCS_ROOT` | `/docs` | docs 只读挂载点，启动/发布钩子对账同步 |
| `KB_RERANK_MODEL_PATH` / `KB_RERANK_TOKENIZER_PATH` | `/models/...` | 重排模型挂载点 |
| `CORS_ALLOWED_ORIGINS` | `https://docs.zhangzhengyang.com` | 必须与公网域名一致 |

## 前端联动注意（重要）

tech-codex 的 rewrites 把后端地址**在构建期烘焙进 routes-manifest**：
前端镜像构建时必须注入 `KB_BACKEND_ORIGIN=http://rag-backend:8081`（见其 Dockerfile），
运行期改环境变量无效。前端发布新镜像 = docs 内容更新 + 触发后端 docs 对账的完整流程。

## 日常运维

- **导入白名单**：目录导入/docs 同步仅允许 `app.kb.import.allowed-roots`（默认 = `KB_DOCS_ROOT`）白名单内的路径。
- **数据增长**：检索追踪/聊天用量/审计日志由保留策略每日清理（`app.data-retention.*`，默认 90/180/365 天，0=不清理）。
- **Schema 变更**：由 Flyway 管理（`db/migration`，V1 存量基线 + 增量脚本），Hibernate 仅 validate；
  升级前建议备份 MySQL。存量库首次启动会自动 baseline，无需手工干预。
- **验证**：`curl http://127.0.0.1:18080/actuator/health` 应返回 UP。

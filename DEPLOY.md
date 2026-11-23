# 部署说明（NAS / Docker）

生产形态：NAS 上 Docker Compose 运行两个容器，经 `kbnet` 网络互通，公网入口为
`https://docs.zhangzhengyang.com`（frp/VPS 转发 → tech-codex 容器:4200）。

```
用户 → docs.zhangzhengyang.com → frp/VPS → tech-codex (Next.js:4200)
                                              ├─ 文档内容：SSR 运行时读后端 /content/**
                                              └─ /kb-auth /kb-api /kb-admin ──kbnet──▶ rag-backend:8081
rag-backend ──▶ kb-redis-stack（向量+BM25 索引，同 kbnet）
            ──▶ dev-stack_devnet 的 dev-mysql（MySQL，langchain4j 库，唯一内容源）
            ──▶ 只读挂载 /models（重排模型；DB-only 不再挂 docs）
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
| `KB_CMS_MIRROR_ENABLED` | `false` | 关闭 CMS 写回 docs；内容只落 MySQL（迁移期可设 true 保留镜像） |
| `KB_RERANK_MODEL_PATH` / `KB_RERANK_TOKENIZER_PATH` | `/models/...` | 重排模型挂载点 |
| `CORS_ALLOWED_ORIGINS` | `https://docs.zhangzhengyang.com` | 必须与公网域名一致 |

## 前端联动注意（重要）

tech-codex 的 rewrites 把后端地址**在构建期烘焙进 routes-manifest**：
前端镜像构建时必须注入 `KB_BACKEND_ORIGIN=http://rag-backend:8081`（见其 Dockerfile），
运行期改环境变量对 SSR 请求有效。DB-only 模式下内容发布不再需要重建前端镜像，Next 通过 CMS 代理失效缓存。

## 日常运维

- **历史导入**：一次性收编仍使用 `/admin/kb/cms/import`，需要临时挂载 docs；导入完成后可移除挂载与文件。
- **数据增长**：检索追踪/聊天用量/审计日志由保留策略每日清理（`app.data-retention.*`，默认 90/180/365 天，0=不清理）。
- **Schema 变更**：由 Flyway 管理（`db/migration`，V1 存量基线 + 增量脚本），Hibernate 仅 validate；
  升级前建议备份 MySQL。存量库首次启动会自动 baseline，无需手工干预。
- **验证**：`curl http://127.0.0.1:18080/actuator/health` 应返回 UP。

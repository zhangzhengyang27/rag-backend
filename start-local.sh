#!/usr/bin/env bash
# 本地开发启动脚本：准备 MySQL 容器 / Redis Stack，然后启动后端（http://localhost:8081）。
# 用法：
#   ./start-local.sh              # 启动基础设施 + 后端
#   ./start-local.sh --infra-only # 只准备基础设施，不启动后端
#
# 说明：.env 面向 NAS 部署（dev-mysql、带密码的 Redis），本脚本在其基础上覆盖为本地地址。
set -euo pipefail

cd "$(dirname "$0")"

ENV_FILE="${ENV_FILE:-.env}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-rag-mysql-local}"
MYSQL_PORT="${MYSQL_PORT:-3308}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.0}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-rag-local-root}"
REDIS_PORT="${REDIS_PORT:-6380}"
INFRA_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --infra-only) INFRA_ONLY=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（可用：--infra-only）" >&2; exit 2 ;;
  esac
done

[ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE（可参照 .env.example 创建）" >&2; exit 1; }

# 逐行导出，避免 DB_URL 里的 & 被 shell 解析
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    \#*|"") continue ;;
  esac
  export "$line"
done < "$ENV_FILE"

: "${DB_PASSWORD:?$ENV_FILE 缺少 DB_PASSWORD}"
: "${JWT_SECRET:?$ENV_FILE 缺少 JWT_SECRET}"
export DB_USERNAME="${DB_USERNAME:-appuser}"

# ---------- 本地覆盖 ----------
export DB_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/langchain4j?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export KB_REDIS_HOST="127.0.0.1"
export KB_REDIS_PORT="$REDIS_PORT"
export REDIS_PASSWORD=""
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

# ---------- MySQL ----------
command -v docker >/dev/null || { echo "需要 Docker 启动 MySQL 容器" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker 未运行，请先启动 Docker" >&2; exit 1; }

if ! docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
  echo "创建 MySQL 容器 $MYSQL_CONTAINER (127.0.0.1:$MYSQL_PORT) ..."
  docker run -d --name "$MYSQL_CONTAINER" \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
    -e MYSQL_DATABASE=langchain4j \
    -e MYSQL_USER="$DB_USERNAME" \
    -e MYSQL_PASSWORD="$DB_PASSWORD" \
    -p "127.0.0.1:${MYSQL_PORT}:3306" \
    "$MYSQL_IMAGE" >/dev/null
elif [ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" != "true" ]; then
  echo "启动 MySQL 容器 $MYSQL_CONTAINER ..."
  docker start "$MYSQL_CONTAINER" >/dev/null
fi

printf '等待 MySQL 就绪'
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping --silent >/dev/null 2>&1; then
    echo ' ✓'
    break
  fi
  printf '.'
  sleep 1
  if [ "$i" -eq 90 ]; then
    echo ' 超时'
    echo "请检查容器日志：docker logs $MYSQL_CONTAINER" >&2
    exit 1
  fi
done

# ---------- Redis Stack ----------
if ! redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  command -v redis-stack-server >/dev/null || {
    echo "需要 Redis Stack（含 RediSearch）。安装：brew tap redis-stack/redis-stack && brew install --cask redis-stack-server" >&2
    exit 1
  }
  echo "启动 Redis Stack (127.0.0.1:$REDIS_PORT) ..."
  redis-stack-server --port "$REDIS_PORT" --daemonize yes --save "" --appendonly no >/dev/null
  for i in $(seq 1 15); do
    redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1 && break
    sleep 1
    if [ "$i" -eq 15 ]; then
      echo "Redis Stack 启动超时" >&2
      exit 1
    fi
  done
fi

module_list="$(redis-cli -p "$REDIS_PORT" MODULE LIST 2>/dev/null || true)"
case "$module_list" in
  *search*) ;;
  *)
    echo "端口 $REDIS_PORT 上的 Redis 不含 RediSearch 模块，请改用 Redis Stack" >&2
    exit 1
    ;;
esac
echo "Redis Stack 就绪 (127.0.0.1:$REDIS_PORT)"

if [ "$INFRA_ONLY" -eq 1 ]; then
  echo "基础设施已就绪（MySQL:${MYSQL_PORT} / Redis:${REDIS_PORT}）"
  exit 0
fi

echo "启动后端：http://localhost:8081"
exec mvn spring-boot:run

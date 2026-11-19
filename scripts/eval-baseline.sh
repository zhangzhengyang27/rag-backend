#!/usr/bin/env bash
# P1-01 基线冻结：跑一次现有检索评测、记录 P95、快照 Redis 索引与运行时检索参数。
#
# 用法：
#   ADMIN_USER=admin ADMIN_PASS=*** ./scripts/eval-baseline.sh
#   BASE_URL=http://localhost:8082 ADMIN_USER=admin ADMIN_PASS=*** ./scripts/eval-baseline.sh
#   LIMIT=40 TOPK=5 ... ./scripts/eval-baseline.sh       # 只跑前 40 条用例
#   TAG_BASELINE=1 ... ./scripts/eval-baseline.sh        # 额外打 git tag
#
# 前置：后端已启动、管理员账号可用、kb_rag_eval 至少有 1 条用例。
# 产物：baseline/<时间戳>/{eval.json,config.json,stats.json,redis.txt,latency.txt,baseline.md}
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8081}"
ADMIN_USER="${ADMIN_USER:-}"
ADMIN_PASS="${ADMIN_PASS:-}"
TOPK="${TOPK:-5}"
LIMIT="${LIMIT:-}"
REDIS_PORT="${REDIS_PORT:-6379}"

# 读 .env 只为拿 REDIS_PASSWORD 等本地连接参数；显式传入的环境变量优先（含空值）。
# 不要 source，DB_URL 里的 & 会被 shell 解析。
if [ -f .env ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      \#*|"") continue ;;
    esac
    case "$line" in
      REDIS_PASSWORD=*)
        if [ -z "${REDIS_PASSWORD+x}" ]; then
          export "$line"
        fi
        ;;
    esac
  done < .env
fi

if [ -z "$ADMIN_USER" ] || [ -z "$ADMIN_PASS" ]; then
  echo "缺少 ADMIN_USER / ADMIN_PASS（管理员账号，用于登录后跑 /admin/kb/eval/run）。" >&2
  echo "示例：ADMIN_USER=admin ADMIN_PASS=*** BASE_URL=http://localhost:8082 $0" >&2
  exit 2
fi

command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

OUT="baseline/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

echo "== 1/5 登录 =="
LOGIN_BODY=$(python3 -c 'import json,os; print(json.dumps({"username":os.environ["ADMIN_USER"],"password":os.environ["ADMIN_PASS"]}))')
LOGIN_RESP=$(curl -sS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY")
TOKEN=$(printf '%s' "$LOGIN_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
if [ -z "$TOKEN" ]; then
  echo "登录失败，响应：$LOGIN_RESP" >&2
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

echo "== 2/5 运行评测 =="
EVAL_URL="$BASE_URL/admin/kb/eval/run?topK=$TOPK"
if [ -n "$LIMIT" ]; then EVAL_URL="$EVAL_URL&limit=$LIMIT"; fi
HTTP=$(curl -sS --max-time 600 -o "$OUT/eval.json" -w '%{http_code}' -H "$AUTH" "$EVAL_URL")
if [ "$HTTP" != "200" ]; then
  echo "评测接口返回 HTTP $HTTP：" >&2
  head -c 500 "$OUT/eval.json" >&2 || true
  exit 1
fi

echo "== 3/5 采集检索参数与规模 =="
curl -sS -H "$AUTH" "$BASE_URL/admin/kb/config" -o "$OUT/config.json" || true
curl -sS -H "$AUTH" "$BASE_URL/admin/kb/stats" -o "$OUT/stats.json" || true
curl -sS -H "$AUTH" "$BASE_URL/admin/kb/eval/trend?days=30" -o "$OUT/trend.json" || true

echo "== 4/5 快照 Redis =="
REDIS_ARGS=(-p "$REDIS_PORT")
if [ -n "${REDIS_PASSWORD:-}" ]; then
  REDIS_ARGS+=(-a "$REDIS_PASSWORD" --no-auth-warning)
fi
{
  echo "# Redis snapshot $(date '+%F %T')"
  echo "## DBSIZE"
  redis-cli "${REDIS_ARGS[@]}" DBSIZE 2>&1 || true
  echo "## keyspace"
  redis-cli "${REDIS_ARGS[@]}" INFO keyspace 2>&1 || true
  echo "## kb_embeddings"
  redis-cli "${REDIS_ARGS[@]}" FT.INFO kb_embeddings 2>&1 || true
  echo "## kb_embeddings_bm25"
  redis-cli "${REDIS_ARGS[@]}" FT.INFO kb_embeddings_bm25 2>&1 || true
} > "$OUT/redis.txt"

echo "== 5/5 采样检索 P95 =="
: > "$OUT/latency.txt"
QUERIES=(
  "HashMap 扩容原理"
  "Spring Bean 生命周期"
  "MySQL 索引失效"
  "Redis 缓存穿透"
  "JVM 垃圾回收"
)
for round in 1 2 3 4; do
  for q in "${QUERIES[@]}"; do
    result=$(curl -sS -o /dev/null -w '%{http_code} %{time_total}' \
      -H "$AUTH" --get \
      --data-urlencode "query=$q" \
      --data-urlencode "k=5" \
      "$BASE_URL/api/kb/search")
    echo "$result" >> "$OUT/latency.txt"
  done
done

# 基线报告：JSON 字段可能随版本变化，取值失败时保留原始文件并标注
python3 - "$OUT" "$BASE_URL" "$TOPK" "$LIMIT" <<'PY'
import json, os, sys, datetime

out, base_url, topk, limit = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

def load(name):
    try:
        with open(os.path.join(out, name), encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return None

ev = load('eval.json') or {}
st = load('stats.json') or {}
cfg = load('config.json') or []

lat = []
try:
    with open(os.path.join(out, 'latency.txt'), encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[0] == '200':
                lat.append(float(parts[1]))
except Exception:
    pass

lat_sorted = sorted(lat)
p95 = lat_sorted[min(len(lat_sorted) - 1, int(len(lat_sorted) * 0.95))] if lat_sorted else None
avg = sum(lat) / len(lat) if lat else None

def cfg_of(key):
    for item in cfg if isinstance(cfg, list) else []:
        if item.get('key') == key:
            return item.get('effective') or item.get('value') or item.get('current')
    return None

lines = []
lines.append('# RAG 检索基线（P1-01）')
lines.append('')
lines.append('- 生成时间：' + datetime.datetime.now().strftime('%F %T'))
lines.append('- 后端：' + base_url)
lines.append('- 评测 topK：' + str(topk) + ('，limit=' + limit if limit else '（全量）'))
lines.append('- 评测用例：' + str(ev.get('total', 'n/a')))
lines.append('- 命中 / 未命中：' + str(ev.get('hit', 'n/a')) + ' / ' + str(ev.get('miss', 'n/a')))
lines.append('- HitRate：' + str(ev.get('hitRate', 'n/a')))
lines.append('- MRR：' + str(ev.get('mrr', 'n/a')))
lines.append('- NDCG：' + str(ev.get('ndcg', 'n/a')))
lines.append('')
lines.append('## 检索延迟（/api/kb/search，20 次采样）')
lines.append('- 成功采样：' + str(len(lat)))
lines.append('- 平均：' + (f'{avg:.3f}s' if avg is not None else 'n/a'))
lines.append('- P95：' + (f'{p95:.3f}s' if p95 is not None else 'n/a'))
lines.append('')
lines.append('## 当前检索参数（运行时配置）')
for key in ['retrieval.vector-top-n', 'retrieval.bm25-top-n', 'retrieval.rrf-k',
            'retrieval.final-top-k', 'retrieval.min-score', 'retrieval.bm25-enabled',
            'retrieval.rerank-enabled', 'retrieval.relevance-check-enabled']:
    lines.append(f'- {key}：{cfg_of(key) if cfg_of(key) is not None else "n/a"}')
lines.append('')
lines.append('## 规模')
for key in ['totalDocuments', 'totalChunks', 'indexedDocuments', 'pendingDocuments',
            'failedDocuments', 'totalVectorCount']:
    if key in st:
        lines.append(f'- {key}：{st.get(key)}')
lines.append('')
lines.append('## 产物')
for name in ['eval.json', 'config.json', 'stats.json', 'trend.json', 'redis.txt', 'latency.txt']:
    if os.path.exists(os.path.join(out, name)):
        lines.append(f'- {name}')
lines.append('')
lines.append('> 说明：HitRate/MRR/NDCG 来自现有 KbEvalServiceImpl（检索级离线指标）；'
             '生成级指标（faithfulness/context precision/context recall）在 M2.2 评测 2.0 补齐。')

with open(os.path.join(out, 'baseline.md'), 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines) + '\n')
print('\n'.join(lines))
PY

if [ "${TAG_BASELINE:-0}" = "1" ]; then
  TAG="baseline-$(date +%Y%m%d-%H%M)"
  git tag -a "$TAG" -m "RAG baseline $TAG" 2>/dev/null || echo "tag 已存在或创建失败：$TAG" >&2
  echo "已创建 git tag：$TAG（未推送）"
fi

echo
echo "基线已落盘：$OUT/baseline.md"

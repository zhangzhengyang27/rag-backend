#!/usr/bin/env bash
# RAG 评测门禁：CI/发布前调用，不达标直接非零退出。
# 用法：KB_ADMIN_TOKEN=... KB_BASE_URL=http://127.0.0.1:8084 ./scripts/eval-gate.sh
# 说明：token 由部署环境注入，不写进仓库；阈值固定在服务端 /admin/kb/eval/gate。
set -euo pipefail

BASE_URL="${KB_BASE_URL:-http://127.0.0.1:8084}"
TOKEN="${KB_ADMIN_TOKEN:?缺少 KB_ADMIN_TOKEN（管理员 JWT）}"
TOP_K="${TOP_K:-5}"

response="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "${BASE_URL}/admin/kb/eval/gate?topK=${TOP_K}")"

printf '%s\n' "$response" | python3 -c '
import json, sys
report = json.load(sys.stdin)
print("gate.passed =", report.get("passed"))
for item in report.get("checks", []):
    print("  %s: %s (阈值 %s) -> %s" % (item.get("name"), item.get("actual"), item.get("threshold"), item.get("pass")))
if not report.get("passed"):
    sys.exit(1)
'

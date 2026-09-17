#!/usr/bin/env bash
# ============================================================
# 内网图片管理器 · 部署后功能验证（在目标服务器上执行）
# 校验：健康接口 → 前端页面 → 管理员登录 → 业务接口 → ffmpeg/AI 状态 → 数据目录
# 用法：bash deploy/verify-remote.sh
# ============================================================
set -uo pipefail

APP_DIR="${APP_DIR:-$HOME/fluxframe}"
cd "$APP_DIR"
APP_PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"' || true)"
APP_PORT="${APP_PORT:-4311}"
BASE="http://127.0.0.1:${APP_PORT}"
ADMIN_USER="$(grep -E '^ADMIN_USERNAME=' deploy/.env | cut -d= -f2 | tr -d '"' || echo admin)"
ADMIN_PASS="$(grep -E '^ADMIN_PASSWORD=' deploy/.env | cut -d= -f2 | tr -d '"' || echo admin123)"
JAR="$(mktemp)"
pass=0
fail=0

check() { # check <名称> <期望> <实际>
  if [ "$2" = "$3" ]; then echo "  PASS  $1（$3）"; pass=$((pass+1))
  else echo "  FAIL  $1（期望 $2，实际 $3）"; fail=$((fail+1)); fi
}

echo "=== 1. 健康接口 ==="
check "GET /api/health" "200" "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/health")"
curl -s -m 5 "$BASE/api/health"; echo

echo
echo "=== 2. 前端页面（单端口同时提供页面与 API） ==="
check "GET /" "200" "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/")"
INDEX="$(curl -s -m 5 "$BASE/")"
check "首页含挂载点 #app" "yes" "$(echo "$INDEX" | grep -q 'id="app"' && echo yes || echo no)"
ASSET="$(echo "$INDEX" | grep -o '/assets/[^"]*\.js' | head -1)"
check "静态资源可加载" "200" "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE$ASSET")"

echo
echo "=== 3. 管理员登录（HTTP 明文下 Cookie 必须可保存） ==="
LOGIN_CODE="$(curl -s -c "$JAR" -o /tmp/login.json -w '%{http_code}' -m 10 -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/auth/login")"
check "POST /api/auth/login" "200" "$LOGIN_CODE"
check "会话 Cookie 已写入" "yes" "$(grep -q fluxframe_session "$JAR" && echo yes || echo no)"
# Netscape cookie jar 第 4 列为 Secure 标记；HTTP 部署必须为 FALSE，否则浏览器不保存登录态
check "Cookie Secure 标记" "FALSE" "$(awk '/fluxframe_session/ {print $4; exit}' "$JAR" | tr -d '\r')"
head -c 200 /tmp/login.json; echo

echo
echo "=== 4. 业务接口（带会话） ==="
check "GET /api/me" "200" "$(curl -s -b "$JAR" -o /tmp/me.json -w '%{http_code}' -m 10 "$BASE/api/me")"
check "GET /api/dashboard" "200" "$(curl -s -b "$JAR" -o /tmp/dash.json -w '%{http_code}' -m 15 "$BASE/api/dashboard")"
check "GET /api/images" "200" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -m 15 "$BASE/api/images")"
check "GET /api/settings" "200" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -m 10 "$BASE/api/settings")"
echo "  /api/me       → $(head -c 160 /tmp/me.json)"
echo "  /api/dashboard→ $(head -c 240 /tmp/dash.json)"

echo
echo "=== 5. ffmpeg / AI 引擎状态（Linux 端适配点） ==="
FF="$(curl -s -b "$JAR" -m 10 "$BASE/api/ffmpeg/status")"
echo "  /api/ffmpeg/status → $FF"
check "容器内 ffmpeg 可用" "yes" "$(echo "$FF" | grep -q '"found":true' && echo yes || echo no)"
AI="$(curl -s -b "$JAR" -m 10 "$BASE/api/ai/status")"
echo "  /api/ai/status     → $(head -c 260 <<<"$AI")"
check "AI 状态接口可用" "yes" "$(echo "$AI" | grep -q '"running"' && echo yes || echo no)"

echo
echo "=== 6. 数据目录（宿主机挂载点） ==="
for d in storage/originals storage/thumbnails storage/trash storage/temp models logs; do
  if [ -d "deploy/data/$d" ]; then echo "  PASS  deploy/data/$d"; pass=$((pass+1))
  else echo "  FAIL  deploy/data/$d 不存在"; fail=$((fail+1)); fi
done

echo
echo "=== 7. 容器与镜像 ==="
docker compose -f deploy/docker-compose.yml ps
docker images fluxframe:latest --format '  image: {{.Repository}}:{{.Tag}} {{.Size}}'
rm -f "$JAR"

echo
echo "==================== 结果：通过 $pass 项，失败 $fail 项 ===================="
[ "$fail" -eq 0 ]

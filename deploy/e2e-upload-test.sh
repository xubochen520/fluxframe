#!/usr/bin/env bash
# ============================================================
# 端到端上传测试（在目标服务器执行）：
# 生成一张 PNG → 登录 → 上传 → 校验落库/缩略图（sharp 原生模块）→ 清理
# 用法：bash deploy/e2e-upload-test.sh
# ============================================================
set -uo pipefail
cd "${APP_DIR:-$HOME/fluxframe}"

PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"')"
PORT="${PORT:-4311}"
BASE="http://127.0.0.1:${PORT}"
ADMIN_USER="$(grep -E '^ADMIN_USERNAME=' deploy/.env | cut -d= -f2 | tr -d '"')"
ADMIN_PASS="$(grep -E '^ADMIN_PASSWORD=' deploy/.env | cut -d= -f2 | tr -d '"')"
JAR="$(mktemp)"
PNG=/tmp/fluxframe-e2e.png

echo "=== 1. 生成测试图片（容器内 ffmpeg） ==="
docker exec fluxframe-app ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=size=800x600:duration=1 -frames:v 1 -y /tmp/e2e.png
docker cp fluxframe-app:/tmp/e2e.png "$PNG"
ls -l "$PNG"

echo
echo "=== 2. 登录 ==="
curl -s -c "$JAR" -o /dev/null -w 'login http=%{http_code}\n' -H 'Content-Type: application/json' \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}" "$BASE/api/auth/login"

echo
echo "=== 3. 上传（走 http://127.0.0.1:${PORT} 已发布端口） ==="
UP="$(curl -s -b "$JAR" -F "file=@${PNG};type=image/png" "$BASE/api/images/upload")"
echo "$UP" | head -c 400; echo
ID="$(echo "$UP" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
if [ -z "$ID" ]; then echo "!! 上传失败，未取得图片 id" >&2; exit 1; fi
echo "图片 id：$ID"

echo
echo "=== 4. 校验列表与文件落盘 ==="
COUNT="$(curl -s -b "$JAR" "$BASE/api/images" | grep -o '"id":"' | wc -l)"
echo "  /api/images 记录数：$COUNT"
echo "  原图文件："; ls -l deploy/data/storage/originals | tail -3
echo "  缩略图（sharp 生成 320/768/1600 webp）："; ls -l deploy/data/storage/thumbnails | tail -4
check_thumb="$(ls deploy/data/storage/thumbnails | grep -c "$ID" || true)"
echo "  该图缩略图数量：$check_thumb"

echo
echo "=== 5. 清理测试图片 ==="
curl -s -b "$JAR" -o /dev/null -w 'delete http=%{http_code}\n' -X DELETE "$BASE/api/images/${ID}/permanent"
curl -s -b "$JAR" -o /dev/null -w 'video/图片数归零检查：' "$BASE/api/images"
echo "$(curl -s -b "$JAR" "$BASE/api/images" | grep -o '"id":"' | wc -l) 条"
rm -f "$JAR" "$PNG"

if [ "$check_thumb" -ge 3 ]; then echo "结果：端到端上传 + 缩略图生成 通过 ✓"; exit 0
else echo "结果：缩略图数量异常（$check_thumb）" >&2; exit 1; fi

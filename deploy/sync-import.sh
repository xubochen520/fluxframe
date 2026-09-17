#!/usr/bin/env bash
# ============================================================
# 把本机（Windows）的数据同步到 Linux/Docker 部署（在目标服务器上执行）
# 同步内容：数据库全量（用户/图片索引/视频/标签/关联/审计/设置）+ 图片与视频文件
# 前置：已上传 db.sql / storage.tar / fix-settings.sql / create-user.mjs 到 $SYNC_DIR
# 用法：bash deploy/sync-import.sh [SYNC_DIR]
# ============================================================
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/fluxframe}"
SYNC_DIR="${1:-$HOME/fluxframe-sync}"
COMPOSE=(docker compose -f deploy/docker-compose.yml)
cd "$APP_DIR"

for f in db.sql storage.tar fix-settings.sql create-user.mjs; do
  [ -f "$SYNC_DIR/$f" ] || { echo "缺少同步文件：$SYNC_DIR/$f" >&2; exit 1; }
done

echo "=== 1/7 停止应用容器（数据库保持运行） ==="
"${COMPOSE[@]}" stop app

echo
echo "=== 2/7 恢复数据库（--clean，覆盖服务端现有数据） ==="
docker exec -i fluxframe-postgres psql -U postgres -d image_manager -q -v ON_ERROR_STOP=1 < "$SYNC_DIR/db.sql"
echo "数据库恢复完成"

echo
echo "=== 3/7 修正容器内路径设置 ==="
docker exec -i fluxframe-postgres psql -U postgres -d image_manager -q -v ON_ERROR_STOP=1 < "$SYNC_DIR/fix-settings.sql"

echo
echo "=== 4/7 同步图片/视频文件 ==="
mkdir -p deploy/data/storage
rm -rf deploy/data/storage/originals deploy/data/storage/thumbnails deploy/data/storage/trash
tar -xf "$SYNC_DIR/storage.tar" -C deploy/data/storage
echo "originals : $(find deploy/data/storage/originals -type f | wc -l) 个文件"
echo "thumbnails: $(find deploy/data/storage/thumbnails -type f | wc -l) 个文件"
echo "trash     : $(find deploy/data/storage/trash -type f 2>/dev/null | wc -l) 个文件"
du -sh deploy/data/storage

echo
echo "=== 5/7 启动应用容器 ==="
"${COMPOSE[@]}" up -d
PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"')"
PORT="${PORT:-4311}"
for i in $(seq 1 60); do
  if curl -fsS -m 3 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then echo "健康检查通过（第 ${i} 次）"; break; fi
  [ "$i" -eq 60 ] && { echo "健康检查失败" >&2; "${COMPOSE[@]}" logs --tail 40 app >&2; exit 1; }
  sleep 3
done

echo
echo "=== 6/7 创建/重置管理员账户 ==="
docker cp "$SYNC_DIR/create-user.mjs" fluxframe-app:/app/server/create-user.mjs
docker exec -w /app/server fluxframe-app node create-user.mjs
# docker cp 落地文件属主为 root，容器内是 node 用户，需要 -u root 才能删除（脚本内含明文密码，必须清掉）
docker exec -u root fluxframe-app rm -f /app/server/create-user.mjs

echo
echo "=== 7/7 校验 ==="
JAR="$(mktemp)"
curl -s -c "$JAR" -o /dev/null -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"REDACTED-PASSWORD"}' "http://127.0.0.1:${PORT}/api/auth/login" \
  -w '  admin 登录 http=%{http_code}\n'
echo "  /api/me        → $(curl -s -b "$JAR" "http://127.0.0.1:${PORT}/api/me")"
DASH="$(curl -s -b "$JAR" "http://127.0.0.1:${PORT}/api/dashboard")"
echo "  /api/dashboard → $(echo "$DASH" | head -c 220)"
echo "  /api/tags 数量 → $(curl -s -b "$JAR" "http://127.0.0.1:${PORT}/api/tags" | grep -o '"id"' | wc -l)"
FIRST_ID="$(curl -s -b "$JAR" "http://127.0.0.1:${PORT}/api/images" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
if [ -n "$FIRST_ID" ]; then
  echo "  首图缩略图   → http=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/images/${FIRST_ID}/variant/320")"
  echo "  首图原文件   → http=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/images/${FIRST_ID}/file")"
fi
rm -f "$JAR"
echo
echo "同步完成 ✓"

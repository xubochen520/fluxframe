#!/bin/sh
# 内网图片管理器 · 容器入口：等待数据库 → 应用 Prisma 迁移 → 启动 Fastify 服务
set -e

echo "[entrypoint] $(date '+%F %T') 启动内网图片管理器（storage=${STORAGE_DIR:-/data/storage}）"

cd /app

# 数据库由 compose 的 healthcheck 等待；这里再重试若干次，避免编排竞态
attempt=1
while :; do
  if npx --no-install prisma migrate deploy --schema /app/prisma/schema.prisma; then
    break
  fi
  if [ "$attempt" -ge 30 ]; then
    echo "[entrypoint] 数据库迁移失败（已尝试 ${attempt} 次），请检查 DATABASE_URL 与 postgres 容器状态" >&2
    exit 1
  fi
  echo "[entrypoint] 数据库尚未就绪，2 秒后重试（第 ${attempt} 次）…" >&2
  attempt=$((attempt + 1))
  sleep 2
done

# 服务端要求工作目录为 server/（相对定位 ../client/dist 与模型目录）
cd /app/server
echo "[entrypoint] 迁移完成，启动：$*"
exec "$@"

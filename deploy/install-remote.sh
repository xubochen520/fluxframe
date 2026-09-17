#!/usr/bin/env bash
# ============================================================
# 在目标服务器（192.168.1.100）上执行的部署脚本
# 前置：部署包已解压到 $APP_DIR（默认 ~/fluxframe）
# 用法：bash deploy/install-remote.sh [--no-build]
# ============================================================
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/fluxframe}"
COMPOSE_FILE="deploy/docker-compose.yml"
cd "$APP_DIR"

echo "=== 1/5 环境检查 ==="
docker version --format 'docker client={{.Client.Version}} server={{.Server.Version}}'
docker compose version | head -1
echo "部署目录：$APP_DIR"

echo
echo "=== 2/5 准备数据目录（必须以当前用户创建，避免 Docker 建出 root 属主目录） ==="
mkdir -p deploy/data/storage deploy/data/models deploy/data/logs
ls -ld deploy/data deploy/data/*

echo
echo "=== 3/5 构建镜像 ==="
if [ "${1:-}" = "--no-build" ]; then
  echo "跳过构建（--no-build）"
else
  docker compose -f "$COMPOSE_FILE" build
fi

echo
echo "=== 4/5 启动服务 ==="
docker compose -f "$COMPOSE_FILE" up -d

echo
echo "=== 5/5 等待就绪 ==="
APP_PORT="$(grep -E '^APP_PORT=' deploy/.env | cut -d= -f2 | tr -d '"' || true)"
APP_PORT="${APP_PORT:-4311}"
for i in $(seq 1 60); do
  if curl -fsS -m 3 "http://127.0.0.1:${APP_PORT}/api/health" >/dev/null 2>&1; then
    echo "健康检查通过（第 ${i} 次尝试）"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "!! 60 次健康检查仍未通过，下面是最近日志" >&2
    docker compose -f "$COMPOSE_FILE" logs --tail 60 app >&2 || true
    exit 1
  fi
  sleep 3
done

echo
echo "=== 容器状态 ==="
docker compose -f "$COMPOSE_FILE" ps

echo
echo "=== 健康接口 ==="
curl -sS "http://127.0.0.1:${APP_PORT}/api/health"; echo

echo
echo "=== 访问地址 ==="
for ip in $(hostname -I); do
  echo "  http://${ip}:${APP_PORT}"
done
echo "完成 ✓"

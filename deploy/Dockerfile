# syntax=docker/dockerfile:1
# ============================================================
# 内网图片管理器 · Linux/Docker 镜像（多阶段构建）
# 前端：Vue3 + Vite  →  client/dist
# 后端：Fastify + Prisma + PostgreSQL  →  server/dist（单端口同时提供页面与 API）
# 构建（在仓库根目录执行）：
#   docker compose -f deploy/docker-compose.yml build
# ============================================================

# ---------- 构建阶段 ----------
FROM node:24-bookworm-slim AS builder
ENV NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false \
    npm_config_update_notifier=false
WORKDIR /app

# argon2 无预编译包时需回退源码编译；Prisma 引擎需要 openssl
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openssl python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

# 先复制依赖清单，命中分层缓存
COPY package.json package-lock.json ./
COPY client/package.json client/package-lock.json ./client/
COPY server/package.json server/package-lock.json ./server/
RUN npm ci \
 && npm --prefix client ci \
 && npm --prefix server ci

# 生成 Prisma Client（生成到根 node_modules，服务端按 Node 解析规则向上查找）
COPY prisma ./prisma
RUN npx prisma generate --schema prisma/schema.prisma

# 源码与编译
COPY client ./client
COPY server ./server
RUN npm --prefix client run build \
 && npm --prefix server run build

# ---------- 运行阶段 ----------
FROM node:24-bookworm-slim AS runtime
ENV NODE_ENV=production \
    TZ=Asia/Shanghai \
    PORT=4311 \
    HOST=0.0.0.0 \
    STORAGE_DIR=/data/storage \
    MODELS_DIR=/data/models \
    LOGS_DIR=/data/logs
WORKDIR /app

# ffmpeg：B 站高清 DASH 音视频合并；curl/unzip：AI 引擎与 ffmpeg 一键下载；tini：正确转发信号
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openssl ffmpeg curl unzip tini tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/prisma ./prisma
COPY --from=builder /app/client/dist ./client/dist
COPY --from=builder /app/server/package.json ./server/package.json
COPY --from=builder /app/server/node_modules ./server/node_modules
COPY --from=builder /app/server/dist ./server/dist
COPY --from=builder /app/server/prompts ./server/prompts
COPY --from=builder /app/server/dyab ./server/dyab

COPY deploy/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
 && mkdir -p /data/storage /data/models /data/logs \
 && chown -R node:node /data

# 以非 root 运行（node 用户 uid/gid = 1000，与宿主机常见用户一致，便于挂载目录读写）
USER node

EXPOSE 4311
WORKDIR /app/server
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["node", "dist/index.js"]

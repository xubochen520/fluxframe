# 内网图片管理器 · Linux / Docker 部署说明

面向 Linux 服务器（本项目已在 **Ubuntu 26.04 · x86_64 · Docker 29.7.2 · 4 核 / 7.2G 内存** 上验证）的容器化部署。
Windows 本机运行方式见根目录 [README.md](../README.md)。

- 镜像：多阶段构建（`node:24-bookworm-slim`），**单端口**同时提供网页与 API
- 数据库：PostgreSQL 17 容器（仅内部网络，不占用宿主机 5432）
- 数据：图片 / 日志 / 模型均挂载到宿主机的 `deploy/data/`，容器重建不丢数据
- 适用平台：`linux/amd64`（原生模块 `sharp`、`argon2`、Prisma 引擎均在镜像内按 Linux 构建）

## 已验证结果（2026-09-16，目标机 192.168.1.100）

| 项目 | 结果 |
| --- | --- |
| 镜像构建 | 成功，`fluxframe:latest` 约 1.46 GB（含 ffmpeg / curl / unzip / tini） |
| 容器状态 | `fluxframe-app` healthy（0.0.0.0:4311）、`fluxframe-postgres` healthy（仅内网 5432） |
| 功能自检 `verify-remote.sh` | **19 项全部通过**（健康接口 / 前端页面 / 静态资源 / 登录 / Cookie Secure=false / me / dashboard / images / settings / ffmpeg / ai 状态 / 6 个数据目录 / 容器状态） |
| 端到端上传 `e2e-upload-test.sh` | 通过：上传 800×600 PNG → `sharp` 生成 320/768/1600 三档 webp 缩略图 → 落库 → 删除回收 |
| 局域网访问 | Windows 侧 `http://192.168.1.100:4311/api/health` → 200；首页 200（含 `/assets/`） |
| 容器内 ffmpeg | `/usr/bin/ffmpeg` 版本 `5.1.9-0+deb12u1`（`/api/ffmpeg/status` 返回 `found:true`） |
| AI 标签引擎 | 默认关闭；`/api/ai/status` 正常返回。Linux 引擎下载路径（`llama-b<tag>-bin-ubuntu-x64.tar.gz` / vulkan 变体）已按官方 Release 命名适配并核对过下载地址，但**未实际下载模型实测推理**（本机 4 核 / 7.2G 内存建议只用 3B 变体） |

访问地址：`http://192.168.1.100:4311`，初始账号 `admin / admin123`（登录后请立即修改）。


## 数据迁移 / 同步（Windows → Linux）

把本机的图片、视频、标签等数据整体搬到服务器（**数据库全量 + 原图与缩略图**）。
已实测：112 张图片（含 5 个视频）、17 个标签、232 条图片标签关联、321 个缩略图变体、421 条审计日志、2 个账户，文件 594MB，全部同步成功。

```powershell
# 1) Windows 侧导出：pg_dump + 打包 storage（产物在 deploy\out\）
powershell -ExecutionPolicy Bypass -File deploy\sync-export.ps1

# 2) 上传（storage.tar 约 593MB，按 3MB/s 约 4 分钟）
$env:DEPLOY_USER='han'
python deploy\remote.py put deploy\out\db.sql /home/han/fluxframe-sync/db.sql
python deploy\remote.py put deploy\out\storage.tar /home/han/fluxframe-sync/storage.tar
python deploy\remote.py put deploy\fix-settings.sql /home/han/fluxframe-sync/fix-settings.sql
python deploy\remote.py put deploy\create-user.mjs /home/han/fluxframe-sync/create-user.mjs
python deploy\remote.py put deploy\sync-import.sh /home/han/fluxframe/deploy/sync-import.sh
python deploy\remote.py put deploy\sync-verify.sh /home/han/fluxframe/deploy/sync-verify.sh
```

```bash
# 3) 服务器侧导入（停应用 → 恢复数据库 → 修正路径 → 解包文件 → 启动 → 建管理员 → 校验）
cd ~/fluxframe && bash deploy/sync-import.sh
bash deploy/sync-verify.sh          # 期望：通过 10 项，失败 0 项
bash deploy/sync-check-detail.sh    # 排查用：r18 可见性、文件清点、抽样文件接口
```

同步脚本做了三件容易被忽略的事：

1. **路径改写**：本地库里的 `storageDir` 是 `C:/Users/.../server/storage`、`aiModel` 是 Windows 下的 `.gguf` 绝对路径。直接把库搬过去，容器会往 `/app/server/C:/...` 这种假目录写文件，图片全部 404。`fix-settings.sql` 会把 `storageDir` 改成 `/data/storage`、`port` 回正 4311、`aiModel` 复位成模型名，并清掉 Windows 的 `ffmpegPath`。
2. **账户密码**：`create-user.mjs` 在容器内用**与服务相同的 argon2** 生成哈希，`upsert` 建号/改密并设为 `ADMIN`，同时清掉该账户的旧会话（同步过来的 Session 记录不残留）。脚本含明文密码，导入脚本用 `-u root` 在最后删除容器内的临时文件。
3. **可见性差异**：接口返回的条数会比库内总数少，因为未开启 `r18Mode` 的用户看不到带 r18 标签的图片与标签（本次 112→109、17→15）；视频没有缩略图变体，`/variant/:width` 返回 404 属正常设计。`sync-verify.sh` 已按这个规则校验，不会误报。

> 同步后服务器上的 `deepseekEnabled=true` 与 DeepSeek KEY 也一并过来了，服务端会开始按间隔观测余额（正常情况下就是这样）。如果只想保留一边记账，建议在本机或服务器其中一侧删除 KEY 或关闭开关。B 站 `biliSessdata` 也是账号数据，同样被同步过去。

重复同步是安全的：脚本按「停应用 → 覆盖式恢复 → 重放文件 → 启动」执行，图片文件目录会先清空再解包，保证与本地一致。

## 目录结构

```
deploy/
├─ Dockerfile              # 多阶段构建（前端 Vite + 后端 tsc + Prisma Client）
├─ docker-compose.yml      # postgres + app 两个服务
├─ .env                    # 部署参数（数据库密码、端口、管理员账号、AI 开关）
├─ docker-entrypoint.sh    # 容器入口：等待数据库 → prisma migrate deploy → 启动服务
├─ pack.ps1                # Windows 侧打包部署包（排除 node_modules/dist/android/模型）
├─ install-remote.sh       # 服务器侧：建目录 → 构建镜像 → 启动 → 等健康检查
├─ verify-remote.sh        # 服务器侧：部署后功能自检（登录 / 业务接口 / 数据目录）
├─ sync-export.ps1         # Windows 侧：导出数据库 dump + storage 压缩包
├─ sync-import.sh          # 服务器侧：导入数据库与文件、建管理员账户、启动
├─ sync-verify.sh          # 服务器侧：同步后核验（账户/数据一致性/文件可读）
├─ sync-check-detail.sh    # 服务器侧：排查用（r18 可见性、文件清点、抽样接口）
├─ fix-settings.sql        # 导入后修正 storageDir/port/aiModel 等容器内路径
├─ create-user.mjs         # 在容器内用 argon2 建/重置管理员账户
├─ e2e-upload-test.sh      # 端到端上传测试（sharp 缩略图）
└─ data/                   # 宿主机数据（首次启动自动创建）
   ├─ storage/             #   originals / thumbnails / trash / temp
   ├─ models/              #   llama.cpp 引擎与 GGUF 模型（AI 标签用）
   └─ logs/                #   llama-server.log 等
```

## 首次部署

```bash
# 1) 服务器上准备目录（必须用当前用户创建，避免 Docker 建出 root 属主目录导致容器无法写入）
mkdir -p ~/fluxframe && cd ~/fluxframe

# 2) 上传并解压源码包（在 Windows 侧打包：powershell -ExecutionPolicy Bypass -File deploy\pack.ps1）
tar -xzf ~/fluxframe-linux.tar.gz -C ~/fluxframe

# 3) 构建并启动（首次约 5~15 分钟：3 次 npm ci + 前端构建 + 后端编译）
bash deploy/install-remote.sh

# 4) 功能自检
bash deploy/verify-remote.sh
```

Windows 侧上传（无 sshpass 时用项目自带的 SFTP 助手）：

```powershell
$env:DEPLOY_USER='han'
python deploy\remote.py put deploy\out\fluxframe-linux.tar.gz /home/han/fluxframe-linux.tar.gz
python deploy\remote.py exec "mkdir -p ~/fluxframe && tar -xzf ~/fluxframe-linux.tar.gz -C ~/fluxframe"
python deploy\remote.py exec "cd ~/fluxframe && bash deploy/install-remote.sh"
```

## 日常运维

```bash
cd ~/fluxframe

docker compose -f deploy/docker-compose.yml ps          # 状态
docker compose -f deploy/docker-compose.yml logs -f app # 应用日志（已设 LOG_LEVEL=info）
docker compose -f deploy/docker-compose.yml restart app # 重启应用
docker compose -f deploy/docker-compose.yml down        # 停止（保留数据卷与 deploy/data）
```

更新代码后重新部署：

```bash
# Windows：重新打包并上传（同首次部署第 2 步）
cd ~/fluxframe
docker compose -f deploy/docker-compose.yml up -d --build
```

数据备份：

```bash
# 图片等文件（直接拷目录）
tar -czf ~/fluxframe-files-$(date +%F).tar.gz -C ~/fluxframe deploy/data
# 数据库（逻辑备份）
docker exec fluxframe-postgres pg_dump -U postgres image_manager | gzip > ~/fluxframe-db-$(date +%F).sql.gz
```

## 配置说明（deploy/.env）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `4311` | **宿主机**对外端口（容器内固定 4311，不要改容器内端口） |
| `POSTGRES_PASSWORD` | 随机生成 | 数据库密码，同时用于拼 `DATABASE_URL`；改后需 `down -v` 或改库内密码 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | **仅首次启动**创建管理员；登录后请立即在系统设置里改密码 |
| `COOKIE_SECURE` | `false` | 纯 HTTP 内网访问必须为 `false`，否则浏览器不保存会话 Cookie（表现为登录后又跳回登录页）。挂 HTTPS 反代时改成 `true` |
| `TRUST_PROXY` | `false` | 前面有 nginx / 内网穿透（frp）时设 `true`，否则来源 IP 全部记成 Docker 网关 `172.19.0.1`，审计日志里一律显示「内网」。实测经 `fluxframe.example.com` 访问：开启前记 `172.19.0.1/内网`，开启后记真实公网 IP/外网。服务直接暴露、前面没有代理时才保持 `false` |
| `STORAGE_DIR` | `/data/storage` | 图片存放目录（容器内路径，对应 `deploy/data/storage`） |
| `MODELS_DIR` / `LOGS_DIR` | `/data/models`、`/data/logs` | AI 引擎与日志目录，容器重建不丢 |
| `AI_ENABLED` | `false` | 是否随服务自动拉起 llama.cpp |
| `AI_DOWNLOAD_MIRROR` | `https://hf-mirror.com` | 国内下载 GGUF 模型走镜像站 |
| `LOG_LEVEL` | `info` | `warn` 更安静，`debug` 排错用 |

> ⚠️ 容器部署下**不要在网页「系统设置」里改端口或存储目录**：这两项会写进数据库并在重启后生效，容器内改了端口会导致端口映射失效，只能在 `deploy/.env` 里改 `APP_PORT`（宿主端口）。

## Linux 适配说明（与 Windows 版的差异）

| 位置 | Windows 版 | Linux 版（本次改动） |
| --- | --- | --- |
| `server/src/index.ts` | 生产模式会话 Cookie 强制 `Secure`（HTTPS）→ 纯 HTTP 无法登录 | 新增 `COOKIE_SECURE` 开关，默认仍跟随 `NODE_ENV`，Docker 部署显式设为 `false` |
| `server/src/ffmpeg-manager.ts` | `models\ffmpeg\ffmpeg.exe`、PowerShell `Expand-Archive` | 无后缀可执行文件 + `unzip`，扫描 `PATH`/`/usr/bin`/`/usr/local/bin`；镜像已预装 `ffmpeg`，一键下载在 Linux 下给出明确提示 |
| `server/src/ai-manager.ts` | `llama-server.exe`、win-cpu/cuda zip、cudart DLL | 按平台选可执行文件名；Linux 取官方 `llama-b<tag>-bin-ubuntu-x64.tar.gz`（有 `/dev/dri` 时优先 `vulkan-x64`），`tar -xzf` 解压；CUDA 运行步骤仅 Windows |
| `server/src/ai-manager.ts` | `models/`、`logs/` 固定在项目目录 | 支持 `MODELS_DIR` / `LOGS_DIR` 环境变量，Docker 下指向 `/data/*` 持久化 |
| `server/src/ai-manager.ts` | 取 GitHub `latest` release 作为 llama.cpp 版本 | 改为在最近 15 条 release 中挑 `b<数字>` 标签（仓库新增了 `v0.4.x` 这类只含说明的 release，原逻辑会取错） |
| 前端 | 与平台无关（`/api` 相对路径） | 无改动 |

## 常见问题

- **打不开页面**：`docker compose -f deploy/docker-compose.yml ps` 看 app 是否 `healthy`；再看 `logs app`。宿主端口被占用时改 `deploy/.env` 的 `APP_PORT` 后 `up -d`。
- **登录后仍停在登录页**：确认 `COOKIE_SECURE=false`，并用 `http://`（不是 `https://`）访问；清掉旧 Cookie 再试。
- **想用域名（反向代理 / 内网穿透）访问**：服务端本身不用改——监听的是 `0.0.0.0`、CORS 放行任意来源、也不校验 Host，域名请求和 IP 请求走的是同一条路。要动的只有 `TRUST_PROXY=true`（理由见上表）。客户端填地址时**别带 `:4311`**：公网入口在 80/443，4311 一般没开。原生 App 从 v2.3.1 起会按 80 → 443 → 4311 自动试探。
- **上传图片报写入失败**：`deploy/data/storage` 属主必须是容器内 `node` 用户（uid 1000）。修复：`sudo chown -R 1000:1000 deploy/data`。
- **B 站高清保存提示未检测到 ffmpeg**：容器内已装 ffmpeg，用 `docker exec fluxframe-app ffmpeg -version` 确认；「系统设置 → ffmpeg」里填 `/usr/bin/ffmpeg`。
- **AI 图片标签**：默认关闭。开启后一键下载的是 Linux CPU/Vulkan 构建；本机 7.2G 内存建议只用 3B 变体，7B 容易 OOM。

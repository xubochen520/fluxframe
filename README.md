# 内网图片管理器

Vue 3 + Fastify + Prisma + PostgreSQL 的持久化图片管理工具。所有用户、图片、缩略图、标签、日志、回收站和系统设置都通过后端 API 保存。

## Windows 本机启动

1. 安装 PostgreSQL for Windows，并创建数据库 `image_manager`。默认连接配置见 `.env.example`。
2. 复制配置文件：

```powershell
Copy-Item .env.example .env
```

3. 根据本机 PostgreSQL 用户名和密码修改 `.env` 中的 `DATABASE_URL`。
4. 安装依赖并生成 Prisma Client：

```powershell
npm.cmd install
npm.cmd --prefix client install
npm.cmd --prefix server install
npm.cmd run db:generate
npm.cmd run db:migrate
```

5. 启动前后端：

```powershell
npm.cmd run dev
```

也可以直接双击项目根目录的 `start-windows.cmd`，或执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\start-windows.ps1
```

启动脚本会检查 PostgreSQL 服务、5432 端口、依赖和数据库迁移，并自动打开前端页面。系统设置中保存的服务端口会在下次启动时被读取，前端代理也会同步切换。

前端地址：`http://localhost:5173`，API 地址：`http://localhost:4311`。

## AI 标签识别（llama.cpp）

上传图片时自动调用本地视觉模型生成建议标签，完全离线、无需密钥。系统设置 → AI 图片标签 → 打开开关后**一键自动下载并启动**（llama.cpp 引擎 + Qwen2.5-VL 模型，自动识别 NVIDIA 显卡与系统代理），就绪后自动填入接口地址并显示「成功」。详见 [AI_SETUP.md](AI_SETUP.md)；识别提示词在 `server/prompts/tagging.txt`，可随时编辑热生效。

首次启动会创建管理员账户：

- 用户名：`ADMIN_USERNAME`，默认 `admin`
- 密码：`ADMIN_PASSWORD`，默认 `admin123`

首次登录后请立即在系统设置中修改密码。

## 数据目录

默认图片文件保存在 `./storage`，包括原图、缩略图和回收站文件。可以在 `.env` 中将 `STORAGE_DIR` 设置为 Windows 本地目录或已映射的 NAS 盘符。

## 可选 Docker

如果后续需要使用容器，项目仍提供 `docker-compose.yml`，但 Windows 本机运行不需要 Docker。

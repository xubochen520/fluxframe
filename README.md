# 内网图片管理器

Vue 3 + Fastify + Prisma + PostgreSQL 的真实持久化图片管理工具。前端不再使用演示数据，所有用户、图片、缩略图、标签、日志、回收站和系统设置都通过后端 API 保存。

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

首次启动会创建管理员账户：

- 用户名：`ADMIN_USERNAME`，默认 `admin`
- 密码：`ADMIN_PASSWORD`，默认 `admin123`

首次登录后请立即在系统设置中修改密码。

## 数据目录

默认图片文件保存在 `./storage`，包括原图、缩略图和回收站文件。可以在 `.env` 中将 `STORAGE_DIR` 设置为 Windows 本地目录或已映射的 NAS 盘符。

## 可选 Docker

如果后续需要使用容器，项目仍提供 `docker-compose.yml`，但 Windows 本机运行不需要 Docker。

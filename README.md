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

## 局域网访问与安卓 App

电脑与手机连同一 Wi-Fi 后：

- **网页访问**：执行 `npm.cmd --prefix client run build` 构建前端，后端（4311 端口）会自动托管页面，手机浏览器打开 `http://<电脑IP>:4311` 即可（电脑 IP 可用 `ipconfig` 查看，如 192.168.1.11）。
- **安卓 App（APK）**：项目内置 Capacitor 壳（`client/android`），App 启动时**自动扫描局域网**并连接 4311 端口的图片管理服务（多个结果会列出选择，地址会被记住；也可手动输入）。构建 APK：

```powershell
npm.cmd --prefix client run build
cd client
npx.cmd cap sync android
cd android
.\gradlew.bat assembleDebug
# 产物：client\android\app\build\outputs\apk\debug\app-debug.apk
```

把 APK 传到手机安装即可（需允许"安装未知来源应用"）。App 图标与等待页在 `client/android/app/src/main/res` 与 `assets/public/index.html`。

> 首次手机访问前，请确认 Windows 防火墙已放行 4311 端口（或后端所在程序），否则手机无法连接。

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

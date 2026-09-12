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

- **一键发布（推荐）**：双击根目录 `start-lan.cmd`（或执行 `.\start-lan.ps1`），脚本会自动完成：检查/启动 PostgreSQL → 按需构建前端与后端 → 以发布模式启动（单端口同时提供页面和 API）→ 显示局域网地址。首次请**以管理员身份运行一次**，脚本会自动放行防火墙（之后无需再操作）。手机端打开 App 会自动扫描并连接，无需输入任何地址。
- **网页访问**：手机浏览器打开启动时显示的 `http://<电脑IP>:4311`（如 http://192.168.1.11:4311）即可。
- **开发模式**：开发调试仍用 `npm.cmd run dev` 或 `start-windows.cmd`（Vite 热更新，手机可访问 5173）；发布模式改动前端源码后需重新运行 start-lan 或 `npm.cmd --prefix client run app:android`（会自动增量构建）。
- **安卓 App（APK）**：`client/android` 为 Capacitor 壳，App 启动时自动扫描局域网 4311/5173 端口并连接（多结果选择、地址记忆、手动输入备用）。构建 APK：

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

## DeepSeek 余额记账（总览横向长条）

总览页顶部的「DEEPSEEK 余额」长条会把 DeepSeek 账户余额与用量持久化到 PostgreSQL，手机/网页端都能实时查看：

- **余额**：每隔 N 秒（默认 60，可在长条的「管理」里改成 30/60/120/300）调用 `GET https://api.deepseek.com/user/balance` 观测一次，最新余额、赠金、充值余额都落库。
- **今日已用 / 本月使用**：**余额差值记账**——余额下降的差值即当日消费，按天归档（跨天不会把昨天的消费算进今天）；余额上升记为充值，不计入用量；币种切换只重置基准。即使只填 API KEY、不填平台令牌也能用。
- **Tokens / 每个 KEY 用量 / 缓存命中率**：填入 platform.deepseek.com 的**平台会话令牌**（可选）后，调用平台用量接口（`/api/v0/usage/by_api_key/amount` 与 `/cost`），按 KEY、模型、天精确统计 tokens 与真实费用，并自动按名称绑定本地 KEY。
- **多 KEY**：每个 KEY 独立探测余额。**同一账户的多个 KEY 请填相同的「账户备注」**（共用一份余额，只记一次账）；若两个独立账户观测到完全相同的余额，长条会提示「可能属于同一个 DeepSeek 账户」，可一键合并账本，避免余额与用量被重复计算。

使用方式：总览横向长条里直接填 `sk-...` 点「保存并开始记账」；平台令牌获取方法见长条内「管理 → 平台令牌」的说明（浏览器登录 platform.deepseek.com → F12 → Network → 找 `usage/by_api_key/amount` → 复制请求头 `Authorization` 的值整段粘贴）。API 密钥与令牌只在服务端保存，接口只回显掩码（`sk-ab****1234`），增删改都会写入访问日志。

首次启动会创建管理员账户：

- 用户名：`ADMIN_USERNAME`，默认 `admin`
- 密码：`ADMIN_PASSWORD`，默认 `admin123`

首次登录后请立即在系统设置中修改密码。

## 数据目录

默认图片文件保存在 `./storage`，包括原图、缩略图和回收站文件。可以在 `.env` 中将 `STORAGE_DIR` 设置为 Windows 本地目录或已映射的 NAS 盘符。

## 可选 Docker

如果后续需要使用容器，项目仍提供 `docker-compose.yml`，但 Windows 本机运行不需要 Docker。

# fluxframe 扩展功能 API 接口契约

> 面向 Android 客户端的实现文档。
> **唯一依据**：后端源码 `server/src/index.ts`、`server/src/parse-server.ts`、`server/src/deepseek.ts`、`server/src/bili-login.ts`、`server/src/ai-manager.ts`、`server/src/ffmpeg-manager.ts` 与 `prisma/schema.prisma`。
> 所有字段均从源码的 `reply.send({...})` / `return {...}` / Prisma 查询结果推导；**源码未明确的信息一律标注「源码未明确」，不做臆测**。
> 文档版本对应源码：`server/src/index.ts`（1481 行）、`server/src/parse-server.ts`（733 行）。

---

## 0. 通用约定

### 0.1 服务基址

| 项目 | 值 | 来源 |
|---|---|---|
| 默认端口 | `4310`（`process.env.PORT` 可覆盖；数据库中 `SystemSetting.port` 可覆盖运行时端口） | `index.ts:29,1437-1439` |
| 监听地址 | `process.env.HOST || '0.0.0.0'` | `index.ts:1473` |
| 图片库与视频解析 | **同一端口同一源**（解析接口由 `registerParseApi(app)` 注册进同一 Fastify 实例） | `index.ts:1433` |
| CORS | `origin: true, credentials: true` → 允许携带 Cookie 的跨域；响应头由 `@fastify/cors` 生成 | `index.ts:334` |
| 静态托管 | 若存在 `client/dist`，非 `/api`、`/assets` 的 GET 请求回落到 `index.html`（SPA 路由） | `index.ts:1457-1469` |

> **真机联调提示**：`http://<PC-IP>:4310`。Android 9+ 默认禁止明文 HTTP，需在 `AndroidManifest.xml` 中配置 `android:usesCleartextTraffic="true"` 或 network-security-config（源码未涉及，属客户端事项）。

### 0.2 请求 / 响应约定

- 请求体统一 `Content-Type: application/json`（`/api/images/upload*` 除外，本文件不涉及）。
- 响应体统一 JSON（`/api/stream`、`/api/ping` 例外，见第 6 章）。
- 所有时间字段为 **UTC ISO-8601 字符串**（`Date.prototype.toISOString()`，如 `2025-01-31T08:00:00.000Z`）；`capturedAt`、DeepSeek 的 `day` 为 `YYYY-MM-DD`。
- 用户 ID（`User.id`、`Image.id`、`Tag.id`、`DeepseekKey.id`、`AuditLog.id`）为 Prisma `cuid()` 字符串；`/api/parse/import` 的任务 ID 为 `randomUUID()`（标准 UUID v4）。
- `BigInt` 字段（`Image.size`）不会直接出现在 JSON 中，服务端已转成人类可读字符串（如 `"1.2 MB"`）。

### 0.3 认证机制

#### 0.3.1 登录（`POST /api/auth/login`）

请求体（zod）：

```ts
z.object({ username: z.string(), password: z.string() })
```

成功响应 `200`：

```json
{ "user": { "id": "clx1234567890", "username": "admin", "role": "ADMIN", "r18Mode": false } }
```

**Cookie 设置**（源码 `index.ts:355-357`）：

```ts
const rawToken = randomBytes(32).toString('hex')      // 64 位小写十六进制
reply.setCookie(sessionCookie, rawToken, {
  httpOnly: true,        // 是
  sameSite: 'lax',       // 是
  secure: cookieSecure,  // 见下表
  path: '/',
  maxAge: 60 * 60 * 24 * 30,   // 2592000 秒 = 30 天
})
```

| 属性 | 值 |
|---|---|
| Cookie 名 | `fluxframe_session`（常量 `sessionCookie`，`index.ts:35`） |
| 值 | `randomBytes(32).toString('hex')` → 64 字符十六进制字符串（非 JWT，未签名） |
| `HttpOnly` | `true` |
| `SameSite` | `Lax` |
| `Path` | `/` |
| `Max-Age` | `2592000`（30 天） |
| `Secure` | 由环境变量决定：`COOKIE_SECURE` 已定义时取其布尔值；否则 `NODE_ENV === 'production'`。**纯 HTTP 内网部署需 `COOKIE_SECURE=false`**，否则浏览器拒存；Android 原生 HTTP 客户端不受 `Secure` 限制 |
| `Domain` | 未设置（Fastify 默认不下发 Domain 属性） |

**会话存储**：服务端保存 `sha256(rawToken)` 到 `Session.tokenHash`（`unique`），`expiresAt = 现在 + 30 天`。每次请求用 Cookie 值算 sha256 查表，并校验 `expiresAt > now`。
**注意**：没有滑动续期——30 天后必须重新登录（源码未提供 refresh 接口）。

#### 0.3.2 后续请求如何携带

- 浏览器：`credentials: 'include'`（fetch）/ `withCredentials`，Cookie 自动携带。
- **Android（OkHttp/Retrofit）**：服务端依赖标准 Cookie，客户端必须自己维护 CookieJar，或手动加请求头：

```
Cookie: fluxframe_session=<64位hex>
```

登录响应的 `Set-Cookie` 需要被持久化（OkHttp 默认 `CookieJar.NO_COOKIES` 不保存，需自行实现或使用 `PersistentCookieJar`）。

- **无 CSRF Token 机制**：没有 `csrf` 字段、没有双提交校验，仅靠 `SameSite=Lax`。因此 Android 原生客户端只需带 Cookie。
- 登出 `POST /api/auth/logout`：删除该 token 对应的会话行，并 `clearCookie(fluxframe_session, { path: '/' })`，返回 **`204` 无响应体**。

#### 0.3.3 `GET /api/me`（完整字段）

- 权限：需登录（`requireUser`）
- 响应 `200`：

```json
{ "id": "clx1234567890", "username": "admin", "role": "ADMIN", "r18Mode": false }
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `id` | string | 用户 ID（cuid） |
| `username` | string | 用户名（唯一） |
| `role` | `"ADMIN"` \| `"USER"` | 角色枚举（Prisma `enum UserRole`） |
| `r18Mode` | boolean | 是否开启 R18 模式；`false` 时所有 R18 相关内容对本次请求不可见 |

未登录 → `401 {"message":"请先登录"}`。

#### 0.3.4 相关辅助接口（与本文件同源，Android 下载场景会用到）

| 方法 | 路径 | 权限 | 响应 |
|---|---|---|---|
| `GET` | `/api/download/session` | 登录 | `{"token":"<当前 Cookie 值>"}`；用于把 Cookie 明文交给系统 DownloadManager（源码注释：令牌不出本机、不进 URL） |
| `PATCH` | `/api/me/r18-mode` | 登录 | body `{"enabled": boolean}`（zod：`z.object({enabled: z.boolean()})`）→ `{"r18Mode": true}` |
| `PATCH` | `/api/auth/password` | 登录 | body `{"currentPassword": string, "newPassword": string(min 8)}` → `{"ok":true}`；当前密码错误 → `400 {"message":"当前密码错误"}` |
| `POST` | `/api/auth/register` | 公开 | body `{"username": string(3..30, trim), "password": string(min 8)}` → `201 {id, username, role, r18Mode:false}`；重名 → `409 {"message":"用户名已存在"}`。**第一个注册用户自动成为 ADMIN** |
| `GET` | `/api/health` | 公开 | `{"ok":true,"service":"fluxframe-api","time":"<ISO>"}` |

### 0.4 通用错误响应格式与状态码

服务端使用**自定义错误处理器**（`index.ts:325-332`），规则如下：

| 触发条件 | 状态码 | 响应体 |
|---|---|---|
| zod 校验失败（任一 `.parse()` 抛 `ZodError`） | `400` | `{"message":"请求参数不正确","issues":[{...ZodIssue}]}` |
| 路由内显式 `reply.code(N).send({message})` | `N` | `{"message":"<中文提示>"}`（各接口自定义，见下文） |
| 未登录（`requireUser` 失败） | `401` | `{"message":"请先登录"}` |
| 非管理员（`requireAdmin` 失败） | `403` | `{"message":"需要管理员权限"}` |
| 其他异常（含 Fastify 内建异常，如 body 为空 / JSON 非法 / Content-Type 不支持 / 路由层抛错） | `error.statusCode \|\| 500` | `{"message": detail}`，其中 `detail = (NODE_ENV !== 'production' && status >= 500) ? "服务器内部错误：<真实异常>" : "服务器内部错误"` |
| 未匹配的 `/api/*` 路由（已托管 `client/dist` 时） | `404` | `{"message":"Not Found"}` |
| 未匹配路由（未托管 `client/dist`，即纯 dev 后端） | `404` | Fastify 默认：`{"message":"Route POST:/api/xxx not found","error":"Not Found","statusCode":404}` |

> ⚠️ **两个必须注意的怪癖**：
> 1. 上表第 5 行的 `detail` 只在 `status >= 500` 时才带真实原因；**400 级别的 Fastify 内建异常（如请求体为空、`Content-Type` 不是 JSON）也会返回 `{"message":"服务器内部错误"}`**，客户端不要把这个文案当作「5xx」判断依据，应以 HTTP 状态码为准。
> 2. `issues[].path` 是数组（如 `["body","variant"]`），`issues[].message` 是英文 zod 文案。

`ZodIssue` 示例：

```json
{
  "message": "请求参数不正确",
  "issues": [
    { "code": "too_small", "minimum": 1, "type": "string", "inclusive": true,
      "path": ["body", "qrcodeKey"], "message": "String must contain at least 1 character(s)" }
  ]
}
```

### 0.5 数值 / 进度字段约定

| 约定 | 说明 |
|---|---|
| 进度 `progress` | 浮点 `0..1`；AI/ffmpeg 的 `done`/`total` 为**字节数** |
| 「进度未知」 | `null`（AI 下载仅当上游返回 `content-length` 才有 total）；客户端应显示不确定进度条 |
| 金额 | `number`，已 `round()`（多数保留 4 位小数，汇总项保留 2 位） |
| 货币 | 字符串，默认 `"CNY"` |

---

## 1. 访问日志与系统设置

### 1.1 `GET /api/audit-logs`

| 项 | 值 |
|---|---|
| 方法 / 路径 | `GET /api/audit-logs` |
| 权限 | **需登录 + `role === 'ADMIN'`**（`requireAdmin`） |
| 请求参数 | **无**。源码未定义任何 query zod schema → `?limit=`、`?offset=`、`?page=` 等参数**全部被忽略** |
| 副作用 | 无（只读，不写审计） |

**响应 `200`**（`index.ts:842-847`）：

```json
{
  "items": [
    {
      "id": "clxaudit0001",
      "action": "上传图片",
      "target": "夕阳剪影",
      "user": "admin",
      "ip": "192.168.1.23",
      "scope": "内网",
      "time": "2025-01-31T08:12:33.123Z",
      "tone": "green"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `items` | array | 最多 **500** 条，按 `createdAt desc`（`take: 500`，**无分页**） |
| `items[].id` | string | `AuditLog.id`（cuid） |
| `items[].action` | string | 中文动作词，如 `用户登录`/`上传图片`/`删除图片`/`修改系统设置`/`AI 引擎启动`/`提取视频` 等 |
| `items[].target` | string | 目标描述；DB 中可空，服务端空值兜底为 `""` |
| `items[].user` | string | 用户名；`AuditLog.userId` 为 null（未登录动作或用户已删除）时为 `"系统"` |
| `items[].ip` | string | 来源 IP（`x-forwarded-for` 仅在 `TRUST_PROXY=true` 时可信；已剥离 `::ffff:` 前缀） |
| `items[].scope` | `"内网"` \| `"外网"` | 服务端把 DB 的 `AuditScope` 映射为中文；**DB 枚举含 `UNKNOWN`，但代码把「非 INTERNAL」一律显示为 `外网`**，客户端只需支持这两个值 |
| `items[].time` | string | `createdAt.toISOString()`（UTC ISO-8601） |
| `items[].tone` | `"blue"` \| `"violet"` \| `"orange"` \| `"red"` \| `"green"` | 展示色阶，服务端按动作关键词计算，**判定顺序即优先级**（见下） |

`tone` 计算规则（`index.ts:87-93`，按顺序判断，命中即返回）：

| 顺序 | 条件（`action` 包含） | 值 |
|---|---|---|
| 1 | `删除` | `red` |
| 2 | `标签` | `violet` |
| 3 | `登录` 或 `上传` | `green` |
| 4 | `下载` 或 `提取` | `orange` |
| 5 | 其他 | `blue` |

**错误**：`401 {"message":"请先登录"}`；`403 {"message":"需要管理员权限"}`。

**数据来源字段类型**（`AuditLog` 模型）：

| Prisma 字段 | 类型 |
|---|---|
| `id` | `String @id @default(cuid())` |
| `action` | `String` |
| `target` | `String?`（可空） |
| `userId` | `String?`（可空，`onDelete: SetNull`） |
| `ip` | `String` |
| `scope` | `AuditScope`（`INTERNAL` / `EXTERNAL` / `UNKNOWN`） |
| `userAgent` | `String?`（**响应中不返回**） |
| `createdAt` | `DateTime @default(now())` |

**保留策略**：服务端每 24 小时清理 30 天前的日志（`auditRetentionDays = 30`，`maintenanceIntervalMs = 24h`）。

---

### 1.2 `GET /api/settings`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求参数 | 无 |
| 副作用 | 无 |

**响应 `200`** = `{ ...默认值, ...数据库 SystemSetting 全部键值, 计算字段 }`（`index.ts:849-877`）。

**源码默认值**（`defaultSettings`，仅当 DB 无对应键时生效）：

| 键 | 默认值 | 类型 |
|---|---|---|
| `siteName` | `"fluxframe"` | string |
| `theme` | `"Aurora"` | string |
| `darkMode` | `true` | boolean |
| `webglEnabled` | `true` | boolean |
| `animationEnabled` | `true` | boolean |
| `fluidColors` | `["#4f46e5","#06b6d4","#f472b6"]` | string[3] |
| `fluidSpeed` | `3`（**恒定，不可改**） | number |
| `uploadLimitMb` | `50` | number |
| `port` | `4310`（`process.env.PORT`） | number |
| `storageDir` | `process.env.STORAGE_DIR \|\| "./storage"` | string |
| `recycleRetentionDays` | `30` | number |
| `aiEnabled` | `AI_ENABLED === 'true' \|\| 存在 AI_API_KEY` | boolean |
| `aiBaseUrl` | `AI_BASE_URL \|\| "http://127.0.0.1:8080/v1"` | string |
| `aiModel` | `AI_MODEL \|\| "qwen2.5-vl-7b-instruct"` | string |

**安全处理（源码显式删除，绝不回显）**：`aiApiKey`、`deepseekPlatformToken`、`biliSessdata`。

**计算/覆盖字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `fluidSpeed` | number | **恒为 `3`**（写入端也被强制覆盖） |
| `aiEnabled` | boolean | 覆盖为 `getAiConfig().enabled`。启用条件：`!显式关闭 && (env AI_ENABLED=true \|\| DB aiEnabled=true \|\| 已配置 apiKey)`；**只有 `AI_ENABLED=false` 或 DB `aiEnabled=false` 能强制关闭** |
| `aiBaseUrl` | string | 覆盖为生效值，**已去掉结尾 `/`** |
| `aiModel` | string | 覆盖为生效值 |
| `aiConfigured` | boolean | 是否已保存/存在 API Key（`Boolean(apiConfig.apiKey)`） |
| `deepseekEnabled` | boolean | DeepSeek 记账开关（DB 缺省 `true`） |
| `deepseekRefreshSeconds` | number | 缺省 `60`，最小 `30` |
| `deepseekPlatformConfigured` | boolean | 是否已配置平台会话令牌 |
| `biliSessdataConfigured` | boolean | DB 有 `biliSessdata` 或环境变量 `BILI_SESSDATA` 存在 |
| `ffmpeg` | object | 见下 |

`ffmpeg` 对象（**注意与 `GET /api/ffmpeg/status` 的结构不同**）：

```json
{
  "found": true,
  "path": "C:\\project\\models\\ffmpeg\\ffmpeg.exe",
  "version": "7.1",
  "busy": false,
  "progress": { "phase": "ready", "done": 1, "total": 1 }
}
```

> `path` = `ffmpeg?.exe || null`；`version` = `ffmpeg?.version || null`；`busy`/`progress` 来自 `getFfmpegStatus()`。

**响应示例**：

```json
{
  "siteName": "fluxframe",
  "theme": "Aurora",
  "darkMode": true,
  "webglEnabled": true,
  "animationEnabled": true,
  "fluidColors": ["#4f46e5", "#06b6d4", "#f472b6"],
  "fluidSpeed": 3,
  "uploadLimitMb": 50,
  "port": 4310,
  "storageDir": "./storage",
  "recycleRetentionDays": 30,
  "aiEnabled": false,
  "aiBaseUrl": "http://127.0.0.1:8080/v1",
  "aiModel": "qwen2.5-vl-7b-instruct",
  "aiConfigured": false,
  "deepseekEnabled": true,
  "deepseekRefreshSeconds": 60,
  "deepseekPlatformConfigured": false,
  "biliSessdataConfigured": false,
  "ffmpeg": { "found": false, "path": null, "version": null, "busy": false,
              "progress": { "phase": "idle", "done": 0, "total": 0 } }
}
```

> 若 DB 中存在客户端写入的其它自定义键（见 1.3），它们会**原样出现在响应顶层**（`{...settings}` 展开）。

**错误**：`401` / `403`。

---

### 1.3 `PATCH /api/settings`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体 | **`z.record(z.unknown())`** → 任意 `{key: value}` 的 JSON 对象，**无字段白名单**，未知键会被静默写入 DB |
| 副作用 | 每个键 `upsert` 进 `SystemSetting`；写一条审计 `修改系统设置`（target = 传入键名逗号拼接） |

**校验与规范化**（`index.ts:878-900`）：

| 键 | 规则 | 违反时 |
|---|---|---|
| `fluidSpeed` | 无论传什么，**强制覆盖为 `3`** | 不报错 |
| `aiApiKey` | 若为 string 且 `trim()` 后为空 → 从 body 中删除（= 不修改） | 不报错 |
| `biliSessdata` | 为 string 时先 `extractSessdata()`（支持整段 Cookie 头或纯值）；提取结果非空且不匹配 `/^[A-Za-z0-9%_-]{20,200}$/` | `400 {"message":"B站 Cookie 格式不正确（请粘贴 SESSDATA 的值，或浏览器里完整的 Cookie 文本）"}` |
| `port` | `Number.isInteger(Number(v))` 且 `1024 <= v <= 65535` | `400 {"message":"端口必须在 1024-65535 之间"}` |
| `uploadLimitMb` | `Number.isFinite(Number(v))` 且 `1 <= v <= 2048` | `400 {"message":"上传限制必须在 1-2048 MB 之间"}` |
| `recycleRetentionDays` | `Number.isFinite(Number(v))` 且 `0 <= v <= 3650` | `400 {"message":"回收站保留天数不正确"}` |

> ⚠️ **重要实现陷阱**：校验用 `Number(v)`（字符串 `"50"` 能通过校验），但**入库是原始值**（`Json`），而读取端用 `typeof value === 'number'` 判断（`index.ts:481,561,1317,1439`）。因此 **Android 客户端必须发送 JSON 数字（`"port": 4310`）而不是字符串（`"port": "4310"`）**，否则值虽写入 DB 但运行时不生效。

**常用可写键**（源码读取/写入过的键，全部可选）：

| 键 | 类型 | 说明 |
|---|---|---|
| `siteName` | string | 站点名 |
| `theme` | string | 主题名（前端主题列表：`Aurora`/`Ember`/`Mono`/`Nebula`/`Forest`/`Rose`/`Ocean`/`Solar`） |
| `darkMode` | boolean | 深浅色 |
| `webglEnabled` | boolean | 全站流体背景 |
| `animationEnabled` | boolean | 页面动画 |
| `fluidColors` | string[] | 三色数组 |
| `uploadLimitMb` | number | 1..2048，影响上传与解析导入的单文件上限 |
| `port` | number | 1024..65535，**需重启服务生效** |
| `storageDir` | string | 存储目录 |
| `recycleRetentionDays` | number | 0..3650 |
| `aiEnabled` | boolean | AI 标签总开关 |
| `aiBaseUrl` | string | OpenAI 兼容或 Ollama 地址 |
| `aiModel` | string | 模型 ID |
| `aiApiKey` | string | 服务端不回显；空串 = 不修改 |
| `biliSessdata` | string | SESSDATA；空串 = **清除**（与 `aiApiKey` 语义相反！） |
| `ffmpegPath` | string | 自定义 ffmpeg 路径（定位优先级最高） |
| `deepseekEnabled` | boolean | 记账开关 |
| `deepseekRefreshSeconds` | number | **注意**：用 PATCH /api/settings 写此键**不经过 30..3600 校验**，建议改用 `PUT /api/deepseek/config` |
| `deepseekPlatformToken` | string | 同上，建议改用 `PUT /api/deepseek/config` |

**响应 `200`**（`index.ts:895-899`）：

```json
{
  "siteName": "fluxframe",
  "theme": "Aurora",
  "darkMode": true,
  "webglEnabled": true,
  "animationEnabled": true,
  "fluidColors": ["#4f46e5", "#06b6d4", "#f472b6"],
  "fluidSpeed": 3,
  "uploadLimitMb": 100,
  "port": 4310,
  "storageDir": "./storage",
  "recycleRetentionDays": 30,
  "aiEnabled": false,
  "aiBaseUrl": "http://127.0.0.1:8080/v1",
  "aiModel": "qwen2.5-vl-7b-instruct",
  "aiConfigured": false
}
```

> ⚠️ **PATCH 响应 ≠ GET 响应**：PATCH 只返回 `{...defaultSettings, ...(本次提交的键, 去掉 aiApiKey/biliSessdata), fluidSpeed:3, aiEnabled, aiBaseUrl, aiModel, aiConfigured}`。
> **不含** `deepseekEnabled` / `deepseekRefreshSeconds` / `deepseekPlatformConfigured` / `biliSessdataConfigured` / `ffmpeg`。
> Android 端保存后若需要完整状态，请**再调用一次 `GET /api/settings`**。

**错误**：`400`（上述校验，或 body 非 JSON 对象）；`401`；`403`。

---

## 2. ffmpeg 引擎（B站高清 DASH 合并）

### 2.1 `GET /api/ffmpeg/status`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求参数 | 无 |
| 副作用 | 无（会 spawn `ffmpeg -version` 探测，结果模块级缓存） |

**响应 `200`**（`index.ts:903-908`）：

```json
{
  "found": true,
  "path": "C:\\project\\models\\ffmpeg\\ffmpeg.exe",
  "version": "7.1",
  "busy": false,
  "progress": { "phase": "idle", "done": 0, "total": 0 }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `found` | boolean | 是否定位到可用的 ffmpeg |
| `path` | string \| null | 可执行文件绝对路径（`ffmpeg?.exe ?? null`） |
| `version` | string \| null | 版本号，取自 `ffmpeg -version` 首行的 `ffmpeg version X` |
| `busy` | boolean | 是否有下载任务进行中 |
| `progress` | object | `getFfmpegStatus().progress` 的**浅拷贝** |

`progress.phase` 枚举（`FfmpegProgress`，`ffmpeg-manager.ts:9`）：

| 值 | 含义 |
|---|---|
| `idle` | 未开始（初始值） |
| `downloading` | 正在下载 zip（`done`/`total` = 字节） |
| `extracting` | 正在解压（`done`/`total` 重置为 0） |
| `ready` | 完成（`done: 1, total: 1`） |
| `error` | 失败，此时 `progress.error` 为中文原因（如「当前为 Linux 环境：请在系统设置中填写 ffmpeg 路径…」、「下载完成但 ffmpeg 无法运行（被杀毒软件拦截？）」） |

`progress.error`：仅 `phase === "error"` 时存在（string）。

**定位优先级**（`locateFfmpeg`）：设置里的 `ffmpegPath` → `FFMPEG_PATH` → `models/ffmpeg/ffmpeg(.exe)` → `PATH` 各目录 → 平台常见目录（Win：`C:\ffmpeg\bin\ffmpeg.exe`、`%ProgramFiles%\ffmpeg\bin\ffmpeg.exe`；Linux：`/usr/bin/ffmpeg`、`/usr/local/bin/ffmpeg`、`/opt/ffmpeg/bin/ffmpeg`、`/snap/bin/ffmpeg`）。

**错误**：`401` / `403`。

---

### 2.2 `POST /api/ffmpeg/download`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体 | **无**（源码不解析 body，传 `{}` 或不传均可） |
| 副作用 | **火忘式后台任务**：立即返回，然后异步下载+解压；写审计 `ffmpeg 下载`（target=`开始`） |

**响应 `200`**：

```json
{ "ok": true }
```

**必须强调的行为**（`index.ts:909-915` + `ffmpeg-manager.ts:148`）：

1. `void downloadFfmpeg().then(...)` —— **不 await**。返回的 `{ok:true}` 只代表「请求已被接受」，**不代表下载开始、成功或失败**。
2. 下载失败**只在服务端日志告警**，HTTP 层拿不到错误。
3. 若 `downloadFfmpeg()` 内部判定已有任务在跑（`busy === true`）或已存在可用 ffmpeg，会返回 `{ok:false,...}` / `{ok:true}`，但**这些都是后台结果，客户端看到的仍是 `{ok:true}`**。
4. 进度获取方式：**轮询 `GET /api/ffmpeg/status`**，观察 `busy` 与 `progress`。

**推荐轮询策略**（同前端实现 `SettingsPanel.vue:45-57`）：间隔 **2000 ms**；`busy === false` 时停止轮询。

**下载源**：`process.env.FFMPEG_DOWNLOAD_URL || "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"`（仅 Windows 构建；Linux 环境会直接返回后台失败并提示用系统包管理器）。

**错误**：`401` / `403`。

---

## 3. 本地 AI 引擎（llama.cpp + Qwen2.5-VL）

### 3.1 `GET /api/ai/status`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求参数 | 无 |
| 副作用 | 无（会 `fetch` 探测 5 个端口） |

探测端口列表（`ai-manager.ts:58`）：`8080, 8081, 8000, 11434, 1234`（依次请求 `http://127.0.0.1:<port>/v1/models`，超时 1.5 s）。

**响应 `200`**（`AiStatusPayload`，`ai-manager.ts:25-32 / 453-469`）：

```json
{
  "running": true,
  "port": 8080,
  "baseUrl": "http://127.0.0.1:8080/v1",
  "detected": { "port": 8080, "modelId": "qwen2.5-vl-7b-instruct" },
  "files": { "server": true, "model": true, "mmproj": true, "variant": "7b" },
  "progress": {
    "phase": "ready",
    "llamaDone": 0, "llamaTotal": 0,
    "modelDone": 0, "modelTotal": 0,
    "modelName": "Qwen2.5-VL-7B",
    "variant": "7b",
    "port": 8080
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `running` | boolean | 是否探测到在跑的 llama.cpp（`Boolean(detected)`） |
| `port` | number \| null | 探测到的端口 |
| `baseUrl` | string \| null | `http://127.0.0.1:<port>/v1`；未运行则 `null` |
| `detected` | object \| null | `{port: number, modelId: string \| null}`；未运行则 `null`。`modelId` 取自 `/v1/models` 的 `data[0].id` |
| `files.server` | boolean | `models/llama-server(.exe)` 是否存在 |
| `files.model` | boolean | 是否存在**任一** variant 的 GGUF 模型 + mmproj 都齐全（`detectReadyVariant()` 按 `3b` → `7b` 顺序取第一个齐全者；`Boolean(ready)`） |
| `files.mmproj` | boolean | 同上（与 `model` **恒等**，不要用它区分两个文件） |
| `files.variant` | `"3b"` \| `"7b"` \| null | 就绪的那个 variant；都不齐则 `null` |
| `progress` | object | 见下 |

`progress` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `phase` | enum | `idle` \| `fetching-release` \| `downloading-llama` \| `extracting` \| `downloading-model` \| `starting` \| `ready` \| `error` \| `stopped` |
| `llamaDone` / `llamaTotal` | number | llama.cpp 安装包下载字节进度（含 cudart 包时被复用） |
| `modelDone` / `modelTotal` | number | GGUF 模型下载字节进度。`modelTotal` 是**估算值**：`(modelMB + mmprojMB) * 1024 * 1024`（7b = 4466+1291 MB，3b = 1840+1276 MB） |
| `modelName` | string | 展示名：`"Qwen2.5-VL-7B"` / `"Qwen2.5-VL-3B"`；启动中的进度对象会保留上次值 |
| `variant` | string | `"3b"` \| `"7b"`（**注意：是请求参数值，未做合法化**） |
| `error` | string? | 仅 `phase === "error"` 时存在 |
| `port` | number? | 仅启动成功/就绪时出现 |

**错误**：`401` / `403`。

> ⚠️ **延迟提示**：`detectRunning()` 会**串行**探测 5 个端口，每端口 1.5 s 超时 → 全部不可达时本接口最坏耗时约 **7.5 s**。Android 端读超时请设 ≥ 15 s。

---

### 3.2 `POST /api/ai/download`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体（zod 完整抄录） | 见下 |
| 副作用 | **火忘式后台任务**：下载 llama.cpp（GitHub Releases）+ GGUF 模型（HuggingFace）→ 解压 → **自动启动服务**；写审计 `AI 引擎下载`（target=`<variant> 开始`） |

**zod schema（完整）**：

```ts
z.object({
  variant: z.enum(['3b', '7b']).default('7b'),
  mirror: z.string().optional(),
})
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `variant` | `"3b"` \| `"7b"` | 否 | `"7b"` | 模型规模 |
| `mirror` | string | 否 | 无 | 模型下载镜像基址；留空/不传时用 `AI_DOWNLOAD_MIRROR` 环境变量，再兜底 `https://huggingface.co`。前端使用的国内镜像是 `https://hf-mirror.com` |

**响应 `200`**：

```json
{ "ok": true }
```

> ⚠️ 与 ffmpeg 下载同样的火忘语义：`{ok:true}` 只代表请求已接受。
> **并且**：若后台已有任务在跑（`busy`），`downloadAiStack` 会返回 `{ok:false, error:"已有下载任务进行中，请稍候"}`，但该结果**只写服务端日志**，HTTP 仍是 `{ok:true}`。
> 进度获取：**轮询 `GET /api/ai/status`**，重点看 `progress.phase` / `progress.error` / `running`。

**推荐轮询策略**（同前端 `SettingsPanel.vue:200-221`）：间隔 **2000 ms**。终止条件：
- `running === true && baseUrl != null` → 成功就绪；
- `progress.phase === "error"` → 失败，展示 `progress.error`；
- `["idle","stopped"].includes(progress.phase) && files.server && files.model && files.mmproj` → 文件就绪但服务没起（应引导用户调 `POST /api/ai/start`）。

**错误**：`400`（body 非法，如 `variant: "13b"`）；`401`；`403`。

---

### 3.3 `POST /api/ai/start`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体 | 无（前端发 `body: '{}'`） |
| 副作用 | 可能 spawn `llama-server` 子进程；**成功时**写审计 `AI 引擎启动`（target=`llama.cpp`） |
| 阻塞时长 | **同步等待就绪，最长约 150 秒**（`deadline = now + 150_000`，每 2 s 轮询一次 `/v1/models`）→ Android 端须把读超时设为 ≥ 180 s |

**响应情形**：

| 情形 | 状态码 | 响应体 |
|---|---|---|
| 已探测到在跑的实例（幂等） | `200` | `{"ok":true}`（同时把 `progress.phase` 置为 `ready`） |
| 文件不完整（缺 `llama-server` 或 模型/mmproj） | `200` | `{"ok":false,"error":"文件不完整，请先自动下载"}` |
| 启动成功 | `200` | `{"ok":true}` |
| 启动失败（退出码非 0）/ 启动超时 / 未找到空闲端口 | **`500`** | `{"message":"服务器内部错误"}`；`NODE_ENV !== 'production'` 时为 `{"message":"服务器内部错误：llama-server 启动失败（退出码 1），详见 logs/llama-server.log"}` 或 `…：llama-server 启动超时，详见 logs/llama-server.log` 或 `…：未找到空闲端口` |

> ⚠️ 源码中 `startAiServer()` 在启动失败/超时路径是 **`throw`**（`ai-manager.ts:352-356`），由全局错误处理器兜底成 500，因此**客户端必须同时处理 200-`ok:false` 与 500 两种失败形态**。

**其他行为**：端口从 8080 起找空闲（最多 20 个）；用 GPU 加速的判定是 `models/llama-variant-cuda.txt` 存在 → 追加 `-ngl 99`；启动日志写入 `logs/llama-server.log`。

**错误**：`401` / `403` / `500`。

---

### 3.4 `POST /api/ai/stop`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体 | 无 |
| 副作用 | `serverProc.kill()`；把 `progress.phase` 置为 `stopped`；**无条件**写审计 `AI 引擎停止`（target=`llama.cpp`） |

**响应 `200`**（永远成功，`stopAiServer()` 不抛错）：

```json
{ "ok": true }
```

> ⚠️ 只能停止**本进程自己 spawn 的**子进程。若服务重启，外部仍在运行的 llama.cpp 不会被停止（`serverProc` 已丢失），客户端应提示用户手动处理。

**错误**：`401` / `403`。

---

## 4. DeepSeek 余额 / 用量记账

### 4.0 公共响应对象：`DeepseekSummary`

`getSummary()`（`deepseek.ts:550-756`）是 6 个接口的公共返回体。字段来自 `DeepseekKey` / `DeepseekUsageDaily` 表与内存态。

```json
{
  "configured": true,
  "enabled": true,
  "refreshSeconds": 60,
  "updatedAt": "2025-01-31T08:00:00.000Z",
  "refreshing": false,
  "error": "",
  "platform": {
    "configured": false,
    "perKey": true,
    "syncedAt": null,
    "error": "",
    "hasData": false,
    "keys": [],
    "unboundCount": 0
  },
  "stats": {
    "balance": 12.34,
    "granted": 0,
    "toppedUp": 12.34,
    "currency": "CNY",
    "accountCount": 1,
    "today": 0.12,
    "month": 3.45,
    "ledgerToday": 0.12,
    "ledgerMonth": 3.45,
    "monthRefill": 10,
    "source": "ledger",
    "tokensToday": 0,
    "tokensMonth": 0,
    "cacheHitRate": null,
    "requestsMonth": 0,
    "keyCount": 1
  },
  "accounts": [
    { "name": "主账号", "keyIds": ["clxkey1"], "currency": "CNY",
      "balance": 12.34, "granted": 0, "toppedUp": 12.34,
      "today": 0.12, "month": 3.45, "isAvailable": true }
  ],
  "mergeHints": [],
  "keys": [
    {
      "id": "clxkey1",
      "name": "主 KEY",
      "accountName": "主账号",
      "masked": "sk-ab****cdef",
      "enabled": true,
      "platformKeyId": "",
      "platformKeyName": "",
      "currency": "CNY",
      "balance": 12.34,
      "grantedBalance": 0,
      "toppedUpBalance": 12.34,
      "isAvailable": true,
      "isAccountOwner": true,
      "accountNameResolved": "主账号",
      "lastObservedAt": "2025-01-31T08:00:00.000Z",
      "lastError": null,
      "todayAmount": 0.12,
      "monthAmount": 3.45,
      "platform": null
    }
  ],
  "chart": [
    { "day": "2025-01-02", "amount": 0, "tokens": 0, "source": "ledger" },
    { "day": "2025-01-31", "amount": 0.12, "tokens": 0, "source": "ledger" }
  ],
  "balance": { "ok": true, "message": "" }
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `configured` | boolean | 是否至少存在一个 DeepSeek KEY（`keys.length > 0`，含停用的） |
| `enabled` | boolean | 记账开关（`SystemSetting.deepseekEnabled`，**DB 缺省即 `true`**） |
| `refreshSeconds` | number | 后台刷新间隔秒。缺省 `60`，**最小 30**（`MIN_REFRESH_SECONDS`），非法值回落默认 |
| `updatedAt` | string \| null | 上一次 `refreshAll` 完成时间（ISO）；从未成功执行过则 `null` |
| `refreshing` | boolean | 是否有刷新正在进行（并发时其它刷新请求会被跳过） |
| `error` | string | 最近一次余额观测失败的汇总，多个 KEY 用 `；` 连接；无错时 `""` |
| `platform.configured` | boolean | 是否配置了平台会话令牌 |
| `platform.perKey` | boolean | 平台数据是否按 KEY 维度返回（否则走按月兜底接口） |
| `platform.syncedAt` | string \| null | 平台用量最近同步时间；进程重启后为 `null`（内存态） |
| `platform.error` | string | 平台同步错误（如「平台令牌已失效，请重新获取」） |
| `platform.hasData` | boolean | 是否有平台用量行 |
| `platform.keys` | array | 平台侧发现的 KEY 列表（见下） |
| `platform.unboundCount` | number | 平台侧存在、但本地未绑定（`platformKeyId` 为空）的 KEY 数量 |
| `stats.balance` | number | 所有账户余额之和（保留 2 位） |
| `stats.granted` / `stats.toppedUp` | number | 赠送 / 充值余额之和（保留 2 位） |
| `stats.currency` | string | 首个账户币种，兜底 `"CNY"` |
| `stats.accountCount` | number | 账户数（按 `accountName` 分组；空 `accountName` 时账户 ID 为 `key:<id>`） |
| `stats.today` / `stats.month` | number | **有平台数据时取平台值，否则取记账值** |
| `stats.ledgerToday` / `stats.ledgerMonth` | number | 始终为余额差值记账值 |
| `stats.monthRefill` | number | 本月充值（余额上升的差值累计） |
| `stats.source` | `"platform"` \| `"ledger"` | `today`/`month` 的数据来源 |
| `stats.tokensToday` / `stats.tokensMonth` | number | **仅平台数据**（记账模式恒为 0） |
| `stats.cacheHitRate` | number \| null | `hit/(hit+miss)`，保留 4 位；无数据时 `null` |
| `stats.requestsMonth` | number | 本月请求数（仅平台数据） |
| `stats.keyCount` | number | KEY 总数（含停用） |
| `accounts[]` | array | 见下 |
| `mergeHints[]` | array | 疑似同一账户的合并建议 |
| `keys[]` | array | 见下 |
| `chart[]` | array | **固定 30 项**，按日期升序（最早 → 最新，即今天在最后） |
| `balance.ok` | boolean | 是否有任一 KEY 有观测时间且无错误 |
| `balance.message` | string | 等于顶层 `error` |

`platform.keys[]` 元素：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 平台 tracking_id（无 KEY 维度时为 `""`） |
| `name` | string | 平台 KEY 名；缺失时回落 `id`，再回落 `"全部 KEY"` |
| `today` / `month` | number | 今日 / 本月金额（4 位小数） |
| `tokensToday` / `tokensMonth` | number | Token 数（hit + miss + out） |
| `requestsMonth` | number | 本月请求数 |
| `cacheHitRate` | number \| null | 缓存命中率 0..1（4 位），无数据 `null` |
| `models` | object | `{ "<模型名>": { "tokens": number, "cost": number } }` |
| `boundKeyId` | string | 已绑定的本地 `DeepseekKey.id`，未绑定为 `""` |

`accounts[]` 元素：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 账户名（`accountName` 或主 KEY 名） |
| `keyIds` | string[] | 该账户下所有 KEY 的本地 id |
| `currency` | string | 币种 |
| `balance` / `granted` / `toppedUp` | number | 只由「记账主 KEY」（同账户中最早创建者）体现，保留 2 位 |
| `today` / `month` | number | 该主 KEY 的记账用量 |
| `isAvailable` | boolean | `key.isAvailable !== false` |

`mergeHints[]` 元素：`{ keyIds: string[], names: string[], balance: number, currency: string }`
（判定条件：不同账户的主 KEY 在 10 分钟内有**完全相同的 `currency|balance|granted|toppedUp` 指纹**，说明它们很可能是同一个 DeepSeek 账户。）

`keys[]` 元素（`DeepseekKey` 的 DTO）：

| 字段 | 类型 | 对应 Prisma 字段（类型） | 说明 |
|---|---|---|---|
| `id` | string | `id String @id @default(cuid())` | |
| `name` | string | `name String` | |
| `accountName` | string | `accountName String @default("")` | 相同账户名 = 同一账户 |
| `masked` | string | 由 `apiKey` 派生 | `maskKey()`：长度 >8 时 `前5****后4`，否则 `前3****`；**明文 key 绝不返回** |
| `enabled` | boolean | `enabled Boolean @default(true)` | |
| `platformKeyId` | string | `platformKeyId String @default("")` | 绑定的平台 KEY |
| `platformKeyName` | string | 由平台数据派生 | 未绑定时 `""` |
| `currency` | string \| null | `currency String?` | |
| `balance` | number \| null | `balance Float?` | 总余额 |
| `grantedBalance` | number \| null | `grantedBalance Float?` | 赠送余额 |
| `toppedUpBalance` | number \| null | `toppedUpBalance Float?` | 充值余额 |
| `isAvailable` | boolean \| null | `isAvailable Boolean?` | 官方 `/user/balance` 的 `is_available` |
| `isAccountOwner` | boolean | 派生 | 是否是本账户的记账主 KEY |
| `accountNameResolved` | string | 派生 | `accountName.trim() \|\| name` |
| `lastObservedAt` | string \| null | `lastObservedAt DateTime?` | 最后观测时间（ISO） |
| `lastError` | string \| null | `lastError String?` | 最后错误（如「API KEY 无效或已被删除（HTTP 401）」、「请求超时」） |
| `todayAmount` | number \| null | 派生 | **非记账主 KEY 时为 `null`** |
| `monthAmount` | number \| null | 派生 | 同上 |
| `platform` | object \| null | 派生 | 与 `platform.keys[]` 元素同构（去掉 `id`/`name`/`boundKeyId`，含 `today`/`month`/`tokensToday`/`tokensMonth`/`requestsMonth`/`cacheHitRate`/`models`）；未绑定平台 KEY 时为 `null` |

`chart[]` 元素：`{ day: "YYYY-MM-DD", amount: number, tokens: number, source: "platform" | "ledger" }`
- 当天有平台数据 → 用平台 `amount`/`tokens` 且 `source: "platform"`；否则用记账 `amount`、`tokens: 0`、`source: "ledger"`。
- 数组恒为 30 项（含无数据日为 0），**客户端可直接画图，无需补零**。

**其他内部行为（客户端需知道但不返回）**：
- 后台自动轮询：`start()` 后 4 s 首次，之后按 `refreshSeconds`（≥30 s）循环；仅当 `enabled` 且有启用的 KEY 或平台令牌时才真正刷新。
- 余额差值记账：两次观测余额下降记为「已用」，上升记为「充值」；币种变化或断线超过 **48 小时**时只重置基准不记账；差额归属「上一次观测」所在日。
- 平台令牌失效（HTTP 401/403 或业务码 40002/40003）→ `platform.error` 提示重新获取。

---

### 4.1 `GET /api/deepseek/summary`

| 项 | 值 |
|---|---|
| 权限 | **需登录**（**不要求 ADMIN**） |
| 请求参数 | 无 |
| 响应 | `DeepseekSummary`（见 4.0） |
| 错误 | `401` |

---

### 4.2 `POST /api/deepseek/refresh`

| 项 | 值 |
|---|---|
| 权限 | **需登录**（不要求 ADMIN） |
| 请求体 | 无（前端发 `{}`） |
| 副作用 | **同步**触发一次全量刷新：逐个启用 KEY 调官方 `/user/balance` 观测余额并记账；若配置了平台令牌则同步平台用量 |

**响应 `200`** = `DeepseekSummary` + 额外 `refresh` 字段：

```json
{
  "configured": true,
  "enabled": true,
  "refreshSeconds": 60,
  "updatedAt": "2025-01-31T08:20:00.000Z",
  "refreshing": false,
  "error": "",
  "platform": { "configured": false, "perKey": true, "syncedAt": null, "error": "", "hasData": false, "keys": [], "unboundCount": 0 },
  "stats": { "…": "同 4.0" },
  "accounts": [],
  "mergeHints": [],
  "keys": [],
  "chart": [],
  "balance": { "ok": false, "message": "" },
  "refresh": { "ok": true }
}
```

`refresh` 字段的三种取值（`deepseek.ts:495-522`）：

| 取值 | 含义 |
|---|---|
| `{"ok":true}` | 刷新执行完成（**注意：单个 KEY 失败不算失败**，失败信息在 `error` 字段里） |
| `{"ok":false,"error":"正在刷新中","skipped":true}` | 已有刷新在跑，本次被跳过 |
| `{"ok":false,"error":"<异常信息>"}` | 刷新过程抛异常 |

**错误**：`401`。

---

### 4.3 `POST /api/deepseek/keys`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 副作用 | 先用传入 key 调一次官方余额接口探测（`probe`）→ 创建 `DeepseekKey` → `refreshAll('add-key')` → 写审计 `添加 DeepSeek 密钥`（target 含掩码 key 与余额） |

**请求体 zod（完整抄录）**：

```ts
z.object({
  name: z.string().trim().min(1).max(40),
  apiKey: z.string().trim().min(8).max(200),
  accountName: z.string().trim().max(40).default(''),
  enabled: z.boolean().default(true),
})
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | **是** | — | 1..40 字符（trim 后）。与平台 KEY 名称一致时会被自动绑定（`autoBindPlatformKeys`） |
| `apiKey` | string | **是** | — | 8..200 字符，形如 `sk-...` |
| `accountName` | string | 否 | `""` | ≤40 字符。**填相同值 = 视为同一 DeepSeek 账户**（共用一份余额，只记一次账）；留空则以 `key:<id>` 作为独立账户 |
| `enabled` | boolean | 否 | `true` | 是否参与记账与刷新 |

**响应 `200`**（`index.ts:960-969`）：

```json
{
  "key": { "id": "clxkey9", "name": "备用 KEY" },
  "probe": {
    "ok": true,
    "isAvailable": true,
    "currency": "CNY",
    "total": 20.5,
    "granted": 5,
    "toppedUp": 15.5
  },
  "summary": { "…": "DeepseekSummary（见 4.0）" }
}
```

**`probe`（`DeepseekBalance`）字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | boolean | 官方 `/user/balance` 是否成功 |
| `error` | string? | 失败原因：`"API KEY 无效或已被删除（HTTP 401）"`、`"HTTP <code>"`、`"接口未返回余额信息"`、`"请求超时"`、网络错误消息 |
| `unauthorized` | boolean? | 仅失败时出现，`status === 401 \|\| 403` |
| `isAvailable` | boolean? | `payload.is_available !== false`（成功时出现） |
| `currency` | string? | 选中的币种，缺省 `"CNY"`（优先 `CNY` 且余额 > 0 的那条） |
| `total` | number? | `total_balance` |
| `granted` | number? | `granted_balance` |
| `toppedUp` | number? | `topped_up_balance` |

> ⚠️ **即便 `probe.ok === false`，KEY 仍会被创建**（源码先 probe 再无条件 create），响应依旧是 `200`。客户端应提示「已保存，但密钥校验失败：<error>」。
> `key` 字段**只含 `id` 与 `name`**，不含余额等；完整信息在 `summary.keys[]` 里。

**错误**：`400`（zod）；`401`；`403`。
**超时**：服务端对官方接口的请求超时为 **15 秒**（`TIMEOUT_MS = 15_000`），客户端读超时应 ≥ 30 s。

---

### 4.4 `PATCH /api/deepseek/keys/:id`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 路径参数 | `id`（`DeepseekKey.id`） |
| 副作用 | 更新字段；若传入 `apiKey` 则**同时把余额相关字段清空**（`balance`/`grantedBalance`/`toppedUpBalance`/`currency`/`lastError`/`lastObservedAt` 置 `null`）→ `refreshAll('update-key')` → 审计 `修改 DeepSeek 密钥` |

**请求体 zod（完整抄录，全部可选）**：

```ts
z.object({
  name: z.string().trim().min(1).max(40).optional(),
  apiKey: z.string().trim().min(8).max(200).optional(),
  accountName: z.string().trim().max(40).optional(),
  platformKeyId: z.string().trim().max(120).optional(),
  enabled: z.boolean().optional(),
})
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 否 | 1..40 |
| `apiKey` | string | 否 | 8..200；传入即视为更换密钥，余额会被重置 |
| `accountName` | string | 否 | ≤40；**改成相同名字即可把多个 KEY 归为同一账户**（不改写历史记账流水，仅影响后续归属） |
| `platformKeyId` | string | 否 | ≤120，手动绑定平台 KEY（对应 `platform.keys[].id`） |
| `enabled` | boolean | 否 | 停用后该 KEY 不再被刷新/记账（但仍在 `summary.keys[]` 与 `accounts[]` 中展示上一次余额） |

**响应 `200`**：直接返回 `DeepseekSummary`（**不是**包在 `summary` 里）。

**错误**：`404 {"message":"KEY 不存在"}`；`400`（zod）；`401`；`403`。

---

### 4.5 `DELETE /api/deepseek/keys/:id`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 路径参数 | `id` |
| 副作用 | 删除 `DeepseekKey` 行 + **级联删除该 KEY 的全部 `DeepseekUsageDaily` 记账行**（`source='ledger'` 且 `keyId=id`）；审计 `删除 DeepSeek 密钥`（含掩码） |

**响应 `200`**：`DeepseekSummary`（不额外包一层）。

**错误**：`404 {"message":"KEY 不存在"}`；`401`；`403`。

> ⚠️ 删除不可恢复，且会丢失该 KEY 的历史记账数据（平台侧数据源为独立行，不受影响）。

---

### 4.6 `POST /api/deepseek/merge`

将多个「独立账户」的 KEY 合并成同一账户（共用一份余额、只记一次账），并迁移已有记账流水。

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 副作用 | ① 把 `keyIds` 中所有 `source='ledger'` 的记账行按天汇总后删除，重建到**最早创建的那个 KEY**（primary）名下；② 把这些 KEY 的 `accountName` 统一改为 `label`；③ `refreshAll('merge')`；④ 审计 `合并 DeepSeek 账户` |

**请求体 zod（完整抄录）**：

```ts
z.object({
  keyIds: z.array(z.string().min(1)).min(2).max(20),
  name: z.string().trim().max(40).optional(),
})
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `keyIds` | string[] | **是** | 2..20 个本地 `DeepseekKey.id` |
| `name` | string | 否 | ≤40，作为合并后的账户名。**留空时账户名回落到 `keys[0].accountName` 或 `keys[0].name`** |

**额外校验**：按 `keyIds` 查库，实际命中数 `< 2` → `400 {"message":"至少需要两个 KEY"}`。

**响应 `200`**：`DeepseekSummary`。

**错误**：`400`（zod 或 KEY 不足）；`401`；`403`。

> `summary.mergeHints` 就是服务端给出的「建议合并」候选，客户端可直接把 `keyIds` 回传本接口。

---

### 4.7 `PUT /api/deepseek/config`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 副作用 | 写入 `SystemSetting`（`deepseekEnabled` / `deepseekRefreshSeconds` / `deepseekPlatformToken`）→ **重启后台轮询计时器（3 s 后重新调度）**；若本次设置了平台令牌 → `refreshAll('config')`；审计 `修改 DeepSeek 记账设置` |

**请求体 zod（完整抄录）**：

```ts
z.object({
  enabled: z.boolean().optional(),
  refreshSeconds: z.number().int().min(30).max(3600).optional(),
  platformToken: z.string().max(4000).optional(),
})
```

| 字段 | 类型 | 必填 | 取值范围 | 说明 |
|---|---|---|---|---|
| `enabled` | boolean | 否 | — | 记账总开关 |
| `refreshSeconds` | number(int) | 否 | **30..3600** | 后台刷新间隔秒 |
| `platformToken` | string | 否 | ≤4000 | 平台网页会话令牌。**自动剥离 `Bearer ` 前缀**后保存；**传空字符串 = 清除令牌**，并**连带删除 DB 中所有 `source='platform'` 的用量行**与内存态的 `syncedAt` |

> ⚠️ **注意是 `PUT`**（不是 PATCH），但语义是部分更新（`undefined` 的字段不动）。
> ⚠️ `refreshSeconds` 的 30 下限在此接口被严格遵守；若改用 `PATCH /api/settings` 写 `deepseekRefreshSeconds` 则**没有该校验**（但读取端 `readConfig()` 会把 <30 的值回落为默认 60）。

**响应 `200`**：`DeepseekSummary`。

**错误**：`400`（zod，如 `refreshSeconds: 10`）；`401`；`403`。

---

## 5. B站扫码登录

用于让服务端获得 `SESSDATA`（B站高清 1080P+ 需要登录态）。成功后 SESSDATA 写入 `SystemSetting.biliSessdata`，**仅存本机、不回显**。

### 5.1 `POST /api/bili/qr/create`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 请求体 | 无 |
| 副作用 | 服务端请求 B站 passport 生成二维码；**无论成败都写审计 `B站扫码登录`**（成功 target=`二维码已生成，等待扫码`；失败 target=`生成二维码失败：<原因>`） |
| 超时 | 服务端内部 15 s（generate）+ 15 s（warmup 首页） |

**响应 `200`**：

成功：

```json
{
  "ok": true,
  "qrcodeKey": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
  "image": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg..."
}
```

失败（HTTP 仍是 `200`）：

```json
{ "ok": false, "error": "二维码接口返回 code=-412" }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | boolean | 是否生成成功 |
| `error` | string? | 仅失败时出现；可为 B站 `message` 或 `二维码接口返回 code=<code>` 或异常消息（如 `请求超时`） |
| `qrcodeKey` | string? | 仅成功时出现；轮询凭证 |
| `image` | string? | 仅成功时出现；**PNG data URL**（`data:image/png;base64,...`），由 `qrcode` 库生成：`margin: 1, width: 260, color: { dark: '#10162a', light: '#ffffff' }` |

**客户端处理**：Android 端把 data URL 的 base64 部分解码后用 `BitmapFactory.decodeByteArray` 显示即可；不要当普通 URL 交给图片库（部分库不支持 data URL）。
**注意**：服务端模块级 `sessionCookie` 是**全进程共享**的（`bili-login.ts:11`），并发扫码会互相影响；建议客户端串行化。

**错误**：`401`；`403`。

---

### 5.2 `POST /api/bili/qr/poll`

| 项 | 值 |
|---|---|
| 权限 | **需登录 + ADMIN** |
| 副作用 | **仅当登录成功（`status='ok'` 且拿到 `sessdata`）时**：`upsert SystemSetting.biliSessdata` + 写审计 `B站扫码登录`（target=`登录成功（<nickname>）`）。其他状态无写操作 |

**请求体 zod（完整抄录）**：

```ts
z.object({ qrcodeKey: z.string().trim().min(1).max(100) })
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `qrcodeKey` | string | **是** | 1..100 字符，来自 `/api/bili/qr/create` |

**响应 `200`**（`index.ts:1043-1055`：**`sessdata` 字段被服务端删除后返回**）：

```json
{ "ok": true, "status": "waiting" }
```

`status` 枚举与语义（`bili-login.ts:85-121`，B站内层 `data.code` 映射）：

| `status` | `ok` | 对应 B站 code | 含义 | 客户端动作 |
|---|---|---|---|---|
| `waiting` | `true` | `86101` | 未扫码 | 继续轮询 |
| `scanned` | `true` | `86090` | 已扫码，等待手机确认 | 继续轮询，提示「已扫码，请在手机上确认」 |
| `expired` | `true` | `86038` | 二维码已过期 | **停止轮询**，提示重新生成；`error` 字段为 `"二维码已过期，请刷新"` |
| `ok` | `true` | `0` | 登录成功，SESSDATA 已保存到服务端 | 停止轮询，刷新设置页（`biliSessdataConfigured` 将变为 `true`） |
| `error` | `false` | 其它 | 轮询失败 | 停止轮询；`error` 含原因 |

其它响应字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `error` | string? | 出现场景：`expired` → `"二维码已过期，请刷新"`；登录成功但未从 `Set-Cookie` 捕获到 SESSDATA → `"登录成功但未捕获到 SESSDATA，请稍后重试或手动粘贴 Cookie"`（此时 `ok:false, status:'error'`）；其它失败 → `json.message` 或 `登录轮询返回 code=<code>` / 异常消息 |
| `nickname` | string? | **仅 `status='ok'` 时可能出现**（尽力调 `/x/web-interface/nav` 取 `data.uname`，失败则无此字段） |

**推荐轮询策略**（源码参考实现 `client/src/components/SettingsPanel.vue:95-123`）：
- 间隔：**2000 ms**
- 总时长上限：**175 000 ms**（本地 deadline，超时后提示「二维码已过期，请点击刷新」）
- 终止条件：`status ∈ {ok, expired, error}`
- 网络异常：静默忽略，等下一次轮询

**错误**：`400`（zod）；`401`；`403`。

---

## 6. 视频解析引擎（PureParse）

> ### ❗ 关于 SSE 的重要说明
> **源码中不存在任何 SSE 接口。** 对 `server/` 全目录检索 `text/event-stream`、`EventSource`、`reply.raw.write`、`addHook` **均无匹配**。
> `server/src/parse-server.ts` 只注册了 **3 个路由**：`POST /api/parse`、`GET /api/stream`、`GET /api/ping`，加上 `index.ts` 里的 `POST/GET/DELETE /api/parse/import`。
>
> - `GET /api/stream` 是**媒体字节代理流**（chunked 传输的 mp4/webm/图片），不是事件流，**没有事件名、没有 SSE 帧格式**。
> - 长耗时任务（服务端导入）的进度通过 **轮询 `GET /api/parse/import/:id`** 获取。
>
> 本文档不臆造 SSE 契约。若需「事件流」，Android 端只能用轮询实现。

同时注意：**`/api/parse`、`/api/stream`、`/api/ping` 三个路由没有任何鉴权**（`registerParseApi` 内部未调用 `requireUser`/`requireAdmin`，且全局无 `onRequest`/`preHandler` 钩子）。`/api/parse/import*` 需要登录（不要求 ADMIN）。

---

### 6.1 `POST /api/parse`

| 项 | 值 |
|---|---|
| 权限 | **无需登录**（公开） |
| Content-Type | `application/json` |
| 请求体 | **不加 zod 校验**，源码直接 `const input: any = request.body ?? {}`，只读 `input.url` 与 `input.share` |
| 响应 Content-Type | `application/json; charset=utf-8` |
| 超时 | 服务端内部：B站/快手单请求 10~12 s，抖音最多 3 次重试（每次间隔 800~1700 ms 抖动）；整体可能耗时十几秒。**客户端读超时建议 ≥ 60 s**（前端用 `AbortSignal.timeout(60_000)`） |

**请求体字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | 二选一 | 分享链接或完整口令文本中的链接 |
| `share` | string | 二选一 | `url` 缺失时的兜底字段 |
| 其他字段 | — | — | **一律被忽略**。⚠️ 前端实际发送的 `share_text` **不被服务端读取**，Android 端不要把链接只放这里 |

**支持的平台识别**（`detectPlatform`，按 hostname 正则）：

| platform id | 匹配域名 | 是否内置真实解析 |
|---|---|---|
| `bilibili` | `bilibili.com` / `b23.tv` / `bilibili.tv` | ✅ 官方开放 API 全链真实解析 |
| `douyin` | `douyin.com` / `iesdouyin.com` | ✅ 网页版官方 API + a_bogus 签名 |
| `kuaishou` | `kuaishou.com` / `chenzhongtech.com` / `gifshow.com` | ✅ H5 分享页详情接口 |
| `xiaohongshu` | `xiaohongshu.com` / `xhslink.com` | ❌ 返回失败提示 |
| `weibo` | `weibo.com` / `weibo.cn` | ❌ |
| `xigua` | `ixigua.com` | ❌ |
| 其他 | — | ❌ `未识别链接平台（支持：B站/抖音/快手/小红书/微博/西瓜）` |

**成功响应 `200`**（外层恒定包一层 `data`）：

```json
{ "ok": true, "data": { "…": "见下" } }
```

**失败响应 `400`**（**注意不是 5xx，也不是 `{message}` 形状**）：

```json
{ "ok": false, "msg": "抖音暂未放行（被风控拦截）——已内置开源 a_bogus 签名并自动重试 3 次，多为平台风控限流，10~30 秒后重试通常可恢复；也可用演示预览体验完整流程" }
```

失败 `msg` 的全部可能取值（源码 `fail()` 调用点）：

| 场景 | msg |
|---|---|
| 平台未识别 | `未识别链接平台（支持：B站/抖音/快手/小红书/微博/西瓜）` |
| 未内置平台 | `<平台中文名> 真实解析接口未内置（登录态/签名要求较高）。可接入自有解析服务或使用演示预览` |
| 解析异常 | `<平台中文名> 解析异常：<错误消息>` |
| 抖音签名器不可用 | `抖音签名器加载失败：<原因>` |
| 抖音 ID 提取失败 | `未能识别抖音视频 ID（短链展开失败）` |
| 抖音风控（3 次重试后） | `抖音暂未放行（<原因>）——…` |
| 抖音无视频流 | `该抖音作品不含视频流（可能是图文/音乐作品），无法提取视频` |
| 抖音直链探测失败 | `抖音视频流验证失败（直链不可达，多为临时限流），请稍后重试［<失败摘要>］` |
| B站接口异常 | `B站接口响应异常` |
| B站风控 | `B站风控拦截（请求过频），稍后再试` |
| B站视频不可解析 | `B站视频不存在或不可解析（番剧/需登录内容）`（或 B站返回的 `message`） |
| B站无可用清晰度 | `未获取到可播放的清晰度（该视频可能需登录或为互动视频）` |
| 快手展开失败 | `快手链接展开失败：未能识别作品 ID（已解析地址：<截断地址>）<可选提示>` |
| 快手接口异常 | `快手详情接口返回 HTTP <code>` / `快手未返回作品：<error_msg>` / `快手未返回作品数据（作品不存在、已删除或账号风控）` |
| 快手无视频流 | `该快手作品为图文/其他类型，不含可提取的视频流` |
| 快手直链探测失败 | `快手视频流验证失败（直链不可达，多为临时限流），请稍后重试` |
| 快手请求异常 | `快手解析请求失败：<消息>` |

#### 6.1.1 `data` 通用字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `platform` | `"bilibili"` \| `"douyin"` \| `"kuaishou"` | 平台 |
| `kind` | `"video"` \| `"images"` | **仅抖音会返回**：图文作品为 `"images"`，视频为 `"video"`。B站/快手**不返回该字段** |
| `title` | string | 标题；缺失时依次为 `未命名视频` / `抖音视频` / `抖音图文` / `快手视频` |
| `author` | object | `{ name: string, handle: string, verified: boolean, tag: string }`。`tag` 取值：`"B站 UP 主"` / `"抖音创作者"` / `"快手"` |
| `stats` | object | `{ like: number, comment: number, share: number, view: number }`，缺失为 0 |
| `cover` | string | **已被改写成同源代理地址** `/api/stream?url=...&ref=...&disposition=inline`（仅当原始 cover 是 `http(s)://` 开头） |
| `coverSrc` | string? | 原始上游封面直链（仅当发生改写时存在） |
| `referer` | string? | 上游防盗链 Referer。**B站不返回该字段**（Referer 在 `media[0].referer`）；抖音为 `https://www.douyin.com/`；快手无顶层字段 |
| `qualityLabel` | string | 清晰度展示文案。B站：`8K`/`杜比`/`HDR`/`4K`/`1080P60`/`1080P+`/`1080P`/`720P60`/`720P`/`480P`/`360P`/`240P`/`<n>P`；抖音：`4K`/`2K`/`1080P`/`720P`/`480P`/`<n>P`/`高清`（单轨流会追加 `（单轨流）`）；快手：`rep.qualityLabel` 或 `<height>P` 或 `原画`；抖音图文：`图文作品 · <n> 张图片` |
| `duration` | number | **秒**（整数）。B站/快手/抖音视频均四舍五入；图文为 `0` |
| `watermarkFree` | boolean | 恒为 `true`（B站/抖音/快手三条链路都返回无水印直链） |
| `pageUrl` | string? | **仅抖音**：`https://www.douyin.com/video/<id>` 或 `https://www.douyin.com/note/<id>` |
| `images` | array? | **仅抖音图文作品**：见 6.1.4 |
| `media` | array | 媒体数组；**图文作品为空数组 `[]`**；视频作品恒为**1 个元素** |
| `high` | object? | **仅 B站且「已登录 SESSDATA + 本机有 ffmpeg + 平台放到 1080P+」时存在**（见 6.1.2） |

#### 6.1.2 `media[]` 元素（`media[0]` 是唯一会被改写的一项）

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | **已被改写为同源代理流地址**：`/api/stream?url=<encodeURIComponent(上游直链)>[&ref=<encodeURIComponent(referer)>]`。播放/下载都走这个（防防盗链 + 免 CORS）。**路径不带 `disposition` 参数** |
| `src` | string | **原始上游直链**（服务端导入入库时用它，客户端一般不用） |
| `width` / `height` | number | 像素尺寸。⚠️ **B站恒为 0/0**（官方接口不返回）；抖音取实际选中档位；快手取 rep 或作品尺寸 |
| `duration` | number | 秒（整数） |
| `size` | number | 字节数；B站取 `durl.size`，抖音取档位/探测值，快手取 rep 或探测值；可能为 `0` |
| `type` | `"mp4"` \| `"webm"` | 快手按 URL 是否含 `.webm` 判断，其余为 `mp4` |
| `referer` | string | 该流的防盗链 Referer（B站 `https://www.bilibili.com/`，抖音 `https://www.douyin.com/`，快手 `https://www.kuaishou.com/`） |

**`high` 对象（B站高清 DASH 候选）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `videoUrl` | string | DASH 视频流上游直链（**未代理**） |
| `audioUrl` | string | DASH 音频流上游直链（**未代理**） |
| `quality` | number | 档位 id（如 80=1080P、116=1080P60、120=4K） |
| `label` | string | 档位名 |

> `high` 只用于 `/api/parse/import` 的 `high` 参数；客户端拿到后应**原样透传**给导入接口，不要自己下载。

#### 6.1.3 B站 `data` 示例

```json
{
  "ok": true,
  "data": {
    "platform": "bilibili",
    "title": "示例视频",
    "author": { "name": "某UP主", "handle": "", "verified": false, "tag": "B站 UP 主" },
    "stats": { "like": 100, "comment": 5, "share": 2, "view": 2000 },
    "cover": "/api/stream?url=https%3A%2F%2Fi0.hdslb.com%2Fbfs%2Farchive%2Fxxx.jpg&ref=https%3A%2F%2Fwww.bilibili.com%2F&disposition=inline",
    "coverSrc": "https://i0.hdslb.com/bfs/archive/xxx.jpg",
    "qualityLabel": "720P",
    "duration": 125,
    "watermarkFree": true,
    "high": {
      "videoUrl": "https://upos-sz-mirrorcos.bilivideo.com/...m4s",
      "audioUrl": "https://upos-sz-mirrorcos.bilivideo.com/...m4s",
      "quality": 80,
      "label": "1080P"
    },
    "media": [
      {
        "url": "/api/stream?url=https%3A%2F%2Fupos-sz-mirrorcos.bilivideo.com%2F...mp4&ref=https%3A%2F%2Fwww.bilibili.com%2F",
        "src": "https://upos-sz-mirrorcos.bilivideo.com/...mp4",
        "width": 0, "height": 0,
        "duration": 125,
        "size": 12345678,
        "type": "mp4",
        "referer": "https://www.bilibili.com/"
      }
    ]
  }
}
```

> B站清晰度实测规律（源码注释）：未登录游客封顶 **720P**；1080P 及以上只有 DASH 分离流，需要登录 Cookie + ffmpeg 才能保存。

#### 6.1.4 抖音 `data` 示例

视频：

```json
{
  "ok": true,
  "data": {
    "platform": "douyin",
    "kind": "video",
    "pageUrl": "https://www.douyin.com/video/7681199202382269706",
    "title": "示例标题",
    "author": { "name": "作者昵称", "handle": "unique_id", "verified": true, "tag": "抖音创作者" },
    "stats": { "like": 1000, "comment": 20, "share": 10, "view": 50000 },
    "cover": "/api/stream?url=...&ref=https%3A%2F%2Fwww.douyin.com%2F&disposition=inline",
    "coverSrc": "https://p3-sign.douyinpic.com/...",
    "referer": "https://www.douyin.com/",
    "qualityLabel": "1080P",
    "duration": 15,
    "watermarkFree": true,
    "media": [
      { "url": "/api/stream?url=...&ref=https%3A%2F%2Fwww.douyin.com%2F",
        "src": "https://v3-dy-o.zjcdn.com/...",
        "width": 1080, "height": 1920, "duration": 15, "size": 3456789,
        "type": "mp4", "referer": "https://www.douyin.com/" }
    ]
  }
}
```

图文作品（`kind === "images"`，`media` 为空）：

```json
{
  "ok": true,
  "data": {
    "platform": "douyin",
    "kind": "images",
    "pageUrl": "https://www.douyin.com/note/7681199202382269706",
    "title": "示例图文",
    "author": { "name": "作者昵称", "handle": "", "verified": false, "tag": "抖音创作者" },
    "stats": { "like": 1, "comment": 0, "share": 0, "view": 10 },
    "cover": "/api/stream?url=...&ref=https%3A%2F%2Fwww.douyin.com%2F&disposition=inline",
    "coverSrc": "https://p3-sign.douyinpic.com/...",
    "referer": "https://www.douyin.com/",
    "qualityLabel": "图文作品 · 9 张图片",
    "duration": 0,
    "watermarkFree": true,
    "images": [
      { "url": "/api/stream?url=<原始图片直链>&ref=https%3A%2F%2Fwww.douyin.com%2F&disposition=inline",
        "src": "https://p3-sign.douyinpic.com/...jpeg",
        "width": 1440, "height": 1920 }
    ],
    "media": []
  }
}
```

`images[]` 元素：

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | 已改写的同源代理地址（**带 `disposition=inline`**） |
| `src` | string | 原始上游图片直链（优先取非 webp 原图，无水印） |
| `width` / `height` | number | 像素尺寸（上游未给时为 0） |

> ⚠️ **只有 `media[0]` 会被改写**（源码 `out.data.media?.[0]`），B站/抖音/快手当前都只有 1 个 media 元素，因此实际无影响；但客户端不应假设 `media.length > 1` 时的代理改写行为。

---

### 6.2 `GET /api/stream`

**媒体代理流**：服务端带防盗链 `UA`+`Referer` 直连上游，透传 `Range`，用于播放与下载。

| 项 | 值 |
|---|---|
| 权限 | **无需登录** |
| 响应类型 | 上游 `Content-Type`（如 `video/mp4`、`image/jpeg`）；**不是** SSE |
| 支持方法 | `GET` 与 `HEAD`（Fastify 默认 `exposeHeadRoutes`；handler 内显式判断 `HEAD` 只发响应头） |

**Query 参数**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | **是** | 上游媒体直链，**必须匹配 `^https?://`**（大小写不敏感）；需 URL 编码 |
| `ref` | string | 否 | 防盗链 Referer。按域名自动选 UA：含 `bilibili/bilivideo/hdslb/ixigua` → 桌面 Chrome UA；含 `douyin/douyinvod/douyinpic/snssdk/amemv/byteimg/bytecdn/toutiao/pstatp` 或 `ref` 含 `douyin.com` → 抖音签名 UA |
| `disposition` | string | 否 | **值严格等于 `inline`** 时设置响应头 `Content-Disposition: inline`（用于 `<img>` 内联展示；其他值被忽略） |

**请求头**：`Range: bytes=start-end`（原样透传给上游，支持拖动播放断点续传）。

**成功响应头**：

| 头 | 值 |
|---|---|
| `Content-Type` | 上游的 contentType，兜底 `application/octet-stream` |
| `Accept-Ranges` | `bytes` |
| `Cache-Control` | `public, max-age=600` |
| `Content-Range` | 仅当请求带 `Range` 且上游返回时透传 |
| `Content-Length` | 上游的 content-length。⚠️ **若上游响应带 `Content-Encoding`（undici 已解压），服务端会刻意不转发 `Content-Length`**，避免客户端误判截断 |
| `Content-Disposition` | 仅 `disposition=inline` 时 |
| HTTP 状态 | 透传上游状态（通常 `200`，带 Range 时为 `206`） |

**错误响应**：

| 情形 | 状态码 | 响应体 / 说明 |
|---|---|---|
| `url` 不合法（不匹配 `^https?://`） | `400` | `text/plain` 正文 `bad url` |
| 连接上游失败/超时（45 s 连接超时） | `502` | `text/plain` 正文 `代理拉流失败：<异常消息>` |
| 上游返回非 2xx 且非 206 | 上游状态码 | **空响应体**（如上游 404 → `404` 无 body） |

**超时与看门狗（长耗时说明）**：

- **连接/首字节超时 45 秒**（放宽是因为同一签名 URL 并发时 CDN 会排队，实测 20 s+）。
- **正文不做硬超时**：一旦开始流式转发就持续传输，适合几十 MB 的慢速 CDN 下载。
- **空闲看门狗 60 秒**：`lastData` 超过 60 s 无新数据即销毁流（每 15 s 检查一次）。
- 客户端断开时立即销毁上游流。

**Android 建议**：用 ExoPlayer/MediaPlayer 直接播 `url`，或把 `url`/`ref` 拼回绝对地址（`baseUrl + url`）；下载时**不要设置整请求超时**（或设 ≥ 10 分钟），只设置连接/读空闲超时（> 60 s）。

---

### 6.3 `GET /api/ping`

| 项 | 值 |
|---|---|
| 权限 | **无需登录** |
| 响应 | `200`，`Content-Type: text/plain`，正文 `pong`（无 JSON） |

用途：解析页据此判断「真实解析模式」是否可用。Android 端可在启动解析页时探活。

---

### 6.4 `POST /api/parse/import`

把解析出来的直链**在服务端后台**下载并入库到图片库（直连片源、流式写盘、sha256 查重、生成缩略图、加标签、写审计）。

| 项 | 值 |
|---|---|
| 权限 | **需登录**（不要求 ADMIN） |
| 副作用 | 创建内存任务 → 后台下载/合并/入库；入库后写审计：视频 → `提取视频`，图文图片 → `提取图片`，封面 → `提取封面`（target 形如 `<名称>（1080P）`） |
| 任务生命周期 | 任务对象存于**进程内存** `Map`；创建后 **30 分钟** 自动清理（`setTimeout(..., 30*60_000)`，`unref`）。**服务重启即全部丢失** |

**请求体 zod（完整抄录）**：

```ts
z.object({
  url: z.string().url(),
  ref: z.string().max(500).optional(),
  name: z.string().trim().min(1).max(200),
  kind: z.enum(['video', 'cover', 'image']),
  platTag: z.string().trim().max(40).optional(),
  high: z.object({
    videoUrl: z.string().url(),
    audioUrl: z.string().url(),
    quality: z.number().int().min(1).max(127).optional(),
    label: z.string().trim().max(20).optional(),
  }).optional(),
})
```

| 字段 | 类型 | 必填 | 取值范围 | 说明 |
|---|---|---|---|---|
| `url` | string | **是** | 合法 URL | **上游原始直链**（即 `media[0].src` 或 `images[i].src`），**不要传 `/api/stream?...` 代理地址** |
| `ref` | string | 否 | ≤500 | 防盗链 Referer（通常取 `media[0].referer` 或 `data.referer`） |
| `name` | string | **是** | 1..200（trim） | 入库展示名（**不带扩展名**，服务端按实际类型补） |
| `kind` | enum | **是** | `video` \| `cover` \| `image` | 决定校验与标签：`video` → 校验 content-type 含 `video`/`octet-stream` 并自动加 `视频` 标签；`cover` / `image` → 校验 `image/*` |
| `platTag` | string | 否 | ≤40 | 平台标签（如 `B站`/`抖音`/`快手`）；`kind === 'cover'` 时不应传 |
| `high` | object | 否 | — | **仅 B站高清**：把 `/api/parse` 返回的 `data.high` 原样透传 |
| `high.videoUrl` | string | 是（在 `high` 内） | 合法 URL | DASH 视频流 |
| `high.audioUrl` | string | 是（在 `high` 内） | 合法 URL | DASH 音频流 |
| `high.quality` | number(int) | 否 | 1..127 | 档位 id |
| `high.label` | string | 否 | ≤20 | 档位名（如 `1080P`） |

**额外运行时校验**：`if (!/^https?:\/\//i.test(body.url)) → 400 {"message":"片源地址无效"}`（zod 的 `.url()` 已基本覆盖）。

**响应 `201`**：

```json
{ "id": "3f2b1c8e-9d4a-4f6b-8c1e-2a7b5d9e0f31" }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 任务 ID（`randomUUID()`，标准 UUID v4），用于查询/取消 |

**错误**：`400`（zod 或片源地址无效）；`401`。

**后台行为（影响客户端预期）**：

1. 若 `kind === 'video'` 且传了 `high`：先尝试「下载 DASH 视频流 + 音频流 → `ffmpeg -c copy` 无损合并」。失败会**自动回落到常规单流 720P 路径**，并在最终 `message` 中附加说明（`未检测到 ffmpeg（可在系统设置一键下载），已按默认清晰度保存` 或 `高清流合并失败（<原因>），已按默认清晰度保存`）。
2. 常规路径：45 s 连接超时；流式写盘；90 s 空闲看门狗（每 15 s 检查）；在 `temp/parse-import/` 下写临时文件。
3. 大小限制：超过 `SystemSetting.uploadLimitMb`（默认 50 MB）→ 任务失败，`message` 为 `文件 <体积> 超过上传上限 <n> MB，可在「系统设置→服务配置」调大后重试`。
4. 查重：按 `sha256` 命中已有记录 → `duplicate: true`，删除临时文件，不入库；若命中的记录**原文件已缺失**（孤儿记录）→ 服务端自动清掉孤儿记录并重新入库。
5. 进度：`progress` 为 `0..1`，仅当上游返回 `content-length` 时才有确定值，否则为 `null`（**且会停留在上一次的非 null 值或 null**）。

**进度获取方式：轮询 `GET /api/parse/import/:id`**（无 SSE、无 WebSocket、无长轮询）。源码参考实现（`client/src/parseSaveStore.ts:142-166`）：**间隔 800 ms**，`status === 'done' | 'error'` 时停止。

---

### 6.5 `GET /api/parse/import/:id`

| 项 | 值 |
|---|---|
| 权限 | **需登录**（且任务必须属于当前用户） |
| 路径参数 | `id`（任务 UUID） |
| 副作用 | 无 |

**响应 `200`**（`index.ts:1406-1413`）：

```json
{
  "id": "3f2b1c8e-9d4a-4f6b-8c1e-2a7b5d9e0f31",
  "status": "working",
  "progress": 0.42,
  "message": "下载片源中 1.20 MB / 2.85 MB",
  "items": [],
  "duplicate": false
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 任务 ID |
| `status` | `"working"` \| `"done"` \| `"error"` | 任务状态 |
| `progress` | number \| null | `0..1`；`null` = 进度不确定。B站高清流程的分段映射：`0.05` → 视频流下载 `0.05..0.7` → 音频流 `0.7..0.88` → `0.9` 合并 → `1` |
| `message` | string | 人类可读状态文案（中文），如 `准备导入…`、`正在下载 1080P 高清视频流…`、`下载片源中 1.20 MB / 2.85 MB`、`正在用 ffmpeg 合并 1080P 高清音视频…`；成功时为 `已保存 N 个文件到图片库 ✓` / `已保存 N 张图片到图片库 ✓` / `已保存 1080P 高清视频到图片库 ✓（<备注>）`；重复时为 `已在图片库中（内容相同，自动去重）`；失败时为错误原因 |
| `items` | array | 成功入库的图片 DTO 数组（见下）。`working`/`error`/`duplicate` 时为空数组 |
| `duplicate` | boolean | 是否因 sha256 重复而未入库 |

> ⚠️ **响应不包含** `error`、`kind`、`highLabel`、`highNote` 字段（任务对象里有，但路由只返回上面 6 个字段）。**失败时请读取 `message`**。

**`items[]`（`imageDto`，`index.ts:301-320`）字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 图片 ID（cuid） |
| `name` | string | 名称（不带扩展名） |
| `mimeType` | string | MIME（如 `video/mp4`、`image/jpeg`） |
| `url` | string | `/api/images/<id>/file`（原文件/播放） |
| `thumb` | string | 有 320 宽变体时为 `/api/images/<id>/variant/320`，否则回落 `/api/images/<id>/file` |
| `width` / `height` | number | 像素尺寸；视频为 `0`（`image.width \|\| 0`） |
| `size` | string | 人类可读体积（如 `"12.3 MB"`），**不是数字** |
| `views` | number | `viewCount` |
| `uploadedAt` | string | ISO-8601 |
| `capturedAt` | string | `YYYY-MM-DD`；无拍摄时间时取上传日期 |
| `tags` | string[] | 标签名数组（含自动的 `视频`/`图片` 平台标签） |
| `tagIds` | string[] | 标签 ID 数组 |
| `r18` | boolean | 是否含 R-18 标签 |
| `deletedAt` | string? | **新建入库时为 `undefined`（JSON 中不出现）** |

**错误**：`404 {"message":"导入任务不存在"}`（任务已被清理 / 客户端在**取消后继续轮询**时也会命中：取消会使 `runParseImportJob` 从 Map 中 `delete`，后续轮询即 404）；`401`。

---

### 6.6 `DELETE /api/parse/import/:id`

取消导入任务 / 清理已完成任务。

| 项 | 值 |
|---|---|
| 权限 | **需登录**（任务必须属于当前用户；不属于则静默不处理） |
| 路径参数 | `id` |
| 副作用 | 若任务存在且属于当前用户：`status === 'working'` → 置 `cancel = true`（下载循环下一次迭代抛「已取消」，清理临时文件并从 Map 删除）；否则直接从 Map 删除 |

**响应 `200`**（**无论任务是否存在都返回成功**）：

```json
{ "ok": true }
```

> ⚠️ 该接口**幂等且不报 404**：任务不存在、不属于自己、已取消过，全部返回 `{ok:true}`。
> 取消是**异步生效**：返回后任务可能仍在收尾（清理临时文件），此时继续 `GET` 会得到 `404`。

**错误**：`401`。

---

## 7. 附：数据模型字段类型对照（`prisma/schema.prisma`）

### 7.1 `AuditLog`（`/api/audit-logs` 数据源）

| 字段 | Prisma 类型 | 可空 |
|---|---|---|
| `id` | `String @id @default(cuid())` | 否 |
| `action` | `String` | 否 |
| `target` | `String?` | 是 |
| `userId` | `String?` | 是（`onDelete: SetNull`） |
| `ip` | `String` | 否 |
| `scope` | `AuditScope`（`INTERNAL`/`EXTERNAL`/`UNKNOWN`） | 否 |
| `userAgent` | `String?` | 是（**不返回**） |
| `createdAt` | `DateTime @default(now())` | 否 |

索引：`@@index([createdAt])`、`@@index([userId, createdAt])`。

### 7.2 `SystemSetting`（`/api/settings` 数据源）

| 字段 | Prisma 类型 | 可空 |
|---|---|---|
| `key` | `String @id` | 否 |
| `value` | `Json` | 否 |
| `updatedAt` | `DateTime @updatedAt` | 否 |

> `value` 为任意 JSON，**没有 schema 约束**；这就是 PATCH 能写任意键、且「存字符串不报错但运行时不生效」的根本原因。

### 7.3 `DeepseekKey`（`summary.keys[]` 数据源）

| 字段 | Prisma 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | `String @id @default(cuid())` | 否 | |
| `name` | `String` | 否 | |
| `apiKey` | `String` | 否 | 明文存 DB，接口只回 `masked` |
| `accountName` | `String @default("")` | 否 | 相同值 = 同一账户 |
| `platformKeyId` | `String @default("")` | 否 | |
| `enabled` | `Boolean @default(true)` | 否 | |
| `currency` | `String?` | 是 | |
| `balance` | `Float?` | 是 | |
| `grantedBalance` | `Float?` | 是 | |
| `toppedUpBalance` | `Float?` | 是 | |
| `isAvailable` | `Boolean?` | 是 | |
| `lastObservedAt` | `DateTime?` | 是 | |
| `lastError` | `String?` | 是 | |
| `createdAt` | `DateTime @default(now())` | 否 | |
| `updatedAt` | `DateTime @updatedAt` | 否 | |

索引：`@@index([accountName])`。

### 7.4 `DeepseekUsageDaily`（`summary.stats`/`chart` 数据源）

| 字段 | Prisma 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | `String @id @default(cuid())` | 否 | |
| `day` | `String` | 否 | `YYYY-MM-DD`（服务器时区） |
| `keyId` | `String @default("")` | 否 | 记账行所属本地 KEY |
| `platformKeyId` | `String @default("")` | 否 | 平台行所属平台 KEY |
| `platformKeyName` | `String @default("")` | 否 | |
| `currency` | `String @default("CNY")` | 否 | |
| `amount` | `Float @default(0)` | 否 | 已用金额 |
| `refill` | `Float @default(0)` | 否 | 当日充值 |
| `tokensHit` | `Float @default(0)` | 否 | |
| `tokensMiss` | `Float @default(0)` | 否 | |
| `tokensOut` | `Float @default(0)` | 否 | |
| `requests` | `Float @default(0)` | 否 | |
| `models` | `Json?` | 是 | `{model: {tokens, cost}}` |
| `source` | `String @default("ledger")` | 否 | `ledger` \| `platform` |
| `updatedAt` | `DateTime @updatedAt` | 否 | |

唯一约束：`@@unique([day, keyId, platformKeyId])`。

### 7.5 `Image`（`parse/import` 的 `items[]` 数据源）

| 字段 | Prisma 类型 | 可空 |
|---|---|---|
| `id` | `String @id @default(cuid())` | 否 |
| `name` | `String` | 否 |
| `originalKey` | `String` | 否 |
| `mimeType` | `String` | 否 |
| `size` | `BigInt` | 否 |
| `width` / `height` | `Int?` | 是 |
| `sha256` | `String @unique` | 否 |
| `viewCount` | `Int @default(0)` | 否 |
| `lastViewed` | `DateTime?` | 是 |
| `capturedAt` | `DateTime?` | 是 |
| `uploadedAt` | `DateTime @default(now())` | 否 |
| `deletedAt` | `DateTime?` | 是 |
| `deletedById` | `String?` | 是 |
| `uploaderId` | `String` | 否 |

---

## 8. 附：轮询参数与客户端行为汇总表

| 场景 | 触发接口 | 轮询接口 | 源码参考间隔 | 停止条件 | 读超时建议 |
|---|---|---|---|---|---|
| ffmpeg 下载 | `POST /api/ffmpeg/download` | `GET /api/ffmpeg/status` | **2000 ms**（`SettingsPanel.vue:57`） | `busy === false`（同时检查 `progress.phase === 'error'`） | 连接/读 ≥ 15 s |
| AI 引擎下载 | `POST /api/ai/download` | `GET /api/ai/status` | **2000 ms**（`SettingsPanel.vue:220`） | `running && baseUrl` 或 `progress.phase === 'error'` 或（`phase ∈ {idle,stopped}` 且文件齐全） | ≥ 15 s |
| AI 引擎启动 | `POST /api/ai/start` | 无需轮询（接口阻塞至就绪） | — | — | **≥ 180 s**（服务端最长等 150 s） |
| B站扫码 | `POST /api/bili/qr/create` | `POST /api/bili/qr/poll` | **2000 ms**，本地截止 **175 s**（`SettingsPanel.vue:93,123`） | `status ∈ {ok, expired, error}` | ≥ 20 s |
| 服务端导入任务 | `POST /api/parse/import` | `GET /api/parse/import/:id` | **800 ms**（`parseSaveStore.ts:147`） | `status ∈ {done, error}`；`404` 视为任务丢失 | ≥ 30 s（`POST` 创建即时返回） |
| 视频解析 | `POST /api/parse` | 无（同步返回） | — | — | **≥ 60 s**（前端 60 s） |
| DeepSeek 余额 | `POST /api/deepseek/refresh` | 无（同步返回完整 summary） | — | — | ≥ 30 s（服务端内部 15 s/KEY，多 KEY 串行） |

**「火忘式」接口清单**（HTTP 返回不代表任务结果，必须靠轮询判断）：

| 接口 | 返回 | 失败可见性 |
|---|---|---|
| `POST /api/ffmpeg/download` | `{ok:true}` | 仅服务端日志 + `GET /api/ffmpeg/status` 的 `progress.phase='error'` |
| `POST /api/ai/download` | `{ok:true}` | 仅服务端日志 + `GET /api/ai/status` 的 `progress.phase='error'` |

**需要 ADMIN 的接口**（共 16 个 —— 下表按「方法 + 路径」逐条列出）：

`GET /api/audit-logs`、`GET /api/settings`、`PATCH /api/settings`、`GET /api/ffmpeg/status`、`POST /api/ffmpeg/download`、`GET /api/ai/status`、`POST /api/ai/download`、`POST /api/ai/start`、`POST /api/ai/stop`、`POST /api/deepseek/keys`、`PATCH /api/deepseek/keys/:id`、`DELETE /api/deepseek/keys/:id`、`POST /api/deepseek/merge`、`PUT /api/deepseek/config`、`POST /api/bili/qr/create`、`POST /api/bili/qr/poll`。

**只需登录（不要求 ADMIN）**：`GET /api/deepseek/summary`、`POST /api/deepseek/refresh`、`POST/GET/DELETE /api/parse/import*`、`GET /api/me` 及第 0.3.4 节列出的辅助接口。

**完全公开（无鉴权）**：`GET /api/health`、`POST /api/auth/login`、`POST /api/auth/register`、`POST /api/parse`、`GET /api/stream`、`GET /api/ping`。

---

## 9. 未明确 / 需服务端确认的事项

以下内容**源码未定义**，Android 端不应假设：

1. **`GET /api/settings` 之外的设置读取途径**：非管理员无法读取任何设置（`GET /api/settings` 强制 ADMIN）。
2. **`/api/audit-logs` 分页**：固定 500 条上限，硬编码 `take: 500`，**无 `page`/`cursor` 参数**。若需历史全量，只能靠服务端改代码或直接查库。
3. **SSE / WebSocket / 长轮询**：源码不存在（见第 6 章开头说明）。所有长任务进度只能短轮询。
4. **`/api/ai/status` 中 `progress.variant` 的合法化**：初始值为 `'7b'`，下载时写入请求参数原值，启动/停止时 `{...progress}` 保留旧值——客户端不要用它判断「当前该下哪个模型」。
5. **`POST /api/ai/stop` 无法停止外部进程**：只 kill 本进程 spawn 的子进程。
6. **任务状态持久化**：`/api/parse/import` 任务仅存内存，服务重启即丢失；客户端在 `404` 后应提示「任务丢失，请重试」。
7. **令牌/密钥的读取接口**：`aiApiKey`、`biliSessdata`、`deepseekPlatformToken` **永不回显**，只有 `*Configured` 布尔状态；无法通过 API 取回明文。
8. **Cookie `Domain` 未设置**、**无 `__Host-` 前缀**、**无刷新/续期接口**。
9. **ffmpeg / AI 下载的实际下载地址**可由环境变量覆盖（`FFMPEG_DOWNLOAD_URL`、`AI_DOWNLOAD_MIRROR`），客户端不可假设固定 URL。

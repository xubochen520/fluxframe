# FluxFrame 原生 Android 客户端

`android/` 是一个**完全原生**的 Android 应用（Kotlin + Jetpack Compose），
直接调用 fluxframe 后端的 REST 接口 —— 不是 WebView 套壳，不加载 `client/dist` 里的网页。

旧的 `client/android/`（Capacitor 壳）保留未动，作为历史实现；本文档描述的是新的原生实现。

---

## 1. 它做了什么

### 与网页端功能对齐

| 模块 | 实现情况 |
|---|---|
| 登录 / 会话 | Cookie 会话（`fluxframe_session`）持久化在 `SharedPreferences`，冷启动自动免登录；401 自动踢回登录页 |
| 注册账号 | ✅ 登录页可切换「注册」，校验与服务端一致（用户名 3–30、密码 8 位以上）；注册接口不下发会话，因此注册成功后自动登录一次 |
| 批量打标签 | ✅ 标签页与人物页都有「批量」入口：搜索 + 全选 + 多选图片，一次贴同一个标签（`POST /api/tags/:id/images`） |
| 全局任务坞 | ✅ 任意页面底部浮层显示上传/提取进度，支持取消、失败重试、完成后直达图片库 |
| 图片库 | 交错网格（瀑布流）、搜索（名称+标签，输入防抖 280ms）、**双排标签筛选（上排人名 / 下排其他标签，均可多选，多个标签「同时满足」）**、三种排序（浏览/最新/名称）、每行 2–5 张可调；**只显示图片** |
| 视频库 | 独立页面，与图片库**完全分开**；同样有搜索、双排标签筛选、排序与列数；只显示视频，卡片带真实帧封面；右上角直达「视频提取」 |
| 视频标签自动归位 | 标签行只列出**在本库里真的有内容**的标签，数量也按本库统计 —— 只打在视频上的标签不会出现在图片库（点它会自动跳到视频库），反之亦然 |
| 全屏查看器 | 横向翻页、双指缩放 / 双击放大、1600 宽变体、自动记一次浏览；**顶栏与底部标签浮层 3.2 秒自动隐藏**，点一下图片 / 点一下视频重新唤出（视频与 ExoPlayer 控制器联动） |
| 视频播放 | Media3 ExoPlayer，复用带 Cookie 的 OkHttp 数据源，服务端支持 Range 可拖动 |
| 视频封面 | 网格里显示**视频 0.5 秒处的真实帧 + 播放按钮**：对 `/api/images/:id/file` 做带会话 Cookie 的 **HTTP Range 分块读取**（`core/media/HttpRangeMediaDataSource`），交给 `MediaMetadataRetriever` 取帧；取不到时自动退回渐变色块，弱网/异常格式也不开天窗 |
| 标签 | 人物组 / 普通标签分区，**两组都可折叠**，人物组横向滑动；新建（人物/R18 开关）、编辑、删除（仅管理员）、人物详情（媒体数 + 共同标签聚合 + 最新预览） |
| 上传 | 系统 Photo Picker（**零存储权限**）→ 服务端 `analyze`（流式上传 + AI 打标 + sha256 查重）→ 本机确认名称/标签/跳过重复 → `complete` 落库并生成 320/768/1600 webp |
| 回收站 | 独立入口、恢复、永久删除（仅管理员，同时删物理文件与缩略图） |
| 重命名 | 前后端一致的扩展名剥离规则 |
| 下载 | 交给系统 DownloadManager（自带断点续传、通知栏进度、点击打开），手动带上会话 Cookie |
| 访问日志 | 仅管理员，8 个分类本地筛选（服务端固定返回 500 条且无分页） |
| 视频提取 | 粘贴分享链接/整段口令 → 服务端解析（B站/抖音/快手）→ 保存视频/封面/图文图片 → 独立轮询任务进度（800ms） |
| DeepSeek 记账 | 余额总览、30 天柱状图、密钥增删改、账户合并建议、记账开关与刷新间隔、平台令牌 |
| 系统设置 | 站点名、存储目录、端口、上传上限、回收站保留天数（数字必须以 JSON number 提交） |
| AI 引擎 | llama.cpp 状态、3B/7B 下载、启动（同步阻塞最长 150s）/停止；ffmpeg 状态与一键下载 |
| B站登录 | 扫码（data URL 解码成 Bitmap 显示）+ 2000ms 轮询、175s 超时，与服务端实现一致 |
| R18 模式 | 顶部一键开关，服务端据此过滤 R18 标签与媒体，切换后全量重拉 |

### 超出网页端的部分

- **三套界面风格**（见下）
- **应用内「流体云」胶囊**：上传/提取进度在顶部以胶囊形态实时呈现，与通知栏、流体云三处同源
- **前台服务保活**：上传/视频提取退到后台不中断
- **局域网自动发现**：扫描同网段 1–254 的 4311/5173 端口，用 `/api/health` 判定（与旧壳的语义一致，但改用了 `NetworkInterface` 枚举网卡，不需要任何权限）
- **上传失败可整体放弃**：会把已上传的临时文件从服务端清掉
- **紧凑顶栏 + 悬浮胶囊底栏**：顶栏固定 48dp、只在滚动后浮出玻璃底板，向下滑自动收起（连状态栏一起让位），向上滑或回到顶部立刻回来；底栏是脱离屏幕边缘的悬浮胶囊，只有选中项展开文字（见 §8）
- **沉浸式全屏**：默认隐藏手势小白条，从底部边缘上滑可临时唤出；全屏查看器里连状态栏一起隐藏
- **视频真实帧封面**：见上表

---

## 2. 三套界面风格

在「设置 → 外观」里实时切换，实现只有一份代码、三组参数（`ui/theme/GlassTokens.kt`）：

| 风格 | 表现 | 实现要点 |
|---|---|---|
| **默认** | 近乎实色的卡片 + 极细描边 | 对比度最高、最省电，低端机推荐 |
| **亚克力** | 半透明底 + 背景副本 + 噪点颗粒 + 亮边 | 噪点是一张 512×512 的静态 ARGB 位图（固定随机种子），全应用共享，`BlendMode.Overlay` 叠加 |
| **液态玻璃** | 更强通透 + 对角高光扫过 + 边缘高光 | 高光带随全局动画相位移动 |

**背景副本是怎么做的**（v2.1.0 重写过，见 §8.1）：Compose 没有原生 backdrop blur，
这里的做法是「先把画布平移 `-本节点的窗口坐标`，再以**背景画布尺寸**绘制同一份流体渐变」。

```
GlassSurface(backdrop = true)
  └─ drawBehind { translate(-positionInWindow) { drawFluidGradient(canvasSize = 背景画布尺寸) } }
       └─ 相位来自 LocalFluidPhase（State，只在绘制阶段读取）
```

节点已被 `clip(shape)` 裁掉，所以**实际着色面积只有部件自身**，而且没有任何离屏纹理。
视觉上与旧版"整屏副本 + 偏移 + 模糊"几乎一致（背景本身就是平滑渐变，模糊看不出差别），
代价却只与部件面积成正比。

> `backdrop = true` 用在顶栏、底栏、胶囊、弹窗、任务坞这类"铬"部件上；
> 图片库里的成百上千张卡片走 `backdrop = false` 的半透明 + 描边路径，零额外成本。

背景本身是纯 Canvas 绘制的三团漂移径向光斑（`ui/glass/FluidBackdrop.kt`），
不依赖 WebGL，配色与服务器 `SystemSetting.fluidColors` 同步 —— 手机端和网页端同一套色。

---

## 3. 流体云 / 实况通知接入

### 3.1 用的是哪条链路（v2.1.0 起：不二选一，而是"主链路 + 并行增强"）

设置页里**没有任何需要你填写的东西**。应用会自动这样做：

| 链路 | 形态 | 本项目 |
|---|---|---|
| **主链路：标准实况通知** | Android 16 的 progress-centric notification（`Notification.ProgressStyle` + `setRequestPromotedOngoing`）；低版本是常驻进度通知 | ✅ **一定可用**，是所有设备上的兜底 |
| **并行增强：OPPO 意图共享** | `ContentProviderClient.call(authority, "shareIntent", null, extras)` 下发 `IntelligentIntent` JSON | ✅ 探测到 ColorOS 通道可用就**同时再发一份**，出不出卡由系统决定 |
| 链路 B 泛在卡片 | SeedlingSupportSDK + 在 Pantanal DevStudio 开发并发布 upk 卡片包 | ❌ 未采用（需另走发布流程） |

两条链路共用同一份 `CapsuleState`，都失败也不影响应用内的顶部胶囊。

OPPO 侧协议要点（`fluidcloud/OppoFluidCloudProvider.kt`）：

| 项 | 值 |
|---|---|
| authority | `IntelligentIntent`（官方文档）／`com.oplus.pantanal.ums.IntentProvider`（SDK 内部）—— 两种都试 |
| method | `shareIntent` |
| 动作 | `intentAction.actionStatus`：**0 创建 / 1 更新 / 2 结束** |
| 垂域 | 用 `TASK`（任务），`secondaryData.type = "PROGRESS"` 对应官方「进度可视化」模板 |
| 开关探测 | `content://intelligent_data_expositor/switch`（游标列 `result`）+ UMS 包 metaData |
| 胶囊时长 | ColorOS 15 上最长 5 分钟，之后系统自动销卡 |

> ⚠️ 网上流传的 `FluidCloudManager` / `FluidCloudTemplate` / `buildFluidCloudData`
> **不是 OPPO 的真实 API**，本实现不使用它们。

### 3.2 必须知道的限制（重要）

1. **实况更新必须同时满足三个条件，缺一不可**（v2.1.1 才补齐，见 §9.1）：
   | 条件 | 说明 |
   |---|---|
   | 清单声明 `POST_PROMOTED_NOTIFICATIONS` | Android 16 QPR1 引入，AOSP 里是 `protectionLevel="normal\|appop"`，安装即授予。**不声明时 `canPostPromotedNotifications()` 恒为 false，永远不出卡** |
   | extras 写 `android.requestPromotedOngoing = true` | 这就是 androidx `setRequestPromotedOngoing(true)` 的真实实现（反编译 androidx.core 1.17.0 确认：只往 extras 塞一个 boolean） |
   | 渠道重要性不能是"低" | 低重要性渠道会被系统排除在提升之外；实况更新因此单独用一个高重要性渠道（与 InstallerX Revived 一致） |
2. **OPPO 的 `serviceId` 必须由 OPPO 分配**（企业开发者认证 → 邮件申请白名单）。
   标准实况更新路线**不需要它**；仅当你要额外走意图共享时才相关，而且现在**不再要求用户填写**：
   优先从 UMS 包 metaData 里自动读取，读不到就留空照发。出不了卡没有副作用。
3. **参数名存在不确定处**：`Bundle` 的 key 名、`milestone.code` 取值官方未完整公开。
   实现里对 Bundle key 做了多候选尝试，并对两个 authority 都做尝试。
4. **权限前提**：Android 13+ 需要 `POST_NOTIFICATIONS`。首次进入设置页时会请求一次，
   没授权时进度只出现在应用内胶囊里，设置页会明确提示。
5. **ColorOS 可能另有策略**：即使系统报告"接受提升"，厂商也可能对第三方应用的流体云
   再加一层开关。设置页的诊断行会如实显示每一档状态，便于定位。

### 3.3 应用内胶囊

无论走哪条链路，应用内顶部都会显示同一份 `CapsuleState`（`fluidcloud/CapsuleState.kt`）：

- 三种来源共用一份状态：**OPPO 流体云 / 系统通知 / 应用内胶囊**，显示永远一致
- 进度支持确定值（0–100%）与不确定值（上游没给 `content-length`）
- 结束后短暂停留 2.6 秒自动收起，也可点 × 立即收起

### 3.4 如果日后要走链路 B

官方 SDK 已在 Maven Central 公开，`app/build.gradle.kts` 里已留好一行注释：

```kotlin
// implementation("com.oplus.pantanal.card:seedling-support-external:3.0.7")
```

取消注释后还需在 Manifest 里自声明 `SeedlingCardWidgetProvider`
（action `com.oplus.seedling.action.SEEDLING_CARD`）。详细调研（含 AAR 反解出的一手证据、
权限清单、各厂商降级方案横向对比）见 [`docs/OPPO-FLUID-CLOUD-RESEARCH.md`](docs/OPPO-FLUID-CLOUD-RESEARCH.md)。

---

## 4. 构建

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 或 21（本工程用 21 验证） |
| Android SDK | `platforms;android-35`、`build-tools;34.0.0+` |
| Gradle | 8.7（wrapper 已内置，指向本机缓存） |
| AGP / Kotlin | 8.6.1 / 2.0.21 |
| minSdk / targetSdk | 26 / 35 |

`gradle.properties` 里写死了 `org.gradle.java.home=D:/Java/Java21`，换机器请改掉或删除该行（改用 `JAVA_HOME`）。

### 命令

```powershell
cd android

# 调试包（applicationId 带 .debug 后缀，可与旧版共存）
.\gradlew.bat :app:assembleDebug

# 正式包（沿用 com.fluxframe.app，可与旧 Capacitor 版覆盖升级）
.\gradlew.bat :app:assembleRelease

# 只做编译检查（最快）
.\gradlew.bat :app:compileDebugKotlin

# 安装到已连接设备
.\gradlew.bat :app:installDebug
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

仓库根目录也放了一份拷贝：`fluxframe-v2.1.0-native-debug.apk` / `fluxframe-v2.1.0-native-release.apk`。

### 两个已知的 Windows 坑（已在工程里处理）

1. **中文路径**：AGP 默认拒绝非 ASCII 工程路径。本工程在 `gradle.properties` 里设了
   `android.overridePathCheck=true`（本仓库旧的 Capacitor 工程就是从同一路径成功构建的）。
2. **全局 init.gradle**：`~/.gradle/init.gradle` 里的阿里云镜像会被注入到所有工程，
   与 `FAIL_ON_PROJECT_REPOS` 冲突。本工程用 `PREFER_SETTINGS`，以工程内声明的
   `google()` / `mavenCentral()` 为准。

### 排错

PowerShell 的 `*>` 重定向会把 Gradle 的 stderr 包装成 `NativeCommandError`，
中文路径与错误消息都被拆散、无法阅读。请用 cmd 重定向：

```powershell
cmd /c "gradlew.bat :app:compileDebugKotlin > build-log.txt 2>&1"
powershell -ExecutionPolicy Bypass -File .\show-errors.ps1
```

---

## 5. 架构

```
app/src/main/java/com/fluxframe/app/
├── FluxFrameApp.kt              Application：依赖容器、Coil 图片管线、通知渠道
├── MainActivity.kt              唯一 Activity，按会话阶段三选一（配服务器/登录/主界面）
├── core/
│   ├── net/                     ServerEndpoint、Cookie Jar、动态 Host 改写、局域网发现、错误归一
│   ├── prefs/                   AppPreferences（服务器地址、三套主题、界面偏好）
│   ├── media/                   HttpRangeMediaDataSource（带鉴权的视频分段读取，用于取封面帧）
│   ├── download/                MediaDownloader（系统 DownloadManager + Cookie）
│   ├── util/                    Formatters（体积/时长/相对时间/金额/大数）
│   └── di/                      AppContainer（手写依赖容器）
├── data/
│   ├── model/                   与服务端 DTO 一一对应的 @Serializable 数据类
│   ├── remote/FluxFrameApi.kt   Retrofit 接口（覆盖全部 54 个路由）
│   ├── repo/                    6 个仓库：Auth / Media / Tag / Admin / Deepseek / Parse
│   └── store/                   6 个应用级状态：Session / Media / Settings / Logs / Deepseek / Task
├── fluidcloud/                  流体云抽象层（标准实况通知 + OPPO 意图共享并行 + 前台服务）
├── ui/
│   ├── theme/                   Color / Typography / Shapes / GlassTokens / Theme
│   ├── glass/                   FluidBackdrop（流体背景 + 12fps 相位）+ GlassSurface（三套玻璃）
│   ├── system/                  SystemBarsEffect（状态栏图标明暗、隐藏小白条、沉浸式）
│   ├── components/              Common（卡片/标签/指标/空态/进度/折叠区块）+ Chrome（栏/胶囊/对话框/输入框）
│   ├── screens/                 13 个页面与覆盖层
│   ├── HeaderAutoHide.kt        顶栏自动收起的判定逻辑（可单测）
│   └── AppShell.kt              路由、返回栈、顶栏底栏、相册选择、覆盖层编排
└── docs/                        API-EXTENDED.md、OPPO-FLUID-CLOUD-RESEARCH.md
```

**几个刻意的设计选择**

- **不用 Hilt/Koin**：只有一个容器，用 `CompositionLocal` 传递最直观，也少一层注解处理。
- **不用 Navigation Compose**：页面少、参数简单，自己维护 `mutableStateListOf<AppRoute>` 返回栈，
  可以让「全屏查看器」这类覆盖层和页面共存而不打架。
- **状态放在应用级 store 而不是每屏一个 ViewModel**：图片库、总览、标签页、回收站共享同一份数据，
  任一处的改名/删除/打标签要立刻反映到其它页面，集中持有最省心也避免重复请求。
- **切服务器不重建 Retrofit**：Retrofit 的 baseUrl 是占位符 `http://fluxframe.invalid/`，
  真实 host/port 由拦截器在请求发出前改写，因此换服务器只改一个 StateFlow。

---

## 6. 接口对接要点（踩过的坑）

这些是服务端实现的"反直觉"之处，客户端已按此处理：

| 坑 | 处理 |
|---|---|
| `PATCH /api/settings` 无字段白名单，但**数字必须发 JSON number**（发字符串能过校验却不被读取） | 用 `JsonPrimitive(Int)` 构体；响应不含计算字段，保存后重新 GET |
| `POST /api/ai/start` **同步阻塞最长 150 秒**，且失败返回 500 | 走 300s 读超时的客户端；同时处理 `200 {ok:false}` 与 500 两种失败形态 |
| `POST /api/ffmpeg/download`、`/api/ai/download` 是"火忘式"，恒返回 `{ok:true}` | 真实结果只能轮询状态接口 |
| `POST /api/parse` 失败是 `400 {ok:false,msg}`（不是 `{message}`） | 接口层用 `Response<ParseResponse>` 接收，手动解错误体 |
| `POST /api/parse` 只读 `body.url`（前端曾误发 `share_text` 被静默忽略） | 客户端先从整段口令里正则提取链接 |
| 服务端**没有 SSE** | 解析导入 800ms 轮询、ffmpeg/AI 状态 2s、B站扫码 2s（上限 175s） |
| `/api/audit-logs` 固定返回 500 条且**无分页参数** | 分类筛选全部在本地做 |
| 视频没有 `ImageVariant`，`/variant/:width` 对视频必然 404 | 视频一律用 `/file`；封面改用 `/file` + Range 分段取帧（`HttpRangeMediaDataSource`） |
| `/api/images/:id/file` 需要会话 Cookie，而 `MediaMetadataRetriever` 自带的 HTTP 栈**不带 Cookie** | 自己实现 `MediaDataSource`，每次 `readAt` 都用应用内那个带 Cookie 的 OkHttp 发 Range 请求（服务端支持 206 + `Content-Range`，已由测试锁定） |
| 人物预览的 `thumb` 是相对路径 | `MediaRepository.absoluteFor()` 补全 |
| `POST /api/deepseek/keys` 即便探测失败**也会创建 KEY**，响应仍是 200 | 提示语区分「已添加并校验通过」与「已保存，但密钥校验失败：…」 |
| `PATCH/DELETE keys/:id`、`merge`、`config` 返回**裸 Summary**（只有 `POST keys` 包了 `{key,probe,summary}`） | 分别声明返回类型 |
| `DELETE /api/parse/import/:id` 幂等恒返回 `{ok:true}`，取消后继续轮询会 404 | 404 视为"已取消/已过期"，不当异常 |

完整的接口契约见 [`docs/API-EXTENDED.md`](docs/API-EXTENDED.md)。

---

## 7. 验证记录

### 7.1 编译与打包

本机 Windows 11 + JDK 21 + Gradle 8.7 + AGP 8.6.1：

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ 0 error |
| `:app:assembleDebug` | ✅ `app-debug.apk` 21.86 MB |
| `:app:assembleRelease` | ✅ `app-release.apk` 14.78 MB |
| `aapt2 dump badging` | ✅ release = `com.fluxframe.app` v2.1.4(35) targetSdk 35 / launchable `MainActivity` / 标签「流体图库」 |
| `aapt2 dump permissions` | ✅ 含 `POST_NOTIFICATIONS`、**`POST_PROMOTED_NOTIFICATIONS`**、`FOREGROUND_SERVICE_DATA_SYNC` |
| 合并后的 Manifest | ✅ minSdk 26、`usesCleartextTraffic=true`、`foregroundServiceType=dataSync`、`<queries>` 含 com.oplus.pantanal.ums / com.oplus.metis / authority `IntelligentIntent`；**已移除 `largeHeap`**（见 §8.1） |

### 7.2 自动化测试：82 项全部通过（0 skipped）

```powershell
# 建议从 ASCII 路径执行（见下方说明）
cmd /c "cd /d C:\ff-android && gradlew.bat :app:testDebugUnitTest --rerun-tasks"
```

| 测试类 | 数量 | 内容 |
|---|---|---|
| `PureLogicTest` | 19 | 服务器地址解析、格式化、日志分组、口令取链接、导入请求构造、上传状态机、主题枚举、排序枚举 |
| `ApiContractTest` | 12 | **对真实运行的后端**逐接口验证 DTO 能解码（含 `/api/settings`、`/api/ffmpeg/status`、320 缩略图 webp 字节、视频 thumb 回落 `/file`） |
| `LayoutRegressionTest` | 6 | **布局回归**：玻璃卡片/顶栏/底栏/弹窗的高度必须贴合内容，内容区高度必须 > 0 且真的可见；三套主题逐一验证；顶栏在滚动态与静止态高度一致；悬浮底栏只给选中项展开文字 |
| `NewUiSmokeTest` | 2 | 任务坞能渲染上传与提取两类卡片、进度与文案正确、点「取消」能回调；空任务时不渲染任何卡片 |
| `PerformanceAndCloudTest` | 10 | 新偏好默认值、三套玻璃参数自洽（默认风格必须完全不做背景副本）、关掉噪点开关真的生效、流体云能力徽标文案、胶囊百分比夹紧、**顶栏自动收起规则**（阈值/抵消/到顶立刻恢复/开关关闭）、视频封面数据源的失败路径 |
| `VideoPosterTest` | 3 | **打真实服务端**：分块读取能拿到数据且文件首尾都可读、同一分块只发一次请求（缓存生效）、**不带会话 Cookie 时读不到任何数据**（正向证明必须走应用内客户端） |
| `LiveUpdateContractTest` | 7 | **流体云为什么不出卡**：清单必须声明 `POST_PROMOTED_NOTIFICATIONS`（修复前必然失败）、实况渠道必须存在且重要性 ≥ 默认且无声无震动、低版本不许谎称支持实况更新、诊断不许把"已声明未授予"误报成"缺失"、低版本仍能构造出带进度的通知 |
| `MediaLibraryFilterTest` | 11 | **图片库 / 视频库的筛选规则**：多选是「同时满足」而不是并集、图片库不混视频、回收站不分类、只打在视频上的标签在图片库里的数量必须为 0、点标签该跳哪个库 |
| `ZoomAndPosterSettingsTest` | 12 | **双击缩放的焦点计算**（不变量：点击点的屏幕位置必须不变）、平移夹紧、倍率夹紧、贝塞尔缓动单调且起步快于线性；**取帧秒数的边界**（负数/超上限/空串/NaN 一律拒绝） |
| `LiveUpdateContractTest` | 7 | **流体云为什么不出卡**：清单必须声明 `POST_PROMOTED_NOTIFICATIONS`（修复前必然失败）、实况渠道必须存在且重要性 ≥ 默认且无声无震动、低版本不许谎称支持实况更新、诊断不许把"已声明未授予"误报成"缺失"、低版本仍能构造出带进度的通知 |

> 服务未启动或库里没有视频时，`ApiContractTest` 与 `VideoPosterTest` 会整类自动跳过（`assumeTrue`）。
> **看结果时一定要核对 `skipped` 计数** —— `gradlew` 的 `-D` 只作用于 Gradle daemon，
> 不转发给 test worker，属性没透传时 12 项会静默跳过而 Gradle 照样报 `BUILD SUCCESSFUL`
> （我第一次就踩了这个"假绿"）。`app/build.gradle.kts` 已加转发，也支持
> `FLUXFRAME_BASE_URL` / `FLUXFRAME_USER` / `FLUXFRAME_PASS` 环境变量。

### 7.3 布局回归测试证明了什么（v2.0.0 "登录后一片空白"的复盘）

v2.0.0 装到手机上后，登录进去只有一页、内容区全空。成因是 `GlassSurface`：
背景副本要 `requiredSize(整屏)` 才能与外层背景像素级对齐，但它**参与了父容器测量**，
而 Compose 的 `Box` 按最大子节点决定自身尺寸 —— 于是顶栏、底栏、胶囊、弹窗
全被撑成整屏高，真正的内容区被压成 0 高度。

修法（v2.0.1）：让背景副本用一个 `layout { }` 主动报告 `0×0`（`Modifier.overlayWithoutSize()`），
既保留整屏绘制对齐，又不影响父容器量尺寸。

> v2.1.0 起这个"整屏副本"的做法被**整体替换**了（见 §8.1）：改成平移画布 + 按卡片区域绘制，
> 于是从根上不存在"整屏子节点"，`overlayWithoutSize()` 也随之删除。
> 回归测试保留并加强，仍然是挡住"内容被挤成 0 高度"的那道闸。

**这不是"改完就说好了"** —— 我把修复临时回退、跑测试，得到的正是用户看到的现象：

| 断言 | 回退修复后实测 | 修复后 |
|---|---|---|
| 顶栏高度 | **1782px（= 整屏）** | ~124px ✅ |
| 底栏高度 | **1782px（= 整屏）** | ~110px ✅ |
| 弹窗高度 | **1782px（= 整屏）** | ~150px ✅ |
| 内容区 | **"The component is not displayed!"（0 高度）** | ~1500px，可见 ✅ |

1782px 正是 891dp × 2(xhdpi) 的整屏高度，与"页面一片空白"完全对应。
所以 `LayoutRegressionTest` 是真正能挡住这个 bug 的测试，不是形式主义。

顺带修掉的第二个 bug：**"关闭动效"其实没关掉动画**。原来即使
`animationsEnabled = false`，`rememberInfiniteTransition` 仍会创建并持续跑帧
（只是取值被固定住），既没省电，也让 Compose 测试永远等不到 idle。
现在关掉时**完全不创建**无限动画。

### 7.4 关于"必须用 ASCII 路径跑测试"

工程位于中文路径时，Gradle 的**测试 worker**（独立 JVM）会用系统 ANSI 代码页解析
classpath，中文目录被解码错，表现为莫名其妙的
`ClassNotFoundException: com.fluxframe.app.PureLogicTest`（类文件明明存在）。

已在 `build.gradle.kts` 里给 Test 任务固定 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`，
但**最稳的办法是从 ASCII 路径执行**（目录联接，不需要复制代码）：

```powershell
cmd /c 'mklink /J C:\ff-android "C:\Users\29329\Desktop\内网图片管理器\android"'
cmd /c "cd /d C:\ff-android && gradlew.bat :app:testDebugUnitTest"
```

顺带的好处：从 ASCII 路径构建时，Kotlin 报错信息不再被中文路径折行，
`show-errors.ps1` 也能正常解析。

### 7.5 尚未完成的验证：真机运行

**没有做真机 / 模拟器运行验证**（编译通过 ≠ 运行不崩），原因：

- 本机没有连接 Android 设备；
- 已自动装好 Android SDK 的 emulator 与 `system-images;android-35;google_apis;x86_64`，
  并创建了 AVD `fluxframe-test`，但启动时报：
  `x86_64 emulation currently requires hardware acceleration!`
  `Android Emulator hypervisor driver is not installed on this machine`
  —— 安装 AEHD / 启用 Windows Hypervisor Platform 需要管理员权限并重启，本会话无法完成。

因此**首屏渲染、滚动流畅度、手势、视频播放、上传全链路这些只能在真机上确认**。
已准备的资产（模拟器 + AVD + 本地后端）都在，装好驱动后即可直接跑：

```powershell
# 1) 启动本地后端（本机 PostgreSQL 需在跑）
cd server; $env:DATABASE_URL="postgresql://postgres:postgres@127.0.0.1:5432/image_manager?schema=public"; node dist/index.js

# 2) 启动模拟器（装好 hypervisor driver 之后）
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd fluxframe-test -gpu swiftshader_indirect

# 3) 安装并启动；模拟器里用 10.0.2.2 访问宿主机
adb install -r ..\fluxframe-v2.0.1-native-debug.apk
adb shell am start -n com.fluxframe.app.debug/com.fluxframe.app.MainActivity
adb shell input text "10.0.2.2:4311"
```

> 自动发现也能命中：模拟器自身在 `10.0.2.x` 网段，宿主机就是 `10.0.2.2`，
> 扫描会直接列出这台服务器。

**真机上优先看这几处**（自动化测不到的部分）：

1. 登录后是否直接看到「总览」内容 —— v2.0.0 的空白 bug 现场；
2. 三套主题切换、图片库滚动是否跟手；
3. 上传一批图 → 顶部胶囊 + 任务坞进度 → 确认入库；
4. 标签页「批量」→ 多选 → 贴标签（`BatchTagDialog` 未做自动化覆盖）；
5. 视频播放拖动进度、全屏查看器双指缩放、下载走系统通知栏。

### 7.6 测试覆盖不到的地方（如实记录）

| 缺口 | 原因 |
|---|---|
| `BatchTagDialog` 渲染 | Dialog 里含 `GlassTextField` 或 `LazyVerticalGrid` 时，Robolectric 下 Compose 永远进不了 idle（`AppNotIdleException`），关掉 `mainClock.autoAdvance` 也没用（`setContent` 自身等 idle）。对照实验：Dialog + 纯文本、Dialog + 玻璃卡片都能 idle，只有加了输入框或懒加载列表才卡住 —— 判定为测试环境限制而非应用缺陷。**该弹窗需真机验证。** |
| 视频播放、手势缩放 | 需要真实 Surface 与触摸事件，Robolectric 覆盖不到 |
| 流体云出卡 | 需要 ColorOS 15+ 真机（OPPO 的 `serviceId` 现在由应用自动获取，不再需要用户填写） |
| 上传全链路（大文件 / 断网 / 切后台） | 需要真机与真实网络环境 |
| **滑动帧率 / 功耗** | 无真机、无模拟器（缺 hypervisor 驱动），**没有实测 fps 与电量**。§8.1 给的是结构性证据（模糊次数从 N 降到 0、相位更新从 60fps 降到 12fps、缓存上限显式化），不是跑分 |
| **视频封面在真机上的解码成功率** | `HttpRangeMediaDataSource` 的读路径已用真实服务端 + 真实视频验证（3 项测试），但 `MediaMetadataRetriever` 在不同 ROM 上对分段数据源的容忍度只有真机能验 |
| 系统栏沉浸行为 | 隐藏小白条 / 隐藏状态栏需要真机（各 ROM 对手势条的处理不同） |


---

## 8. 这一轮做了什么（v2.1.0）

### 8.1 性能：把每张"铬"部件的全屏模糊拿掉

**问题**：滑动卡顿、发热、耗电。根因有三处，都是"每帧做全屏级工作"：

| # | 旧实现 | 代价 | 现在 |
|---|---|---|---|
| 1 | `GlassSurface(backdrop = true)` 用 `requiredSize(整屏) + Modifier.blur(32.dp)` 生成磨砂背景 | 每个铬部件 = 一张全屏离屏纹理 + 三遍模糊。顶栏+底栏+胶囊+任务坞+提示条同时在场就是 4~5 次全屏模糊/帧 | **0 次**。改成"平移画布到 `-positionInWindow`，按卡片区域绘制同一份流体渐变"，无离屏纹理、无 RenderEffect |
| 2 | `rememberInfiniteTransition` 以 60fps 推进背景相位 | 30 秒的缓慢漂移却让整屏 60fps 重绘 | 相位按 **12fps** 量化；用 `withFrameNanos` 跟随帧时钟，**退到后台完全停止推进** |
| 3 | `LocalFluidPhase` 是裸 `Float`，各玻璃组件在**组合阶段**读取 | 相位一变 → 所有读取者重组 → 整棵树每帧重组 | 改成下发 `State<Float>`，只在 `Canvas` / `drawBehind` 的绘制 lambda 里读 `.value` → **只失效绘制阶段** |

结构性证据（可直接在源码里核对，不是估算）：

| 指标 | 旧 | 新 |
|---|---|---|
| `Modifier.blur` 调用 | 1 处（作用于所有 backdrop 部件） | **0 处**（仅注释里提到） |
| `rememberInfiniteTransition` | 1 处 | **0 处** |
| 相位更新频率 | ~60/s | **~12/s** |
| 卡片背景的着色面积 | 整屏 × 部件数 | **仅部件自身面积** |

**内存**：`FluxFrameApp` 现在显式设定 Coil 缓存 —— 位图内存缓存按系统**常规堆**的 18% 计算
并夹在 24–96 MB（Coil 默认按 `Runtime.maxMemory()` 的 25% 算，而清单里开着 `largeHeap`
会把这个基数放大到 512MB 级，这是"占用很高"的一个直接来源）；磁盘缓存固定 192 MB。
同时**移除了清单里的 `largeHeap`**（已在 APK 里核对）。

### 8.2 顶栏：紧凑 + 随滚动收起（连状态栏一起）

- 高度从 68dp（标题 + 副标题两行）压到 **48dp**，副标题里的账号信息移到设置页；
- 停在顶部时**完全透明**，滚动后才浮出一层玻璃底板 —— 视觉上"没有上栏"，内容顶到状态栏下；
- 向下滑累积 56px 后收起，**状态栏（时间那一行）同时收起**；向上滑抵消到 0 就回来，
  **滑到列表顶部再往上拖会立刻回来**（不用等抵消）；
- 判定逻辑抽成 `ui/HeaderAutoHide.kt`，4 条规则都有单测（阈值、抵消、到顶立刻恢复、开关关闭）。

### 8.3 底栏：SukiSU Ultra 风格的悬浮胶囊

脱离屏幕左右与底部边缘的圆角胶囊（左右各 14dp、上下 8dp 外边距），高 48dp；
**只有选中项展开成"图标 + 文字"的实心药丸，未选中项只留图标**（带小圆点角标）。
视觉重量集中在当前位置，比五项"图标 + 文字"平均用力清爽得多。有单测锁死"未选中项不出现文字"。

### 8.4 沉浸式：小白条自动隐藏 + 状态栏图标明暗自适应

`ui/system/SystemBars.kt` 统一处理：

- **状态栏图标跟主题走**。主题 XML 里 `windowLightStatusBar=false` 是写死的，浅色主题下
  白字白底、时间根本看不见；现在按当前明暗实时切换；
- **手势小白条默认隐藏**：`hide(navigationBars)` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，
  从底部边缘上滑才临时浮出，随后自动收回。可在「设置 → 外观」关掉；
- 系统在 `onResume` / 窗口重新获得焦点时会重置系统栏可见性，因此这里在 `ON_RESUME`
  与视图重新 attach 时都会重新下发一次 —— 否则会出现"切出去再回来小白条又冒出来"。
- 全屏查看器打开时**连状态栏一起隐藏**，真正做到全屏看图。

### 8.5 标签页：可折叠 + 人物横滑

- 「人物」与「全部标签」两组都用 `CollapsibleHeader` / `CollapsibleSection` 折叠，
  折叠状态用 `rememberSaveable` 记住；
- 人物组从"固定高度的三列网格"改成**横向滑动的 `LazyRow`**，人物再多也不会把页面拉长；
- 「全部标签」默认只渲染前 24 个，超出时给一个"展开全部（还有 N 个）"——
  折叠时干脆不生成后续 item，长列表保持懒加载；
- 人物详情的「共同出现的标签」同样可折叠。

### 8.6 流体云：Google 实况更新规范 + OPPO 原生共享接口，不用填任何东西

见 §3。要点：主链路是标准实况通知（Android 16 的 progress-centric notification，
低版本是常驻进度通知），ColorOS 上再**并行**发一份 OPPO 意图共享；
`serviceId` 由应用自动从系统 metaData 获取，**设置页里那个输入框已经删掉**。

> ⚠️ **v2.1.0 的这条链路在真机上并没有生效** —— 清单里少了
> `POST_PROMOTED_NOTIFICATIONS`，导致通知永远不会被提升。修复与完整原因见 **§9.1**。
设置页会如实显示当前是哪一档（待授权 / 实况更新 / 流体云 + 实况窗 / 系统通知）。

> API 36 的 `Notification.ProgressStyle` 在 `compileSdk = 35` 下拿不到，因此这一段用
> 反射实现，**任何一步失败都退回标准进度通知**。等 AGP 与 SDK 36 稳定后可以改成直接编译期调用。

### 8.7 视频预览：0.5 秒处的真实帧 + 播放按钮

原来的视频卡片是"渐变色块 + 播放按钮"。现在显示**真实帧**，做法是：

```
MediaMetadataRetriever.setDataSource(MediaDataSource)
   └─ HttpRangeMediaDataSource（core/media/）
        ├─ getSize()      → Range: bytes=0-0，读 Content-Range 里的总长度
        └─ readAt(pos,..) → 按 64KB 对齐分块，带会话 Cookie 发 Range 请求，LRU 缓存 32 块（2MB）
```

为什么不能图省事直接 `setDataSource(url)`：`MediaMetadataRetriever` 自带的 HTTP 栈
**不带我们的会话 Cookie**，只会拿到 401（这一条有反向测试锁定）。

安全边界：只有真正返回 `206` 且长度不超过一个块的响应才会被缓存 ——
服务端若忽略了 Range（返回 200 全量），这里会**直接放弃并退回渐变占位**，
绝不会为了封面把整个视频读进内存。取帧位置 0.5 秒，避开片头黑场与台标淡入。
可在「设置 → 外观 → 视频缩略图取真实帧」关掉（省流量）。

> ⚠️ **v2.1.0 的这条链路在真机上也没有生效**（仍然只有渐变）。原因与改法见 **§9.2**：
> 取帧不再经过 Coil，改为自己按 4 条策略依次尝试，并且失败原因会显示在设置页。

---

## 9. v2.1.1：两个"真机上没生效"的修复

v2.1.0 的两个功能在真机上是失效的 —— 流体云不出卡、视频预览仍然只有渐变。
两个都修好了，并且都补上了**能防回归的断言**（而不是"改完就说好了"）。

### 9.1 流体云无效：清单里少了一个权限

**参考实现**：[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)
的 `framework/notification/`。它的 `ModernNotificationBuilder` 用
`NotificationCompat.ProgressStyle` + `setRequestPromotedOngoing(true)` + `setShortCriticalText()`
构造实况更新，并且 —— 这是最关键的一行 —— **清单里声明了
`android.permission.POST_PROMOTED_NOTIFICATIONS`**。

v2.1.0 缺的就是它。没有这个权限时
`NotificationManager.canPostPromotedNotifications()` **恒为 false**，
通知永远不会被提升为实况更新，ColorOS 上自然也就没有流体云。
这类问题编译不报错、运行不抛异常，界面上只表现为"什么都没发生"。

修复涉及的三个必要条件（缺一不可）：

| # | 条件 | v2.1.0 | v2.1.1 |
|---|---|---|---|
| 1 | 清单声明 `POST_PROMOTED_NOTIFICATIONS` | ❌ 缺失 | ✅ 已声明（AOSP 里是 `normal\|appop`，安装即授予） |
| 2 | extras 写 `android.requestPromotedOngoing=true` | ❌ 反射调了一个**不存在**的平台方法 | ✅ 直接写 extra（与 androidx 实现逐字一致） |
| 3 | 渠道重要性足够高 | ❌ 用了 `IMPORTANCE_LOW` | ✅ 实况更新单独用 `IMPORTANCE_HIGH` 渠道（无声音无震动） |

第 2 条的细节值得记一笔：我反编译了 `androidx.core:core:1.17.0` 的
`NotificationCompat$Builder.setRequestPromotedOngoing(boolean)`，它的实现**只是往
extras 里塞一个 boolean**，并没有调用平台 Builder 上的同名方法 ——
也就是说平台根本没有这个方法，v2.1.0 的反射必然失败。

同时，Android 16 的 `Notification.ProgressStyle` 仍然只能反射（`compileSdk` 还是 35；
能用编译期 API 的 `androidx.core:core:1.17.0` 要求 `compileSdk=36` + AGP 8.9.1），
调用序列完全照抄 androidx 的 `ProgressStyle.apply()`。

**防回归**：新增 `LiveUpdateContractTest`（7 项），其中最关键的一条是
"清单必须声明 `POST_PROMOTED_NOTIFICATIONS`" —— 它在修复前必然失败。
另外还锁死了"实况渠道重要性 ≥ 默认且无声无震动"和"低版本不许谎称支持"。

**设置页现在会显示一行诊断**，例如：

> Android 34（14）· 提升权限=已声明未授予 · 系统接受提升=否 · 实况渠道重要性=高。
> 实况更新 / 流体云需要 Android 16 及以上，当前系统只能显示常驻进度通知。

（"已声明未授予"是正常态：该权限 Android 16 QPR1 才引入，16.0 上尚未定义，
所以诊断会区分"缺失"和"已声明未授予"，避免把人引向错误方向。）

### 9.2 视频预览仍是渐变：把取帧从 Coil 手里拿回来

v2.1.0 把取帧整条链路交给了 Coil（`MediaDataSourceFetcher` + `VideoFrameDecoder`），
结果是**任何失败都不可观测**：界面只表现为"永远只有渐变"，没有任何日志或提示。
同时它依赖两个都不一定成立的前提：`MediaMetadataRetriever.setDataSource(MediaDataSource)`
在厂商 ROM 上必须可用、Coil 必须把数据源原样交给解码器。

v2.1.1 改成自己取帧（`core/media/VideoPosterLoader`），按兼容性从高到低依次尝试：

| 策略 | 做法 | 代价 |
|---|---|---|
| 1 | `setDataSource(url, headers)` —— 平台 HTTP 栈，会话 Cookie 作为**请求头**传入 | 不落地文件 |
| 2 | `setDataSource(MediaDataSource)` —— 应用内带 Cookie 的 OkHttp 发 Range 请求 | 不落地文件 |
| 3 | 下载文件头 8MB 到临时文件后取帧（faststart 的 MP4 足够取到 0.5s 那一帧） | ≤8MB 流量 |
| 4 | 整包下载（仅对 ≤40MB 的视频） | ≤40MB 流量 |

4 条都失败才退回渐变占位，并且**会把失败原因记录下来**显示在设置页
（「关于」里新增了"视频封面"一行：用的是哪条策略、成功多少次、最后一次失败的原因）。

缓存三层：内存 LRU（16MB）→ 磁盘 JPEG（`cacheDir/video_posters`）→ 重新提取；
并发用信号量限制为 2，避免一屏视频同时解码把 CPU 打满。

> 仍然要说清楚：**`MediaMetadataRetriever` 的真实解码行为只有真机能验**。
> 第 1、2 条路径的数据面（鉴权、Range 206 响应、分块缓存）已由
> `VideoPosterTest` 打真实服务端验证过 3 项；但"解码器认不认这个数据源"
> 只能靠真机 + 设置页那行诊断来确认。

### 9.3 媒体库：图片与视频彻底分开 + 标签双排多选（v2.1.2）

**四个改动**：

1. **底部导航那个"永远亮着的小红点"去掉了**。
   它原本是把"媒体总数"当徽标挂在图片库上（`BottomNavItem(badge = media.size)`），
   于是只要库里有东西就恒亮 —— 那不是"有新内容"的意思，纯粹是误导。
2. **新增视频库页面**，与图片库完全分开：
   - 底部导航改为 总览 / 图片库 / 视频库 / 标签 / 设置（5 项）；
     「视频提取」入口移到视频库右上角（总览里也仍然有）；
   - 图片库只出图片、视频库只出视频，**回收站保持图文混排**（它本来就是一份独立列表）。
3. **标签区做成两排，且可多选**：
   - 上排 = **人名**（人物标签），下排 = **其他标签**，两排各自独立多选；
   - 每排第一个「不限」清空该排；上方实时显示"已选 N 个标签 · 同时满足 · M 项"，
     并给一键清空；
   - **多个标签之间是「同时满足」**（选「人物甲 + 海边」= 既有人物甲又打了海边）。
     这一点在界面上写明了，因为并集和交集的结果差别很大；筛空时也有专门的空状态解释原因。
   - 上下间距同时压紧了（搜索栏 6dp、两排之间 4dp、网格顶部 4dp）。
4. **视频标签自动归位**：标签行只列出**在本库里真的有内容**的标签，数量也按本库统计。
   「只打在视频上的标签」因此只会出现在视频库，点它也会自动跳到视频库
   （`MediaFiltering.libraryFor`）；标签页里的每个标签还会顺带标出"图 N / 视频 M"或"仅视频"。

实现上有一处刻意的选择：**标签筛选放在本地做**，只有搜索与排序发给服务端。
原因是 `/api/images` 既没有分页、也没有多标签参数，而在"不选标签"的默认情况下
本来就一次返回全部 —— 既然如此，多选就没必要每次点击都打一次网络请求。
副作用是切标签变成了瞬时的，而且天然支持任意多个标签。

**防回归**：新增 `MediaLibraryFilterTest`（11 项），把三条最容易搞错的规则钉死：
多选是交集不是并集、图片库不混视频、只打在视频上的标签在图片库里的数量必须是 0。

> 视图层的 `LibraryScreen` 现在接收 `kind: MediaKind`（IMAGE / VIDEO / TRASH）而不是
> `trashMode: Boolean`；查看器翻页用的列表与网格渲染的列表**来自同一个 StateFlow**，
> 所以"看到的"和"滑到的"不会再对不上（以前搜索/筛选后翻页会串）。

### 9.4 缩略帧可自定义、人物方块用真实图片、双击按贝塞尔曲线放大点击点（v2.1.3 / v2.1.4）

**四项改动**（其中第 2 项在 v2.1.4 已回退，见下）：

1. **视频缩略帧位置可自定义**（设置 → 外观 → 视频缩略图取真实帧 下面）。
   默认 0.5 秒（避开片头黑场），可填 0–600 之间的任意秒数（支持小数），
   另给「首帧 / 0.5s / 1s / 3s / 5s」五个快选。
   - 合法性交给 `AppPreferences.parsePosterSeconds`：负数、超上限、空串、`NaN` 一律拒绝，
     界面上会提示"已保留上一次的值"，而不是把非法值偷偷存进去；
   - **缓存键里带上了秒数**（`fluxframe-poster:<秒>:<url>`），所以改了位置之后
     旧封面自动失效，不会继续显示过期帧；改完还会顺手清一次封面磁盘缓存。
2. ~~**底栏的「图片库 / 视频库」直接显示库里的内容**~~ —— **v2.1.4 已改回矢量图标**。
   试过把这两项换成 22dp 圆角缩略图（未选中 alpha 0.55、选中 0.95），
   但实际观感有两个问题：五个图标里三个是线稿、两个是照片，**风格不统一**；
   而且在深色磨砂底上照片会显得脏。底栏现在一律用矢量图标，
   `BottomNavItem` 也去掉了 `thumbnail` 字段，不留"半吊子"的兼容分支。
   （如需"图标 + 背后半透明缩略图"那种折中方案，重新加回一个字段即可。）
3. **标签页的人物方块改用真实图片**（替换掉原来那个通用人脸 SVG）：
   取"该标签下第一个可见媒体"作为封面（在内存里按 `liveImages` 建一次映射，零额外请求）；
   该人物还没有可见媒体时才退回人脸占位。人物详情页的头像同样处理，优先用它最新的那张。
4. **双击放大改为以「点击位置」为焦点，并用三次贝塞尔曲线过渡**：
   手指点在哪儿，那个像素就留在原地（`ZoomMath.focalOffset` 解的是 `p·(1-s)`），
   而不是永远放大正中间。缓动用 `CubicBezierEasing(0.2, 0.9, 0.25, 1)`（起步快、收尾稳）；
   缩放与位移由**同一个 0→1 的进度值**插值出来，两者天然同步，
   不会出现"先放大再挪位"的割裂感。双指捏合时会取消正在跑的双击动画，避免两边抢状态。

**防回归**：新增 `ZoomAndPosterSettingsTest`（12 项），
其中一条是**不变量断言**——"屏幕位置 = 中心 + (点 - 中心)·scale + offset"必须等于点击点本身，
另一条断言缓动曲线单调不减且起步快于线性。

---

## 10. 下一步（建议按顺序）

1. **真机冒烟**：`adb install -r fluxframe-v2.1.4-native-debug.apk`，
   重点看首屏、图片库滚动跟手程度、视频卡片是否出真实帧、全屏查看器、上传流程、
   顶栏收起/状态栏让位、隐藏小白条、以及退到后台时的胶囊/通知。
2. **先看设置页那两行诊断**（v2.1.1 新增）：
   - 「流体云 / 实况通知」下面那行写清 Android 版本、提升权限、系统是否接受提升、渠道重要性；
   - 「关于 → 视频封面」写清用的是哪条取帧策略、成功次数、最后一次失败原因。
   这两行是排查这两个问题的**第一手证据**，比猜有效得多。
3. **实测帧率与功耗**：本机无真机无模拟器，**没有实测数据**。建议在真机上用
   `adb shell dumpsys gfxinfo com.fluxframe.app framestats` 对比"液态玻璃 vs 默认风格"
   在图片库快速滑动时的 jank 百分比，以及 `Battery Historian` 看后台耗电；
   如果液态玻璃仍偏重，第一顺位是把 `GlassTokens.LIQUID_GLASS.tintAlpha` 调高
   （更不透 → 背景副本占比更低）或直接建议用户用「默认」风格。
3. **流体云真机验证**：需要 **Android 16**（ColorOS 16）才可能出卡 ——
   Google 的实况更新（progress-centric notification）是 Android 16 才有的能力，
   Android 15 及以下无论怎么配置都不会有流体云，只能显示常驻进度通知。
   在 16 上触发一次上传，先看通知栏，再看状态栏胶囊 / 流体云。
   若诊断显示"系统接受提升=是"但仍不出卡，那就是厂商对第三方应用的额外策略，
   客户端已经无能为力 —— 这一点文档里如实写了，不做承诺。
4. **Android 16 实况更新的编译期版本**：`compileSdk = 36` 后可以把
   `FluidCloudNotifier` 里的反射换成 `Notification.ProgressStyle` 直接调用
   （AGP 8.6.1 上限是 35，等升级 AGP 一起做）。
5. **上传续传**：目前一次 `analyze` 传完所有文件，中途断网需要重来。
   服务端的 `temp` 目录与 2 小时自动清理已经就绪，可在此基础上做分片续传。
6. **视频海报的服务端方案（可选）**：客户端现在自己取帧（4 条策略兜底），
   如果希望更快更省，可以让服务端用 ffmpeg 为视频生成一张 poster 作为 320 变体，
   客户端一行改回变体地址即可。

# UI 重构交接文档（极光玻璃 / Aurora Glass）

更新于：2026-10-11。上一版记录的是「半成品、工作区未提交」的状态，已经不适用；这一版记录**现在到底做完了什么、验证到哪一步、还剩什么**。

设计方案（必须参照的原始文档）：[`C:\Users\29329\.claude\plans\glistening-yawning-plum.md`](C:/Users/29329/.claude/plans/glistening-yawning-plum.md)，本文不重复里面的令牌表、分页清单与性能守则。

---

## 一、当前状态一句话

- **网页端（client/）**：方案里的页面迁移、覆盖层拆分、组件重做、星系（2D + 3D）绘制升级 **全部完成**，并且用 Chrome DevTools 协议做过真实走查（截图见 `_shots_aurora/`，60+ 张，控制台无 error）。
- **安卓端（android/）**：三层**全部完成**——①主题/玻璃令牌与 `GlassSurface`/`FluidBackdrop`；②`Chrome.kt`/`Common.kt`/`AppShell.kt`；③`ui/screens/*` 全部页面 + `ui/galaxy/GalaxyStage.kt` 星系绘制 + 设置页「相似图索引」卡片 + 版本号 2.9.0(54)。`./gradlew clean assembleDebug` 通过（APK 23.5 MB）。**安卓只做了编译级验证，没有真机/模拟器视觉走查**（原因见第五节）。
- 所有改动都已分批提交；`origin`（GitHub）与 `backup`（本地裸库）双远端推送。**GitHub 偶发连接重置/超时，push 失败时重试即可**。

## 二、网页端做完了什么

结构（App.vue 只留状态与副作用，1067 行 → 约 1000 行纯逻辑，模板全部拆走）：

- `client/src/styles/`：`tokens.css`（设计令牌，含 8 套主题）、`base.css`（按钮/输入/芯片/开关/分段/面板/菜单/弹层/Toast 原语）、`layout.css`（壳层与断点）。旧 `styles.css`、`settings.css` **已删除**。
- `client/src/shell/`：`AppSidebar.vue`（桌面悬浮玻璃导航）、`AppTopbar.vue`（面包屑 + ⌘K/Ctrl+K 搜索 + 用户芯片，已按方案移除两个无动作的图标按钮）、`MobileNav.vue`（手机底部胶囊导航 + 「更多」面板）。
- `client/src/views/`：`OverviewView`、`LibraryView`（图片库/回收站同一组件按模式切换）、`SimilarView`、`TagsView`、`LogsView`、`SettingsView`、`parts/MediaCard.vue`、`shared.ts`。
- `client/src/overlays/`：`ViewerOverlay`（大图查看器 + 相似图抽屉 + 框选工具条 + FLIP 放大）、`ContextMenu`、`TagContextMenu`、`PersonDetailModal`、`ReferencesModal`、`BatchTagModal`、`NewTagModal`、`AddTagModal`、`RemoveTagModal`、`RenameModal`、`AppToast`。
- `client/src/motion.ts`：统一动效（一条 cubic-bezier(.2,.8,.2,1)，150/240/420ms，`data-motion=reduced` 时只淡入）与「从缩略图位置 FLIP 放大」所需的指针记录。
- `client/src/galaxy/`：`engine.ts`（2D）与 `engine3d.ts`（3D）绘制升级，共用 `render-sprites.ts` 的光晕/卡片/标签图集/舞台着色器。**布局、物理、相机数学、事件载荷一行未动**；升级前的对照副本 `__baseline__/` 已删除。
- 组件重做：`ParsePanel`、`DeepseekBar`、`UploadReviewModal`、`TaskDock`、`LoginView`、`TagBrowser`、`SimilarGalaxy`、`SimilarGalaxy3D`、`FluidCanvas`（只调配色参数，像素预算/帧率分档/空闲冻结/后台暂停等性能机制未动）。

字体：`@fontsource/dm-sans`、`dm-mono`、`space-grotesk`，`main.ts` 里按需引入字重；安卓侧 `res/font/` 自带 5 个静态 TTF（SIL OFL）。两端都不依赖远程字体。

## 三、顺手修掉的既有问题（都在这次提交里）

1. **「相似图索引」不可达**：建立索引、阈值滑块、校准原先只存在于被设置覆盖层遮住的内联分支里，网页端没法调相似度阈值。现在并入新的 `SettingsView.vue`，分区导航可以直接跳到「相似图索引」。
2. **查看器滚轮缩放整条路走不通**：`scale = 1` 时滚轮被「上滑拉出相似图」占用，而放大又要求 `scale > 1`，形成死循环（缩放永远进不去）。现在滚轮恢复缩放（以指针为锚点），手机端的触摸上滑拉面板不变，桌面端的「相似图」按钮也还在。
3. **浅色模式存了也白存**：`refreshSettings()` 里写死 `isDark = true`，管理员每次进设置页都被强制切回深色。改为按保存的 `darkMode` 生效（已实测浅色下各页成立）。
4. **服务端设置里的旧路径（数据修复，不是代码）**：仓库从桌面移到了 `D:\我的Vibe Coding\内网图片管理器`，但数据库里 `storageDir` 还指向 `C:/Users/29329/Desktop/...`，导致全站图片/视频 404、只能看到「无法加载预览」。已通过设置接口改回当前路径；`aiModel` 同样指向旧路径，也一并改回（`ffmpeg` 路径本来就是对的）。**这两项是 DB 里的系统设置，换机器/搬目录后都要检查**。

## 四、怎么验证（可复现）

```
npm --prefix client run typecheck && npm --prefix client run build     # 网页
cd android && ./gradlew assembleDebug                                  # 安卓（用 Git Bash 的 ./gradlew）
```

网页真实走查：仓库根目录的 `_shot.mjs`（未入库的一次性工具）会拉起 headless Chrome，用 `.env` 里的管理员账号登录本机 4311 上的服务，逐场景截图到 `_shots_aurora/`：

```
node _shot.mjs desktop overlays interactions flip galaxy themes panels mobile upload cleanupUpload
node _shot.mjs tagops extra longpress
```

覆盖的场景：桌面 1440×900 / 手机 390×844、深色与浅色、八个页面、右键与长按菜单、改名/加标签/移除标签/批量加图/人物详情/参考图库/上传审核、查看器滚轮缩放与相似图抽屉与框选工具条、FLIP 放大、星系 2D/3D/分组列表、Ctrl+K、Esc、减少动效、上传→审核→入库→回收站→永久删除的完整闭环、标签的增删改（建标签 → 加到图 → 从图移除 → 放入/移出人物组 → 删标签，全部用后即清）。

用途提醒：`upload` 场景会真的上传一张 `_test_upload.png`，**必须跟一个 `cleanupUpload`**（脚本里就是这么串的），否则库里会多一张测试图。

## 五、已知限制与未做（如实记录）

1. **安卓没有真机/模拟器走查（最大的验收缺口）**：AVD `fluxframe-test` 在这台机器上极不稳定——无窗口模式卡在 `offline`，窗口模式启动后 1~2 分钟常崩，还会留下 `multiinstance.lock` 让后续启动直接 FATAL；清理锁之后再启仍长时间停在 `offline`。`assembleDebug`（含 `clean assembleDebug`）是唯一可靠的门槛，**视觉仍需在真机上复核**（真机 `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`，debug 包名 `com.fluxframe.app.debug`）。需要重点看的：五种玻璃风格 × 深浅色、空状态线稿插画、确认/输入弹窗、Toast、`FluidCapsule`、星系降级（低端机）、查看器底部操作条与被相似图面板遮挡的关系。
2. **`testDebugUnitTest` 在本机跑不通**：中文路径导致 11 个测试类初始化时 `ClassNotFoundException`，`C:\ff-android` 目录联接也绕不过去（联接解析后路径仍含中文）。要在纯 ASCII 路径下复制一份仓库才能跑。**NewUiSmokeTest 依赖的文案已被刻意保留**：「上传中 · 3 个文件」「已上传 42%」「保存到图片库 · 示例视频」「已保存 1 个文件到图片库 ✓」「去图片库查看」。
3. **本机没有 `ccip_feat.onnx`**（`server/models/embed/`），所以「视觉指纹」索引建不起来：网页端「相似图」页会显示「还没有建立视觉指纹」，设置页的相似图索引卡片会显示「指纹模型缺失」。这是环境缺文件，不是 UI 问题；**标签模式不受影响**（走的是标签相似度），上面的星系截图就是用标签模式拍的。
4. **`content-visibility: auto` 没有采用**：图片库的瀑布流要靠 `<img onload>` 量卡片高度来排行高，`content-visibility` 会让未渲染卡片报出内在尺寸，容易被量错、错位。方案里这条属于性能建议，权衡后不采纳，改用「常量行高代替 getComputedStyle」的既有优化。
5. **主页面之间没有转场动画**：`HorizontalPager` 独占那块 Box，包进 `AnimatedContent` 会重建 pager 状态（属于改路由），非主页面之间的转场已加。
6. **`FluxTopBar.search` 目前无调用点**：接线全局搜索必须动 `screens/`，作为扩展点保留（参数有默认值，现有调用点零影响）。
7. 临时产物：`_shot.mjs`、`_android_shot.py`、`_chrome_profile/`、`_shots_aurora/`、`_shots_android/`、`_ck.txt`（**里面有会话 cookie，别再提交/外传**）都在 `.gitignore` 的 `/_*` 下，不入库；不需要时可以整批删掉。

## 五½、安卓端做完的清单（对应方案「安卓端」一节）

- `ui/theme/`：Color/Theme/GlassTokens 换新配色、排版（DM Sans / Space Grotesk / DM Mono）与圆角，五种玻璃风格参数重设（枚举值不变，DataStore 里已存的值不失效）。
- `ui/glass/`：GlassSurface 顶部高光描边 + 可选辉光；FluidBackdrop 调整光晕分布，性能机制（State 相位只在绘制阶段读、不用 `Modifier.blur`）保留。
- `ui/components/Chrome.kt`：FluxTopBar（大标题 + 等宽副标题 + 玻璃搜索）、FluxBottomBar（单一弹性滑动指示器）、FluidCapsule、FluxToast、确认/文本输入弹窗、GlassTextField、SwitchRow、ClickableRow。
- `ui/components/Common.kt`：SectionTitle、StatTile（等宽大数字 + 辉光）、MediaCard（两段渐变遮罩 + 玻璃角标）、TagChip（双层色点）、EmptyState（线稿插画）、按钮与进度。
- `ui/AppShell.kt`：底栏与非主页面转场（主页面之间仍是 `HorizontalPager` 独占，没加转场动画，见下一节）。
- `ui/screens/*`：总览 bento、图片库吸顶玻璃工具条、标签页人物色环头像卡与 1/2/3 列切换、日志玻璃行表格、视频提取平台胶囊与全不选/反选、查看器顶/底玻璃条、相似图底部 sheet、登录全屏极光 + 居中玻璃卡、设置页分区卡（**新增相似图索引卡片**）。
- `ui/galaxy/GalaxyStage.kt`（新）+ `GalaxyScreen`/`Galaxy3DScreen`：深空舞台、尘埃按深度染色、连线同组同色、预渲染光晕纹理、选中脉冲、缩略图三层玻璃边框 + 标签芯片；低端机/关动效时关脉冲与尘埃；`GalaxyMath.kt`/`Galaxy3DMath.kt` 一行未动。
- 版本：`versionCode 54` / `versionName "2.9.0"`（debug 包带既有的 `-debug` 后缀）。
- **未做**：设置页的左侧分组导航（安卓是单列手机版式，加侧栏会改变结构，这一条是主动取舍）；相似图页跳设置页阈值区块的入口（需要 `AppShell` 给回调，属禁改范围）。

## 六、下一轮建议

1. 安卓：真机上把五种玻璃风格 × 深浅色 × 主要页面过一遍（模拟器在这台机器上不可靠）。
2. 服务器上可以顺手做的：核对 `deploy/data/models/detect` 的人脸/头检测模型（网页端「框选」用得到）；新上传的图需要时在设置页点一次「建立索引」补指纹（当前服务器 128/128 已齐）。
3. 想继续打磨视觉的话，最值得动的是：星系常量区（`DUST_COUNT / DUST_SPREAD / HALO_SCALE / RING_SCALE / CARD_MARGIN`）与 `drawEdges` 里那条相似度 alpha 曲线；以及安卓 `EmptyState` 插画与 `FluidCapsule` 的实机观感。

## 七、服务器部署（2026-10-11 已上线）

- 机器：`100.87.5.50`（SSH `han`），源码在 `/home/han/fluxframe`，容器由 `/home/han/fluxframe/deploy/docker-compose.yml` 起（compose 项目名 `fluxframe`，所以在 1Panel 的 Docker 列表里能看到 `fluxframe-app` / `fluxframe-postgres`；1Panel 自己的 compose 目录是空的，这套不是从面板里编出来的）。数据在 `deploy/data/`（storage / models / logs），数据库是容器内的 PostgreSQL。
- 该机 **有 `ccip_feat.onnx` 与现成索引**：视觉指纹模式可用（128/128 已建指纹、平均每张 23.56 个相似、阈值 35%），2D/3D 星系能看真实聚类；本机因为缺模型只能跑标签模式。
- 部署流程（本轮就是这么做的）：
  1. 本地打包源码：`tar czf _deploy_src.tar.gz --exclude='*/node_modules' --exclude='client/dist' --exclude='server/storage' client server prisma package.json package-lock.json .dockerignore`
  2. 上传：`DEPLOY_HOST=100.87.5.50 DEPLOY_USER=han DEPLOY_PASS=han <bundled-python> deploy/remote.py put _deploy_src.tar.gz /home/han/fluxframe-sync/aurora-src.tar.gz`（**Git Bash 里要加 `MSYS_NO_PATHCONV=1`**，否则 `/home/...` 会被改写成 Windows 路径前缀、传到奇怪的地方去）
  3. 远端解包覆盖 `/home/han/fluxframe`（别碰 `deploy/.env` 与 `deploy/data`），再 `cd deploy && setsid nohup docker compose build app > build.log 2>&1 &`，构建完 `docker compose up -d app`。
  4. 校验：`docker compose ps` 应 healthy，且 `curl -s http://100.87.5.50:4311/ | grep -o 'index-[A-Za-z0-9_-]*\.js'` 与本地 `client/dist/assets/` 的哈希一致 —— 这是「服务器跑的确实是新前端」最直接的证据。
- 账号：服务器数据库里只有 `加菲520`（ADMIN）；`deploy/.env` 的 `ADMIN_USERNAME/PASSWORD` 只在库中没有管理员时用于初始化。走查时建过一个临时管理员 `dsh-verify-temp`（随机密码，按用户要求**未删除**；不需要时 `delete from "User" where username='dsh-verify-temp';`）。
- 走查脚本指向服务器：`SHOT_BASE=http://100.87.5.50:4311 SHOT_OUT=_shots_server SHOT_READONLY=1 SHOT_GALAXY_MODE=visual SHOT_USER=... SHOT_PASS=... node _shot.mjs desktop overlays interactions flip galaxy panels mobile longpress`。`SHOT_READONLY=1` 跳过上传/保存设置/建删标签等写数据的场景；`SHOT_GPU=1` 用真实 GPU 的窗口化 Chrome（窗口放到屏幕外），判断渲染问题必须用这条——headless 的 SwiftShader 会把结论带偏。

## 八、服务器走查抓到的真 bug（已修，`28a49a1`）

**3D 星系星雾有一道竖直缝**：左边雾淡、右边雾浓，随镜头自转来回扫。排查与结论：

- 无头截图有缝 → 先怀疑抓帧撕裂；换真实 GPU 的窗口化 Chrome 仍然有 → 排除抓帧；
- 用「减少动效」让画面静止后**依旧**有缝 → 排除撕裂（静止内容上撕裂不留痕）；
- 读 GL 状态：`viewport=[0,0,1111,559]`、`drawingBuffer=1111×559`、scissor 关闭 → 排除视口/裁剪；
- 落到着色器：`BACKDROP_FS` 用 `atan(ray.x, -ray.z)` 当经度采样两层星雾，x 缩放取的是 0.18 / 0.55 —— **不是 1/2π 的整数倍**。`atan` 在 ±π 处的分支切割两侧因此落到 REPEAT 贴图的不同位置，屏幕上就是那道缝；镜头自转时切割扫过屏幕，所以缝会移动。
- 修复：改成整周期倍数——远层一圈 1 个周期（`×1/2π ≈ 0.15915`）、近层一圈 3 个周期（`×3/2π ≈ 0.47746`），3:1 正是原来 0.55:0.18 想表达的视差比，观感不变。本机拖动镜头转一圈验证：改前有缝、改后干净；服务器重建镜像后同样干净。

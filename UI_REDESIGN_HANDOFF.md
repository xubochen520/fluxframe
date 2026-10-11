# UI 重构交接文档

状态：**半成品，工作区未提交**。因后台子任务额度与模型网关两次中断，这次会话先停在这里。

## 任务背景

用户要求：完全重构网页端与安卓端 UI，保留所有现有功能，风格要「优美、优雅、惊艳」。

已批准的设计方案见：[`C:\Users\29329\.claude\plans\glistening-yawning-plum.md`](C:/Users/29329/.claude/plans/glistening-yawning-plum.md)（极光玻璃 / Aurora Glass）。**继续这项工作前必须先读这个文件**，里面有设计令牌表、分页改造清单、功能保留清单、性能守则和验证步骤，本文档不重复这些内容，只说当前进度和下一步。

两个已确认的设计决定（不要再问用户）：
- 字体：网页与安卓都自托管（不依赖 Google Fonts 远程 @import）。
- 星系（相似图 2D/3D）：外观 + 节点渲染升级，布局与物理算法不动。

## 当前 git 状态

分支 `master`，工作区未提交，改动如下（可用 `git status` / `git diff --stat` 复核）：

```
M  android/app/src/main/java/com/fluxframe/app/ui/glass/FluidBackdrop.kt
M  android/app/src/main/java/com/fluxframe/app/ui/glass/GlassSurface.kt
M  android/app/src/main/java/com/fluxframe/app/ui/theme/Color.kt
M  android/app/src/main/java/com/fluxframe/app/ui/theme/GlassTokens.kt
M  android/app/src/main/java/com/fluxframe/app/ui/theme/Theme.kt
M  client/package-lock.json
M  client/package.json
M  client/src/App.vue
M  client/src/galaxy/engine.ts
M  client/src/main.ts
?? android/app/src/main/res/font/            新增字体资源（见下）
?? client/src/galaxy/__baseline__/            星系改造前的备份副本，收尾时需删除
?? client/src/galaxy/render-sprites.ts         星系节点光晕纹理的新辅助文件
?? client/src/motion.ts                        网页动效系统（统一缓动/时长）
?? client/src/overlays/                        目前只有 AppToast.vue
?? client/src/shell/                            AppSidebar.vue / AppTopbar.vue / MobileNav.vue
?? client/src/styles/                           新 tokens.css / base.css / layout.css 等
?? client/src/views/                            目前只有 OverviewView.vue / LibraryView.vue
```

没有任何 commit，没有 push。`.claude/launch.json`（供浏览器预览用，内容是 server/client 两个 dev 命令）已建好但特意排除在 git 之外（写进了 `.git/info/exclude`），不需要处理。

## 已完成并验证通过的部分

**网页端**
- 自托管字体已接入：`@fontsource/dm-sans`、`@fontsource/dm-mono`、`@fontsource/space-grotesk` 已装好并在 `main.ts` 中按需引入指定字重。`styles.css` 第一行的远程 Google Fonts `@import` 需要确认是否已删（见下方「已知问题」）。
- `client/src/styles/`：令牌与基础样式原语已建立。
- `client/src/shell/`：桌面侧栏、顶栏、手机底部导航已拆出独立组件。
- `client/src/motion.ts`：统一动效系统已建立。
- `client/src/views/OverviewView.vue`：总览页已拆出并可渲染。
- `client/src/galaxy/engine.ts` + `render-sprites.ts`：2D 星系渐染已做了外观和节点渲染升级（深空渐变、尘埃、连线透明度、节点光晕纹理、选中脉冲环）。**`engine3d.ts`（3D 星系）还没有改**，`__baseline__/` 里存的是两个引擎改之前的原始副本，供改完 3D 后做性能对比，最后要删除整个 `__baseline__` 目录。
- `npm --prefix client run typecheck` 通过（刚刚重新验证过，exit 0）。
- `npm --prefix client run build` 在上一轮验证中通过过（产物含字体 woff/woff2，CSS 约 81KB、JS 约 421KB，gzip 后分别约 15.6KB / 149.6KB）。

**安卓端**
- `ui/theme/Color.kt`、`Theme.kt`、`GlassTokens.kt`：配色、字体、五种玻璃风格参数已按方案改好。
- `ui/glass/GlassSurface.kt`、`FluidBackdrop.kt`：玻璃描边高光和流体背景已调整，沿用了原有的性能机制（State 相位只在绘制阶段读取、不用 `Modifier.blur`）。
- `android/app/src/main/res/font/`：已放入 5 个静态 TTF（`dm_sans_regular/medium/semibold`、`space_grotesk_semibold`、`dm_mono_medium`，SIL OFL）。
- `cd android && ./gradlew assembleDebug`（用 Git Bash 的 `./gradlew`，不要用 `gradlew.bat` 配 cmd，路径里的中文和空格会出问题）**编译通过**（刚才这轮重跑过，`BUILD SUCCESSFUL`）。

## 还没做的部分

**网页端**
- `client/src/views/LibraryView.vue` 已创建但未核实是否完整（图片库/回收站网格、角标、工具条、加载进度条）。
- `client/src/overlays/` 缺 `ViewerOverlay.vue`（大图查看器）、`ContextMenu.vue`（右键/长按菜单）、`PersonDetailModal.vue`、`ReferencesModal.vue`、`BatchTagModal.vue`、`TagDialogs.vue`、`RenameModal.vue`——只有 `AppToast.vue` 一个文件存在。
- `client/src/views/` 还缺 `SimilarView.vue`、`TagsView.vue`、`LogsView.vue`、`ParseView.vue`、`SettingsView.vue`、`LoginView.vue`（重做登录页样式）。
- `SettingsPanel.vue` 的 `position: fixed` 覆盖层还没有改成壳内 `SettingsView`；App.vue 里被该覆盖层遮住、实际不可达的内联 settings 分支（含「相似图索引」区块：建立索引、阈值滑块、校准）还没删除/迁移——**这是一个需要修复的既有 bug，网页端目前真的没法调相似度阈值**，方案里已经点出来了。
- `ParsePanel.vue`、`DeepseekBar.vue`、`SimilarGalaxy.vue`、`SimilarGalaxy3D.vue`、`UploadReviewModal.vue`、`TaskDock.vue`、`FluidCanvas.vue` 还没有按新视觉重做样式（功能都还是旧的，能用，但没套新设计）。
- `client/src/styles.css`、`client/src/settings.css`（旧文件）还没删除，组件里可能还有引用，接手前先搜一遍确认。
- `client/src/galaxy/engine3d.ts`（3D 星系）还没做外观/节点渲染升级。
- 还没有做过浏览器里的真实走查（截图对比、交互测试），只验证过 typecheck 和 build。

**安卓端**
- `ui/components/Chrome.kt`、`ui/components/Common.kt` **完全没有改动**（这是下一步最大的一块：FluxTopBar、FluxBottomBar、FluidCapsule、FluxToast、各种 Dialog、SectionTitle、StatTile、MediaCard、TagChip、EmptyState 等全部组件）。
- `ui/AppShell.kt` 的底栏和转场动画还没改。
- `ui/screens/` 下所有页面（Overview、Library、Tags、Parse、Deepseek、Similar、Settings、Logs、Auth、Viewer、UploadReview、MediaActions、BatchTag）都还没按新组件重排。
- `ui/screens/GalaxyScreen.kt`、`Galaxy3DScreen.kt` 及 `ui/galaxy/` 下的 Canvas 绘制代码还没做外观升级（`GalaxyMath.kt` 本身不需要动）。
- 设置页的「相似图索引」卡片（对接已有的 `EmbedRepository.backfill/calibration/threshold`）还没加——方案里指出安卓数据层已经有这些接口，只是 UI 没接。
- `app/build.gradle.kts` 的版本号还没改（计划是 versionName "2.9.0"，versionCode 54）。
- `testDebugUnitTest` **在这台机器上跑不通**：11 个测试类全部在初始化阶段报 `ClassNotFoundException`，`build.gradle.kts` 注释里提到的中文路径问题是已知根因；已存在的 `C:\ff-android` 目录联接（junction，指向 `android/` 目录）本身没问题，但 junction 解析后路径依然含中文，测试 worker 照样失败，等于这条路没能绕开。这不是本次改动引入的新问题，但目前没有已验证可行的绕过方法——真要跑单测，可能需要把整个仓库复制一份到纯 ASCII 路径（而不是建软链接/联接），或者在非中文路径的机器上跑。`assembleDebug` 不受影响，一直能过。
- 没有做过真机/模拟器的视觉走查。

## 已知问题与坑

1. **测试网关不稳定**：这次会话里 Sonnet 模型网关出现过多次 `503 No available channel`。重试间隔拉长一点（几分钟）通常能恢复；不是额度问题，不需要充值。
2. **额度**：会话中途额度降到约 $0.38–$0.97 之间，Opus 单次子任务请求预扣约 $1。如果接手时用 Opus 跑大任务，先确认额度够。改用 Sonnet 后单次成本低很多，但质量会稍降，复杂的视觉细节建议人工复核。
3. **安卓单测中文路径问题**：见上一节，目前没有已验证的绕过方案。
4. **并行编辑冲突风险**：如果继续用多个子任务并行改 `App.vue`，必须只用 `Edit`（小片段、唯一匹配）去改，不能用 `Write` 整体覆盖，否则会互相冲掉对方的改动。目前 `App.vue` 的改动量不大（41 行），冲突风险低，但后续还要继续从里面挪模板到 views/overlays，仍需小心。
5. **远程字体 @import 是否已删**：`client/src/App.vue` 和 `main.ts` 已经改了，但没有重新确认 `styles.css` 第一行的 Google Fonts `@import` 是否已经移除——`styles.css` 本身应该整个被替换掉，接手时直接确认这个文件还在不在、有没有被引用。

## 建议的下一步顺序

严格按已批准方案的「执行顺序」走，当前相当于卡在步骤 2（网页逐页迁移）和步骤 3（安卓组件）之间：

1. 网页：先补完 `LibraryView.vue` 的核实，再做 `ViewerOverlay.vue` + `ContextMenu.vue`（这两个涉及最多交互，优先级最高：缩放拖动、左右切换、视频全屏、框选、相似图抽屉都在这里）。
2. 网页：`SettingsView.vue`，顺带修掉「相似图索引不可达」的 bug。
3. 网页：剩余页面（Similar、Tags、Logs、Parse、Login）按方案列的清单做。
4. 网页：`engine3d.ts` 补齐外观升级，然后删除 `galaxy/__baseline__/`、`styles.css`、`settings.css`。
5. 安卓：`Chrome.kt`、`Common.kt`（工作量最大的一块），然后 `AppShell.kt` 底栏，然后各 screens，然后两个星系绘制文件，最后设置页补索引卡片和版本号。
6. 全量验证：按方案「验证」章节的清单过一遍（网页截图对比、交互走查、控制台/网络面板检查；安卓 `assembleDebug` + 人工真机检查）。
7. 确认无误后再 commit——**到目前为止没有任何 commit**，接手者可以自由决定怎么分批提交。

## 本次会话做过的环境准备（不需要重做）

- `.claude/launch.json`：定义了 `server`（`npm run dev:server`，端口 4311）和 `client`（`npm run dev:client`，端口 5173）两个预览配置，已加入 `.git/info/exclude`。
- 确认过：PostgreSQL 在 5432 端口监听（docker-compose 起的），`server/.env` 已配好 `DATABASE_URL`、`PORT`、`ADMIN_USERNAME`、`ADMIN_PASSWORD` 等。登录测试要用 `.env` 里的管理员账号密码，不要写死在代码或文档里。
- `C:\ff-android` 是一个已存在的目录联接，指向 `android/` 目录（不是本次会话建的，Oct 7 就有了）。

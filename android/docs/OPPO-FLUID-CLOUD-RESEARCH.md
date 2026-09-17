# OPPO ColorOS「流体云」(Fluid Cloud) 开放能力 Android 接入技术调研报告

> 调研时间：2026 年（文档中抓取到的官方页面更新时间集中在 2024-10 ~ 2026-09）
> 调研方式：`web_search` / `web_fetch` + 直接调用 OPPO 开放平台文档检索 API + 下载并反解 Maven Central 上的官方 SDK AAR
> 结论可信度分级：
> - ✅ **已确认（有来源）**：有可复现的来源 URL，或直接从官方 SDK 二进制中提取。
> - ⚠️ **不确定/推测**：仅有间接证据、社区说法或作者推断。
> - ❌ **未能确认**：明确说明没找到。

---

## 0. 一句话结论

**流体云的官方 SDK 叫 `SeedlingSupportSDK`，包名命名空间是 `com.oplus.pantanal.seedling`，已公开发布在 Maven Central（`com.oplus.pantanal.card:seedling-support-external:3.0.7`），因此"拿不到 aar 无法编译"这个前提并不成立** —— 真正需要申请的是**服务资质与 `serviceId`（卡片 ID）/ 授权码白名单**，而不是二进制产物。

---

## 1. 官方名称 / 包名 / 类名 / SDK 名称与获取途径

### 1.1 名称体系 ✅

| 项 | 值 | 来源 |
|---|---|---|
| 中文名 | 流体云 | [open.oppomobile.com 流体云](https://open.oppomobile.com/documentation/page/info?id=13270) |
| 英文名 | **Fluid Cloud** | [Aliyun EMAS "OPPO Fluid Cloud Push Guide"](https://help.aliyun.com/en/document_detail/2997310.html)（官方英文文档标题） |
| 上位概念 | **泛在服务 / 泛在卡片**（Ubiquitous Service / Ubiquitous Card，`upk`）；「流体云」是泛在卡片的一种展现形态 | [流体云卡片](https://open.oppomobile.com/documentation/page/info?id=12965) |
| 生态代号 | **潘塔纳尔（Pantanal）** —— 即 Pantanal DevStudio、Pantanal 服务库 | [Pantanal DevStudio](https://open.oppomobile.com/documentation/page/info?id=12718) |
| SDK 名称 | **SeedlingSupportSDK**（泛在接入 SDK） | [SeedlingSupportSDK接入指南](https://open.oppomobile.com/documentation/page/info?id=12719) |

官方原文对"流体云卡片"的定义：

> 从 API2.0 (ColorOS14.0) 开始，泛在服务框架支持流体云开发，流体云作为泛在服务卡片的一种的展现形式，承载用户确定、实时信息的服务形态，例如正在通话中、正在录音中。
> —— [流体云卡片](https://open.oppomobile.com/documentation/page/info?id=12965)

> 流体云是信息的轻量形态，聚焦精炼，并能在不同设备之间自由流动，把系统中的多种交互形式进行了整合和归一，再统一分发，在适当的场景智能显示。
> —— [流体云](https://open.oppomobile.com/documentation/page/info?id=13270)

### 1.2 真实包名与类名 ✅（**从官方 AAR 二进制中直接提取，非推测**）

从 Maven Central 下载 `seedling-support-external-3.0.7.aar` 后解出 `classes.jar`，实测存在以下 `com.oplus.pantanal.seedling.**` 公开类：

```text
# 入口与基类
com.oplus.pantanal.seedling.SeedlingCardWidgetProvider     # 业务方必须继承；收卡片生命周期 + 回传数据
com.oplus.pantanal.seedling.SeedlingCardEventProvider      # 卡片创建失败事件（可选继承）
com.oplus.pantanal.seedling.serviceLayer.BaseSeedlingCardStrategyProvider

# 工具与接口
com.oplus.pantanal.seedling.util.SeedlingTool              # 核心工具类（更新数据 / 发意图 / 能力探测）
com.oplus.pantanal.seedling.util.BaseTool
com.oplus.pantanal.seedling.util.Logger
com.oplus.pantanal.seedling.intent.IIntentManager          # sendSeedling / sendSeedlings / registerResultCallBack
com.oplus.pantanal.seedling.intent.IIntentResultCallBack
com.oplus.pantanal.seedling.update.ISeedlingDataUpdate
com.oplus.pantanal.seedling.update.SeedlingCardOptions     # 卡片可选参数（流体云设置项都走这里）
com.oplus.pantanal.seedling.intelligent.IIntelligent
com.oplus.pantanal.seedling.intelligent.IntelligentData
com.oplus.pantanal.seedling.lifecycle.ISeedlingCardLifecycle
com.oplus.pantanal.seedling.observer.ISeedlingCardObserver
com.oplus.pantanal.seedling.file.FileShareHelper
com.oplus.pantanal.seedling.file.provider.FileShareProvider

# bean
com.oplus.pantanal.seedling.bean.SeedlingCard
com.oplus.pantanal.seedling.bean.SeedlingIntent
com.oplus.pantanal.seedling.bean.SeedlingIntentFlagEnum   # START / END
com.oplus.pantanal.seedling.bean.SeedlingHostEnum
com.oplus.pantanal.seedling.bean.SeedlingCardSizeEnum
com.oplus.pantanal.seedling.bean.SeedlingSubscribeTypeEnum
com.oplus.pantanal.seedling.bean.PanelActionEnum
com.oplus.pantanal.seedling.bean.CancelPanelActionConfigEnum
com.oplus.pantanal.seedling.bean.CardCreateErrorBean
com.oplus.pantanal.seedling.constants.Constants
com.oplus.pantanal.seedling.constants.Constants$FluidCloudSize   # @IntDef 注解
```

**再次强调：以上类名全部来自我实际下载并用类文件字符串表解析出的官方 AAR，不是博客推测。**
（AAR 来源：[Maven Central `com/oplus/pantanal/card/seedling-support-external/3.0.7/`](https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-external/3.0.7/)）

> ⚠️ 官方文档并没有公开提到 `FluidCloudManager` / `FluidCloudTemplate` / `buildFluidCloudData` 这类类名——网上若出现这些名字，属于**命名想象，不是 OPPO 的真实 API**。OPPO 的真实命名是 `Seedling*` / `IntelligentIntent*`。

### 1.3 英文名与「实况通知」的关系 ✅/⚠️

- ColorOS 16（2025-10-15 发布）已**完整接入原生 Android 16 Live Updates API**，OPPO 官方（设计总监陈希）表态采用"双兼容方案"：
  > 流体云从一开始的设计就是开放生态，有完整的开发文档和 API 接口，现在已经是流体云的第四个版本了……最后选择了成本高但最完善的双兼容方案。
  > —— [IT之家：遵循谷歌实时活动开发规范，可直接适配 OPPO ColorOS 16 流体云](https://m.ithome.com/html/890345.htm)（[网易转载](https://www.163.com/dy/article/KC3IMBH30511B8LM.html)）
- "实况通知"在 OPPO 生态中**不是**一个独立 API 名，而是「流体云」+「通知中心卡片」的泛称。官方 SDK 里的 `Constants$FluidCloudSize` 常量把入口固定为 `NOTIFICATION_SM / NOTIFICATION_MD / NOTIFICATION_LG`，其中 `SM` 明确注释为"流体云胶囊形态（包含气泡）"。
- ⚠️ **不确定**：ColorOS 16 是否允许**完全不接入 OPPO 任何 SDK**、仅靠标准 `Notification.ProgressStyle` + `POST_PROMOTED_NOTIFICATIONS` 就点亮流体云胶囊。官方中文文档未明确写入该承诺，仅有高管口径的新闻报道。**上线前必须真机验证。**

### 1.4 SDK 获取途径与资质要求 ✅

**三条并存路径：**

| 路径 | 内容 | 是否需要资质 |
|---|---|---|
| **A. Maven Central（公开可下载）** | `com.oplus.pantanal.card:seedling-support-external:3.0.7`，`implementation` 直接引用 | ✅ **不需要**，任何人可 `gradle` 拉取 |
| **B. 开放平台下载 AAR** | 登录 OPPO 开放平台下载 `SeedlingSupportSDK`（v3.0.7，574 KB），放 `libs/` 后 `implementation fileTree(dir: 'libs', includes: ['*.aar'])` | ⚠️ 需要登录开放平台（个人/企业开发者账号） |
| **C. 官方文档所述的推荐方式** | 同上 B | ⚠️ 见下 |

官方接入指南原文片段（由平台检索索引返回）：

> `bc6c2248a7d2d29174bf24c63732bd68cebe2a67ac0935756b0f5ae96eb12c3b` / `574 KB` / `3.0.7` / `下载`
> `将SDK放在lib文件下`
> `dependencies { implementation fileTree(dir: 'libs', includes: ['*.aar']) }`
> `1、SDK初始化 …… 上述三个版本的SDK，均为自动初始化，不需要额外的初始化操作。`
> —— [SeedlingSupportSDK接入指南](https://open.oppomobile.com/documentation/page/info?id=12719)

**真正卡口：`serviceId`（卡片 ID）与授权码白名单** ✅

> **申请授权码**
> 1、注册企业开发者帐号 —— 点击《开发者认证流程》，认证成为**企业开发者**。
> 2、创建应用 —— 登录 OPPO 开放平台后，选择"管理中心-移动应用列表-创建应用-普通应用"。
> 3、申请卡片服务授权码 —— **开通白名单**：目前潘塔纳尔服务库还处于**定邀测试阶段**，若您需要申请接入，请按照以下模板发送邮件进行申请……
> —— [发布 > 申请授权码](https://open.oppomobile.com/documentation/page/info?id=12716)

> 申请通过后会进入合作伙伴评估……若评估通过平台将会为您开通泛在服务软件管理、潘塔纳尔服务库入口权限，即可进入开放平台进行服务发布。
> **收件人：fwst@oppo.com**（需提供开发者ID、公司名称、联系人、`applicationId` 等）
> —— [发布 > 服务发布](https://open.oppomobile.com/documentation/page/info?id=12715)

> 开始接入前，开发者需明确以下需求信息，并**联系 OPPO 进行确认**：服务形态（流体云：状态栏/通知中心/锁屏；小布建议：桌面卡、负一屏；跨端：车机、手表、耳机）、服务信息（QPS 峰值/均值、单日请求总量、单次履约请求量……）
> —— [简介 > 接入准备](https://open.oppomobile.com/documentation/page/info?id=13572)

> 选择"标准意图接入"；**如果没有该产品，请联系 OPPO 并提供开发者 ID 以便添加白名单**
> —— [接入步骤 > 获取 client_id 和 client_secret](https://open.oppomobile.com/documentation/page/info?id=13559)

> 宿主开发前，**请联系 OPPO 将 upk 发布到服务库中**。
> —— [快速开始](https://open.oppomobile.com/documentation/page/info?id=12639)

**小结（明确区分）：**
- ✅ **二进制/SDK 本身是公开的**（Maven Central 可拉取，Apache-2.0 许可，见 AAR 内 POM）。
- ✅ **企业开发者认证 + 邮件申请白名单 + OPPO 侧分配 `serviceId`/授权码**，是"能真正出卡"的前置条件。个人开发者账号**不足以**完成（文档明确要求"企业开发者帐号"）。
- ⚠️ 审核时长：服务发布文档提到"申请后 T+2 个工作日内留意申请答复"，随后进入"合作伙伴评估"，实际周期不确定。

---

## 2. 接入前置条件

### 2.1 系统 / API 版本要求 ✅

| 要求 | 值 | 来源 |
|---|---|---|
| ColorOS | **ColorOS 15 及以上**（意图共享链路）；泛在卡片链路自 **API 2.0 / ColorOS 14.0** 起支持 | [Aliyun OPPO Fluid Cloud Push Guide](https://help.aliyun.com/en/document_detail/2997310.html)、[流体云卡片](https://open.oppomobile.com/documentation/page/info?id=12965) |
| minSdkVersion | **26**（Android 8.0） | AAR 内 `AndroidManifest.xml`（实测） |
| targetSdk | SDK 3.0.7 已升至 **target 35** | [SDK更新记录](https://open.oppomobile.com/documentation/page/info?id=13331) |
| 语言 | Kotlin（含协程 `kotlinx.coroutines`，`isServiceEnabled` 为 `suspend`） | AAR 类文件实测 |
| 卡片渲染入口 | `notification_sm`（胶囊+气泡）/ `notification_md`（通知大胶囊）/ `notification_lg`（展开面板） | [card-config.json](https://open.oppomobile.com/documentation/page/info?id=12646) |

> 流体云支持 **ColorOS15 及以上**的 OPPO 手机，测试环境需要参考《环境搭建》在客户端搭建。
> —— [Aliyun：OPPO流体云推送指南](https://help.aliyun.com/zh/document_detail/2997310.html)

**胶囊时长限制 ✅**
> 流体云胶囊受系统限制，在 OS15 **最长只能显示 5 分钟**（不同 OS 系统有差异），不受销卡延时控制。
> —— [垂域里程碑](https://open.oppomobile.com/documentation/page/info?id=13568)

### 2.2 测试环境依赖的系统应用 ✅

> 共涉及以下应用：**智慧数据增强服务、智慧决策服务、智慧建议服务、快应用服务框架（可选，若只接入流体云可不安装该应用）**
> 环境配置：清除数据 → 在应用管理找到上述 4 个应用，分别进入应用详情页，点击存储占用，清除数据 → 在桌面找到并打开**智慧建议服务 App，点击测试**
> —— [接入步骤 > 环境搭建](https://open.oppomobile.com/documentation/page/info?id=13590)

### 2.3 自定义权限与 Manifest 声明 ✅（**从 AAR 的 AndroidManifest.xml 实测提取**）

SDK 的 `AndroidManifest.xml`（会由 manifest merger 合入宿主 App）：

```xml
<manifest package="com.oplus.pantanal.seedling">
    <uses-sdk android:minSdkVersion="26"
        tools:overrideLibrary="com.oplus.channel.client, com.oplus.sdk.addon.sdk,
                               com.oplus.utrace.sdk, com.oplus.utrace.lib" />

    <!-- 让业务方 App 在高版本手机上可以查询负一屏等 -->
    <queries>
        <package android:name="com.coloros.assistantscreen" />
        <package android:name="com.oplus.metis" />
        <package android:name="com.oplus.pantanal.ums" />
    </queries>

    <!-- 与大脑通讯需要的权限 -->
    <uses-permission android:name="com.oplus.permission.safe.AI_APP" />
    <uses-permission android:name="com.oplus.metis.factdata.permission.DATABASE" />
    <uses-permission android:name="com.oplus.flashback.permission.FLASH_VIEWS_SERVICE" />

    <application>
        <provider
            android:name="com.oplus.pantanal.seedling.file.provider.FileShareProvider"
            android:authorities="${applicationId}.FileShareProvider"
            android:enabled="true"
            android:exported="false"
            android:grantUriPermissions="true"
            android:readPermission="com.oplus.permission.safe.ASSISTANT" >
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
                       android:resource="@xml/provider_file_paths" />
        </provider>
        <meta-data android:name="cardwidget.support.client.version.code"
                   android:value="2000008" />
    </application>
</manifest>
```

**需要开发者自己在 App 里额外声明的部分** ✅（来自接入指南与 API 文档）：

```xml
<!-- 1) 必须：与泛在卡片通信的 Provider -->
<provider
    android:name="你的 SeedlingCardWidgetProvider 实现类"
    android:authorities="com.xxx.xxx"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.oplus.seedling.action.SEEDLING_CARD" />
    </intent-filter>
</provider>

<!-- 2) 可选：卡片创建失败事件 -->
<!-- authorities 必须为 ${applicationId}.card.event.provider -->
<provider
    android:name="你的 SeedlingCardEventProvider 实现类"
    android:authorities="${applicationId}.card.event.provider"
    ... />
```

> `SeedlingCardEventProvider` 由业务方配置，在 AndroidManifest.xml 文件中进行注册，**authorities 必须配置为 `android:authorities="${applicationId}.card.event.provider"`**。
> —— [SeedlingCardEventProvider](https://open.oppomobile.com/documentation/page/info?id=12694)

> SeedlingCardWidgetProvider 是用于业务方与泛在卡片进行通信的抽象基类，**业务方必须自己去继承实现**。
> —— [SeedlingCardWidgetProvider](https://open.oppomobile.com/documentation/page/info?id=12693)

**意图共享（端侧）路径** 还需要声明 `IntelligentIntent` provider 的可见性（用于 Android 11+ 包可见性）：

```xml
<queries>
    <provider android:authorities="IntelligentIntent" tools:replace="android:authorities" />
</queries>
```
> 端侧 Manifest 注册 —— 见 [接入步骤 > 账号关联（云侧）](https://open.oppomobile.com/documentation/page/info?id=13875) 中的 `端侧Manifest注册` 段落

**⚠️ 注意：** SDK 自带的 `com.oplus.permission.safe.AI_APP`、`com.oplus.metis.factdata.permission.DATABASE`、`com.oplus.flashback.permission.FLASH_VIEWS_SERVICE` 是 **OPPO 系统签名级/系统应用级权限**，三方 App 无法在运行时申请到，作用只是让系统侧识别调用来源；**不要**在业务代码里 `requestPermissions()`。

---

## 3. 核心 API 调用形态

流体云有**两条完全不同的出卡链路**，且**可以互相独立**：

- **链路 A：意图共享（Intent Sharing）** —— 数据驱动，不需要写卡片 UI，OPPO 侧模板渲染。**这是接入流体云最主流、最"轻"的方式。**
- **链路 B：泛在卡片 SeedlingSupportSDK + upk** —— 需要 Pantanal DevStudio 开发 `upk` 卡片包、发布到服务库、宿主 APK 集成 SDK。适用于要自定义布局的桌面卡/负一屏卡，也能覆盖流体云入口。

### 3.1 链路 A-1：端侧意图共享 ✅

> 通过调用该接口向系统 Provider 发送数据，通过 **`ContentProviderClient.call()`** 直接调用系统的 **`IntelligentIntentProvider`**，call 方法声明如下：
>
> | 参数 | 值 |
> |---|---|
> | **authority** | `IntelligentIntent` |
> | **method** | `shareIntent` |
>
> —— [接入步骤 > 出卡 > 意图共享（端侧）](https://open.oppomobile.com/documentation/page/info?id=13558)

从 AAR 中提取到 SDK 内部使用的真实 URI 常量（**这是最硬的证据**）：

```java
// com.oplus.pantanal.seedling.constants.Constants  (实测常量池)
INTENT_PROVIDER_URI = Uri.parse("content://com.oplus.pantanal.ums.IntentProvider");
SWITCH_QUERY_URI    = Uri.parse("content://intelligent_data_expositor/switch");
UMS_QUERY_ADVICE_URI= Uri.parse("content://intelligent_data_expositor/data");
// 另有 content://com.oplus.pantanal.ums.statictis 与
//      content://com.oplus.pantanal.ums.decision/log_switch_status
```

> ⚠️ **两条 URI 的关系不确定**：文档写 authority = `IntelligentIntent`，而 SDK 二进制里是 `content://com.oplus.pantanal.ums.IntentProvider`。推测二者是**同一 Provider 的两种暴露方式**（`IntelligentIntent` 为对外文档化的 authority，`com.oplus.pantanal.ums.IntentProvider` 为 UMS 包内的实现 authority），也可能是新旧版本差异。**建议以官方文档 `IntelligentIntent` 为准做接口，真机用两种都试。**

**开关探测（不需要 SDK 也能做）** —— SDK 内部实现即一次 `ContentResolver.query`：

```java
// 语义还原自 SeedlingTool.isServiceEnabled / isSupportFluidCloud
Cursor c = context.getContentResolver().query(
        Uri.parse("content://intelligent_data_expositor/switch"),  // UMS_QUERY_URI
        null, null, null, null);
// 游标列：code / message / result，其中 result 即 isServiceOn
```

另有系统 Metadata 探针（`com.oplus.pantanal.ums` 的 `ApplicationInfo.metaData`）：

```java
// SeedlingTool 中实测读取的 meta key
"is_seedling_support"        // → isSeedlingCardSupport
"is_system_send_intent_support" // → isSystemSendIntentSupport
"is_fluid_cloud_support"     // → isFluidCloudSupport
```

### 3.2 链路 A-2：云侧意图共享 → 推送接口 ✅

> **测试环境**：`https://oop-openapi-cn.wanyol.com/intent/v1/shareIntent`
> **正式环境**：`https://…`（同一域名体系）
> —— [接入步骤 > 出卡 > 意图共享（云侧）](https://open.oppomobile.com/documentation/page/info?id=13563)

也可通过**移动推送（Push / MassPush）** 远程出卡，关键参数：

| 参数 | 说明 |
|---|---|
| `PushType` | 固定 `"NOTICE"` |
| `AndroidTargetUserType` / `AndroidOppoIntentEnv` | `1`=测试环境，`0`=正式环境 |
| `AndroidOppoIntelligentIntent` | `IntelligentIntent` 的 JSON 字符串，`actionStatus` = `0/1/2` 对应 创建/更新/结束 |
| `AndroidOppoDeleteIntentData` | 强制销卡的数据结构；当 `AndroidOppoIntelligentIntent` 已填时此参数无效 |

—— [Aliyun：OPPO流体云推送指南](https://help.aliyun.com/zh/document_detail/2997310.html)

### 3.3 `IntelligentIntent` 数据结构 ✅

> `IntelligentIntent`：意图共享的基础数据单元，开发者在调用意图共享接口的时候需传入该数据结构共享数据。
> —— [意图共享数据结构](https://open.oppomobile.com/documentation/page/info?id=13565)

| 字段 | 类型 | 必选 | 说明 |
|---|---|---|---|
| `intentName` | String | 是 | 意图名称 |
| `identifier` | String | 是 | 本次共享唯一 id（可用 UUID） |
| `timestamp` | Long | 是 | 毫秒时间戳 |
| `serviceId` | JSON | 是 | 卡片 ID；`{"launcher":"…","fluidCloud":"…"}`，每个入口一个 serviceId |
| `intentAction.actionStatus` | Int | 是 | **0=创建 / 1=更新 / 2=结束** |
| `intentEntity.entityName` | String | 是 | 垂域，见下表 |
| `intentEntity.entityId` | String | 是 | 实体 ID（如订单号），**更新/结束必须与创建一致** |
| `intentEntity.milestone` | JSON | 是 | `{code, text}`，驱动销卡延时/强提醒/语音播报 |
| `intentEntity.capsule` | JSON | 是 | 胶囊：`{leftImg, rightText, legacyImg, legacyText}` |
| `intentEntity.primary` | JSON | 是 | 主信息：`{title:[{text,color,darkColor}], content, rightImg, clickAction, miniImg}` |
| `intentEntity.secondaryData` | JSON | 否 | 扩展（如 `{type:"PROGRESS", progress:20, indicatorImg, style:"inside", nodeLabels:[...]}`） |
| `extra` | JSON | 否 | 业务扩展数据 |
| `isSensitive` | Boolean | 否 | 是否含金额/地理位置等敏感数据 |

**垂域（entityName）枚举 ✅**：`TAXI`（打车）、`DELIVERY`（外卖配送）、`PICKUP`（到店取餐）、`MATCH`（赛事比分）、`NAVIGATION`（驾车导航）、`TASK`（任务）
—— [Aliyun OPPO Fluid Cloud Push Guide](https://help.aliyun.com/en/document_detail/2997310.html)

**5 种样式模板 ✅**：进度可视化、赛事、强调信息、大图形、对称
—— 官方模板页：[进度可视化](https://open.oppomobile.com/documentation/page/info?id=13567)、[赛事](https://open.oppomobile.com/documentation/page/info?id=13569)、[强调信息](https://open.oppomobile.com/documentation/page/info?id=13576)、[大图形](https://open.oppomobile.com/documentation/page/info?id=13579)、[对称](https://open.oppomobile.com/documentation/page/info?id=13557)

**销卡机制 ✅**
> OPPO 识别销卡场景有以下四种途径，**前两种方式不会删除意图实体（intentEntity），销卡后收到相同 entityId 数据推送会重新出卡；后两种方式会删除意图实体**，销卡后收到相同 entityId 数据推送不会再出卡：
> 1. 延时自动销卡（`milestone` 决定延时）
> 2. …（用户划掉）
> 3. / 4. …（显式结束）
> —— [接入步骤 > 销卡](https://open.oppomobile.com/documentation/page/info?id=13578)

**完整请求示例（官方，可直接复用）**：

```java
PushRequest pushRequest = new PushRequest();
pushRequest.setAppKey(appKey);
pushRequest.setPushType("NOTICE");
pushRequest.setDeviceType("ANDROID");
pushRequest.setTarget(target);
pushRequest.setTargetValue(targetValue);
pushRequest.setAndroidTargetUserType(1); // 0 正式环境，1 测试环境

String startIntelligentIntent = """
{
    "intentName": "Example.Progress",
    "identifier": "d71ebd3119877b12ecdb6c4fe96b068e",
    "timestamp": 1729485000989,
    "serviceId": { "launcher": "999800001", "fluidCloud": "999900001" },
    "intentAction": { "actionStatus": 0 },
    "intentEntity": {
        "entityName": "TAXI",
        "entityId": "A580202509130712",
        "milestone": { "code": 30, "text": "vehicle_coming" },
        "capsule": { "leftImg": "https://xxxx", "rightText": "接驾中",
                     "legacyImg": "https://xxxx", "legacyText": "接驾中" },
        "primary": {
            "title": [ { "text": "司机正在赶来", "color": "#00FF00", "darkColor": "#00FF00" } ],
            "content": "粤BDG4251 灰 广汽埃安",
            "rightImg": "https://xxxx", "clickAction": "xxxx", "miniImg": "xxxx"
        },
        "secondaryData": {
            "type": "PROGRESS", "progress": 20, "indicatorImg": "https://xxxx",
            "style": "inside", "nodeLabels": ["接单", "上车点", "目的地"]
        }
    }
}
""";
pushRequest.setAndroidOppoIntelligentIntent(startIntelligentIntent);
PushResponse pushResponse = client.getAcsResponse(pushRequest);
```
> —— [Aliyun：OPPO流体云推送指南（远程创建）](https://help.aliyun.com/zh/document_detail/2997310.html)

### 3.4 链路 B：SeedlingSupportSDK 真实 API ✅

**`SeedlingTool`**（方法名从 AAR 提取）：

```kotlin
object SeedlingTool {
    // 能力探测
    fun isSupportSeedlingCard(context: Context): Boolean
    fun isSupportSystemSendIntent(context: Context): Boolean
    fun isSupportFluidCloud(context: Context): Boolean
    fun isSupportFluidCloud(context: Context, callback: (Boolean) -> Unit)   // 2.0.0+ 重载
    suspend fun isServiceEnabled(context: Context, serviceType: Int): Boolean

    // 数据更新（旧卡片模型）
    fun updateData(card: SeedlingCard, businessData: JSONObject?,
                   cardOptions: SeedlingCardOptions?)
    fun updateData(card: SeedlingCard, businessData: JSONObject?,
                   cardOptions: SeedlingCardOptions?, instanceId: String, instanceIdMatchType: Int)
    fun updateAllCardData(cards: List<SeedlingCard>, businessData: JSONObject?,
                          cardOptions: SeedlingCardOptions?)

    // 意图发送（2.0.8 起的推荐方式）
    fun sendSeedling(context: Context, intent: SeedlingIntent,
                     callBack: IIntentResultCallBack?): Int
    fun sendSeedlings(context: Context, intents: List<SeedlingIntent>): Int

    // 回调注册
    fun registerResultCallBack(context: Context, actions: Array<String>)
    fun unRegisterResultCallBack(context: Context, actions: Array<String>)

    fun getSeedlingCardMap(): HashMap<String, MutableList<SeedlingCard>>

    // 常量
    const val INSTANCE_ID_MATCH_TYPE_WEAK
    const val INSTANCE_ID_MATCH_TYPE_STRONG
    const val DECISION_RESULT_SUCCEED / DECISION_RESULT_FAILED / DECISION_RESULT_REPEATED_ACTION
    const val EVENT_CODE_BUILD_INTENT_DIRECTLY
}
```

**`SeedlingIntent`**（实测构造器参数顺序）：

```java
SeedlingIntent(
    long timestamp,
    String action,
    SeedlingIntentFlagEnum flag,   // START / END
    JSONObject data,
    JSONObject options,
    SeedlingCardOptions cardOptions
)
// 另有：isSupportMultiInstance / setSupportMultiInstance / serviceInstanceId
```

**`SeedlingCard`**（实测字段）：

```java
SeedlingCard(
    String serviceId,      // 泛在卡片服务 id，代表一张卡片
    int cardId,            // 申请泛在卡片时获取的卡片 id（同卡片不同尺寸对应不同 cardId；测试/正式环境不同）
    int cardIndex,         // 卡片序号，默认 1，重复添加时递增
    int hostId,
    SeedlingHostEnum host,
    SeedlingSubscribeTypeEnum subscribeType,
    SeedlingCardSizeEnum size,
    String pageId,
    long upkVersionCode,
    String serviceInstanceId
)
```

**`SeedlingCardOptions`**（实测 15 个字段 —— 官方叫"流体云设置项"）：

```java
SeedlingCardOptions(
    String pageId,                    // 切换卡片页面时使用
    String dataSourcePkgName,
    boolean isMilestone,
    Boolean requestShowPanel,         // 请求展示展开面板
    boolean requestHideStatusBar,
    Integer grade,                    // GRADE_BASE / GRADE_1..GRADE_5
    List<Integer> notificationIdList,
    Map<SeedlingHostEnum, Boolean> showHostMap,
    Map<SeedlingHostEnum, Boolean> lockScreenShowHostMap,
    CancelPanelActionConfigEnum cancelPanelActionConfig,   // @Deprecated，改用 panelActionConfigMap
    Map<PanelActionEnum, CancelPanelActionConfigEnum> panelActionConfigMap,
    Integer controlAction,
    int remindType,                   // REMIND_TYPE_NORMAL / STRONG_SHORT / STRONG_LONG / STRONG_ALWAYS
    boolean shouldFocus,
    Long focusTimestamp,
    Map<String, Object> extensibleActionMap
)
```
> 流体云的设置项都是通过 `SeedlingCardOptions` 来进行设置的，可以在更新数据或者触发卡片的时候，定义 `SeedlingCardOptions` 的参数，达到控制流体云卡片的目的。
> —— [工具 > SeedlingSupportSDK > 流体云设置项](https://open.oppomobile.com/documentation/page/info?id=13330)

**流体云尺寸常量 ✅**

```java
// com.oplus.pantanal.seedling.constants.Constants$FluidCloudSize  (@IntDef)
UNKNOWN = 0 附近
NOTIFICATION_SM   // 流体云胶囊形态（包含气泡）
NOTIFICATION_MD   // 流体云通知大胶囊形态
NOTIFICATION_LG   // 流体云展开面板形态
```

**宿主入口枚举 `SeedlingHostEnum` ✅**

```text
Unknown, Assistant, Launcher, AOD, StatusBar, Notification, LockScreen, Voice,
SecondaryLockScreen, SecondaryNotification, SecondaryLauncher, Watch, CarLauncher,
Calendar, SpeechAssistant, FullSearch, SeedlingHostAPP

// 相关包名
com.coloros.assistantscreen   com.android.systemui   com.oplus.seedling.hostapp
com.heytap.quicksearchbox     com.oplus.aod          com.oplus.ocar
```

**卡片尺寸枚举 `SeedlingCardSizeEnum` ✅**：`Unknown, TwoXTwo(2x2), TwoXFour(2x4), FourXFour(4x4), OneXTwo(1x2), WidgetOneXOne(widget_1x1), NXN`

**调用片段（官方指南风格）**：

```kotlin
class DemoSeedlingCardProvider : SeedlingCardWidgetProvider() {
    override fun onUpdateData(context: Context, card: SeedlingCard, data: Bundle) {
        val businessData = JSONObject().apply {
            put("title", "正在处理")
            put("bgId", "@drawable/bg_img")
        }
        SeedlingTool.updateData(card, businessData, null)   // 3. 更新卡片数据
    }
}
// 流体云使用：通过 SeedlingCardOptions + extensibleActionMap，仅流体云入口支持
val map = hashMapOf<String, Any>(/* ... */)
val cardOptions = SeedlingCardOptions().apply { extensibleActionMap = map }
SeedlingTool.updateData(card, null, cardOptions)
```
> —— [SeedlingSupportSDK接入指南](https://open.oppomobile.com/documentation/page/info?id=12719)、[流体云设置项](https://open.oppomobile.com/documentation/page/info?id=13330)

**⚠️ 重要限制（实测 `SeedlingTool` 日志字符串）：**
```text
metis do not support deal with businessData or seedlingCardOptions when eventCode is not 20104
```
即 `updateIntelligentData` 只在特定 `eventCode`（20104）下才处理数据 —— 这属于内部实现细节，**不要在业务里硬编码依赖这个 eventCode**。

---

## 4. Maven 坐标 vs 手动集成 AAR

### 4.1 结论 ✅：**公开 Maven 坐标存在，且官方 AAR 就在 Maven Central**

| 坐标 | 版本 | 大小 | 说明 |
|---|---|---|---|
| `com.oplus.pantanal.card:seedling-support-external:3.0.7` | 3.0.7（另有 3.0.4 / 2.0.13 / 2.0.3） | 587,800 B（≈574 KB，与官方文档标注完全一致） | 完整版 |
| `com.oplus.pantanal.card:seedling-support-liteQuick:3.0.7` | 3.0.7 | 289,795 B（≈283 KB） | 轻量/快应用变体 |

```groovy
// settings.gradle / build.gradle
repositories { mavenCentral() }   // 无需额外私服

dependencies {
    implementation 'com.oplus.pantanal.card:seedling-support-external:3.0.7'
}
```

- 仓库直链：<https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-external/>
- 元数据：<https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-external/maven-metadata.xml>
- 最新版本 `3.0.7`，`lastUpdated 20250218091720`
- POM 声明：`licenses = Apache License 2.0`，`developer = OPPO-OpenPlatform <devkefu@oppo.com>`
- ⚠️ **POM 无任何 `<dependencies>`**，即 SDK 把 `com.oplus.channel.client`、`com.oplus.sdk.addon.sdk`、`com.oplus.utrace.*`、`kotlinx-coroutines` 等都**内置打包**了（这解释了 587 KB 的体积）。若与其它 OPPO 能力 SDK（如 `com.oplus.ocs:*`）同时接入，注意 `tools:overrideLibrary` 与类冲突。

### 4.2 ⚠️ 官方文档口径 vs 实际可获取性

- 官方《SeedlingSupportSDK接入指南》**仍写的是手动 AAR**（下载 → `libs/` → `implementation fileTree`），**没有在文档里给出 Maven 坐标**。这是一个**文档滞后**现象：Maven Central 上的制品是真实存在的官方发布物（有 OPPO 开发者署名与 Apache-2.0 许可、版本号与 `SDK更新记录` 的 v3.0.7 完全对应、体积与文档标注 574 KB 吻合）。
- ❌ **没有找到** `com.oppo.fluidcloud:xxx` 这类坐标（Maven Central `fluidcloud` 关键词 0 命中）。
- ⚠️ `SeedlingSupportSDK` 的**源码/javadoc 未发布**（Maven Central 上只有 `.aar` + `.pom` + 签名，无 `-sources.jar`），因此 IDE 里只有字节码，**没有内联文档**。

### 4.3 关键区分 ✅

> **"能编译" 与 "能出卡" 是两件事。**
> - 依赖能不能拉下来 → **完全没问题**（Maven Central 公开）。
> - 卡片能不能真的出现在流体云上 → 需要 App 的 `applicationId` 在 OPPO 白名单里、拿到 `serviceId`/`cardId`/授权码，且设备上"意图共享特性开关"打开。

---

## 5. 替代方案 / 降级方案对比

### 5.1 Android 16 原生：`Notification.ProgressStyle` + Live Updates ✅

这是**唯一不依赖任何厂商 SDK 的标准化路径**。

> Android 16 introduces progress-centric notifications…
> `Notification.ProgressStyle` is a new notification style… Key use cases include **rideshare, delivery, and navigation**. Within the `Notification.ProgressStyle` class, you can denote states and milestones in a user journey using **points** and **segments**.
> —— [Progress-centric notifications | Android Developers](https://developer.android.com/about/versions/16/features/progress-centric-notifications)

```kotlin
val ps = Notification.ProgressStyle()
    .setStyledByProgress(false)
    .setProgress(456)
    .setProgressTrackerIcon(Icon.createWithResource(appContext, R.drawable.ic_car_red))
    .setProgressSegments(listOf(
        Notification.ProgressStyle.Segment(41).setColor(Color.BLACK),
        Notification.ProgressStyle.Segment(552).setColor(Color.YELLOW),
        Notification.ProgressStyle.Segment(253).setColor(Color.WHITE),
        Notification.ProgressStyle.Segment(94).setColor(Color.BLUE)))
    .setProgressPoints(listOf(
        Notification.ProgressStyle.Point(60).setColor(Color.RED),
        Notification.ProgressStyle.Point(560).setColor(Color.GREEN)))
```

**成为 Live Update 的硬性条件 ✅**（[创建实时更新通知](https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=zh-cn)）：

| # | 要求 |
|---|---|
| 1 | 样式必须是 `BigTextStyle` / `CallStyle` / **`ProgressStyle`** / `MetricStyle` |
| 2 | 必须在 Manifest 声明 **`android.permission.POST_PROMOTED_NOTIFICATIONS`**（非运行时权限） |
| 3 | 必须用 `EXTRA_REQUEST_PROMOTED_ONGOING` 或 `NotificationCompat.Builder#setRequestPromotedOngoing` 请求提升 |
| 4 | 必须 `ongoing`（`FLAG_ONGOING_EVENT`） |
| 5 | 必须设置 `contentTitle` |
| 6 | **不得设置 `customContentView`（即不允许 RemoteViews）** |
| 7 | 不得是 `setGroupSummary` 的组摘要 |
| 8 | 不得 `setColorized(TRUE)` |
| 9 | 通知渠道不得为 `IMPORTANCE_MIN` |

**能力探测 API ✅**：`Notification.FLAG_PROMOTED_ONGOING`、`Notification.hasPromotableCharacteristics()`、`NotificationManager.canPostPromotedNotifications()`、`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`

> **注意**：原始设备制造商 (OEM) 可以强制执行有关实时更新资格的其他条件。如需了解详情，请参阅相应文档。
> —— [创建实时更新通知](https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=zh-cn)

**ColorOS 16 与 Live Updates 的关系 ⚠️**

- ✅ 已确认：ColorOS 16 **完整接入了** Android 16 Live Updates API，遵循谷歌规范的 App "能直接打通适配 OPPO 的流体云"（[IT之家报道](https://m.ithome.com/html/890345.htm)）。
- ⚠️ **未确认**：这是否意味着**零 OPPO SDK、零白名单**即可在 ColorOS 16 上显示流体云。OPPO 的"双兼容"表述来自高管采访而非文档；`POST_PROMOTED_NOTIFICATIONS` 是 Android 平台权限，但 OEM 可追加条件（见上方官方 Note）。**这必须实机验证，不能作为唯一依赖。**

### 5.2 各厂商对应能力横向对比 ✅

| 能力 | 厂商 | 客户端 API 形态 | 是否需要申请 | 关键来源 |
|---|---|---|---|---|
| **流体云 Fluid Cloud** | OPPO / 一加 / realme | ①`ContentProviderClient.call(authority="IntelligentIntent", method="shareIntent")`<br>②`SeedlingSupportSDK`（`com.oplus.pantanal.seedling`）<br>③云侧 REST / 推送 | **是**：企业开发者 + 邮件白名单 + `serviceId` 分配 | [Aliyun](https://help.aliyun.com/zh/document_detail/2997310.html)、[意图共享（端侧）](https://open.oppomobile.com/documentation/page/info?id=13558) |
| **焦点通知** | 小米 HyperOS | `content://miui.statusbar.notification.public` 查权限；通知 `extras` 带 `miui.focus.param`；或 MiPush | **是**：邮件申请至 **`mipush-permission@xiaomi.com`** | [小米澎湃OS 常见Q&A](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146) |
| **原子岛 / 原子通知** | vivo OriginOS | **标准 Android Notification API** + `Bundle` extras：`notification.superx.operation`(0/1/2)、`notification.superx.template`、`notification.superx.scene` | **是**：vivo 开放平台申请 + 邮件；且**当前为公测** | [Aliyun: vivo Atomic Island](https://help.aliyun.com/zh/document_detail/3030718.html)、[vivo 原子通知接入](https://dev.vivo.com.cn/documentCenter/doc/894) |
| **实况窗 / 实况通知** | 华为 HarmonyOS / EMUI | 标准 `Notification` + `addExtras(Bundle)`：`notification.live.operation`(0/1/2)、`notification.live.event`、`notification.live.capsule*`、`notification.live.feature.*` | **是**：AppGallery Connect 申请权限 | [Aliyun: Huawei Live Window](https://help.aliyun.com/zh/document_detail/2983768.html) |
| **Live Updates / ProgressStyle** | Google Android 16+ | 标准 `Notification.ProgressStyle` + `POST_PROMOTED_NOTIFICATIONS`，**无厂商 SDK** | **否** | [Android Developers](https://developer.android.com/about/versions/16/features/progress-centric-notifications) |

**小米焦点通知权限查询示例（可直接复用，无需 SDK）** ✅：

```java
public static boolean hasFocusPermission(Context ctx) {
    boolean canShowFocus = false;
    try {
        Uri uri = Uri.parse("content://miui.statusbar.notification.public");
        Bundle extras = new Bundle();
        extras.putString("package", ctx.getPackageName());
        Bundle bundle = ctx.getContentResolver().call(uri, "canShowFocus", null, extras);
        canShowFocus = bundle.getBoolean("canShowFocus", false);
    } catch (Exception e) { }
    return canShowFocus;
}
```
> —— [小米澎湃OS 常见Q&A](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146)

**vivo 原子岛客户端示例（最"标准"的一家，值得抄结构）** ✅：

```java
Bundle b = new Bundle();
b.putInt("notification.superx.operation", 0);          // 0 创建 / 1 更新 / 2 结束
b.putBoolean("notification.superx.showNotify", true);  // 不支持时是否降级为普通通知
b.putInt("notification.superx.template", 1);
b.putParcelable("notification.superx.clickResp", mPendingIntent);
b.putString("notification.superx.scene", "HEALTH_REGISTER");

Bundle baseInfo = new Bundle();
baseInfo.putParcelable("notification.superx.baseInfos.icon", icon);
baseInfo.putCharSequence("notification.superx.baseInfos.title", title);
baseInfo.putCharSequence("notification.superx.baseInfos.content", content);
b.putBundle("notification.superx.baseInfos", baseInfo);

Notification n = new NotificationCompat.Builder(context, channelId)
        .setContentTitle(traditionalNotificationTitle)
        .setContentText(traditionalNotificationContent)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setExtras(b)
        .build();
notificationManager.notify(notificationId, n);
```
> —— [Aliyun: vivo 原子岛推送](https://help.aliyun.com/zh/document_detail/3030718.html)

**关键工程启示 ✅**：
- **vivo / 华为** 走的是「标准 `Notification` + `Bundle` extras」，所以**可以零 SDK 依赖实现**（反射都不需要，只是 `Bundle` 键值）。
- **OPPO（端侧意图共享）** 走 `ContentProvider.call` + JSON，也**可以零 SDK 依赖实现**。
- **小米** 走 extras，同样零 SDK 依赖（但走 MiPush 远程下发时需要 MiPush SDK）。
- 因此：**一个"抽象通知能力层"完全可以把四家都做成零编译依赖的运行时适配器**，OPPO 官方 SDK 只是一个可选的增强路径。

### 5.3 各家时间/频率限制对比 ✅

| 厂商 | 限制 |
|---|---|
| OPPO | 胶囊在 OS15 **最长 5 分钟**；不同 `entityName` 有不同销卡延时 |
| vivo | 单活动更新最快 **10 秒一次**；**2 小时**无更新系统清除；**最长显示 8 小时**；用户手动在通知中心/锁屏划掉后不再显示 |
| 华为 | 事件相关（胶囊按需显示，前台页面时不出胶囊） |
| 小米 | `timeout` 单位 min（默认 720min）；`islandTimeout` 单位 s；`miui.focus.param` payload ≤ 3072 B；单图 ≤ 100 KB；图片 1.78(16:9)~1(1:1)；最多 10 张 |
| Android 16 | 由用户控制；用户关闭后不得重复发布，用 `setDeleteIntent` 感知 |

---

## 6. 拿不到官方 SDK / 无资质时的工程化做法

### 6.1 先纠正一个常见误区 ⚠️

**OPPO 这边"拿不到 SDK"其实不成立**（见 §4）。真正的约束是：
1. 拿不到 **`serviceId`（卡片 ID）** 与授权码 → 即使代码全对，系统也不会出卡；
2. 拿不到 **企业开发者资质 / 白名单** → 服务库发布不了 `upk`。

所以工程上的目标不是"绕过 SDK"，而是：

> **让「无资质/无 serviceId 时」代码能编译、能跑、优雅降级为普通通知；一旦拿到资质与 serviceId，只需配置注入、零代码改动即可点亮流体云。**

### 6.2 可行性分析：哪些路径可以零 SDK 依赖实现 ✅

| OPPO 路径 | 零 SDK 可实现？ | 依据 |
|---|---|---|
| 端侧意图共享（`ContentProviderClient.call` authority=`IntelligentIntent`, method=`shareIntent`） | ✅ **可以**，纯 `ContentResolver.call` + `JSONObject` | [意图共享（端侧）](https://open.oppomobile.com/documentation/page/info?id=13558) |
| 开关探测 | ✅ 可以，`ContentResolver.query` | SDK 内部实现 |
| 云侧 `shareIntent` REST | ✅ 可以，纯 HTTP | [意图共享（云侧）](https://open.oppomobile.com/documentation/page/info?id=13563) |
| `SeedlingSupportSDK` 卡片链路 | ✅ 可以直接依赖 Maven Central 坐标 | §4.1 |
| `upk` 卡片包开发 | ❌ 必须用 Pantanal DevStudio | [Pantanal DevStudio](https://open.oppomobile.com/documentation/page/info?id=12718) |

**→ 结论：最省事的工程路线是「端侧/云侧意图共享」——它对官方 SDK 的编译期依赖为零。**

### 6.3 三种降级/占位技术对比

| 方案 | 可编译 | 编译期依赖 | 类型安全 | 适用 |
|---|---|---|---|---|
| **① compileOnly AAR 占位** | ✅ | 需要一份 stub AAR/JAR | ✅ 强 | 需要直接调用 `SeedlingTool` 等强类型 API，且能自建 stub |
| **② 抽象接口 + 运行时探测**（推荐） | ✅ | 无 | ✅ 强（自有接口） | **厂商无关的能力层**，天然支持降级 |
| **③ 纯反射调用** | ✅ | 无 | ❌ 弱 | 兜底；`ContentProvider.call` 场景其实**不需要反射** |

> **重要澄清 ⚠️**：端侧意图共享走 `ContentResolver.call(authority, method, arg, extras)`，参数是 `String`/`Bundle`/`JSONObject` —— **这本身就是"反射友好"的契约，完全不需要反射**。真正需要反射的场景是：你想调用 `SeedlingTool.isSupportFluidCloud()` 这类**强类型方法但不想引入 AAR**。而既然 AAR 在 Maven Central 上，这个需求已被消除。

### 6.4 推荐架构：`LiveActivityGateway` 抽象层

```
app/
 └─ liveactivity/
     ├─ api/                        ← 纯 Kotlin/Java，零第三方依赖，可编译
     │   ├─ LiveActivityRequest.kt  ← 厂商无关的领域模型
     │   ├─ LiveActivityHandle.kt
     │   ├─ LiveActivityGateway.kt  ← 统一接口（start/update/end/isSupported）
     │   └─ LiveActivityCapability.kt
     ├─ internal/
     │   ├─ dispatcher/             ← 按能力优先级选择 provider
     │   ├─ android16/              ← ProgressStyle + POST_PROMOTED_NOTIFICATIONS（永远可用的兜底）
     │   ├─ oppo/                   ← 端侧意图共享（ContentResolver.call，零依赖）
     │   ├─ xiaomi/                 ← miui.focus.param（零依赖）
     │   ├─ vivo/                   ← notification.superx.*（零依赖）
     │   ├─ huawei/                 ← notification.live.*（零依赖）
     │   └─ oppoSdk/                ← 可选：仅此模块依赖 seedling-support-external
     └─ config/
         └─ LiveActivityConfig.kt   ← serviceId / entityName / 模板 / 环境（远程配置注入）
```

**统一接口（零依赖，可编译）**：

```kotlin
// api/LiveActivityRequest.kt —— 不引用任何厂商类型
data class LiveActivityRequest(
    val localId: String,                 // 本地唯一键（对应 entityId）
    val kind: Kind,                      // TAXI / DELIVERY / PICKUP / MATCH / NAVIGATION / TASK
    val title: String,
    val content: String,
    val progress: Int?,                  // 0..100
    val segments: List<Segment>?,
    val points: List<Point>?,
    val capsuleText: String?,
    val iconUri: String?,
    val clickUri: String?,
    val sensitive: Boolean = false,
) {
    enum class Kind { TAXI, DELIVERY, PICKUP, MATCH, NAVIGATION, TASK }
    data class Segment(val length: Int, val color: Int)
    data class Point(val position: Int, val color: Int)
}

// api/LiveActivityGateway.kt
interface LiveActivityGateway {
    /** 该厂商通道在当前设备 + 当前配置下是否可用（必须无副作用、可频繁调用） */
    fun isSupported(): Boolean
    /** 0=创建 1=更新 2=结束 */
    fun submit(op: Op, request: LiveActivityRequest): Result
    fun cancel(localId: String): Result

    enum class Op { CREATE, UPDATE, END }
    sealed interface Result {
        object Ok : Result
        data class Unsupported(val reason: String) : Result
        data class NeedPermission(val applyTo: String) : Result  // 例如 mipush-permission@xiaomi.com
        data class Failed(val code: Int, val message: String?) : Result
    }
}
```

**OPPO 适配器（零 SDK 依赖，可直接落地）**：

```kotlin
class OppoIntentShareGateway(
    private val context: Context,
    private val config: OppoConfig,      // serviceId.launcher / serviceId.fluidCloud / intentName / 环境
) : LiveActivityGateway {

    // 与实际文档一致的 authority；若真机失败，回退实测 URI（见 §3.1 的双 URI 说明）
    private val authorities = listOf("IntelligentIntent", "com.oplus.pantanal.ums.IntentProvider")
    private val switchUri = Uri.parse("content://intelligent_data_expositor/switch")

    override fun isSupported(): Boolean {
        if (!isOplusDevice()) return false
        // 系统"意图共享"开关探测：query → result / code / message
        return runCatching {
            context.contentResolver.query(switchUri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use false
                val idx = c.getColumnIndex("result")
                idx >= 0 && c.getInt(idx) == 1
            } ?: false
        }.getOrDefault(false)
    }

    override fun submit(op: LiveActivityGateway.Op, request: LiveActivityRequest): LiveActivityGateway.Result {
        if (!isSupported()) return LiveActivityGateway.Result.Unsupported("intent-share switch off")
        val payload = buildIntelligentIntent(op, request)   // 见下方 JSON 结构
        for (auth in authorities) {
            val r = runCatching {
                val extras = Bundle().apply { putString("intent", payload.toString()) }
                context.contentResolver.call(
                    Uri.parse("content://$auth"),
                    "shareIntent",           // 文档规定的 method
                    null,
                    extras
                )
            }
            if (r.isSuccess) return LiveActivityGateway.Result.Ok
        }
        return LiveActivityGateway.Result.Failed(-1, "all authorities failed")
    }
    // …
}
```

**`buildIntelligentIntent` —— 与官方 `IntelligentIntent` 对齐**：

```kotlin
fun buildIntelligentIntent(op: LiveActivityGateway.Op, r: LiveActivityRequest): JSONObject = JSONObject().apply {
    put("intentName", config.intentName)                     // 与 OPPO 约定的固定值
    put("identifier", UUID.randomUUID().toString())
    put("timestamp", System.currentTimeMillis())
    put("serviceId", JSONObject().apply {
        put("launcher", config.serviceIdLauncher)            // 例如 "999800001"
        put("fluidCloud", config.serviceIdFluidCloud)        // 例如 "999900001"
    })
    put("intentAction", JSONObject().put("actionStatus", when (op) {
        LiveActivityGateway.Op.CREATE -> 0
        LiveActivityGateway.Op.UPDATE -> 1
        LiveActivityGateway.Op.END    -> 2
    }))
    put("intentEntity", JSONObject().apply {
        put("entityName", r.kind.name)                       // TAXI/DELIVERY/…
        put("entityId", r.localId)                           // 更新/结束必须一致
        put("milestone", JSONObject().apply {
            put("code", config.milestoneCode)
            put("text", config.milestoneText)
        })
        put("capsule", JSONObject().apply {
            put("leftImg", r.iconUri ?: "")
            put("rightText", r.capsuleText ?: r.title)
            put("legacyImg", r.iconUri ?: "")
            put("legacyText", r.capsuleText ?: r.title)
        })
        put("primary", JSONObject().apply {
            put("title", JSONArray().put(JSONObject().apply {
                put("text", r.title); put("color", "#00FF00"); put("darkColor", "#00FF00")
            }))
            put("content", r.content)
            put("rightImg", r.iconUri ?: "")
            put("clickAction", r.clickUri ?: "")
            put("miniImg", r.iconUri ?: "")
        })
        r.progress?.let { p ->
            put("secondaryData", JSONObject().apply {
                put("type", "PROGRESS")
                put("progress", p)
                put("indicatorImg", r.iconUri ?: "")
                put("style", "inside")
                put("nodeLabels", JSONArray())                // 按模板填
            })
        }
    })
    put("isSensitive", r.sensitive)
}
```

**Android 16 兜底适配器（永远可用，不再依赖任何厂商）**：

```kotlin
@RequiresApi(Build.VERSION_CODES.BEYOND_BETA) // Android 16 / API 36
class Android16ProgressGateway(private val context: Context) : LiveActivityGateway {

    override fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.canPostPromotedNotifications()
    }

    override fun submit(op: LiveActivityGateway.Op, r: LiveActivityRequest): LiveActivityGateway.Result {
        if (op == LiveActivityGateway.Op.END) {
            NotificationManagerCompat.from(context).cancel(r.localId.hashCode())
            return LiveActivityGateway.Result.Ok
        }
        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(r.progress ?: 0)
            .apply {
                r.iconUri?.let { /* 下载后转 Icon */ }
                r.segments?.let { segs -> setProgressSegments(segs.map {
                    Notification.ProgressStyle.Segment(it.length).setColor(it.color) }) }
                r.points?.let { pts -> setProgressPoints(pts.map {
                    Notification.ProgressStyle.Point(it.position).setColor(it.color) }) }
            }

        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(r.title)                    // 必填
            .setContentText(r.content)
            .setStyle(style)                             // 必须在允许清单内
            .setOngoing(true)                            // 必须 ongoing
            .setRequestPromotedOngoing(true)             // 必须请求提升
            // 不得 setCustomContentView / 不得 setColorized(true) / 不得 groupSummary
            .build()

        NotificationManagerCompat.from(context).notify(r.localId.hashCode(), n)
        return LiveActivityGateway.Result.Ok
    }
    companion object { const val CHANNEL_ID = "live_updates" } // IMPORTANCE 不得为 MIN
}
```

**Dispatcher：能力优先级 + 降级链**：

```kotlin
class LiveActivityDispatcher(private val gateways: List<LiveActivityGateway>) {
    // 顺序建议：厂商通道（体验最好） → Android 16 原生 → 普通通知降级
    fun best(request: LiveActivityRequest): LiveActivityGateway? =
        gateways.firstOrNull { it.isSupported() }

    fun submit(op: LiveActivityGateway.Op, r: LiveActivityRequest): LiveActivityGateway.Result {
        for (g in gateways) {
            if (!g.isSupported()) continue
            when (val res = g.submit(op, r)) {
                is LiveActivityGateway.Result.Ok -> return res
                is LiveActivityGateway.Result.Unsupported -> continue
                is LiveActivityGateway.Result.NeedPermission -> return res   // 交给上层引导申请
                is LiveActivityGateway.Result.Failed -> continue
            }
        }
        return LiveActivityGateway.Result.Unsupported("no gateway available")
    }
}
```

### 6.5 「未来一键替换为官方 SDK」的落地细节

1. **依赖隔离在单一模块**
   把 `implementation 'com.oplus.pantanal.card:seedling-support-external:3.0.7'` 只放进 `:feature-liveactivity-oppo-sdk` 模块，`api` 模块永不引用厂商类型。将来换版本/去掉依赖只动一个 `build.gradle`。

2. **`compileOnly` 占位做单元测试友好**
   若确实需要强类型调用 `SeedlingTool` 又不想让 CI 拉外网：
   ```groovy
   // 自建 stub：只放方法签名，方法体 throw NotImplementedError
   compileOnly project(':stub:oppo-seedling-stub')
   testImplementation project(':stub:oppo-seedling-stub')
   runtimeOnly 'com.oplus.pantanal.card:seedling-support-external:3.0.7'
   ```
   ⚠️ **注意**：stub 的类/方法签名必须与真实 AAR **逐字段一致**（本报告 §3.4 已给出可从 AAR 校验的清单），否则 `NoSuchMethodError` 只会在真机崩溃时才暴露。

3. **配置全部外置**
   `serviceId.launcher`、`serviceId.fluidCloud`、`intentName`、`milestoneCode/Text`、测试/正式环境开关 —— 全部走远程配置（Firebase Remote Config / 自建配置中心）。**拿到 serviceId 那天不需要发版。**

4. **加一个「厂商能力控制台」调试页**
   展示：设备型号 / ColorOS 版本 / `isSupportFluidCloud` / 意图共享开关 / `canPostPromotedNotifications()` / 当前选中的 gateway / 最近 10 次 `submit` 结果。**这是接入厂商通道时最省时间的一件事**（厂商字段名与语义变化频繁）。

5. **构建期开关**
   ```groovy
   // gradle.properties 可一键切走
   def useOppoOfficialSdk = project.findProperty('useOppoOfficialSdk') == 'true'
   dependencies {
       if (useOppoOfficialSdk) implementation 'com.oplus.pantanal.card:seedling-support-external:3.0.7'
   }
   ```

6. **`tools:replace` 冲突预案**
   SDK manifest 用了 `tools:overrideLibrary`，且需要一个 `${applicationId}.FileShareProvider`。若 App 已有同名/同 authorities 的 FileProvider，会发生 manifest merge 冲突 —— **预留 `tools:replace="android:authorities"` 的改造点**。

7. **降级不是"隐藏功能"，而是"换壳"**
   降级路径应当是：厂商流体云 → Android 16 Live Updates → 普通 `Notification` + 进度条 → 应用内 banner。**领域模型（`LiveActivityRequest`）在四层之间完全复用**，只有 renderer 不同。

### 6.6 ⚠️ 建议的验证顺序（避免白做）

1. **先验证 Android 16 原生路径**在原定目标机型上是否点亮流体云（最高价值、零申请成本）。
2. 同时跑通 **端侧意图共享** 的 `ContentResolver.call`（拿测试用 `serviceId` + 搭好测试环境 4 个系统 App）。
3. 再决定是否引入 `SeedlingSupportSDK`（只有当需要自定义卡片布局 / 桌面卡 / 负一屏卡时才必要）。
4. `upk` 发布（Pantanal DevStudio）**最后做**，因为它必须人工评估。

---

## 7. 明确区分：已确认 vs 不确定

### ✅ 已确认的事实（有来源）

1. 官方英文名 **Fluid Cloud**；上位概念为泛在服务/泛在卡片（`upk`）；生态代号潘塔纳尔（Pantanal）。
2. SDK 名为 **SeedlingSupportSDK**，命名空间 **`com.oplus.pantanal.seedling`**。
3. **Maven 坐标公开可拉取**：`com.oplus.pantanal.card:seedling-support-external:3.0.7`（≈574 KB）与 `…:seedling-support-liteQuick:3.0.7`（≈283 KB），Apache-2.0，OPPO-OpenPlatform 署名。
4. 要求 **ColorOS 15+**；SDK `minSdkVersion=26`；SDK 3.0.7 已升 target 35。
5. SDK manifest 声明的权限：`com.oplus.permission.safe.AI_APP`、`com.oplus.metis.factdata.permission.DATABASE`、`com.oplus.flashback.permission.FLASH_VIEWS_SERVICE`；`<queries>` 声明 `com.coloros.assistantscreen`、`com.oplus.metis`、`com.oplus.pantanal.ums`。
6. 端侧出卡接口：`ContentProviderClient.call(authority="IntelligentIntent", method="shareIntent")`。
7. SDK 实测 URI：`content://com.oplus.pantanal.ums.IntentProvider`、`content://intelligent_data_expositor/switch`、`content://intelligent_data_expositor/data`、`content://com.oplus.pantanal.ums.statictis`、`content://com.oplus.pantanal.ums.decision/log_switch_status`。
8. 云侧接口：测试环境 `https://oop-openapi-cn.wanyol.com/intent/v1/shareIntent`。
9. `actionStatus`：`0/1/2` = 创建/更新/结束。
10. `IntelligentIntent` 字段表与 6 个 `entityName` 垂域、5 种样式模板。
11. 胶囊在 ColorOS 15 上**最长显示 5 分钟**。
12. 需 **企业开发者认证** + 邮件申请白名单（`fwst@oppo.com`）+ OPPO 侧分配 `serviceId`/授权码；潘塔纳尔服务库处于"定邀测试阶段"。
13. `SeedlingTool` / `SeedlingCard` / `SeedlingCardOptions` / `SeedlingIntent` / `FluidCloudSize` / `SeedlingHostEnum` / `SeedlingCardSizeEnum` 的真实签名（见 §3.4）。
14. Android 16 `Notification.ProgressStyle` + `POST_PROMOTED_NOTIFICATIONS` + `setRequestPromotedOngoing` + `ongoing` + 无 `RemoteViews` 等 9 条硬性条件。
15. ColorOS 16 **已接入** Android 16 Live Updates API（多家媒体报道 + OPPO 高管表态）。
16. 小米焦点通知需邮件申请 `mipush-permission@xiaomi.com`；vivo 原子岛当前为公测且需申请；华为实况窗需 AGC 申请。
17. vivo 原子岛走标准 `Notification` + `notification.superx.*` extras；华为实况窗走 `notification.live.*` extras。

### ⚠️ 不确定 / 推测

1. `IntelligentIntent`（文档 authority）与 `content://com.oplus.pantanal.ums.IntentProvider`（SDK 实测）的**确切对应关系**。
2. `shareIntent` 的 **`Bundle` 参数 key 名**（官方页面相关表格在登录墙后未能取全；建议真机抓取或向 OPPO 索取）。
3. ColorOS 16 是否允许**零 OPPO SDK / 零白名单**通过标准 Live Updates 点亮流体云。
4. 各 `entityName` 的**具体销卡延时秒数**与 `milestone.code` 取值表（`垂域里程碑`页面需 OPPO 侧配置）。
5. `SeedlingCardOptions` 各字段的**默认值与取值范围**细节（官方页面在登录墙后）。
6. Maven Central 上的坐标是否为 OPPO **官方长期承诺**的发布渠道（文档未提及）。⚠️ **生产环境建议以开放平台下载的 AAR 为准并自行镜像到内网私服**，避免上游突然撤版。
7. `com.oplus.pantanal.card:seedling-support-external` 与 `liteQuick` 的**功能差异**。
8. 5 种模板各自的完整字段 schema（`progress/milestone/capsule/primary/secondaryData` 的逐模板差异）。
9. `里程碑` 与 `强提醒`、`语音播报` 的联动规则。
10. 个人开发者是否**绝对**无法接入（文档写"企业开发者"，但未明确排除个人）。

### ❌ 未能确认 / 未找到

1. **官方文档正文**：`open.oppomobile.com/oneoppoapi/doc/detail` 与文档树/房间接口**全部需要登录**（返回 `{"code":300005,"message":"登录失效"}`），因此模板页字段表、`垂域里程碑`表、`接入准备`细节无法逐字获取。本报告这些部分依赖**平台自身检索索引返回的正文片段** + **Aliyun EMAS 官方镜像** + **AAR 二进制**，已逐条标注来源。
2. **`com.oppo.fluidcloud:*` 之类的 Maven 坐标** —— 不存在（Maven Central `fluidcloud` 关键词 0 命中）。
3. **真实公开的 OPPO 流体云开源示例仓库** —— GitHub 上只有 Xposed/LSPosed 逆向修改模块（[mouzuan/FluidCloudExtension](https://github.com/mouzuan/FluidCloudExtension)、[com.astraflow.tool](https://github.com/Xposed-Modules-Repo/com.astraflow.tool)），**不是**官方接入示例。其中 `FluidCloudExtension` 的 hook 目标是 `com.oplus.systemui.statusbar.seeding.SeedlingPluginManager`（印证了 `Seedling` 命名），但**不含官方 SDK 用法**。
4. **`SeedlingSupportSDK` 的源码 / javadoc** —— Maven Central 未发布。
5. **端侧 `shareIntent` 的完整参数表** —— 登录墙后，未获取。
6. **`com.oplus.pantanal.ums` 的完整组件清单** —— 仅从社区去膨胀清单确认它是 "Ubiquitous Manager Service, required for Fluid Cloud feature in OxygenOS/ColorOS/RealmeUI"（[UAD issue #449](https://github.com/universal-debloater-alliance/universal-android-debloater-next-generation/issues/449)）。

---

## 8. 工程落地建议

### 8.1 总体策略

> **不要把"接入 OPPO 流体云"当成一个 SDK 集成任务，而要当成一个"通知能力抽象层"的一个 adapter。**

理由：
1. 四家厂商（OPPO/小米/vivo/华为）的客户端 API 有三种形态，但**都可以做到零编译依赖**（`ContentResolver.call` / `Bundle` extras）；
2. 只有 OPPO 有公开 Maven 坐标，其余靠 extras 键值；
3. Android 16 原生 `ProgressStyle` + Live Updates 是**唯一不需要任何申请**的路径，且 ColorOS 16 已兼容；
4. 真正的 blocker 是**商务/资质**（`serviceId`、白名单），而这**必须是运行时可配置的**，不能写死在代码里。

### 8.2 分层设计（4 层，依赖单向）

```
┌─────────────────────────────────────────────┐
│ L4 app / feature                            │  只依赖 L1
├─────────────────────────────────────────────┤
│ L3 adapters (oppo / xiaomi / vivo / hw /    │  只依赖 L1 + Android SDK
│    android16)                               │  ← 例外：oppoSdkAdapter 依赖 AAR
├─────────────────────────────────────────────┤
│ L2 dispatcher + capability probe + 远程配置  │  只依赖 L1
├─────────────────────────────────────────────┤
│ L1 liveactivity-api（领域模型 + 接口）        │  零第三方依赖，永远可编译
└─────────────────────────────────────────────┘
```

**依赖规则（硬约束）**：
- L1 **禁止**出现任何 `com.oplus.*` / `miui.*` / `notification.live.*` 字符串常量以外的东西（键名字符串可以放 L3）。
- L3 各 adapter **互不引用**。
- `:feature-liveactivity-oppo-sdk`（唯一持有 AAR 的模块）**只能被 L2 通过接口注入**，且必须可被 Gradle 属性一键关闭。

### 8.3 关键设计决策

| 决策点 | 建议 | 理由 |
|---|---|---|
| 首选路径 | **Android 16 ProgressStyle 兜底 + 厂商 adapter 增强** | 零申请成本；OEM 追加条件的风险由兜底层吸收 |
| OPPO 首选实现 | **端侧意图共享（`ContentResolver.call`）**，而非 SeedlingSupportSDK | 零编译依赖、零 AAR 升级负担；SDK 只在需要自定义卡片布局时才引入 |
| 配置来源 | 远程配置（`serviceId` / `intentName` / `milestoneCode` / 环境） | 拿到资质当天不发版 |
| 能力探测 | 每次 submit 前 `isSupported()`，结果缓存 TTL（如 30s） | 开关、白名单、系统版本都可能变 |
| 失败处理 | 永远 `catch` 一切（`SecurityException` / `IllegalArgumentException` / Provider 不存在） | 厂商 Provider 缺失/变更会抛异常，不能崩主流程 |
| AAR 版本 | 内网私服镜像 `3.0.7`，不用 `+` / `latest.release` | 上游可能撤版；`3.0.7` 是当前 release |
| 可观测 | 上报 `gateway chosen` / `result` / `device model` / `ColorOS version` | 没有这个，厂商通道问题无法定位 |

### 8.4 落地里程碑

| 阶段 | 交付 | 前置依赖 |
|---|---|---|
| M1 | L1 接口 + L3 Android16 适配器 + L2 dispatcher，跑通本地通知兜底 | 无 |
| M2 | L3 OPPO 端侧意图共享 adapter（`ContentResolver.call`），用测试 `serviceId` 在测试环境验证 | 需要一台 ColorOS 15+ 真机 + 测试 `serviceId` + 4 个测试环境系统 App |
| M3 | L3 小米/vivo/华为 adapter（extras 键值，纯字符串） | 需要一台各家真机做验证（代码零成本，验证成本高） |
| M4 | 商务申请：企业开发者认证 + 邮件 `fwst@oppo.com` 申请白名单与授权码 | 公司资质 |
| M5 | 远程配置注入真实 `serviceId`，灰度上线 | M2 + M4 |
| M6（可选） | 引入 `seedling-support-external`，支持自定义卡片布局 / 桌面卡 / 负一屏卡 | M4 + Pantanal DevStudio 出 `upk` |

### 8.5 一句话结论

**这件事的工程难点不在技术，而在"商务资质 × 多厂商碎片化"的组合。** 正确的做法是：**用一份领域模型 + 一组零依赖的 adapter 覆盖所有厂商，把 OPPO 官方 SDK 降级为可选增强模块，把 `serviceId`/白名单这类只能靠申请获得的东西全部做成远程配置。** 这样无论资质何时到位、ColorOS 16 的 Live Updates 兼容是否如宣传所说，代码都能编译、能上线、能优雅降级，且在拿到资质时零代码改动即可点亮。

---

## 附录 A：本报告的一手取证方法（可复现）

1. **OPPO 文档正文被登录墙挡住**，但开放平台自身的**检索接口**可用，且会返回正文片段：
   ```bash
   curl "https://open.oppomobile.com/newsearch/wiki/index.json?keyword=流体云&page=1&pageSize=20"
   # 返回 { data: { hits: [ { id, title, multilevel_title, content, update_time } ] } }
   # content 即检索索引中的正文片段（就是本报告大量引用的"官方原文"）
   ```
   - 文档树：`POST/GET https://open.oppomobile.com/wiki/new-doc/index.json`
   - 正文详情：`POST https://open.oppomobile.com/oneoppoapi/doc/detail` → ❌ `{"code":300005,"message":"登录失效"}`

2. **SDK 二进制取证**（最硬证据）：
   ```bash
   curl -O https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-external/3.0.7/seedling-support-external-3.0.7.aar
   # aar → zip → AndroidManifest.xml + classes.jar + proguard.txt + R.txt
   # classes.jar → 类文件字符串表即暴露全部公开类名/方法名/常量
   ```

3. **官方英文镜像**（Aliyun EMAS 转载了 OPPO 的接入文档，可读性最好）：
   - [OPPO Fluid Cloud Push Guide (EN)](https://help.aliyun.com/en/document_detail/2997310.html)
   - [OPPO流体云推送指南 (中文)](https://help.aliyun.com/zh/document_detail/2997310.html)
   - [Android Dynamic Island push guide（四家并列索引页）](https://help.aliyun.com/en/document_detail/2983766.html)

## 附录 B：参考链接汇总

**OPPO 官方**
- [流体云（服务介绍）](https://open.oppomobile.com/documentation/page/info?id=13270)
- [流体云卡片（概述）](https://open.oppomobile.com/documentation/page/info?id=12965)
- [流体云卡片设置项 / SeedlingCardOptions](https://open.oppomobile.com/documentation/page/info?id=13330)
- [简介 > 接入说明](https://open.oppomobile.com/documentation/page/info?id=13556)
- [简介 > 接入准备](https://open.oppomobile.com/documentation/page/info?id=13572)
- [接入步骤 > 环境搭建](https://open.oppomobile.com/documentation/page/info?id=13590)
- [接入步骤 > 出卡 > 意图共享（端侧）](https://open.oppomobile.com/documentation/page/info?id=13558)
- [接入步骤 > 出卡 > 意图共享（云侧）](https://open.oppomobile.com/documentation/page/info?id=13563)
- [接入步骤 > 出卡 > 意图共享数据结构](https://open.oppomobile.com/documentation/page/info?id=13565)
- [接入步骤 > 出卡 > 垂域里程碑](https://open.oppomobile.com/documentation/page/info?id=13568)
- [接入步骤 > 销卡](https://open.oppomobile.com/documentation/page/info?id=13578)
- [接入步骤 > 获取 client_id 和 client_secret](https://open.oppomobile.com/documentation/page/info?id=13559)
- [卡片模板：进度可视化](https://open.oppomobile.com/documentation/page/info?id=13567) / [赛事](https://open.oppomobile.com/documentation/page/info?id=13569) / [强调信息](https://open.oppomobile.com/documentation/page/info?id=13576) / [大图形](https://open.oppomobile.com/documentation/page/info?id=13579) / [对称](https://open.oppomobile.com/documentation/page/info?id=13557)
- [工具 > SeedlingSupportSDK > 接入指南](https://open.oppomobile.com/documentation/page/info?id=12719)
- [工具 > SeedlingSupportSDK > SDK更新记录](https://open.oppomobile.com/documentation/page/info?id=13331)
- [API参考 > SeedlingSupportSDK API 目录](https://open.oppomobile.com/documentation/page/info?id=12686)
- [API参考 > SeedlingCard](https://open.oppomobile.com/documentation/page/info?id=12688)
- [API参考 > SeedlingCardWidgetProvider](https://open.oppomobile.com/documentation/page/info?id=12693)
- [API参考 > SeedlingCardEventProvider](https://open.oppomobile.com/documentation/page/info?id=12694)
- [API参考 > SeedlingTool](https://open.oppomobile.com/documentation/page/info?id=12696)
- [组件参考 > 流体云组件](https://open.oppomobile.com/documentation/page/info?id=12703)
- [配置文件 > card-config.json](https://open.oppomobile.com/documentation/page/info?id=12646)
- [工具 > Pantanal DevStudio](https://open.oppomobile.com/documentation/page/info?id=12718)
- [发布 > 申请授权码](https://open.oppomobile.com/documentation/page/info?id=12716)
- [发布 > 服务发布](https://open.oppomobile.com/documentation/page/info?id=12715)
- [账号关联（云侧）（含端侧 Manifest 注册）](https://open.oppomobile.com/documentation/page/info?id=13875)

**SDK 制品**
- [Maven Central: com.oplus.pantanal.card:seedling-support-external](https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-external/)
- [Maven Central: com.oplus.pantanal.card:seedling-support-liteQuick](https://repo1.maven.org/maven2/com/oplus/pantanal/card/seedling-support-liteQuick/)

**第三方镜像 / 报道**
- [Aliyun EMAS: OPPO Fluid Cloud Push Guide (EN)](https://help.aliyun.com/en/document_detail/2997310.html) / [(中文)](https://help.aliyun.com/zh/document_detail/2997310.html)
- [Aliyun EMAS: vivo Atomic Island push](https://help.aliyun.com/zh/document_detail/3030718.html)
- [Aliyun EMAS: Huawei Live Window Push Guide](https://help.aliyun.com/zh/document_detail/2983768.html)
- [小米澎湃OS 超级岛 常见Q&A](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146)
- [IT之家：ColorOS 16 流体云接入 Android 16 Live Updates API](https://m.ithome.com/html/890345.htm)
- [IT之家：ColorOS 16 正式发布](https://www.ithome.com/0/889/567.htm)
- [IT之家：ColorOS 官方答疑 Vol.1 流体云](https://www.ithome.com/0/815/879.htm)
- [ColorOS 15 流体云介绍](https://www.coloros.com/article/A00000075/)
- [UAD issue #449: com.oplus.pantanal.ums](https://github.com/universal-debloater-alliance/universal-android-debloater-next-generation/issues/449)

**Google / Android**
- [Progress-centric notifications (Android 16)](https://developer.android.com/about/versions/16/features/progress-centric-notifications)
- [创建实时更新通知（中文）](https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=zh-cn)

**社区（非官方，仅作命名印证）**
- [mouzuan/FluidCloudExtension](https://github.com/mouzuan/FluidCloudExtension) —— Xposed 模块，hook `com.oplus.systemui.statusbar.seeding.SeedlingPluginManager`
- [Xposed-Modules-Repo/com.astraflow.tool](https://github.com/Xposed-Modules-Repo/com.astraflow.tool) —— OPlus 流体云增强模块

---

## 附录 D：v2.1.0 的实现决定（零配置双链路）

调研结论落地后，实现策略在 v2.1.0 调整为**"主链路 + 并行增强"**，不再二选一：

| | 主链路 | 并行增强 |
|---|---|---|
| 实现 | `SystemLiveUpdateProvider` + `FluidCloudNotifier` | `OppoFluidCloudProvider` |
| 规范 | Android 16 的 progress-centric notification（`Notification.ProgressStyle` + `setRequestPromotedOngoing(true)`）；低版本是常驻进度通知 | OPPO 意图共享（`shareIntent` + `IntelligentIntent`） |
| 可用性 | **一定可用**（只受通知权限与用户的渠道开关影响） | 取决于 ColorOS 是否提供 Provider、流体云开关、以及系统白名单 |
| 用户配置 | 无 | **无**（`serviceId` 改为从 UMS 包 metaData 自动读取，读不到就留空照发） |

这么做的理由：OPPO 那条通道需要系统白名单，**客户端无法确知结果**；
如果做成"二选一"，一旦 OPPO 通道实际上打不通，用户就什么也看不到。
两条同时下发则互不干扰 —— 谁生效由系统决定，失败没有任何副作用。

同时明确两条**不做承诺**的事：

1. `setRequestPromotedOngoing(true)` 只是"申请"，是否真的提升为实况更新由
   `NotificationManager.canPostPromotedNotifications()` 与用户设置决定；
   设置页因此如实显示当前档位，而不是宣称"已接入实况窗"。
2. `compileSdk` 仍是 35（AGP 8.6.1 的上限），API 36 的 `ProgressStyle` 通过**反射**挂载，
   任何一步失败都退回标准进度通知。这不是临时糊弄：反射调用集中在
   `FluidCloudNotifier.buildLiveUpdate()` 一处，且方法名做了候选列表尝试；
   升级到 `compileSdk = 36` 后可以直接换成编译期调用（见 `android/README.md` §9.4）。

截至 v2.1.0，**OPPO 流体云出卡仍未在任何真机上验证过**（本机没有 ColorOS 设备），
因此文档里对它的所有描述都是"按公开协议实现"，不是"已验证可用"。

---

## 附录 E：v2.1.1 —— 为什么 v2.1.0 的流体云完全无效（一手取证）

### 现象
客户端按公开协议实现了 IntentProvider 意图共享 + 标准实况通知两条链路，
但真机上「流体云」什么也不显示，通知栏也没有被提升。

### 取证过程
1. **参考实现对比**：反查开源项目 InstallerX Revived 的
   `app/src/main/java/com/rosan/installer/framework/notification/`。
   - `ModernNotificationBuilder` 标注 `@RequiresApi(Build.VERSION_CODES.BAKLAVA)`（API 36），
     base builder 为 `NotificationCompat.Builder(...).setOngoing(true).setRequestPromotedOngoing(true)`，
     并 `setStyle(NotificationCompat.ProgressStyle().setProgressSegments(...).setStyledByProgress(true))`
     与 `setShortCriticalText(...)`；
   - **它的 `AndroidManifest.xml` 第 5 行是
     `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`**。
2. **AOSP 侧证实**：`frameworks/base/core/res/AndroidManifest.xml`
   的 `android16-qpr1-release` 分支里定义：
   ```xml
   <permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"
       android:protectionLevel="normal|appop"
       android:featureFlag="android.app.ui_rich_ongoing"/>
   ```
   `normal` 说明**声明即授予，不需要运行时申请**；同时说明该权限是
   Android 16 QPR1 才引入的（`android16-release` 分支上还没有）。
3. **反编译 androidx.core 1.17.0**（唯一带 `NotificationCompat.ProgressStyle` 的版本线）：
   - `NotificationCompat$Builder.setRequestPromotedOngoing(boolean)` 的实现
     **只是往 extras 写 `android.requestPromotedOngoing` 一个 boolean**，
     并没有调用平台 `Notification.Builder` 上的同名方法 ——
     也就是说**平台根本没有这个方法**，v2.1.0 里对它做反射必然失败；
   - `ProgressStyle.apply()` 在 `SDK_INT >= 36` 时构造
     `android.app.Notification$ProgressStyle`，依次调用
     `setStyledByProgress` / `setProgress` / `setProgressIndeterminate`
     / `setProgressStartIcon` / `setProgressEndIcon` / `setProgressTrackerIcon`
     / `setProgressPoints` / `setProgressSegments`，最后 `builder.setStyle(ps)`。
4. **依赖可行性**：`androidx.core:core:1.17.0` 的 `aar-metadata.properties` 写着
   `minCompileSdk=36`、`minAndroidGradlePluginVersion=8.9.1`，
   而本工程是 `compileSdk 35` + AGP 8.6.1 → 无法直接使用，只能继续反射。

### 结论（三个必要条件，缺一不可）
| # | 条件 | v2.1.0 | v2.1.1 |
|---|---|---|---|
| 1 | 清单声明 `POST_PROMOTED_NOTIFICATIONS` | ❌ | ✅ |
| 2 | extras 写 `android.requestPromotedOngoing=true` | ❌（反射了不存在的方法） | ✅ |
| 3 | 渠道重要性足够（低重要性会被排除在提升之外） | ❌ `IMPORTANCE_LOW` | ✅ 专用 `IMPORTANCE_HIGH` 渠道，无声无震动 |

### 仍未验证的部分（不夸大）
- **Android 16.0 上该权限尚未定义**，因此"声明了但授不到"是正常现象；
  诊断文案已经区分"缺失"与"已声明未授予"，不会误导排查方向。
- 即使 `canPostPromotedNotifications()` 返回 true，**ColorOS 是否真的把第三方应用的
  标准实况更新渲染成流体云胶囊，只有真机能确认**。本机没有 ColorOS 设备，
  因此这一条依然是"按规范实现"，不是"已验证可用"。
- OPPO 私有的意图共享链路（需要白名单 serviceId）依旧保持"自动尝试、失败无副作用"，
  不作为唯一依赖。

package com.fluxframe.app.fluidcloud

import android.content.ContentProviderClient
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OPPO ColorOS「流体云」适配器 —— **意图共享（端侧）链路**。
 *
 * ### 为什么是这条链路
 * OPPO 流体云的开放方式有两类：
 *  - **链路 A 意图共享**：直接调系统 Provider，`ContentProviderClient.call` +
 *    `IntelligentIntent` JSON。**不需要把任何 SDK 打进包**；
 *  - 链路 B 泛在卡片（SeedlingSupportSDK / 潘塔纳尔 upk）：需要先在 Pantanal
 *    DevStudio 开发卡片包、发布到 OPPO 服务库，再拿 `serviceId` / `cardId`，
 *    属于重资产接入。
 *
 * 本项目走链路 A。
 *
 * ### 关于 `serviceId`（重要）
 * OPPO 的规范里 `serviceId` 必须由 OPPO 分配给已认证的企业开发者。**本应用不去
 * 要求用户填写它**：优先从系统 UMS 包（`com.oplus.pantanal.ums`）的 metaData 里
 * 自动读取，读不到就留空照发 —— 部分 ColorOS 版本对系统级任务垂域并不校验它。
 * 是否真的出卡由系统的白名单决定，出不了卡时**没有任何副作用**，
 * 标准链路（[SystemLiveUpdateProvider]）照常工作。
 *
 * ### 真实的协议常量（来自 OPPO 官方文档与 SDK AAR 二进制取证）
 * | 项 | 值 |
 * |---|---|
 * | authority | `IntelligentIntent`（对外文档）／`com.oplus.pantanal.ums.IntentProvider`（SDK 内部） |
 * | method | `shareIntent` |
 * | 开关探测 URI | `content://intelligent_data_expositor/switch`（游标列 `result`） |
 * | 动作 | `intentAction.actionStatus`：**0 创建 / 1 更新 / 2 结束** |
 * | 垂域 | `TAXI` / `DELIVERY` / `PICKUP` / `MATCH` / `NAVIGATION` / `TASK` |
 *
 * 另注：网上流传的 `FluidCloudManager` / `FluidCloudTemplate` / `buildFluidCloudData`
 * **不是 OPPO 的真实 API**，本实现不使用它们。
 */
class OppoFluidCloudProvider(private val context: Context) : FluidCloudProvider {

    override val backend: FluidCloudBackend = FluidCloudBackend.OPPO_FLUID_CLOUD

    /** 供设置页展示的探测结论（面向用户，不出现"请填写 xx"这类要求） */
    var diagnostic: String = "未探测"
        private set

    private var serviceSwitchOn: Boolean? = null
    private var metaFluidCloudSupport: Boolean? = null
    private var providerDetected: Boolean = false

    /** 从系统 metaData 自动读到的服务号；读不到就是空串 */
    private var serviceId: String = ""

    /** 本次会话共享出去的 entityId：更新/结束必须与创建时一致 */
    private var currentEntityId: String? = null
    private var created = false

    init {
        probe()
    }

    /* ============================ 能力探测 ============================ */

    private fun probe() {
        if (!isOppoFamily()) {
            diagnostic = "非 OPPO / 一加 / realme 设备，流体云不适用（已使用标准实况通知）"
            return
        }
        readUmsMetadata()
        providerDetected = detectIntentProvider()
        serviceSwitchOn = queryServiceSwitch()
        diagnostic = buildString {
            append("ColorOS 流体云：系统能力=")
            append(
                when (metaFluidCloudSupport) {
                    true -> "支持"
                    false -> "不支持"
                    null -> "未知"
                },
            )
            append("，流体云开关=")
            append(
                when (serviceSwitchOn) {
                    true -> "已开启"
                    false -> "未开启"
                    null -> "未探测到"
                },
            )
            append(if (serviceId.isNotBlank()) "；已自动获取服务号" else "；将由系统按默认规则处理")
            append(if (providerDetected) "；意图 Provider 可达" else "；未发现可投递的意图 Provider")
        }
        Log.i(TAG, diagnostic)
    }

    private fun isOppoFamily(): Boolean {
        val brand = (Build.BRAND + Build.MANUFACTURER).lowercase()
        if (brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) return true
        val rom = (Build.DISPLAY + Build.VERSION.INCREMENTAL + Build.PRODUCT).lowercase()
        return rom.contains("coloros") || rom.contains("oplus") || rom.contains("realme")
    }

    /**
     * 读取 UMS 包（com.oplus.pantanal.ums）的 metaData：
     * 既拿能力开关，也顺手看看系统有没有把服务号公开出来 —— 有就用，没有不追问用户。
     */
    private fun readUmsMetadata() {
        runCatching {
            val info = context.packageManager.getApplicationInfo(UMS_PACKAGE, PackageManager.GET_META_DATA)
            val meta = info.metaData ?: return@runCatching
            metaFluidCloudSupport = when {
                meta.containsKey("is_fluid_cloud_support") -> meta.getBoolean("is_fluid_cloud_support")
                meta.containsKey("is_seedling_support") -> meta.getBoolean("is_seedling_support")
                meta.containsKey("is_system_send_intent_support") -> meta.getBoolean("is_system_send_intent_support")
                else -> null
            }
            serviceId = SERVICE_ID_KEYS
                .firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.isNotBlank() } }
                .orEmpty()
        }
    }

    /** 开关探测：一次 ContentResolver.query，游标列 code / message / result */
    private fun queryServiceSwitch(): Boolean? = runCatching {
        context.contentResolver.query(SWITCH_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex("result")
            if (index < 0) null else cursor.getInt(index) == 1
        }
    }.getOrNull()

    private fun detectIntentProvider(): Boolean = AUTHORITIES.any { authority ->
        runCatching {
            val client = context.contentResolver.acquireUnstableContentProviderClient(
                Uri.parse("content://$authority"),
            ) ?: return@runCatching false
            client.close()
            true
        }.getOrDefault(false)
    }

    override fun isAvailable(): Boolean =
        isOppoFamily() && providerDetected && serviceSwitchOn != false && metaFluidCloudSupport != false

    /* ============================ 状态下发 ============================ */

    override fun publish(state: CapsuleState) {
        if (!isAvailable()) return
        if (!created || currentEntityId == null) {
            currentEntityId = "fluxframe-" + java.util.UUID.randomUUID().toString().take(8)
            created = true
            send(state, actionStatus = ACTION_CREATE, entityId = currentEntityId!!)
        } else {
            send(state, actionStatus = ACTION_UPDATE, entityId = currentEntityId!!)
        }
    }

    override fun finish(state: CapsuleState) {
        val entityId = currentEntityId
        if (isAvailable() && entityId != null) {
            send(state, actionStatus = ACTION_END, entityId = entityId)
        }
        created = false
        currentEntityId = null
    }

    override fun cancel() {
        val entityId = currentEntityId
        if (isAvailable() && entityId != null) {
            send(
                CapsuleState(active = false, title = "fluxframe", subtitle = "已取消"),
                actionStatus = ACTION_END,
                entityId = entityId,
            )
        }
        created = false
        currentEntityId = null
    }

    /**
     * 真正下发一次意图共享。
     * 先试文档 authority，再试 SDK 内部 authority；`Bundle` 的 key 名做多候选尝试。
     */
    private fun send(state: CapsuleState, actionStatus: Int, entityId: String) {
        val payload = buildIntelligentIntent(state, actionStatus, entityId)
        var delivered = false

        for (authority in AUTHORITIES) {
            for (key in BUNDLE_KEYS) {
                val extras = Bundle().apply {
                    putString(key, payload)
                    putString("intentAction", actionStatus.toString())
                }
                if (callProvider(Uri.parse("content://$authority"), extras)) {
                    delivered = true
                    break
                }
            }
            if (delivered) break
        }

        if (!delivered) {
            Log.w(TAG, "意图共享未成功：系统未提供 IntelligentIntent Provider（需要 ColorOS 15+ 且已开通流体云）")
        }
    }

    private fun callProvider(uri: Uri, extras: Bundle): Boolean {
        // 方式一：ContentProviderClient.call（官方文档写法）
        val viaClient = runCatching {
            val client: ContentProviderClient =
                context.contentResolver.acquireContentProviderClient(uri) ?: return@runCatching false
            client.use { provider ->
                provider.call(METHOD_SHARE_INTENT, null, extras)
                true
            }
        }.getOrDefault(false)
        if (viaClient) return true

        // 方式二：ContentResolver.call（等价、兼容性更好）
        return runCatching {
            context.contentResolver.call(uri, METHOD_SHARE_INTENT, null, extras)
            true
        }.getOrDefault(false)
    }

    /* ====================== IntelligentIntent 构造 ====================== */

    /**
     * 构造官方 `IntelligentIntent` JSON。
     *
     * 垂域固定用 `TASK`（任务）—— 上传/下载/提取在语义上就属于「任务」，
     * 而 `secondaryData.type = PROGRESS` 正好对应官方「进度可视化」模板。
     */
    private fun buildIntelligentIntent(
        state: CapsuleState,
        actionStatus: Int,
        entityId: String,
    ): String {
        val percent = state.progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: 0
        val accent = when (state.tone) {
            CapsuleTone.SUCCESS -> "#22C55E"
            CapsuleTone.ERROR -> "#EF4444"
            CapsuleTone.INFO -> "#6366F1"
        }
        val json: JsonObject = buildJsonObject {
            put("intentName", "FluxFrame.Transfer")
            put("identifier", java.util.UUID.randomUUID().toString().replace("-", ""))
            put("timestamp", System.currentTimeMillis())
            put("serviceId", buildJsonObject {
                put("launcher", serviceId)
                put("fluidCloud", serviceId)
            })
            put("intentAction", buildJsonObject { put("actionStatus", actionStatus) })
            put("intentEntity", buildJsonObject {
                put("entityName", ENTITY_NAME)
                put("entityId", entityId)
                // milestone 决定销卡延时与提醒强度；具体 code 取值官方未公开，取 1
                put("milestone", buildJsonObject {
                    put("code", 1)
                    put("text", if (state.active) "transferring" else "finished")
                })
                put("capsule", buildJsonObject {
                    put("leftImg", "")
                    put("rightText", capsuleText(state))
                    put("legacyImg", "")
                    put("legacyText", capsuleText(state))
                })
                put("primary", buildJsonObject {
                    put("title", buildJsonArray {
                        add(buildJsonObject {
                            put("text", state.title.ifBlank { "fluxframe" })
                            put("color", accent)
                            put("darkColor", accent)
                        })
                    })
                    put("content", state.subtitle.ifBlank { state.kind.label })
                    put("rightImg", "")
                    put("clickAction", "")
                    put("miniImg", "")
                })
                put("secondaryData", buildJsonObject {
                    put("type", "PROGRESS")
                    put("progress", percent)
                    put("indicatorImg", "")
                    put("style", "inside")
                    put("nodeLabels", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(state.kind.label))
                        add(kotlinx.serialization.json.JsonPrimitive(if (percent > 0) "$percent%" else "进行中"))
                    })
                })
            })
        }
        return json.toString()
    }

    /** 胶囊上那行小字要极短（官方模板里是 2~4 个字） */
    private fun capsuleText(state: CapsuleState): String {
        val percent = state.progress?.let { " ${(it.coerceIn(0f, 1f) * 100).toInt()}%" }.orEmpty()
        return when {
            !state.active && state.tone == CapsuleTone.ERROR -> "失败"
            !state.active -> "完成"
            else -> state.kind.label + percent
        }
    }

    private companion object {
        const val TAG = "OppoFluidCloud"
        const val UMS_PACKAGE = "com.oplus.pantanal.ums"
        const val METHOD_SHARE_INTENT = "shareIntent"
        const val ENTITY_NAME = "TASK"
        const val ACTION_CREATE = 0
        const val ACTION_UPDATE = 1
        const val ACTION_END = 2

        /** 文档 authority 在前，SDK 内部 authority 兜底 */
        val AUTHORITIES = listOf(
            "IntelligentIntent",
            "com.oplus.pantanal.ums.IntentProvider",
        )

        /** Intent 数据的 Bundle key 名官方未公开，多候选尝试 */
        val BUNDLE_KEYS = listOf(
            "intelligentIntent",
            "intent",
            "data",
            "intentData",
            "json",
        )

        /** UMS metaData 里可能公开服务号的键名，全部试一遍 */
        val SERVICE_ID_KEYS = listOf(
            "fluid_cloud_service_id",
            "fluidCloudServiceId",
            "serviceId",
            "seedling_service_id",
        )

        val SWITCH_URI: Uri = Uri.parse("content://intelligent_data_expositor/switch")
    }
}

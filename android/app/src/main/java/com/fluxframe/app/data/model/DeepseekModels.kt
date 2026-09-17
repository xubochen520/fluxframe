package com.fluxframe.app.data.model

import kotlinx.serialization.Serializable

/* ============================================================================
 * DeepSeek 余额 / 用量记账（GET /api/deepseek/summary 等 6 个接口的公共响应体）
 * ========================================================================== */

@Serializable
data class DeepseekModelUsage(val tokens: Double = 0.0, val cost: Double = 0.0)

@Serializable
data class DeepseekPlatformKey(
    val id: String = "",
    val name: String = "",
    val today: Double = 0.0,
    val month: Double = 0.0,
    val tokensToday: Double = 0.0,
    val tokensMonth: Double = 0.0,
    val requestsMonth: Int = 0,
    val cacheHitRate: Double? = null,
    val models: Map<String, DeepseekModelUsage> = emptyMap(),
    val boundKeyId: String = "",
)

/** `keys[].platform`：platform.keys[] 去掉 id/name/boundKeyId 后的同构对象 */
@Serializable
data class DeepseekKeyPlatform(
    val today: Double = 0.0,
    val month: Double = 0.0,
    val tokensToday: Double = 0.0,
    val tokensMonth: Double = 0.0,
    val requestsMonth: Int = 0,
    val cacheHitRate: Double? = null,
    val models: Map<String, DeepseekModelUsage> = emptyMap(),
)

@Serializable
data class DeepseekPlatform(
    val configured: Boolean = false,
    val perKey: Boolean = false,
    val syncedAt: String? = null,
    val error: String = "",
    val hasData: Boolean = false,
    val keys: List<DeepseekPlatformKey> = emptyList(),
    val unboundCount: Int = 0,
)

@Serializable
data class DeepseekStats(
    val balance: Double = 0.0,
    val granted: Double = 0.0,
    val toppedUp: Double = 0.0,
    val currency: String = "CNY",
    val accountCount: Int = 0,
    val today: Double = 0.0,
    val month: Double = 0.0,
    val ledgerToday: Double = 0.0,
    val ledgerMonth: Double = 0.0,
    val monthRefill: Double = 0.0,
    /** `platform` 或 `ledger` —— today/month 的数据来源 */
    val source: String = "ledger",
    val tokensToday: Double = 0.0,
    val tokensMonth: Double = 0.0,
    val cacheHitRate: Double? = null,
    val requestsMonth: Int = 0,
    val keyCount: Int = 0,
)

@Serializable
data class DeepseekAccount(
    val name: String = "",
    val keyIds: List<String> = emptyList(),
    val currency: String = "CNY",
    val balance: Double = 0.0,
    val granted: Double = 0.0,
    val toppedUp: Double = 0.0,
    val today: Double = 0.0,
    val month: Double = 0.0,
    val isAvailable: Boolean = true,
)

@Serializable
data class DeepseekMergeHint(
    val keyIds: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val balance: Double = 0.0,
    val currency: String = "CNY",
)

@Serializable
data class DeepseekKeyItem(
    val id: String,
    val name: String,
    val accountName: String = "",
    val accountNameResolved: String = "",
    val masked: String = "",
    val enabled: Boolean = true,
    val platformKeyId: String = "",
    val platformKeyName: String = "",
    val currency: String? = null,
    val balance: Double? = null,
    val grantedBalance: Double? = null,
    val toppedUpBalance: Double? = null,
    val isAvailable: Boolean? = null,
    val isAccountOwner: Boolean = false,
    val lastObservedAt: String? = null,
    val lastError: String? = null,
    val todayAmount: Double? = null,
    val monthAmount: Double? = null,
    val platform: DeepseekKeyPlatform? = null,
)

@Serializable
data class DeepseekChartPoint(
    val day: String = "",
    val amount: Double = 0.0,
    val tokens: Double = 0.0,
    val source: String = "ledger",
)

@Serializable
data class DeepseekBalanceFlag(val ok: Boolean = false, val message: String = "")

@Serializable
data class DeepseekRefreshResult(val ok: Boolean = false, val error: String? = null, val skipped: Boolean? = null)

/**
 * 6 个 DeepSeek 接口的公共响应体（`PATCH/DELETE keys/:id`、`merge`、`config` 直接返回它）。
 * `POST /api/deepseek/refresh` 会额外多一个 `refresh` 字段，因此一并放在这里。
 */
@Serializable
data class DeepseekSummary(
    val configured: Boolean = false,
    val enabled: Boolean = true,
    val refreshSeconds: Int = 60,
    val updatedAt: String? = null,
    val refreshing: Boolean = false,
    val error: String = "",
    val platform: DeepseekPlatform = DeepseekPlatform(),
    val stats: DeepseekStats = DeepseekStats(),
    val accounts: List<DeepseekAccount> = emptyList(),
    val mergeHints: List<DeepseekMergeHint> = emptyList(),
    val keys: List<DeepseekKeyItem> = emptyList(),
    /** 固定 30 项，按日期升序（最后一个 = 今天），客户端无需补零 */
    val chart: List<DeepseekChartPoint> = emptyList(),
    val balance: DeepseekBalanceFlag = DeepseekBalanceFlag(),
    /** 仅 refresh 接口返回 */
    val refresh: DeepseekRefreshResult? = null,
)

/* ------------------------------ 请求体 ------------------------------ */

@Serializable
data class DeepseekKeyCreateRequest(
    val name: String,
    val apiKey: String,
    val accountName: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class DeepseekKeyUpdateRequest(
    val name: String? = null,
    val apiKey: String? = null,
    val accountName: String? = null,
    val platformKeyId: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class DeepseekMergeRequest(val keyIds: List<String>, val name: String? = null)

@Serializable
data class DeepseekConfigRequest(
    val enabled: Boolean? = null,
    val refreshSeconds: Int? = null,
    val platformToken: String? = null,
)

@Serializable
data class DeepseekKeyRef(val id: String, val name: String)

@Serializable
data class DeepseekProbe(
    val ok: Boolean = false,
    val error: String? = null,
    val unauthorized: Boolean? = null,
    val isAvailable: Boolean? = null,
    val currency: String? = null,
    val total: Double? = null,
    val granted: Double? = null,
    val toppedUp: Double? = null,
)

/** `POST /api/deepseek/keys`：即便 probe 失败，KEY 也已被创建（响应仍是 200） */
@Serializable
data class DeepseekKeyCreateResponse(
    val key: DeepseekKeyRef,
    val probe: DeepseekProbe = DeepseekProbe(),
    val summary: DeepseekSummary = DeepseekSummary(),
)

/* ============================================================================
 * B站扫码登录（服务端拿 SESSDATA 换高清 1080P+ 能力）
 * ========================================================================== */

@Serializable
data class BiliQrCreateResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val qrcodeKey: String? = null,
    /** PNG data URL：`data:image/png;base64,...`，需自行解码为 Bitmap */
    val image: String? = null,
)

@Serializable
data class BiliQrPollRequest(val qrcodeKey: String)

@Serializable
data class BiliQrPollResponse(
    val ok: Boolean = false,
    /** waiting | scanned | expired | ok | error */
    val status: String = "waiting",
    val error: String? = null,
    val nickname: String? = null,
)

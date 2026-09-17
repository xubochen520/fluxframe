package com.fluxframe.app.data.model

import kotlinx.serialization.Serializable

/* ============================================================================
 * 视频解析引擎（PureParse）与「提取入库」任务
 * ========================================================================== */

@Serializable
data class ParseAuthor(
    val name: String = "",
    val handle: String = "",
    val verified: Boolean = false,
    val tag: String = "",
)

@Serializable
data class ParseStats(
    val like: Long = 0,
    val comment: Long = 0,
    val share: Long = 0,
    val view: Long = 0,
)

@Serializable
data class ParseMedia(
    /** 已改写的同源代理流地址 `/api/stream?url=...&ref=...`，播放/下载走它 */
    val url: String = "",
    /** 上游原始直链 —— 只有它会透传给 /api/parse/import */
    val src: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val size: Long = 0,
    val type: String = "mp4",
    val referer: String = "",
)

@Serializable
data class ParseImage(
    val url: String = "",
    val src: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class ParseHigh(
    val videoUrl: String = "",
    val audioUrl: String = "",
    val quality: Int = 0,
    val label: String = "",
)

@Serializable
data class ParseData(
    /** bilibili | douyin | kuaishou */
    val platform: String = "",
    /** 仅抖音返回：video | images */
    val kind: String? = null,
    val title: String = "",
    val author: ParseAuthor = ParseAuthor(),
    val stats: ParseStats = ParseStats(),
    /** 已改为同源代理地址（含 disposition=inline） */
    val cover: String = "",
    val coverSrc: String? = null,
    val referer: String? = null,
    val qualityLabel: String = "",
    /** 秒；图文作品为 0 */
    val duration: Int = 0,
    val watermarkFree: Boolean = true,
    /** 仅抖音 */
    val pageUrl: String? = null,
    /** 仅抖音图文作品 */
    val images: List<ParseImage> = emptyList(),
    val media: List<ParseMedia> = emptyList(),
    /** 仅 B站「已登录 + 有 ffmpeg + 平台放到 1080P+」时存在；原样透传给导入接口 */
    val high: ParseHigh? = null,
) {
    val platformLabel: String
        get() = when (platform) {
            "bilibili" -> "B站"
            "douyin" -> "抖音"
            "kuaishou" -> "快手"
            else -> platform.ifBlank { "未知" }
        }

    val isImageCollection: Boolean get() = kind == "images"

    /** 可入库的视频流（图文作品为空） */
    val primaryMedia: ParseMedia? get() = media.firstOrNull()
}

/**
 * `POST /api/parse` 响应。
 * 注意：失败时是 **HTTP 400** + `{"ok":false,"msg":"..."}`（不是 `{message}`），
 * 所以接口层用 `Response<ParseResponse>` 接收，以便读到 400 的 body。
 */
@Serializable
data class ParseResponse(
    val ok: Boolean = false,
    val data: ParseData? = null,
    val msg: String? = null,
)

@Serializable
data class ParseImportRequest(
    val url: String,
    val ref: String? = null,
    val name: String,
    /** video | cover | image */
    val kind: String,
    val platTag: String? = null,
    val high: ParseHigh? = null,
)

@Serializable
data class ParseImportCreated(val id: String)

@Serializable
data class ParseImportStatus(
    val id: String = "",
    /** working | done | error */
    val status: String = "working",
    /** 0..1；null = 上游未给 content-length，进度不确定 */
    val progress: Double? = null,
    val message: String = "",
    val items: List<ImageItem> = emptyList(),
    val duplicate: Boolean = false,
) {
    val isWorking: Boolean get() = status == "working"
    val isDone: Boolean get() = status == "done"
    val isError: Boolean get() = status == "error"
}

/* --------------------------------- 其它 --------------------------------- */

@Serializable
data class HealthResponse(val ok: Boolean = false, val service: String = "", val time: String = "")

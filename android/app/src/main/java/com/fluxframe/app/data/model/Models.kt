package com.fluxframe.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ============================================================================
 * 后端 REST 契约的 Kotlin 映射。
 * 字段名与 server/src/index.ts 中的 DTO 一一对应（camelCase），
 * 所有字段都给默认值，配合 Json { ignoreUnknownKeys = true } 保证后端加字段不炸客户端。
 * ========================================================================== */

/* ------------------------------- 认证 / 用户 ------------------------------- */

@Serializable
data class CurrentUser(
    val id: String,
    val username: String,
    val role: String = "USER",
    val r18Mode: Boolean = false,
) {
    val isAdmin: Boolean get() = role.equals("ADMIN", ignoreCase = true)
}

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val user: CurrentUser)

@Serializable
data class R18ModeRequest(val enabled: Boolean)

@Serializable
data class R18ModeResponse(val r18Mode: Boolean)

@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class DownloadSessionResponse(val token: String = "")

/* --------------------------------- 媒体 --------------------------------- */

@Serializable
data class ImageItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val url: String = "",
    val thumb: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** 后端已格式化的可读体积，例如 "1.2 MB" */
    val size: String = "0 B",
    val views: Int = 0,
    val uploadedAt: String = "",
    val capturedAt: String = "",
    val tags: List<String> = emptyList(),
    val tagIds: List<String> = emptyList(),
    val r18: Boolean = false,
    val deletedAt: String? = null,
    /**
     * 余弦相似度，仅 `/api/images/:id/similar` 会返回（1.0 = 完全一样）。
     * 其他接口没有这个字段，默认 0 不影响任何判断。
     */
    val score: Double = 0.0,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    /** 相似度百分比（0~100），供相似图列表展示 */
    val similarPercent: Int get() = Math.round(score * 100).toInt()
    /** Android Motion Photo 规范建议文件名以 MP 结尾；这类单文件可由 Media3 播放内嵌视频。 */
    val isMotionPhoto: Boolean
        get() = isImage && (
            tags.any { it.equals("实况", ignoreCase = true) } ||
                Regex("""(?i).*MP(?:\.(?:jpe?g|heic|heif|avif))?$""").matches(name)
            )
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
    /** 是否落在回收站 */
    val inTrash: Boolean get() = !deletedAt.isNullOrBlank()
}

@Serializable
data class ImageListResponse(val items: List<ImageItem> = emptyList())

@Serializable
data class ImageDeleteResponse(val ok: Boolean = true)

@Serializable
data class ImageViewResponse(val ok: Boolean = true, val views: Int = 0)

@Serializable
data class RenameImageRequest(val name: String)

@Serializable
data class RenameImageResponse(val ok: Boolean = true, val name: String)

/* --------------------- 相似图 / 关系网（CCIP 视觉指纹） ---------------------
 * 服务端 embed.ts 用 CCIP 视觉指纹算相似度，与标签无关 ——
 * 标签向量那条路实测对只有 1~2 张图的角色几乎无效，视觉指纹才是按像素找同角色。
 * 指纹只对静态图片算，视频没有，请求时会返回 indexed=false。
 */

/** 一张相似图 = 图片本体（带 [ImageItem.score]），不再单开一套字段 */
typealias SimilarImageItem = ImageItem

@Serializable
data class SimilarImagesResponse(
    /** 服务端指纹模型是否就绪 */
    val ready: Boolean = false,
    /** 当前这张图是否已建指纹 */
    val indexed: Boolean = false,
    /** 还没建指纹、服务端正在算（正常约 1 秒，客户端可稍后重试） */
    val analyzing: Boolean = false,
    /** 当前相似度阈值（0~1） */
    val threshold: Double = 0.35,
    val items: List<SimilarImageItem> = emptyList(),
)

/** `GET /api/embed/status`：索引概况（设置页展示） */
@Serializable
data class EmbedStatus(
    val modelReady: Boolean = false,
    val sessionActive: Boolean = false,
    val version: String = "",
    val threshold: Double = 0.35,
    /** 已建指纹的图片数 */
    val indexed: Int = 0,
    /** 库内静态图片总数 */
    val imageCount: Int = 0,
    /** 还没建指纹的数量 */
    val missing: Int = 0,
    /** 平均每张图有多少相似图 */
    val avgNeighbors: Double = 0.0,
    val updatedAt: String = "",
    val queueLength: Int = 0,
    val working: Boolean = false,
    val failed: Int = 0,
    val lastError: String = "",
)

@Serializable
data class EmbedGraphEdge(val a: String, val b: String, val score: Double = 0.0)

@Serializable
data class EmbedGraphGroup(val members: List<String> = emptyList(), val size: Int = 0)

/** 关系网的依据：视觉指纹 / 标签 */
enum class EmbedGraphMode(val wire: String, val label: String) {
    /** CCIP 视觉指纹：「这两张图长得像不像」 */
    VISUAL("visual", "视觉指纹"),

    /** 标签的 TF-IDF 相似度：「这两张图被打了同一批标记没有」 */
    TAG("tag", "标签"),
}

/** `GET /api/embed/graph`：关系网（节点 + 相似边 + 相似分组 + 二维布局） */
@Serializable
data class EmbedGraph(
    val ready: Boolean = false,
    /** 这次返回的是哪套关系（visual / tag） */
    val mode: String = "visual",
    val threshold: Double = 0.35,
    /** 已建指纹（标签模式下 = 有标签）的图片总数（含没有相似图的） */
    val totalIndexed: Int = 0,
    /** 其中真正连上相似关系的张数 */
    val linked: Int = 0,
    /** 没有任何相似图的张数 */
    val isolated: Int = 0,
    val nodes: List<ImageItem> = emptyList(),
    val edges: List<EmbedGraphEdge> = emptyList(),
    val groups: List<EmbedGraphGroup> = emptyList(),
    /**
     * 星系图坐标：id → [x, y]，归一化到 [-1,1]。
     * 视觉模式由服务端 `layout.ts` 用 UMAP 式降维算好并缓存；标签模式是
     * 「图片挂到自己标签的锚点上」。客户端只负责画。
     */
    val positions: Map<String, List<Double>> = emptyMap(),
    /** id → 主标签名（视觉模式 = 主角色，标签模式 = 最有区分度的标签） */
    val characters: Map<String, String> = emptyMap(),
    /**
     * 标签锚点：标签名 → [x, y]。只有标签模式有。
     * 标签模式下的星云名称直接画在这些位置上 —— 图就是围着标签聚起来的。
     */
    val tagAnchors: Map<String, List<Double>> = emptyMap(),
    /** 标签模式：参与聚类的标签总数 */
    val tagCount: Int = 0,
    /** 布局算法版本与生成时间，用于判断缓存是否过期 */
    val layoutVersion: String = "",
    val layoutAt: String = "",
)

/** `POST /api/embed/backfill`：把还没建指纹的图排进后台队列 */
@Serializable
data class EmbedBackfillRequest(val force: Boolean = false)

@Serializable
data class EmbedBackfillResponse(val ok: Boolean = true, val queued: Int = 0, val scanning: Boolean = false)

/** `PATCH /api/embed/threshold` */
@Serializable
data class EmbedThresholdRequest(val threshold: Double)

@Serializable
data class EmbedThresholdResponse(val ok: Boolean = true, val threshold: Double = 0.35)

/** 当前阈值的实测校准数据（同角色 / 异角色的分数分布） */
@Serializable
data class EmbedQuantiles(val p10: Double? = null, val p50: Double? = null, val p90: Double? = null)

@Serializable
data class EmbedCalibration(
    val threshold: Double = 0.35,
    val pairs: EmbedPairCounts = EmbedPairCounts(),
    val same: EmbedQuantiles = EmbedQuantiles(),
    val diff: EmbedQuantiles = EmbedQuantiles(),
)

@Serializable
data class EmbedPairCounts(val same: Int = 0, val diff: Int = 0)

/* --------------------------------- 标签 --------------------------------- */

@Serializable
data class TagItem(
    val id: String,
    val name: String,
    val color: String = "#a78bfa",
    val r18: Boolean = false,
    val person: Boolean = false,
    val count: Int = 0,
)

@Serializable
data class TagListResponse(val items: List<TagItem> = emptyList())

@Serializable
data class CreateTagRequest(
    val name: String,
    val color: String = "#a78bfa",
    val r18: Boolean = false,
    val person: Boolean = false,
)

@Serializable
data class UpdateTagRequest(
    val name: String? = null,
    val r18: Boolean? = null,
    val color: String? = null,
    val person: Boolean? = null,
)

@Serializable
data class PersonPreview(val id: String, val name: String, val thumb: String)

@Serializable
data class PersonDetail(
    val id: String,
    val name: String,
    val color: String = "#22c55e",
    val r18: Boolean = false,
    val person: Boolean = true,
    val count: Int = 0,
    val imageCount: Int = 0,
    val related: List<TagItem> = emptyList(),
    val latest: List<PersonPreview> = emptyList(),
)

@Serializable
data class AddTagToImageRequest(val tagId: String? = null, val name: String? = null)

@Serializable
data class BatchTagImagesRequest(val imageIds: List<String>)

@Serializable
data class BatchTagImagesResponse(val ok: Boolean = true, val added: Int = 0)

@Serializable
data class SimpleOkResponse(val ok: Boolean = true)

/* -------------------------------- 总览看板 -------------------------------- */

@Serializable
data class DashboardStats(
    val imageCount: Int = 0,
    val tagCount: Int = 0,
    val userCount: Int = 0,
    val totalViews: Int = 0,
    val storage: String = "0 B",
    val storageCapacity: String = "未知",
    val storagePercent: Double = 0.0,
    val databaseImageBytes: String = "0 B",
    /** 宿主磁盘实际已用空间；storage 仍表示 FluxFrame 媒体目录自身占用 */
    val storageUsed: String = "0 B",
)

@Serializable
data class AuditLogItem(
    val id: String,
    val action: String,
    val target: String = "",
    val user: String = "系统",
    val ip: String = "",
    val scope: String = "内网",
    /** ISO8601 时间串 */
    val time: String = "",
    /** blue / violet / orange / red / green —— 后端给的颜色语义 */
    val tone: String = "blue",
)

@Serializable
data class AuditLogListResponse(val items: List<AuditLogItem> = emptyList())

@Serializable
data class DashboardResponse(
    val stats: DashboardStats = DashboardStats(),
    val top: List<ImageItem> = emptyList(),
    val recent: List<ImageItem> = emptyList(),
    val logs: List<AuditLogItem> = emptyList(),
)

/* -------------------------------- 上传流程 -------------------------------- */
/*
 * 三步式上传（与网页端一致）：
 *   1) POST /api/images/upload/analyze  multipart 上传原文件 → 落 temp 目录
 *      返回 items（含 tempId、AI 推荐标签、重复判定）
 *   2) 用户在客户端「上传确认」界面改名 / 增删标签 / 勾选跳过
 *   3) POST /api/images/upload/complete JSON 提交 tempId + 最终名称 + 标签
 *      服务端 rename 到 originals、生成 320/768/1600 webp 缩略图并入库
 */

@Serializable
data class UploadAnalyzeItem(
    val sourceIndex: Int = 0,
    val tempId: String? = null,
    val fileName: String = "",
    val name: String = "",
    val mimeType: String = "",
    val size: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val tags: List<String> = emptyList(),
    val duplicate: Boolean = false,
    val duplicateName: String? = null,
    val aiError: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val tempIdOrNull: String? get() = tempId?.takeIf { it.isNotBlank() }
}

@Serializable
data class UploadAnalyzeResponse(
    val items: List<UploadAnalyzeItem> = emptyList(),
    val aiEnabled: Boolean = false,
    val aiModel: String = "",
)

@Serializable
data class UploadCompleteItem(val tempId: String, val name: String, val tags: List<String> = emptyList())

@Serializable
data class UploadCompleteRequest(val items: List<UploadCompleteItem>)

@Serializable
data class UploadCompleteResponse(val items: List<ImageItem> = emptyList())

/* -------------------------------- 系统设置 -------------------------------- */

/**
 * 引擎（ffmpeg / AI）的进度对象。
 * 注意：`progress` 是**对象**而不是数字 ——
 * 真实响应形如 `{"phase":"idle","done":0,"total":0}`（由契约测试锁定）。
 */
@Serializable
data class EngineProgress(
    val phase: String = "idle",
    val done: Long = 0,
    val total: Long = 0,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val active: Boolean get() = phase != "idle" && phase != "ready" && phase != "stopped"
}

@Serializable
data class SystemSettings(
    val siteName: String = "fluxframe",
    val theme: String = "Aurora",
    val darkMode: Boolean = true,
    val webglEnabled: Boolean = true,
    val animationEnabled: Boolean = true,
    @SerialName("fluidColors") val fluidColors: List<String> = emptyList(),
    val fluidSpeed: Int = 3,
    val uploadLimitMb: Int = 50,
    val port: Int = 4310,
    val storageDir: String = "./storage",
    val recycleRetentionDays: Int = 30,
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiConfigured: Boolean = false,
    val deepseekEnabled: Boolean = false,
    val deepseekRefreshSeconds: Int = 60,
    val deepseekPlatformConfigured: Boolean = false,
    val biliSessdataConfigured: Boolean = false,
    val ffmpeg: EngineStatus = EngineStatus(),
)

@Serializable
data class EngineStatus(
    val found: Boolean = false,
    val path: String? = null,
    val version: String? = null,
    val busy: Boolean = false,
    val progress: EngineProgress = EngineProgress(),
)

@Serializable
data class AiDetected(val port: Int = 0, val modelId: String? = null)

@Serializable
data class AiFiles(
    val server: Boolean = false,
    val model: Boolean = false,
    val mmproj: Boolean = false,
    val variant: String? = null,
)

@Serializable
data class AiProgress(
    val phase: String = "idle",
    val llamaDone: Long = 0,
    val llamaTotal: Long = 0,
    val modelDone: Long = 0,
    val modelTotal: Long = 0,
    val modelName: String = "",
    val variant: String = "",
    val error: String? = null,
    val port: Int? = null,
) {
    /** 0..1，总进度：llama.cpp 安装包 + 模型文件两段字节合并计算 */
    val fraction: Float
        get() {
            val total = llamaTotal + modelTotal
            if (total <= 0) return 0f
            return ((llamaDone + modelDone).toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * `GET /api/ai/status` 的真实响应形状。
 * 注意：其最坏耗时约 7.5 s（串行探测 5 个端口，每个 1.5 s 超时），客户端读超时需 ≥ 15 s。
 */
@Serializable
data class AiStatus(
    val running: Boolean = false,
    val port: Int? = null,
    val baseUrl: String? = null,
    val detected: AiDetected? = null,
    val files: AiFiles = AiFiles(),
    val progress: AiProgress = AiProgress(),
)

@Serializable
data class TaskAccepted(val ok: Boolean = true, val message: String = "")

/**
 * 「火忘式」接口的统一响应：`/api/ffmpeg/download`、`/api/ai/download` 恒返回 `{ok:true}`，
 * 真正的失败（含「已有任务进行中」）只写服务端日志，必须靠轮询状态接口判定；
 * 而 `/api/ai/start` 在文件不完整时返回 `200 {ok:false,error}`，失败时返回 500。
 */
@Serializable
data class OkTaskResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

/* --------------------------------- 通用 --------------------------------- */

@Serializable
data class ApiMessage(val message: String = "")

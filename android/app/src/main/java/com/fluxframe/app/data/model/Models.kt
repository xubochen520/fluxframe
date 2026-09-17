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
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
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

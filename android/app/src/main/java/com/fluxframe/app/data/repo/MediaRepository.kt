package com.fluxframe.app.data.repo

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.media.HttpRangeMediaDataSource
import com.fluxframe.app.core.media.VideoPosterLoader
import com.fluxframe.app.core.net.UriRequestBody
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.data.model.DashboardResponse
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.UploadAnalyzeResponse
import com.fluxframe.app.data.model.UploadCompleteItem
import com.fluxframe.app.data.model.UploadCompleteRequest
import com.fluxframe.app.data.model.RenameImageRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.util.concurrent.ConcurrentHashMap

/** 与服务端 `query.tag !== '全部标签'` 的特殊值保持一致：表示"不限标签" */
const val ALL_TAGS = "全部标签"

/** 待上传的本地媒体（来自系统相册选择器） */
data class UploadSource(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isSupported: Boolean get() = isImage || isVideo
    /** 去掉扩展名的默认入库名 */
    val defaultName: String
        get() = fileName.substringBeforeLast('.', fileName).ifBlank { "未命名" }
}

/**
 * 媒体（图片 / 视频）的读取、上传与增删改。
 *
 * 上传走服务端的三步式协议：
 *   analyze（原文件 → temp + AI 打标 + 查重）→ 客户端确认 → complete（落库 + 生成缩略图）。
 */
class MediaRepository(private val container: AppContainer) {

    private val api get() = container.api
    private val longApi get() = container.longApi
    private val resolver get() = container.context.contentResolver

    /** 视频封面数据源（按 URL 复用，见 [videoPosterSource]） */
    private val posterSources = ConcurrentHashMap<String, HttpRangeMediaDataSource>()

    /* --------------------------- 地址拼接 --------------------------- */

    private fun absolute(path: String): String =
        container.prefs.server.value?.absolute(path) ?: path

    /** 把后端返回的相对路径（如人物预览的 `thumb`）补成可访问的绝对地址 */
    fun absoluteFor(path: String): String = absolute(path)

    /** 网格缩略图（320；视频没有变体，后端已回落为原文件） */
    fun gridUrl(item: ImageItem): String = absolute(item.thumb.ifBlank { item.url })

    /** 视频封面用到的数据源（带会话 Cookie 的 Range 读取），作为 HTTP 头方式的兜底 */
    fun videoPosterSource(item: ImageItem): HttpRangeMediaDataSource? {
        if (!item.isVideo) return null
        val url = videoPosterUrl(item).takeIf { it.isNotBlank() } ?: return null
        return posterSources.getOrPut(url) { HttpRangeMediaDataSource(url, container.okHttp) }
    }

    /** 视频封面的绝对地址（`/api/images/:id/file`） */
    fun videoPosterUrl(item: ImageItem): String = absolute(item.url)

    /**
     * 封面缓存键。
     *
     * **必须带上取帧位置**：用户把「第 0.5 秒」改成「第 3 秒」之后，
     * 旧键自然失效，不会拿着一张过期封面继续显示。
     */
    fun videoPosterCacheKey(item: ImageItem): String {
        val seconds = container.prefs.ui.value.videoPosterSeconds
        return "fluxframe-poster:$seconds:${videoPosterUrl(item)}"
    }

    /**
     * 取视频封面（用户配置的那个时间点的真实帧，默认 0.5 秒）。
     *
     * 平台自带的 `MediaMetadataRetriever` HTTP 栈不共享 OkHttp 的 CookieJar，
     * 因此这里显式把会话 Cookie 作为请求头传下去；失败时再退回带鉴权的 Range 数据源。
     */
    suspend fun loadVideoPoster(item: ImageItem, targetWidthPx: Int): Bitmap? {
        if (!item.isVideo) return null
        val url = videoPosterUrl(item)
        if (url.isBlank()) return null
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val cookie = host?.let { container.cookieJar.rawCookieHeader(it) }
        val seconds = container.prefs.ui.value.videoPosterSeconds
        return VideoPosterLoader.load(
            context = container.context,
            key = videoPosterCacheKey(item),
            url = url,
            cookieHeader = cookie,
            rangeSource = videoPosterSource(item),
            targetWidthPx = targetWidthPx,
            frameAtMillis = (seconds * 1000f).toLong().coerceAtLeast(0L),
            fetchToFile = { limit -> downloadPrefix(url, cookie, limit) },
        )
    }

    /**
     * 兜底用：把 [url] 的前 [limit] 字节下载到临时文件。
     *
     * 只在平台解码器既不认 HTTP 头、也不认 `MediaDataSource` 时才走到这里 ——
     * 代价是几 MB 流量，换来的是"任何机型都能出封面"。
     */
    private fun downloadPrefix(url: String, cookieHeader: String?, limit: Long): java.io.File? {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .apply { cookieHeader?.let { header("Cookie", it) } }
            .build()
        val file = java.io.File.createTempFile("poster", ".bin", container.context.cacheDir)
        return try {
            container.okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    file.delete()
                    return null
                }
                val body = response.body ?: run {
                    file.delete()
                    return null
                }
                var written = 0L
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        while (written < limit) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                }
                if (written == 0L) {
                    file.delete()
                    null
                } else {
                    file
                }
            }
        } catch (error: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    /** 退出登录 / 换服务器时清掉封面缓存 */
    fun clearVideoPosterCache() = VideoPosterLoader.clear(container.context)

    /** 全屏预览：图片用 1600 变体，视频用原文件交给 ExoPlayer */
    fun previewUrl(item: ImageItem): String = if (item.isVideo) {
        absolute(item.url)
    } else {
        absolute("/api/images/${item.id}/variant/1600")
    }

    /** 播放 / 内联展示 */
    fun fileUrl(item: ImageItem): String = absolute(item.url)

    /** 下载原文件（服务端会记「下载图片 / 下载视频」审计） */
    fun downloadUrl(item: ImageItem): String = absolute("/api/images/${item.id}/download")

    /* --------------------------- 列表 / 看板 --------------------------- */

    suspend fun images(
        search: String? = null,
        tag: String? = null,
        sort: String = "views",
        trash: Boolean = false,
    ): Result<List<ImageItem>> = apiCall {
        api.images(
            search = search?.trim()?.takeIf { it.isNotEmpty() },
            tag = tag?.takeIf { it.isNotEmpty() && it != ALL_TAGS },
            sort = sort,
            trash = trash,
        ).items
    }

    suspend fun dashboard(): Result<DashboardResponse> = apiCall { api.dashboard() }

    /* --------------------------- 单条操作 --------------------------- */

    /** 记一次浏览（视频与图片在后端分开记「查看视频 / 查看图片」） */
    suspend fun markViewed(id: String): Result<Int> = apiCall { api.markViewed(id).views }

    suspend fun rename(id: String, name: String): Result<String> =
        apiCall { api.renameImage(id, RenameImageRequest(name.trim())).name }

    suspend fun moveToTrash(id: String): Result<Unit> = apiCall {
        api.trashImage(id)
        Unit
    }

    suspend fun restore(id: String): Result<Unit> = apiCall {
        api.restoreImage(id)
        Unit
    }

    /** 永久删除（仅 ADMIN，会删除物理文件与缩略图） */
    suspend fun purge(id: String): Result<Unit> = apiCall {
        api.purgeImage(id)
        Unit
    }

    /* --------------------------- 上传 --------------------------- */

    /** 读取 `content://` 的显示名、大小与 MIME，用于上传前展示与 multipart 头 */
    fun describe(uri: Uri): UploadSource {
        var name: String = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "未命名"
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = resolver.getType(uri).orEmpty().ifBlank { guessMimeType(name) }
        return UploadSource(uri, name, mime, size)
    }

    /**
     * 第一步：把原文件流式上传到服务端 temp 目录，顺带拿到 AI 推荐标签与查重结果。
     * 大视频不会读进内存；[onProgress] 是 0..1 的总体进度（在 OkHttp 写线程回调）。
     */
    suspend fun analyzeUpload(
        sources: List<UploadSource>,
        onProgress: (Float) -> Unit = {},
    ): Result<UploadAnalyzeResponse> {
        if (sources.isEmpty()) return Result.failure(IllegalArgumentException("没有选择任何文件"))
        val totalBytes = sources.sumOf { if (it.size > 0) it.size else 0L }
        val writtenBytes = ConcurrentHashMap<Int, Long>()

        val parts = sources.mapIndexed { index, source ->
            val mediaType = source.mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
            val body = UriRequestBody(
                resolver = resolver,
                uri = source.uri,
                mediaType = mediaType,
                declaredLength = source.size,
            ) { written, _ ->
                writtenBytes[index] = written
                if (totalBytes > 0) {
                    val sum = writtenBytes.values.sum()
                    onProgress((sum.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f))
                }
            }
            MultipartBody.Part.createFormData("files", source.fileName, body)
        }
        return apiCall { longApi.uploadAnalyze(parts) }
    }

    /** 第三步：确认名称与标签，服务端 rename 到 originals、生成 320/768/1600 webp 并入库 */
    suspend fun completeUpload(items: List<UploadCompleteItem>): Result<List<ImageItem>> =
        apiCall { longApi.uploadComplete(UploadCompleteRequest(items)).items }

    /** 放弃某个待上传文件（服务端 temp 目录里 2 小时后也会自动清理） */
    suspend fun discardPending(tempId: String): Result<Unit> = apiCall {
        api.discardPendingUpload(tempId)
        Unit
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "m4v" -> "video/x-m4v"
        else -> "application/octet-stream"
    }

    companion object {
        val SORT_OPTIONS = listOf(
            SortOption("views", "最多浏览"),
            SortOption("newest", "最新上传"),
            SortOption("name", "名称"),
        )

        /** 上传前做一次轻量校验，避免白传 */
        fun validate(sources: List<UploadSource>, uploadLimitMb: Int): String? {
            val unsupported = sources.filterNot { it.isSupported }
            if (unsupported.isNotEmpty()) {
                return "有 ${unsupported.size} 个文件不是图片或视频，已跳过"
            }
            val tooBig = sources.firstOrNull { it.size > 0 && it.size > uploadLimitMb.toLong() * 1024 * 1024 }
            if (tooBig != null) {
                return "「${tooBig.fileName}」超过 ${uploadLimitMb} MB 的单文件上限"
            }
            return null
        }
    }
}

data class SortOption(val key: String, val label: String)

/** 把异常转成可直接展示的文案 */
fun Result<*>.messageOrNull(): String? = exceptionOrNull()?.toApiException()?.message

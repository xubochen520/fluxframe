package com.fluxframe.app.core.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.fluidcloud.CapsuleKind
import com.fluxframe.app.fluidcloud.CapsuleTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 原文件下载交给系统 [DownloadManager]：
 * 它自带断点续传、通知栏进度与「下载完成点击打开」，比自己在应用内写盘体验好得多，
 * 而且 **不需要任何存储权限**（下载由系统进程落盘）。
 *
 * 部分 ColorOS 版本会丢弃 DownloadManager 请求中的 Cookie 头，所以下载前先用登录态
 * 换取一个仅限下载接口、短期有效的授权令牌，再把令牌放进下载 URL。
 */
class MediaDownloader(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val observedDownloads = linkedMapOf<Long, ImageItem>()
    private val reservedFileNames = linkedSetOf<String>()

    /**
     * 加入系统下载队列。返回 DownloadManager 的任务 id，失败抛 [com.fluxframe.app.core.net.ApiException]。
     */
    suspend fun enqueue(item: ImageItem): Result<Long> {
        val token = downloadToken().getOrElse { return Result.failure(it) }
        val result = enqueueInternal(item, token)
        result.getOrNull()?.let { id -> monitor(listOf(id to item)) }
        return result
    }

    /** 批量加入系统下载队列，并用同一个流体云任务汇总全部文件的进度。 */
    suspend fun enqueueBatch(items: List<ImageItem>): Result<Int> {
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("没有选中任何文件"))
        val token = downloadToken().getOrElse { return Result.failure(it) }
        val queued = mutableListOf<Pair<Long, ImageItem>>()
        var lastError: Throwable? = null
        items.distinctBy { it.id }.forEach { item ->
            enqueueInternal(item, token)
                .onSuccess { queued += it to item }
                .onFailure { lastError = it }
        }
        if (queued.isEmpty()) return Result.failure(lastError ?: IllegalStateException("加入下载队列失败"))
        monitor(queued)
        return Result.success(queued.size)
    }

    private suspend fun downloadToken(): Result<String> = try {
        val token = container.api.downloadSession().token.trim()
        if (token.isBlank()) Result.failure(IllegalStateException("服务器没有返回下载授权"))
        else Result.success(token)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }

    private suspend fun enqueueInternal(item: ImageItem, token: String): Result<Long> = try {
        val endpoint = container.prefs.server.value
            ?: return Result.failure(IllegalStateException("尚未配置服务器地址"))
        val manager = container.context.getSystemService(DownloadManager::class.java)
            ?: return Result.failure(IllegalStateException("系统下载服务不可用"))

        val cookie = container.cookieJar.rawCookieHeader(endpoint.host).orEmpty()
        val fileName = uniqueFileName(buildFileName(item))
        val downloadUri = Uri.parse(container.mediaRepository.downloadUrl(item))
            .buildUpon()
            .appendQueryParameter("token", token)
            .build()
        val request = DownloadManager.Request(downloadUri)
            .setTitle(item.name)
            .setDescription("来自 fluxframe · ${item.size}")
            .setMimeType(item.mimeType.ifBlank { "application/octet-stream" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Accept-Encoding", "identity")
            .addRequestHeader("User-Agent", "FluxFrame/${container.appVersionName} Android")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fluxframe/$fileName")
        // 大多数系统会正常转发 Cookie；短期下载令牌是 ColorOS 丢 Cookie 时的可靠兜底。
        if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)

        Result.success(manager.enqueue(request))
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }

    /**
     * DownloadManager 负责真正落盘；这里轮询它的公开状态，把汇总进度同步到应用内胶囊、
     * Android 实况更新与 ColorOS 流体云。这样不会重复下载，也保留系统断点续传能力。
     */
    private fun monitor(downloads: List<Pair<Long, ImageItem>>) {
        val snapshot = synchronized(observedDownloads) {
            downloads.forEach { (id, item) -> observedDownloads[id] = item }
            observedDownloads.map { it.key to it.value }
        }
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val manager = container.context.getSystemService(DownloadManager::class.java) ?: return@launch
            val count = snapshot.size
            container.fluidCloud.begin(
                kind = CapsuleKind.DOWNLOAD,
                title = if (count == 1) "正在下载 ${snapshot.first().second.name}" else "正在下载 $count 个文件",
                subtitle = "已加入系统下载队列",
                withForegroundService = true,
            )

            while (true) {
                var completed = 0
                var failed = 0
                var downloadedBytes = 0L
                var totalBytes = 0L
                var totalKnown = true
                val failureReasons = mutableListOf<String>()

                snapshot.forEach { (id, item) ->
                    val query = DownloadManager.Query().setFilterById(id)
                    runCatching {
                        val result = manager.query(query)
                        if (result == null) {
                            totalKnown = false
                            return@runCatching
                        }
                        result.use { cursor ->
                            if (!cursor.moveToFirst()) {
                                totalKnown = false
                                return@use
                            }
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) completed++
                            if (status == DownloadManager.STATUS_FAILED) {
                                failed++
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                failureReasons += "${item.name}：${failureMessage(reason)}"
                            }
                            val done = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            ).coerceAtLeast(0L)
                            val total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                            )
                            downloadedBytes += done
                            if (total > 0L) totalBytes += total else totalKnown = false
                        }
                    }.onFailure { totalKnown = false }
                }

                val terminal = completed + failed
                val progress = if (totalKnown && totalBytes > 0L) {
                    downloadedBytes.toFloat() / totalBytes.toFloat()
                } else {
                    null
                }
                container.fluidCloud.update(
                    progress = progress,
                    subtitle = "已完成 $completed/$count${if (failed > 0) " · 失败 $failed" else ""}",
                )

                if (terminal >= count) {
                    if (failed == 0) {
                        container.fluidCloud.finish("$count 个文件已保存到 Downloads/fluxframe")
                    } else {
                        container.fluidCloud.finish(
                            failureReasons.firstOrNull() ?: "$completed 个完成，$failed 个失败",
                            tone = CapsuleTone.ERROR,
                        )
                    }
                    synchronized(observedDownloads) {
                        snapshot.forEach { (id, _) -> observedDownloads.remove(id) }
                    }
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 避免 DownloadManager 因目标文件已存在直接报 ERROR_FILE_ALREADY_EXISTS。 */
    private fun uniqueFileName(requested: String): String = synchronized(reservedFileNames) {
        var candidate = requested
        var index = 1
        while (candidate in reservedFileNames || existsInDownloads(candidate)) {
            candidate = bumpFileName(requested, index++)
        }
        reservedFileNames += candidate
        candidate
    }

    private fun existsInDownloads(fileName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null,
            )?.use { it.moveToFirst() } == true
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "fluxframe/$fileName").exists()
        }
    }.getOrDefault(false)

    /** 不用 DownloadManager 的场合（例如仅预览），直接把原文件读成字节 */
    suspend fun fetchBytes(item: ImageItem): Result<ByteArray> = try {
        val endpoint = container.prefs.server.value
            ?: return Result.failure(IllegalStateException("尚未配置服务器地址"))
        val url = Uri.parse(container.mediaRepository.downloadUrl(item))
        val request = okhttp3.Request.Builder()
            .url(url.toString())
            .header("Cookie", container.cookieJar.rawCookieHeader(endpoint.host).orEmpty())
            .build()
        val response = container.okHttp.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            Result.success(it.body?.bytes() ?: ByteArray(0))
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }

    companion object {
        private const val POLL_INTERVAL_MS = 650L
        /** 服务端下载响应里带了正确文件名，这里只需要把非法字符换掉并补扩展名 */
        fun buildFileName(item: ImageItem): String {
            val base = item.name
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
                .trim('.', ' ', '\t')
                .ifBlank { "媒体文件" }
            val extension = extensionFor(item.mimeType)
            return if (extension.isEmpty() || base.endsWith(".$extension", ignoreCase = true)) {
                base
            } else {
                "$base.$extension"
            }
        }

        fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/x-m4v" -> "m4v"
            "video/mpeg" -> "mpg"
            else -> ""
        }

        fun bumpFileName(fileName: String, index: Int): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) {
                "${fileName.substring(0, dot)} ($index)${fileName.substring(dot)}"
            } else {
                "$fileName ($index)"
            }
        }

        fun failureMessage(reason: Int): String = when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "服务器不支持继续下载"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "同名文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "无法写入下载目录"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据传输中断"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "手机存储空间不足"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "服务器重定向次数过多"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器拒绝下载或登录已失效"
            DownloadManager.ERROR_UNKNOWN -> "系统下载器发生未知错误"
            in 400..599 -> "服务器返回 HTTP $reason"
            else -> "系统错误码 $reason"
        }
    }
}

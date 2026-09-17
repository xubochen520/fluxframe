package com.fluxframe.app.core.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.data.model.ImageItem

/**
 * 原文件下载交给系统 [DownloadManager]：
 * 它自带断点续传、通知栏进度与「下载完成点击打开」，比自己在应用内写盘体验好得多，
 * 而且 **不需要任何存储权限**（下载由系统进程落盘）。
 *
 * 唯一麻烦是 DownloadManager 不共享 OkHttp 的 CookieJar，需要手动带上会话 Cookie。
 */
class MediaDownloader(private val container: AppContainer) {

    /**
     * 加入系统下载队列。返回 DownloadManager 的任务 id，失败抛 [com.fluxframe.app.core.net.ApiException]。
     */
    suspend fun enqueue(item: ImageItem): Result<Long> = try {
        val endpoint = container.prefs.server.value
            ?: return Result.failure(IllegalStateException("尚未配置服务器地址"))
        val manager = container.context.getSystemService(DownloadManager::class.java)
            ?: return Result.failure(IllegalStateException("系统下载服务不可用"))

        // 优先用本机会话 Cookie（离线也能拼出来）；拿不到再问服务端要一次
        val cookie = container.cookieJar.rawCookieHeader(endpoint.host)
            ?: runCatching { "fluxframe_session=${container.api.downloadSession().token}" }
                .getOrElse { return Result.failure(it.toApiException()) }

        val fileName = buildFileName(item)
        val request = DownloadManager.Request(Uri.parse(container.mediaRepository.downloadUrl(item)))
            .setTitle(item.name)
            .setDescription("来自 fluxframe · ${item.size}")
            .setMimeType(item.mimeType.ifBlank { "application/octet-stream" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Cookie", cookie)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fluxframe/$fileName")

        Result.success(manager.enqueue(request))
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }

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
    }
}

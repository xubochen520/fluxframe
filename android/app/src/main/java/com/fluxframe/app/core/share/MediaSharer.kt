package com.fluxframe.app.core.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.download.MediaDownloader
import com.fluxframe.app.data.model.ImageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 把带登录态的服务端原文件暂存到 cache 后交给 Android Sharesheet。
 * 系统会据 MIME 类型列出 Quick Share、OPPO/一加互传等真实可用目标。
 */
class MediaSharer(private val container: AppContainer) {

    suspend fun share(context: Context, items: List<ImageItem>): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.failure(IllegalArgumentException("没有可分享的媒体"))
        try {
            val directory = File(container.context.cacheDir, "shared_media").apply { mkdirs() }
            // 分享 URI 获得授权后由目标应用读取；只清理一天前的旧缓存，避免打断正在进行的分享。
            val expiry = System.currentTimeMillis() - CACHE_TTL_MS
            directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.delete() }

            val uris = ArrayList<android.net.Uri>(items.size)
            items.forEachIndexed { index, item ->
                val baseName = MediaDownloader.buildFileName(item)
                val file = uniqueFile(directory, baseName, item.id, index)
                val response = container.okHttp.newCall(
                    Request.Builder().url(container.mediaRepository.downloadUrl(item)).build(),
                ).execute()
                response.use {
                    if (!it.isSuccessful) error("${item.name} 获取失败（HTTP ${it.code}）")
                    val body = it.body ?: error("${item.name} 返回了空文件")
                    file.outputStream().buffered().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
                }
                if (file.length() <= 0L) error("${item.name} 文件为空")
                uris += FileProvider.getUriForFile(
                    container.context,
                    "${container.context.packageName}.files",
                    file,
                )
            }

            val commonType = commonMime(items)
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.apply {
                type = commonType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "FluxFrame 媒体", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "分享 ${items.size} 个原文件"))
            }
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun commonMime(items: List<ImageItem>): String = when {
        items.all { it.isImage } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }

    private fun uniqueFile(directory: File, requested: String, id: String, index: Int): File {
        val safe = requested.replace(Regex("""[\\/:*?\"<>|\u0000-\u001f]"""), "_").ifBlank { "media_$index" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        return File(directory, "${stem.take(80)}_${id.take(8)}$ext")
    }

    private companion object {
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

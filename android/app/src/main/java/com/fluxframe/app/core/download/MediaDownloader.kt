package com.fluxframe.app.core.download

import android.app.DownloadManager
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * 原文件先由系统 [DownloadManager] 下载到应用专属临时目录，再发布到 MediaStore。
 *
 * 不能直接把 DownloadManager 的公共 Downloads 记录当成“已保存到图库”：
 * ColorOS 会在某些版本上忽略指定文件名 / MIME，最终只产生一个
 * `Downloads/fluxframe-N` + `application/octet-stream` 记录，图库当然不会显示。
 *
 * Android 10+ 上只在 MediaStore 复制、取消 IS_PENDING 并回查大小后才算成功；
 * 图片进 Pictures/Fluxframe，视频进 DCIM/Fluxframe，其他文件进 Downloads/Fluxframe。
 */
class MediaDownloader(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val observedDownloads = linkedMapOf<Long, ImageItem>()
    private val outputFileNames = linkedMapOf<Long, String>()
    private val reservedFileNames = linkedSetOf<String>()
    // 监控协程在连续点击下载时会被新的汇总任务替换，终态必须跨协程保留。
    private val publishedDownloads = linkedMapOf<Long, String>()
    private val terminalFailures = linkedMapOf<Long, String>()

    /** 加入下载队列。返回 DownloadManager 任务 id。 */
    suspend fun enqueue(item: ImageItem): Result<Long> {
        val token = downloadToken().getOrElse { return Result.failure(it) }
        val result = enqueueInternal(item, token)
        result.getOrNull()?.let { id -> monitor(listOf(id to item)) }
        return result
    }

    /** 批量加入队列，并用同一个流体云任务汇总下载与入库进度。 */
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
        val fileName = uniqueFileName(item, buildFileName(item))
        val downloadUri = Uri.parse(container.mediaRepository.downloadUrl(item))
            .buildUpon()
            .appendQueryParameter("token", token)
            .build()
        val request = DownloadManager.Request(downloadUri)
            .setTitle(item.name)
            .setDescription("正在下载，完成后保存到系统图库")
            .setMimeType(mimeType(item, fileName))
            // 只显示进度；真正入库后由 FluxFrame 发送唯一的完成通知。
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Accept-Encoding", "identity")
            .addRequestHeader("User-Agent", "FluxFrame/${container.appVersionName} Android")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // OEM 的公共 Downloads 命名 / MIME 行为不一致，因此先下到应用私有暂存区。
            val stageName = "${UUID.randomUUID()}-$fileName"
            request
                .setVisibleInDownloadsUi(false)
                .setDestinationInExternalFilesDir(
                    container.context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "fluxframe-stage/$stageName",
                )
        } else {
            // Android 9- 由 DownloadManager 直接写公共目录，成功后触发媒体扫描。
            val directory = when {
                item.isVideo -> Environment.DIRECTORY_DCIM
                item.isImage -> Environment.DIRECTORY_PICTURES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            request
                .setDestinationInExternalPublicDir(directory, "Fluxframe/$fileName")
                .allowScanningByMediaScanner()
        }
        if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)

        val id = manager.enqueue(request)
        synchronized(observedDownloads) { outputFileNames[id] = fileName }
        Log.i(TAG, "enqueued id=$id item=${item.id} name=$fileName mime=${mimeType(item, fileName)}")
        Result.success(id)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }

    /**
     * DownloadManager 只负责把字节完整拉到暂存区；状态成功后还必须通过
     * [publishToMediaStore] 发布并回查，才对用户报告“已保存到图库”。
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
            val lastStatuses = mutableMapOf<Long, Pair<Int, Int>>()
            val missingQueries = mutableMapOf<Long, Int>()
            container.fluidCloud.begin(
                kind = CapsuleKind.DOWNLOAD,
                title = if (count == 1) "正在下载 ${snapshot.first().second.name}" else "正在下载 $count 个文件",
                subtitle = "下载完成后将自动保存到系统图库",
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
                    val savedPath = synchronized(observedDownloads) { publishedDownloads[id] }
                    if (savedPath != null) {
                        completed++
                        return@forEach
                    }
                    synchronized(observedDownloads) { terminalFailures[id] }?.let { message ->
                        failed++
                        failureReasons += "${item.name}：$message"
                        return@forEach
                    }

                    val query = DownloadManager.Query().setFilterById(id)
                    runCatching {
                        val result = manager.query(query)
                        if (result == null) {
                            val misses = (missingQueries[id] ?: 0) + 1
                            missingQueries[id] = misses
                            if (misses >= MAX_MISSING_QUERIES) {
                                val message = "系统下载记录不存在"
                                synchronized(observedDownloads) { terminalFailures[id] = message }
                                failed++
                                failureReasons += "${item.name}：$message"
                            } else {
                                totalKnown = false
                            }
                            return@runCatching
                        }
                        result.use { cursor ->
                            if (!cursor.moveToFirst()) {
                                val misses = (missingQueries[id] ?: 0) + 1
                                missingQueries[id] = misses
                                if (misses >= MAX_MISSING_QUERIES) {
                                    val message = "系统下载记录不存在"
                                    synchronized(observedDownloads) { terminalFailures[id] = message }
                                    failed++
                                    failureReasons += "${item.name}：$message"
                                } else {
                                    totalKnown = false
                                }
                                return@use
                            }
                            missingQueries.remove(id)
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val done = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            ).coerceAtLeast(0L)
                            val total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                            )
                            downloadedBytes += done
                            if (total > 0L) totalBytes += total else totalKnown = false

                            val state = status to reason
                            if (lastStatuses[id] != state) {
                                lastStatuses[id] = state
                                Log.i(TAG, "status id=$id status=$status reason=$reason bytes=$done/$total")
                            }

                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    container.fluidCloud.update(null, "下载完成，正在写入系统图库…")
                                    val fileName = synchronized(observedDownloads) {
                                        outputFileNames[id] ?: buildFileName(item)
                                    }
                                    runCatching {
                                        validateStagedDownload(manager, id, done, total)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            publishToMediaStore(manager, id, item, fileName, total)
                                        } else {
                                            scanLegacyDownload(manager, id, item, fileName)
                                        }
                                    }.onSuccess { saved ->
                                        // 先持久终态再删 DownloadManager 暂存记录，避免新监控器误判丢失。
                                        synchronized(observedDownloads) { publishedDownloads[id] = saved }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) manager.remove(id)
                                        completed++
                                        Log.i(TAG, "published id=$id saved=$saved bytes=$done")
                                    }.onFailure { error ->
                                        val message = error.message?.takeIf { it.isNotBlank() } ?: "图库入库失败"
                                        synchronized(observedDownloads) { terminalFailures[id] = message }
                                        failed++
                                        failureReasons += "${item.name}：$message"
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) manager.remove(id)
                                        Log.e(TAG, "publish failed id=$id name=$fileName", error)
                                    }
                                }

                                DownloadManager.STATUS_FAILED -> {
                                    failed++
                                    val message = failureMessage(reason)
                                    synchronized(observedDownloads) { terminalFailures[id] = message }
                                    failureReasons += "${item.name}：$message"
                                }
                            }
                        }
                    }.onFailure { error ->
                        totalKnown = false
                        Log.w(TAG, "query failed id=$id", error)
                    }
                }

                val terminal = completed + failed
                val progress = if (totalKnown && totalBytes > 0L) {
                    downloadedBytes.toFloat() / totalBytes.toFloat()
                } else {
                    null
                }
                if (terminal < count) {
                    container.fluidCloud.update(
                        progress = progress,
                        subtitle = "已保存到图库 $completed/$count${if (failed > 0) " · 失败 $failed" else ""}",
                    )
                }

                if (terminal >= count) {
                    if (failed == 0) {
                        container.fluidCloud.finish(
                            if (count == 1) "已保存到系统图库" else "$count 个文件已保存到系统图库",
                        )
                    } else {
                        container.fluidCloud.finish(
                            failureReasons.firstOrNull() ?: "$completed 个完成，$failed 个失败",
                            tone = CapsuleTone.ERROR,
                        )
                    }
                    synchronized(observedDownloads) {
                        snapshot.forEach { (id, _) ->
                            observedDownloads.remove(id)
                            outputFileNames.remove(id)?.let(reservedFileNames::remove)
                            publishedDownloads.remove(id)
                            terminalFailures.remove(id)
                        }
                    }
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 检查下载字节完整，并拦截被误当成媒体的登录页 / JSON 错误。 */
    private fun validateStagedDownload(manager: DownloadManager, id: Long, downloaded: Long, total: Long) {
        if (downloaded <= 0L) throw IOException("下载内容为空")
        if (total > 0L && downloaded != total) {
            throw IOException("下载内容不完整（$downloaded/$total 字节）")
        }
        manager.openDownloadedFile(id)?.use { descriptor ->
            if (descriptor.statSize == 0L) throw IOException("系统下载完成但临时文件为空")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val prefixBytes = ByteArray(512)
                val count = input.read(prefixBytes)
                if (count > 0) {
                    val prefix = String(prefixBytes, 0, count, StandardCharsets.UTF_8)
                        .trim()
                        .lowercase(Locale.ROOT)
                    if (
                        prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
                        prefix.startsWith("{\"error") || prefix.startsWith("{\"message")
                    ) {
                        throw IOException("服务器返回了登录页或错误信息，未保存到图库")
                    }
                }
            }
        } ?: throw IOException("系统下载文件无法打开")
    }

    /** Android 10+：写入正确的图片 / 视频 MediaStore 集合，然后再公开条目。 */
    private suspend fun publishToMediaStore(
        manager: DownloadManager,
        id: Long,
        item: ImageItem,
        requestedName: String,
        expectedBytes: Long,
    ): String {
        val resolver = container.context.contentResolver
        val (collection, relativePath) = mediaDestination(item)
        var target: Uri? = null
        var fileName = requestedName
        repeat(MAX_INSERT_ATTEMPTS) { attempt ->
            if (target != null) return@repeat
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(item, fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            target = resolver.insert(collection, values)
            if (target == null) fileName = bumpFileName(requestedName, attempt + 1)
        }
        val targetUri = target ?: throw IOException("无法在系统图库创建文件")

        try {
            val copied = resolver.openOutputStream(targetUri, "w")?.use { output ->
                manager.openDownloadedFile(id)?.use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        input.copyTo(output, COPY_BUFFER_SIZE).also { output.flush() }
                    }
                } ?: throw IOException("系统下载文件无法打开")
            } ?: throw IOException("图库文件无法写入")

            if (copied <= 0L) throw IOException("写入图库的文件为空")
            if (expectedBytes > 0L && copied != expectedBytes) {
                throw IOException("图库写入不完整（$copied/$expectedBytes 字节）")
            }
            resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            verifyPublished(targetUri, copied)
            return "$relativePath$fileName"
        } catch (error: Throwable) {
            runCatching { resolver.delete(targetUri, null, null) }
            if (error is IOException) throw error
            throw IOException("图库发布失败：${error.message}", error)
        }
    }

    /** 发布后回查，避免“通知说成功，但图库实际没有”。 */
    private suspend fun verifyPublished(uri: Uri, expectedBytes: Long) {
        var lastError: Throwable? = null
        repeat(VERIFY_ATTEMPTS) {
            runCatching {
                container.context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                        if (pending == 0 && size == expectedBytes && size > 0L) return
                    }
                }
            }.onFailure { lastError = it }
            delay(VERIFY_DELAY_MS)
        }
        throw IOException(
            lastError?.message?.let { "系统媒体库回查失败：$it" }
                ?: "系统媒体库未确认文件已保存",
            lastError,
        )
    }

    /** Android 9-：DownloadManager 已直接写公共目录，通知媒体扫描器入库。 */
    private fun scanLegacyDownload(
        manager: DownloadManager,
        id: Long,
        item: ImageItem,
        fileName: String,
    ): String {
        var localUri: String? = null
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            }
        }
        val path = localUri?.let(Uri::parse)?.path ?: throw IOException("找不到已下载的文件")
        MediaScannerConnection.scanFile(
            container.context,
            arrayOf(path),
            arrayOf(mimeType(item, fileName)),
            null,
        )
        return path
    }

    /** 输出名在对应图片 / 视频集合与当前队列中都不重复。 */
    private fun uniqueFileName(item: ImageItem, requested: String): String = synchronized(reservedFileNames) {
        var candidate = requested
        var index = 1
        while (candidate in reservedFileNames || existsInMediaStore(item, candidate)) {
            candidate = bumpFileName(requested, index++)
        }
        reservedFileNames += candidate
        candidate
    }

    private fun existsInMediaStore(item: ImageItem, fileName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relativePath) = mediaDestination(item)
            container.context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(fileName, relativePath),
                null,
            )?.use { it.moveToFirst() } == true
        } else {
            val directory = when {
                item.isVideo -> Environment.DIRECTORY_DCIM
                item.isImage -> Environment.DIRECTORY_PICTURES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            File(Environment.getExternalStoragePublicDirectory(directory), "Fluxframe/$fileName").exists()
        }
    }.getOrDefault(false)

    private fun mediaDestination(item: ImageItem): Pair<Uri, String> = when {
        item.isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_DCIM}/Fluxframe/"

        item.isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_PICTURES}/Fluxframe/"

        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
            "${Environment.DIRECTORY_DOWNLOADS}/Fluxframe/"
    }

    /** 不用 DownloadManager 的场合（例如仅预览），直接把原文件读成字节。 */
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
        private const val TAG = "FluxFrameDownload"
        private const val POLL_INTERVAL_MS = 650L
        private const val VERIFY_ATTEMPTS = 10
        private const val VERIFY_DELAY_MS = 200L
        private const val MAX_INSERT_ATTEMPTS = 20
        private const val MAX_MISSING_QUERIES = 3
        private const val COPY_BUFFER_SIZE = 64 * 1024

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

        private fun mimeType(item: ImageItem, fileName: String): String =
            item.mimeType.takeIf { it.contains('/') && it.isNotBlank() }
                ?: when (fileName.substringAfterLast('.', "").lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "mp4", "m4v" -> "video/mp4"
                    "mov" -> "video/quicktime"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    else -> "application/octet-stream"
                }
    }
}

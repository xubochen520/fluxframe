package com.fluxframe.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 视频封面（0.5 秒处的真实帧）的提取与缓存。
 *
 * ### v2.1.0 为什么没出图，这一版怎么修
 * 上一版把「取帧」整条链路交给了 Coil（`MediaDataSourceFetcher` + `VideoFrameDecoder`），
 * 结果在真机上永远只看到渐变占位 —— 因为整条链路**没有任何一处失败是可观测的**，
 * 且依赖了两个都不一定成立的前提：`MediaMetadataRetriever.setDataSource(MediaDataSource)`
 * 在厂商 ROM 上必须可用、Coil 必须把数据源原样交给解码器。
 *
 * 现在改成自己取帧，并按**兼容性从高到低**依次尝试：
 *  1. `setDataSource(url, headers)` —— 平台自带的 HTTP 栈，我们把会话 Cookie 作为
 *     **请求头**传进去。这是最"正统"的远程媒体读取方式，也支持 Range 拖取；
 *  2. `setDataSource(MediaDataSource)` —— 上一版的方案，走应用内带 Cookie 的 OkHttp
 *     发 Range 请求（`HttpRangeMediaDataSource`），作为第 1 条失败时的兜底；
 *  3. 都失败就返回 null，界面退回渐变色块，并**把失败原因记录下来**给设置页显示。
 *
 * 缓存三层：内存 LRU → 磁盘 JPEG（`cacheDir/video_posters`）→ 重新提取。
 * 并发用信号量限制为 2，避免一屏视频同时解码把 CPU 打满。
 */
object VideoPosterLoader {

    private const val TAG = "FluxFramePoster"

    /** 默认取帧位置：0.5 秒，避开片头黑场与台标淡入（用户可在设置里改） */
    const val FRAME_AT_MILLIS = 500L

    private const val DIR_NAME = "video_posters"
    private const val MEMORY_CACHE_BYTES = 16 * 1024 * 1024

    /** 兜底策略先只取文件头这么多字节（faststart 的 MP4 足够取到 0.5 秒的帧） */
    private const val PREFIX_BYTES = 8L * 1024 * 1024

    /** 仍然不行才考虑整包，且只对不超过这个体积的视频做 */
    private const val FULL_DOWNLOAD_LIMIT_BYTES = 40L * 1024 * 1024

    /* ------------------------------ 诊断信息 ------------------------------ */

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastStrategy: String? = null
        private set

    @Volatile
    var successCount: Int = 0
        private set

    @Volatile
    var failureCount: Int = 0
        private set

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val decodePermits = Semaphore(2)

    /** 同步看一眼内存缓存，用于给 `produceState` 一个不会闪的初值 */
    fun peek(key: String): Bitmap? = memory.get(key)

    /**
     * 取一帧作为封面。
     *
     * @param key 缓存键（用绝对 URL + 取帧位置，改位置后不会命中旧封面）
     * @param url 媒体地址（`/api/images/:id/file` 的绝对地址）
     * @param cookieHeader 会话 Cookie 原文；平台 HTTP 栈不会共享 OkHttp 的 CookieJar
     * @param rangeSource 兜底数据源（带 Cookie 的 Range 读取），可为 null
     * @param targetWidthPx 目标宽度（按卡片实际像素宽度解码，避免解出 4K 帧）
     * @param frameAtMillis 取第几毫秒的帧（用户可在设置里自定义）
     */
    suspend fun load(
        context: Context,
        key: String,
        url: String,
        cookieHeader: String?,
        rangeSource: MediaDataSource?,
        targetWidthPx: Int,
        frameAtMillis: Long = FRAME_AT_MILLIS,
        /** 兜底：把 url 的前 N 字节下载到临时文件（由仓库层注入，见 MediaRepository） */
        fetchToFile: ((Long) -> File?)? = null,
    ): Bitmap? {
        memory.get(key)?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            memory.get(key)?.let { return@withLock it }

            val fromDisk = readFromDisk(context, key)
            if (fromDisk != null) {
                memory.put(key, fromDisk)
                lastStrategy = "磁盘缓存"
                return@withLock fromDisk
            }

            // 最后一段就是整个 withLock 的返回值
            decodePermits.withPermit {
                val width = targetWidthPx.coerceIn(160, 1280)
                val frame = extract(url, cookieHeader, rangeSource, width, frameAtMillis, fetchToFile)
                if (frame == null) {
                    failureCount++
                } else {
                    successCount++
                    memory.put(key, frame)
                    writeToDisk(context, key, frame)
                }
                frame
            }
        }
    }

    /** 清空（退出登录 / 换服务器时调用） */
    fun clear(context: Context) {
        memory.evictAll()
        runCatching { posterDir(context).deleteRecursively() }
        lastError = null
        lastStrategy = null
        successCount = 0
        failureCount = 0
    }

    /* -------------------------------- 取帧 -------------------------------- */

    private fun extract(
        url: String,
        cookieHeader: String?,
        rangeSource: MediaDataSource?,
        targetWidth: Int,
        frameAtMillis: Long,
        fetchToFile: ((Long) -> File?)?,
    ): Bitmap? {
        var failure: String? = null

        // 策略 1：平台 HTTP 栈 + 自定义请求头（兼容性最好，不落地文件）
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (cookieHeader.isNullOrBlank()) {
                    retriever.setDataSource(url)
                } else {
                    retriever.setDataSource(url, mapOf("Cookie" to cookieHeader))
                }
                grabFrame(retriever, targetWidth, frameAtMillis)
            } finally {
                runCatching { retriever.release() }
            }
        }.onSuccess { bitmap ->
            if (bitmap != null) {
                lastStrategy = "HTTP 头鉴权"
                lastError = null
                return bitmap
            }
            failure = "HTTP 方式没取到帧"
        }.onFailure { error ->
            failure = "HTTP 方式失败：${error.javaClass.simpleName}: ${error.message}"
        }

        // 策略 2：自己实现的带鉴权 Range 数据源（随机访问，不落地文件）
        if (rangeSource != null) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(rangeSource)
                    grabFrame(retriever, targetWidth, frameAtMillis)
                } finally {
                    runCatching { retriever.release() }
                }
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    lastStrategy = "Range 数据源"
                    lastError = null
                    return bitmap
                }
                failure = "$failure；Range 方式也没取到帧"
            }.onFailure { error ->
                failure = "$failure；Range 方式失败：${error.javaClass.simpleName}: ${error.message}"
            }
        }

        if (fetchToFile == null) return finishWithError(failure)

        // 策略 3/4：前两条都失败时退回"落地成文件再取帧"。
        // 让平台解码器读本地文件是最稳的；先只取文件头（faststart 的 MP4
        // 足以取到 0.5 秒那一帧），仍不行再考虑整包（有体积上限）。
        for ((limit, label) in listOf(
            PREFIX_BYTES to "分段下载(${PREFIX_BYTES / 1024 / 1024}MB)",
            FULL_DOWNLOAD_LIMIT_BYTES to "整包下载",
        )) {
            val frame = tryFrameFromFile(fetchToFile, limit, targetWidth, frameAtMillis)
            if (frame != null) {
                lastStrategy = label
                lastError = null
                return frame
            }
            failure = "$failure；$label 没取到帧"
        }

        return finishWithError(failure)
    }

    /** 下载（最多 [limit] 字节）到临时文件后取帧；无论成败都删掉临时文件 */
    private fun tryFrameFromFile(
        fetchToFile: (Long) -> File?,
        limit: Long,
        targetWidth: Int,
        frameAtMillis: Long,
    ): Bitmap? {
        val file = runCatching { fetchToFile(limit) }.getOrNull() ?: return null
        return try {
            if (!file.isFile || file.length() == 0L) return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                grabFrame(retriever, targetWidth, frameAtMillis)
            } finally {
                runCatching { retriever.release() }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "从临时文件取帧失败", error)
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun finishWithError(failure: String?): Bitmap? {
        lastError = failure ?: "取帧失败（原因未知）"
        Log.w(TAG, "视频封面提取失败：$lastError")
        return null
    }

    private fun grabFrame(retriever: MediaMetadataRetriever, targetWidth: Int, frameAtMillis: Long): Bitmap? {
        // API 27+ 才有按尺寸缩放取帧，能显著降低解码开销
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val outH = (targetWidth.toFloat() * height / width).roundToInt().coerceAtLeast(1)
                val scaled = retriever.getScaledFrameAtTime(
                    frameAtMillis * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    outH,
                )
                if (scaled != null) return scaled
            }
        }
        return retriever.getFrameAtTime(
            frameAtMillis * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        )
    }

    /* ------------------------------ 磁盘缓存 ------------------------------ */

    private fun posterDir(context: Context): File = File(context.cacheDir, DIR_NAME)

    private fun posterFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + ".jpg"
        return File(posterDir(context), name)
    }

    private fun readFromDisk(context: Context, key: String): Bitmap? = runCatching {
        val file = posterFile(context, key)
        if (!file.isFile || file.length() == 0L) return null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun writeToDisk(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            val dir = posterDir(context)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = posterFile(context, key)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        }.onFailure { Log.w(TAG, "封面写入磁盘失败", it) }
    }
}

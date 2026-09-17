package com.fluxframe.app.core.media

import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 用**带鉴权的 HTTP Range 请求**喂给 `MediaMetadataRetriever` 的数据源。
 *
 * ### 为什么需要它
 * 服务端的媒体文件走 `/api/images/:id/file`，需要会话 Cookie；而
 * `MediaMetadataRetriever` 自带的 HTTP 栈**不会带上我们的 Cookie**，
 * 于是直接 `setDataSource(url)` 只会拿到 401。`MediaDataSource`（API 23+）
 * 正好提供了插手的机会：由我们自己实现 `readAt`，每一次随机读取都用应用内
 * 那个带 Cookie 的 OkHttp 客户端去取。
 *
 * ### 为什么不用"整包下载再截帧"
 * 视频动辄几十上百 MB，为了第一帧下载整个文件完全不成比例。服务端支持
 * `Range: bytes=…`（返回 206 + `Content-Range`），所以我们按
 * [chunkSize] 对齐做**分块缓存**：`MediaMetadataRetriever` 解析 moov 与取帧时
 * 通常只碰十几个 64KB 块，请求数可控、流量可控。
 *
 * ### 安全边界
 * 只有真正返回 `206` 且长度不超过一个块的响应才会被缓存。若服务端忽略了
 * Range（返回 200 全量），这里会直接放弃 —— 宁可退回渐变占位，
 * 也绝不把整个视频读进内存。
 */
class HttpRangeMediaDataSource(
    private val url: String,
    private val client: OkHttpClient,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : MediaDataSource() {

    @Volatile
    private var totalSize: Long = -1L

    private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_CACHED_CHUNKS
    }

    /** 一次探测拿总长度：`Range: bytes=0-0` 的 `Content-Range` 里带着总大小 */
    override fun getSize(): Long {
        totalSize.takeIf { it >= 0 }?.let { return it }
        synchronized(this) {
            totalSize.takeIf { it >= 0 }?.let { return it }
            val size = try {
                probeSize()
            } catch (_: IOException) {
                -1L
            } catch (_: RuntimeException) {
                -1L
            }
            totalSize = size
            return size
        }
    }

    private fun probeSize(): Long {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            // 让 Range 与压缩不要互相打架
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { response ->
            // 401/404 的响应体也有 Content-Length，但它不是媒体长度 —— 必须排除
            if (!response.isSuccessful) return -1L
            val contentRange = response.header("Content-Range")
            if (contentRange != null) {
                val total = contentRange.substringAfterLast('/', "").trim().toLongOrNull()
                if (total != null && total > 0) return total
            }
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            // 200 表示服务端没理会 Range；此时 Content-Length 就是完整大小，仍可用
            return if (length > 0) length else -1L
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val total = getSize()
        if (total <= 0L || position < 0L || position >= total) return -1

        val wanted = minOf(size.toLong(), total - position).toInt()
        var copied = 0
        while (copied < wanted) {
            val absolute = position + copied
            val index = absolute / chunkSize
            val chunk = loadChunk(index) ?: return if (copied > 0) copied else -1
            val startInChunk = (absolute % chunkSize).toInt()
            if (startInChunk >= chunk.size) return if (copied > 0) copied else -1
            val count = minOf(wanted - copied, chunk.size - startInChunk)
            System.arraycopy(chunk, startInChunk, buffer, offset + copied, count)
            copied += count
        }
        return copied
    }

    override fun close() {
        synchronized(this) { chunks.clear() }
    }

    private fun loadChunk(index: Long): ByteArray? {
        synchronized(this) { chunks[index]?.let { return it } }

        val beginning = index * chunkSize
        val end = minOf(beginning + chunkSize - 1, getSize() - 1)
        if (beginning > end) return null

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$beginning-$end")
            .header("Accept-Encoding", "identity")
            .build()

        val bytes = try {
            client.newCall(request).execute().use { response ->
                // 必须真的是 206 分片；否则宁可放弃，也不能顺手把整个视频读进内存
                if (response.code != 206) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > chunkSize) return@use null
                val read = body.bytes()
                if (read.isEmpty() || read.size > chunkSize) null else read
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        if (bytes != null) {
            synchronized(this) { chunks[index] = bytes }
        }
        return bytes
    }

    private companion object {
        /** 64KB：够 `MediaMetadataRetriever` 少发几十次请求，单块也足够小 */
        const val DEFAULT_CHUNK_SIZE = 64 * 1024

        /** 最多缓存 32 块 = 2MB，任何时刻都不会因为封面把内存顶上去 */
        const val MAX_CACHED_CHUNKS = 32
    }
}

package com.fluxframe.app.core.net

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * 把 `content://` 媒体流直接喂给 OkHttp，**不把文件读进内存**。
 *
 * 上传 1 GB 视频时如果用 ByteArray 会直接 OOM，而服务端本身支持最大 2 GB
 * 的流式 multipart，所以这里必须走流式 RequestBody。
 */
class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType?,
    private val declaredLength: Long = -1L,
    /** 已写入的字节数回调，用于上传进度（在 OkHttp 的写线程上调用） */
    private val onProgress: ((written: Long, total: Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = if (declaredLength > 0) declaredLength else -1L

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")
        input.use { stream ->
            stream.source().use { source ->
                val total = if (declaredLength > 0) declaredLength else -1L
                var written = 0L
                val buffer = okio.Buffer()
                while (true) {
                    buffer.clear()
                    val read = source.read(buffer, DEFAULT_CHUNK)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    written += read
                    onProgress?.invoke(written, total)
                }
                sink.flush()
            }
        }
    }

    private companion object {
        const val DEFAULT_CHUNK = 64L * 1024L
    }
}

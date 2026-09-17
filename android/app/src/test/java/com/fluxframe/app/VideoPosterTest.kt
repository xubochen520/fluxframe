package com.fluxframe.app

import com.fluxframe.app.core.media.HttpRangeMediaDataSource
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.ImageListResponse
import com.fluxframe.app.data.model.LoginRequest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「视频缩略图取真实帧」的数据源测试 —— 打**真实服务端**。
 *
 * 这条链路最容易被想当然：`MediaMetadataRetriever` 自带的 HTTP 栈不带会话 Cookie，
 * 直接 `setDataSource(url)` 只会拿到 401。因此必须由我们自己实现 `MediaDataSource`，
 * 用应用内那个带 Cookie 的 OkHttp 客户端做 Range 读取。
 *
 * 这里验证四件事：
 *  1. 分片读取真的能拿到数据（而不是被 401 挡掉）；
 *  2. 文件头与文件尾都读得到（MP4 的 moov 可能在尾部，这决定了取帧能否成功）；
 *  3. 越界读返回 -1（解码器据此判断文件结束）；
 *  4. 同一分块只发一次请求（缓存真的生效，不会把视频来回拉）。
 *
 * 服务未启动或库里没有视频时整类跳过（`assumeTrue`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPosterTest {

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }

        private val baseUrl = (System.getProperty("fluxframe.baseUrl") ?: "http://127.0.0.1:4311/").trimEnd('/')
        private val username = System.getProperty("fluxframe.user") ?: "admin"
        private val password = System.getProperty("fluxframe.pass") ?: "admin123"

        private val plainClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private var cookie: String? = null
        private var reachable = false
        private var video: ImageItem? = null

        @BeforeClass
        @JvmStatic
        fun probe() {
            reachable = runCatching {
                plainClient.newCall(Request.Builder().url("$baseUrl/api/health").get().build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (!reachable) return

            cookie = login()
            if (cookie == null) return
            video = runCatching {
                val request = Request.Builder().url("$baseUrl/api/images?sort=newest").get()
                    .header("Cookie", cookie!!)
                    .build()
                plainClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    json.decodeFromString(ImageListResponse.serializer(), text)
                        .items.firstOrNull { it.isVideo }
                }
            }.getOrNull()
        }

        private fun login(): String? {
            val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/auth/login").post(body).build()
            plainClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                return response.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').takeIf { part -> part.startsWith("fluxframe_session=") } }
                    .firstOrNull()
            }
        }

        /** 带会话 Cookie 的客户端 —— 与 AppContainer.okHttp 的行为一致 */
        private fun authenticatedClient(counter: AtomicInteger? = null): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .apply {
                    if (counter != null) {
                        addInterceptor(
                            Interceptor { chain ->
                                counter.incrementAndGet()
                                chain.proceed(chain.request())
                            },
                        )
                    }
                }
                .addInterceptor(
                    Interceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Cookie", cookie.orEmpty())
                                .header("Accept-Encoding", "identity")
                                .build(),
                        )
                    },
                )
                .build()
    }

    private fun requireServer(): ImageItem {
        assumeTrue("本地 fluxframe 服务未启动，跳过视频封面测试", reachable)
        assumeTrue("登录未成功，跳过视频封面测试", cookie != null)
        val item = video
        assumeTrue("图库里没有视频，跳过视频封面测试", item != null)
        return item!!
    }

    private fun fileUrl(item: ImageItem) = "$baseUrl/api/images/${item.id}/file"

    /** 独立探一次长度：`Range: bytes=0-0` 的 Content-Range 里带总数 */
    private fun probeLength(item: ImageItem): Long {
        val request = Request.Builder()
            .url(fileUrl(item))
            .header("Range", "bytes=0-0")
            .header("Cookie", cookie.orEmpty())
            .build()
        plainClient.newCall(request).execute().use { response ->
            assertTrue("分片请求应返回 206，实际 ${response.code}", response.code == 206)
            val total = response.header("Content-Range").orEmpty().substringAfterLast('/', "").toLongOrNull()
            assertTrue("Content-Range 应带总长度，实际=${response.header("Content-Range")}", total != null && total > 0)
            return total!!
        }
    }

    @Test
    fun `分块读取能拿到数据且首尾都可读`() {
        val item = requireServer()
        val expected = probeLength(item)
        val source = HttpRangeMediaDataSource(fileUrl(item), authenticatedClient())
        try {
            assertEquals("getSize 应与 Content-Range 里的总长度一致", expected, source.getSize())

            val head = ByteArray(32)
            assertEquals("文件头 32 字节应完整读到", 32, source.readAt(0, head, 0, 32))
            assertTrue("读到的内容不应该全是 0", head.any { it != 0.toByte() })

            // MP4 的 moov 可能在文件尾部，解码器一定会去读结尾 —— 这一段必须能读到
            val tail = ByteArray(32)
            assertEquals(
                "文件尾 32 字节应完整读到（moov 常在尾部）",
                32,
                source.readAt(expected - 32, tail, 0, 32),
            )

            // 越界：解码器据此判断文件结束
            assertEquals("越过文件末尾必须返回 -1", -1, source.readAt(expected, ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    @Test
    fun `同一分块只发一次请求`() {
        val item = requireServer()
        val counter = AtomicInteger(0)
        val source = HttpRangeMediaDataSource(fileUrl(item), authenticatedClient(counter))
        try {
            val first = ByteArray(8)
            val second = ByteArray(8)
            source.readAt(0, first, 0, 8)
            val afterFirst = counter.get()
            source.readAt(8, second, 0, 8)
            val afterSecond = counter.get()

            assertTrue("首次读取需要探测长度 + 取块，至少 2 次请求，实际 $afterFirst", afterFirst >= 2)
            assertEquals(
                "同一 64KB 分块内的第二次读取不应再发请求（缓存失效了？）",
                afterFirst,
                afterSecond,
            )
        } finally {
            source.close()
        }
    }

    /**
     * 反向验证：「不带 Cookie 就读不到」。
     *
     * 这正是**不能**直接用 `MediaMetadataRetriever.setDataSource(url)` 的原因 ——
     * 它没有我们的会话，因此必须经由本类走应用内客户端。
     */
    @Test
    fun `不带会话 Cookie 时读不到任何数据`() {
        val item = requireServer()
        val anonymous = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val source = HttpRangeMediaDataSource(fileUrl(item), anonymous)
        try {
            assertEquals("匿名请求不该拿到媒体长度", -1L, source.getSize())
            assertEquals("匿名请求不该读到字节", -1, source.readAt(0, ByteArray(16), 0, 16))
        } finally {
            source.close()
        }
    }
}

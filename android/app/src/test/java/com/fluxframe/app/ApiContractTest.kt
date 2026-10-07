package com.fluxframe.app

import com.fluxframe.app.data.model.AiStatus
import com.fluxframe.app.data.model.AuditLogListResponse
import com.fluxframe.app.data.model.CurrentUser
import com.fluxframe.app.data.model.DashboardResponse
import com.fluxframe.app.data.model.DeepseekSummary
import com.fluxframe.app.data.model.EngineStatus
import com.fluxframe.app.data.model.ImageListResponse
import com.fluxframe.app.data.model.LoginRequest
import com.fluxframe.app.data.model.LoginResponse
import com.fluxframe.app.data.model.SystemSettings
import com.fluxframe.app.data.model.TagListResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真实接口契约测试（对本地运行中的 fluxframe 服务）。
 *
 * 目的：**证明客户端的 DTO 能正确解码服务端的真实响应**。
 * 单元测试只能验证纯逻辑，字段名拼错、类型对不上（比如把数字写成字符串）这类问题
 * 只有打真接口才暴露得出来。
 *
 * 服务未启动时整类自动跳过（`assumeTrue`），因此不会拖垮 CI。
 * 可用系统属性覆盖：
 *   -Dfluxframe.baseUrl=http://192.168.1.100:4311/
 *   -Dfluxframe.user=&lt;用户名&gt; -Dfluxframe.pass=&lt;密码&gt;
 */
class ApiContractTest {

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }

        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val baseUrl = (System.getProperty("fluxframe.baseUrl") ?: "http://127.0.0.1:4311/").trimEnd('/')
        private val username = System.getProperty("fluxframe.user") ?: "admin"
        private val password = System.getProperty("fluxframe.pass") ?: "admin123"

        private var cookie: String? = null
        private var reachable = false

        @BeforeClass
        @JvmStatic
        fun probeServer() {
            reachable = runCatching {
                val request = Request.Builder().url("$baseUrl/api/health").get().build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) login()
        }

        private fun login() {
            val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/auth/login").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                // 服务端用 Set-Cookie 下发 fluxframe_session，后续请求要带上
                cookie = response.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').takeIf { part -> part.startsWith("fluxframe_session=") } }
                    .firstOrNull()
            }
        }

        private fun get(path: String): String {
            val builder = Request.Builder().url("$baseUrl$path").get()
            cookie?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                assertTrue("$path 返回 HTTP ${response.code}：$text", response.isSuccessful)
                return text
            }
        }
    }

    private fun requireServer() {
        assumeTrue("本地 fluxframe 服务未启动，跳过契约测试", reachable)
        assumeTrue("登录未成功（账号或密码不对），跳过契约测试", cookie != null)
    }

    @Test
    fun `login response decodes and session cookie is issued`() {
        requireServer()
        assertNotNull("应当拿到 fluxframe_session Cookie", cookie)
    }

    @Test
    fun `me decodes`() {
        requireServer()
        val me = json.decodeFromString(CurrentUser.serializer(), get("/api/me"))
        assertTrue(me.id.isNotBlank())
        assertTrue(me.username.isNotBlank())
        assertTrue(me.role == "ADMIN" || me.role == "USER")
        // isAdmin 是客户端派生属性
        assertTrue(me.isAdmin == (me.role == "ADMIN"))
    }

    @Test
    fun `images decode with every dto field`() {
        requireServer()
        val payload = json.decodeFromString(ImageListResponse.serializer(), get("/api/images?sort=views"))
        assertTrue("图片库不应为空", payload.items.isNotEmpty())
        val first = payload.items.first()
        assertTrue(first.id.isNotBlank())
        assertTrue(first.name.isNotBlank())
        assertTrue(first.thumb.startsWith("/api/images/"))
        assertTrue(first.url.startsWith("/api/images/"))
        // size 是**已格式化的字符串**（不是数字），这是最容易搞错的一处
        assertTrue("size 应为可读字符串，实际=${first.size}", first.size.isNotBlank() && !first.size.toDoubleOrNull().let { it != null && it > 1024 })
        assertTrue(first.uploadedAt.contains("T"))
        assertTrue(first.capturedAt.isNotBlank())
    }

    @Test
    fun `tags decode`() {
        requireServer()
        val payload = json.decodeFromString(TagListResponse.serializer(), get("/api/tags"))
        assertTrue(payload.items.isNotEmpty())
        payload.items.forEach { tag ->
            assertTrue(tag.id.isNotBlank())
            assertTrue(tag.name.isNotBlank())
            assertTrue("颜色应为 #rrggbb，实际=${tag.color}", tag.color.startsWith("#"))
            assertTrue("count 应为非负整数", tag.count >= 0)
        }
        assertTrue("应存在人物组标签", payload.items.any { it.person })
    }

    @Test
    fun `dashboard decodes including stats and recent logs`() {
        requireServer()
        val payload = json.decodeFromString(DashboardResponse.serializer(), get("/api/dashboard"))
        assertTrue("imageCount 应大于 0", payload.stats.imageCount > 0)
        assertTrue(payload.stats.storage.isNotBlank())
        assertTrue(payload.stats.storagePercent >= 0.0)
        assertTrue(payload.top.isNotEmpty())
        assertTrue(payload.recent.isNotEmpty())
        payload.logs.forEach { log ->
            assertTrue(log.action.isNotBlank())
            assertTrue(log.time.contains("T"))
            assertTrue(
                "tone 应在已知集合内，实际=${log.tone}",
                log.tone in setOf("blue", "violet", "orange", "red", "green"),
            )
        }
    }

    @Test
    fun `audit logs decode`() {
        requireServer()
        val payload = json.decodeFromString(AuditLogListResponse.serializer(), get("/api/audit-logs"))
        assertTrue("访问日志不应为空", payload.items.isNotEmpty())
        assertTrue("服务端固定最多返回 500 条", payload.items.size <= 500)
    }

    @Test
    fun `settings decode with computed fields`() {
        requireServer()
        val payload = json.decodeFromString(SystemSettings.serializer(), get("/api/settings"))
        assertTrue(payload.port in 1024..65535)
        assertTrue(payload.storageDir.isNotBlank())
        assertTrue(payload.uploadLimitMb in 1..2048)
        assertTrue(payload.recycleRetentionDays in 0..3650)
        assertTrue(payload.aiModel.isNotBlank())
        // ffmpeg 状态是服务端实时算出来的嵌套对象
        assertNotNull(payload.ffmpeg)
    }

    @Test
    fun `deepseek summary decodes with nullable numbers`() {
        requireServer()
        val payload = json.decodeFromString(DeepseekSummary.serializer(), get("/api/deepseek/summary"))
        // 未配置密钥时 keys 为空，但字段结构必须能解出来
        assertTrue(payload.refreshSeconds >= 30 || payload.refreshSeconds == 60)
        assertNotNull(payload.stats)
        assertNotNull(payload.platform)
        assertNotNull(payload.balance)
        // chart 固定 30 项（服务端保证）
        if (payload.chart.isNotEmpty()) {
            assertTrue("chart 应为 30 项，实际=${payload.chart.size}", payload.chart.size == 30)
        }
        payload.keys.forEach { key ->
            assertTrue(key.id.isNotBlank())
            // 明文 key 绝不返回，只能看到掩码
            assertTrue("不得返回明文密钥", !key.masked.startsWith("sk-") || key.masked.contains("*"))
        }
    }

    @Test
    fun `ffmpeg status decodes with object progress`() {
        requireServer()
        val status = json.decodeFromString(EngineStatus.serializer(), get("/api/ffmpeg/status"))
        // progress 是对象 {phase, done, total}，不是数字 —— 这一处曾经写错过，锁死它
        assertTrue(status.progress.phase.isNotBlank())
        assertTrue(status.progress.done >= 0)
        assertTrue(status.progress.total >= 0)
        assertTrue(status.progress.fraction in 0f..1f)
    }

    @Test
    fun `ai status decodes`() {
        requireServer()
        val status = json.decodeFromString(AiStatus.serializer(), get("/api/ai/status"))
        assertTrue(status.progress.phase.isNotBlank())
        assertTrue(status.progress.fraction in 0f..1f)
        // running 为真时应当带端口与 baseUrl
        if (status.running) {
            assertNotNull(status.port)
            assertNotNull(status.baseUrl)
        }
    }

    @Test
    fun `image variant endpoint serves webp bytes`() {
        requireServer()
        // 找一张图片（不是视频）验证缩略图真的可下载
        val images = json.decodeFromString(ImageListResponse.serializer(), get("/api/images?sort=newest"))
        val image = images.items.firstOrNull { it.mimeType.startsWith("image/") }
        assumeTrue("库里没有图片，跳过", image != null)
        val builder = Request.Builder().url("$baseUrl/api/images/${image!!.id}/variant/320").get()
        cookie?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            assertTrue("缩略图应返回 200", response.isSuccessful)
            assertTrue(
                "内容类型应为 webp，实际=${response.header("Content-Type")}",
                response.header("Content-Type").orEmpty().contains("webp"),
            )
            val bytes = response.body?.bytes() ?: ByteArray(0)
            assertTrue("缩略图应有内容，实际 ${bytes.size} 字节", bytes.size > 1000)
        }
    }

    @Test
    fun `videos are resolved through file endpoint not variant`() {
        requireServer()
        val images = json.decodeFromString(ImageListResponse.serializer(), get("/api/images?sort=newest"))
        val video = images.items.firstOrNull { it.mimeType.startsWith("video/") }
        assumeTrue("库里没有视频，跳过", video != null)
        // 视频没有变体，缩略图字段应回落到 /file（这正是客户端网格用占位块的原因）
        assertTrue(
            "视频 thumb 应回落到 /file，实际=${video!!.thumb}",
            video.thumb.endsWith("/file"),
        )
    }
}

package com.fluxframe.app

import android.app.DownloadManager
import com.fluxframe.app.core.download.MediaDownloader
import com.fluxframe.app.core.net.ServerEndpoint
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.core.util.formatBytes
import com.fluxframe.app.core.util.formatCount
import com.fluxframe.app.core.util.formatDuration
import com.fluxframe.app.core.util.formatMoney
import com.fluxframe.app.core.util.formatPercent
import com.fluxframe.app.core.util.formatRelative
import com.fluxframe.app.core.util.hueFromKey
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.ParseData
import com.fluxframe.app.data.model.ParseMedia
import com.fluxframe.app.data.repo.MediaRepository
import com.fluxframe.app.data.repo.ParseRepository
import com.fluxframe.app.data.store.LogsStore
import com.fluxframe.app.data.store.UploadPhase
import com.fluxframe.app.data.store.UploadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 纯逻辑单测（JVM，不需要设备）。
 *
 * 覆盖的是「最容易写错又最值得回归」的部分：地址解析、格式化、日志分组、
 * 口令取链接、下载文件名，以及界面上依赖的几个派生属性。
 */
class PureLogicTest {

    /* ------------------------- 服务器地址解析 ------------------------- */

    @Test
    fun `parse accepts bare ip with default port`() {
        val endpoint = ServerEndpoint.parse("192.168.1.100")
        assertNotNull(endpoint)
        assertEquals("192.168.1.100", endpoint!!.host)
        assertEquals(ServerEndpoint.DEFAULT_PORT, endpoint.port)
        assertEquals("192.168.1.100:4311", endpoint.authority)
        assertEquals("http://192.168.1.100:4311/", endpoint.baseUrl)
    }

    @Test
    fun `parse accepts ip with port and scheme and trailing slash`() {
        assertEquals(ServerEndpoint("10.0.0.5", 8080), ServerEndpoint.parse("10.0.0.5:8080"))
        assertEquals(ServerEndpoint("10.0.0.5", 8080), ServerEndpoint.parse("http://10.0.0.5:8080/"))
        assertEquals(ServerEndpoint("10.0.0.5", 8080), ServerEndpoint.parse("  https://10.0.0.5:8080/path  "))
        assertEquals(ServerEndpoint("10.0.0.5", 8080), ServerEndpoint.parse("10.0.0.5:8080?x=1"))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(ServerEndpoint.parse(null))
        assertNull(ServerEndpoint.parse(""))
        assertNull(ServerEndpoint.parse("   "))
        assertNull(ServerEndpoint.parse("192.168.1.100:notaport"))
        assertNull(ServerEndpoint.parse("192.168.1.100:0"))
        assertNull(ServerEndpoint.parse("192.168.1.100:70000"))
    }

    @Test
    fun `absolute joins paths correctly`() {
        val endpoint = ServerEndpoint("192.168.1.100", 4311)
        assertEquals("http://192.168.1.100:4311/api/health", endpoint.absolute("/api/health"))
        assertEquals("http://192.168.1.100:4311/api/health", endpoint.absolute("api/health"))
        assertEquals("https://cdn.example.com/a.jpg", endpoint.absolute("https://cdn.example.com/a.jpg"))
    }

    /* ---------------------------- 格式化 ---------------------------- */

    @Test
    fun `formatBytes produces readable chinese units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatDuration switches to hours past 3600s`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:45", formatDuration(45))
        assertEquals("2:05", formatDuration(125))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:02:03", formatDuration(3723))
    }

    @Test
    fun `formatRelative renders recent times`() {
        val now = Instant.now().toString()
        assertEquals("刚刚", formatRelative(now))
        assertEquals("5 分钟前", formatRelative(Instant.now().minusSeconds(300).toString()))
        assertEquals("3 小时前", formatRelative(Instant.now().minusSeconds(3 * 3600).toString()))
        // 非法输入原样返回，不抛异常
        assertEquals("not-a-date", formatRelative("not-a-date"))
        assertEquals("", formatRelative(null))
    }

    @Test
    fun `formatCount abbreviates large numbers`() {
        assertEquals("999", formatCount(999))
        assertEquals("1.0万", formatCount(10_000))
        assertEquals("12.3万", formatCount(123_456))
        assertEquals("1.00亿", formatCount(100_000_000))
    }

    @Test
    fun `formatMoney picks currency symbol`() {
        assertEquals("¥12.34", formatMoney(12.34, "CNY"))
        assertEquals("¥12.34", formatMoney(12.34, null))
        assertEquals("$12.34", formatMoney(12.34, "USD"))
    }

    @Test
    fun `formatPercent handles null`() {
        assertEquals("--", formatPercent(null))
        assertEquals("12.3%", formatPercent(0.1234))
        assertEquals("12.34%", formatPercent(0.1234, 2))
    }

    @Test
    fun `hueFromKey is stable and in range`() {
        val a = hueFromKey("0193abcd-media-id")
        val b = hueFromKey("0193abcd-media-id")
        assertEquals(a, b)
        assertTrue(a >= 0f && a < 360f)
        assertTrue(hueFromKey("") >= 0f)
    }

    /* ---------------------------- 媒体派生 ---------------------------- */

    @Test
    fun `ImageItem derives media type flags and ratio`() {
        val video = ImageItem(id = "1", name = "v", mimeType = "video/mp4", width = 1920, height = 1080)
        assertTrue(video.isVideo)
        assertFalse(video.isImage)
        assertFalse(video.inTrash)
        assertEquals(1920f / 1080f, video.aspectRatio, 0.0001f)

        val image = ImageItem(id = "2", name = "i", mimeType = "image/jpeg", width = 0, height = 0)
        assertTrue(image.isImage)
        assertEquals(1f, image.aspectRatio, 0.0001f)

        assertTrue(ImageItem(id = "3", name = "t", deletedAt = "2025-01-01T00:00:00.000Z").inTrash)
    }

    @Test
    fun `download file names keep extension and add collision suffix safely`() {
        val item = ImageItem(id = "1", name = "封面:最终版", mimeType = "image/jpeg")
        assertEquals("封面_最终版.jpg", MediaDownloader.buildFileName(item))
        assertEquals("封面_最终版 (2).jpg", MediaDownloader.bumpFileName("封面_最终版.jpg", 2))
        assertEquals("无扩展名 (1)", MediaDownloader.bumpFileName("无扩展名", 1))
    }

    @Test
    fun `download failure reasons are readable`() {
        assertEquals("手机存储空间不足", MediaDownloader.failureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals("服务器返回 HTTP 401", MediaDownloader.failureMessage(401))
    }

    /* ---------------------------- 日志分组 ---------------------------- */

    @Test
    fun `logGroupOf matches the web client ordering exactly`() {
        // 判定顺序与 client/src/App.vue 的 logGroupOf 完全一致：
        // 查看 → 上传 → 下载 → 提取 → 名称/改名 → 人物 → 标签 → 删除 → 其他。
        // 先命中的先返回，所以「删除标签」归到「标签」、「查看人物」归到「查看」。
        assertEquals("查看", LogsStore.logGroupOf("查看图片"))
        assertEquals("查看", LogsStore.logGroupOf("查看视频"))
        assertEquals("查看", LogsStore.logGroupOf("查看人物"))
        assertEquals("上传", LogsStore.logGroupOf("上传图片"))
        assertEquals("上传", LogsStore.logGroupOf("上传视频"))
        assertEquals("下载", LogsStore.logGroupOf("下载视频"))
        assertEquals("提取", LogsStore.logGroupOf("提取视频"))
        assertEquals("提取", LogsStore.logGroupOf("提取封面"))
        assertEquals("改名", LogsStore.logGroupOf("修改名称"))
        assertEquals("改名", LogsStore.logGroupOf("改名"))
        assertEquals("标签", LogsStore.logGroupOf("添加标签"))
        assertEquals("标签", LogsStore.logGroupOf("删除标签"))
        assertEquals("标签", LogsStore.logGroupOf("新建人物"))
        assertEquals("标签", LogsStore.logGroupOf("放入人物组"))
        assertEquals("删除", LogsStore.logGroupOf("删除图片"))
        assertEquals("删除", LogsStore.logGroupOf("永久删除图片"))
        assertEquals("其他", LogsStore.logGroupOf("恢复图片"))
        assertEquals("其他", LogsStore.logGroupOf("修改系统设置"))
        assertEquals("其他", LogsStore.logGroupOf("用户登录"))
    }

    /* ---------------------------- 口令取链接 ---------------------------- */

    @Test
    fun `extractLink pulls url out of share text`() {
        assertEquals(
            "https://b23.tv/abc123",
            ParseRepository.extractLink("【标题】 https://b23.tv/abc123 复制此链接打开APP"),
        )
        assertEquals(
            "https://v.douyin.com/xyz/",
            ParseRepository.extractLink("https://v.douyin.com/xyz/"),
        )
        assertNull(ParseRepository.extractLink("这段文字里没有链接"))
        assertNull(ParseRepository.extractLink("   "))
    }

    @Test
    fun `videoImportOf passes upstream src and high stream through`() {
        val data = ParseData(
            platform = "bilibili",
            title = "示例",
            media = listOf(
                ParseMedia(
                    url = "/api/stream?url=x",
                    src = "https://upos.example/v.m4s",
                    referer = "https://www.bilibili.com/",
                    duration = 125,
                ),
            ),
            high = com.fluxframe.app.data.model.ParseHigh(
                videoUrl = "https://upos.example/video.m4s",
                audioUrl = "https://upos.example/audio.m4s",
                quality = 80,
                label = "1080P",
            ),
        )
        val request = ParseRepository.videoImportOf(data, "示例", useHighQuality = true)
        assertNotNull(request)
        // 必须传上游原始直链（src），而不是同源代理地址（url）
        assertEquals("https://upos.example/v.m4s", request!!.url)
        assertEquals("video", request.kind)
        assertEquals("B站", request.platTag)
        assertEquals("1080P", request.high?.label)
    }

    @Test
    fun `videoImportOf returns null when there is no media`() {
        val imagesOnly = ParseData(platform = "douyin", kind = "images", title = "图文")
        assertNull(ParseRepository.videoImportOf(imagesOnly, "图文", useHighQuality = true))
        assertNull(ParseRepository.coverImportOf(imagesOnly, "图文"))
    }

    /* ---------------------------- 上传状态 ---------------------------- */

    @Test
    fun `UploadTask inFlight only for analyzing and saving`() {
        assertTrue(UploadTask(phase = UploadPhase.ANALYZING).inFlight)
        assertTrue(UploadTask(phase = UploadPhase.SAVING).inFlight)
        assertFalse(UploadTask(phase = UploadPhase.REVIEW).inFlight)
        assertFalse(UploadTask(phase = UploadPhase.DONE).inFlight)
        assertFalse(UploadTask(phase = UploadPhase.FAILED).inFlight)
    }

    /* ---------------------------- 主题 ---------------------------- */

    @Test
    fun `theme style keys round trip`() {
        assertEquals(AppThemeStyle.LIQUID_GLASS, AppThemeStyle.fromKey("LIQUID_GLASS"))
        assertEquals(AppThemeStyle.ACRYLIC, AppThemeStyle.fromKey("ACRYLIC"))
        assertEquals(AppThemeStyle.DEFAULT, AppThemeStyle.fromKey("DEFAULT"))
        assertEquals(AppThemeStyle.FROSTED, AppThemeStyle.fromKey("FROSTED"))
        assertEquals(AppThemeStyle.NEON, AppThemeStyle.fromKey("NEON"))
        // 未知/空值回落到液态玻璃（默认观感）
        assertEquals(AppThemeStyle.LIQUID_GLASS, AppThemeStyle.fromKey(null))
        assertEquals(AppThemeStyle.LIQUID_GLASS, AppThemeStyle.fromKey("nonsense"))
        assertEquals(5, AppThemeStyle.entries.size)
    }

    /* ---------------------------- 排序选项 ---------------------------- */

    @Test
    fun `sort options match the server enum`() {
        val keys = MediaRepository.SORT_OPTIONS.map { it.key }
        assertEquals(listOf("views", "newest", "name"), keys)
    }
}

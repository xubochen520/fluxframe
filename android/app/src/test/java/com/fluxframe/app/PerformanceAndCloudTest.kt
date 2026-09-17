package com.fluxframe.app

import com.fluxframe.app.core.media.HttpRangeMediaDataSource
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.core.prefs.UiPrefs
import com.fluxframe.app.fluidcloud.CapsuleKind
import com.fluxframe.app.fluidcloud.CapsuleState
import com.fluxframe.app.fluidcloud.CapsuleTone
import com.fluxframe.app.fluidcloud.FluidCloudBackend
import com.fluxframe.app.fluidcloud.FluidCloudCapability
import com.fluxframe.app.ui.HeaderAutoHide
import com.fluxframe.app.ui.theme.glassTokensFor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 这一轮优化与新功能里**不需要真机、也不需要服务端**的那部分断言。
 *
 * 都是纯逻辑，因此不设 `assumeTrue`：任何一次构建都会真的跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceAndCloudTest {

    /* --------------------------- 性能相关的默认值 --------------------------- */

    @Test
    fun `新装的默认偏好是省电且信息量正确的`() {
        val prefs = UiPrefs()
        assertTrue("默认隐藏手势小白条", prefs.immersiveMode)
        assertTrue("默认顶栏随滚动收起", prefs.autoHideHeader)
        assertTrue("默认视频缩略图取真实帧", prefs.videoPosterEnabled)
        assertTrue("默认开启背景动效（但已量化到 12fps）", prefs.animationsEnabled)
        assertTrue("默认开启底栏触觉反馈", prefs.bottomBarHapticsEnabled)
        assertTrue("网格列数应落在 2..5", prefs.gridColumns in 2..5)
    }

    @Test
    fun `玻璃参数在全部主题下都自洽`() {
        AppThemeStyle.entries.forEach { style ->
            listOf(true, false).forEach { dark ->
                val tokens = glassTokensFor(style, dark, noiseEnabled = true)
                assertEquals("token 必须记住自己的风格", style, tokens.style)
                assertTrue("$style: tintAlpha 必须在 0..1，实际=${tokens.tintAlpha}", tokens.tintAlpha in 0f..1f)
                assertTrue("$style: borderAlpha 必须在 0..1", tokens.borderAlpha in 0f..1f)
                assertTrue("$style: backdropSoftness 必须在 0..1", tokens.backdropSoftness in 0f..1f)
                assertTrue("$style: 圆角必须为正", tokens.cornerRadius.value > 0f)

                // 「默认」风格必须完全不做背景副本，这是省电的底线
                if (style == AppThemeStyle.DEFAULT) {
                    assertFalse("默认风格不应绘制背景副本", tokens.drawsBackdrop)
                    assertFalse("默认风格不应绘制噪点", tokens.drawsNoise)
                    assertFalse("默认风格不应绘制高光扫过", tokens.drawsSpecular)
                }
                // 高光扫过只用于液态玻璃与霓虹风格
                if (style != AppThemeStyle.LIQUID_GLASS && style != AppThemeStyle.NEON) {
                    assertFalse("$style 不应绘制高光扫过", tokens.drawsSpecular)
                }
            }
        }
    }

    @Test
    fun `关掉噪点开关后亚克力与液态玻璃都不再画噪点`() {
        listOf(AppThemeStyle.ACRYLIC, AppThemeStyle.LIQUID_GLASS).forEach { style ->
            assertFalse(glassTokensFor(style, dark = true, noiseEnabled = false).drawsNoise)
            assertTrue(glassTokensFor(style, dark = true, noiseEnabled = true).drawsNoise)
        }
    }

    /* ------------------------------- 流体云 ------------------------------- */

    @Test
    fun `能力徽标按链路与授权状态给出准确文案`() {
        val base = FluidCloudCapability(backend = FluidCloudBackend.NOTIFICATION, description = "")
        assertEquals("未授权时优先提示待授权", "待授权", base.copy(notificationsAllowed = false).badge)
        assertEquals("系统通知", base.badge)
        assertEquals(
            "实况更新",
            base.copy(backend = FluidCloudBackend.SYSTEM_LIVE_UPDATE).badge,
        )
        assertEquals(
            "OPPO 通道可用时两条链路都写上",
            "流体云 + 实况窗",
            base.copy(oppoBound = true).badge,
        )
    }

    @Test
    fun `胶囊状态把未知进度表达为不确定进度`() {
        val unknown = CapsuleState(active = true, kind = CapsuleKind.UPLOAD, title = "上传")
        assertTrue(unknown.indeterminate)
        assertEquals("", unknown.percentText)

        val known = unknown.copy(progress = 0.427f)
        assertFalse(known.indeterminate)
        assertEquals("42%", known.percentText)

        // 超出范围要夹紧，不能出现 150% 或负百分比
        assertEquals("100%", known.copy(progress = 1.8f).percentText)
        assertEquals("0%", known.copy(progress = -0.5f).percentText)
        assertEquals(CapsuleTone.INFO, known.tone)
    }

    /* --------------------------- 顶栏自动收起的规则 --------------------------- */

    @Test
    fun `向下滚够阈值后顶栏才收起`() {
        val state = HeaderAutoHide(enabled = true, threshold = 56f)
        assertFalse("一开始不该收起", state.hidden)
        assertFalse("没滚动过不该标记为已滚动", state.scrolled)

        state.onScroll(consumedY = -20f, availableY = 0f)
        assertFalse("20px 还不够", state.hidden)
        assertTrue("滚过就应该标记已滚动（用于浮现玻璃底板）", state.scrolled)

        state.onScroll(consumedY = -20f, availableY = 0f)
        assertFalse("40px 还不够", state.hidden)

        state.onScroll(consumedY = -20f, availableY = 0f)
        assertTrue("累计 60px ≥ 56px，应收起", state.hidden)
    }

    @Test
    fun `往回滑要把累积量抵消掉才放出来`() {
        val state = HeaderAutoHide(enabled = true, threshold = 56f)
        state.onScroll(consumedY = -100f, availableY = 0f)
        assertTrue(state.hidden)

        state.onScroll(consumedY = 40f, availableY = 0f)
        assertTrue("累积量还剩 60 > 0，仍应保持收起", state.hidden)

        state.onScroll(consumedY = 80f, availableY = 0f)
        assertFalse("抵消到 0 以下就该放出来", state.hidden)
    }

    @Test
    fun `列表到顶再往上拖时顶栏立刻回来`() {
        val state = HeaderAutoHide(enabled = true, threshold = 56f)
        state.onScroll(consumedY = -200f, availableY = 0f)
        assertTrue(state.hidden)

        // 已经到顶：子容器没得消费，availableY > 0
        state.onScroll(consumedY = 0f, availableY = 30f)
        assertFalse("到顶必须立刻放出顶栏，不必再抵消", state.hidden)
        assertFalse("并且要清掉已滚动标记", state.scrolled)
    }

    @Test
    fun `关掉自动收起后顶栏永不隐藏但仍记录滚动状态`() {
        val state = HeaderAutoHide(enabled = false, threshold = 56f)
        repeat(10) { state.onScroll(consumedY = -100f, availableY = 0f) }
        assertFalse("开关关掉后不该隐藏", state.hidden)
        assertTrue("滚动状态仍要记录，顶栏才能浮现底板", state.scrolled)

        state.reset()
        assertFalse(state.scrolled)
    }

    /* ------------------------ 视频封面数据源的失败路径 ------------------------ */

    /**
     * 服务不可达时必须是"安静地失败"，而不是抛异常或把整个文件读进内存。
     * 这条路径决定了弱网下网格会不会白屏或崩溃。
     */
    @Test
    fun `服务不可达时探测长度与读取都安静失败`() {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        // 127.0.0.1:1 一定连不上
        val source = HttpRangeMediaDataSource("http://127.0.0.1:1/api/images/x/file", client)
        try {
            assertEquals(-1L, source.getSize())
            assertEquals(-1, source.readAt(0, ByteArray(16), 0, 16))
            // 重复调用不会因为缓存了失败结果而行为突变
            assertEquals(-1L, source.getSize())
        } finally {
            source.close()
        }
    }
}

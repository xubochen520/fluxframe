package com.fluxframe.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fluxframe.app.core.prefs.AppPreferences
import com.fluxframe.app.core.prefs.UiPrefs
import com.fluxframe.app.ui.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这一轮四项改动里不需要真机就能验证的部分。
 *
 * 两件事最容易出错、而且错了以后"看起来只是有点别扭"、很难归因：
 *  1. 双击缩放要**以点击位置为焦点** —— 点在哪，那个像素就该留在原地；
 *  2. 自定义取帧秒数的**边界**（负数、超上限、空串、小数位过多）。
 */
class ZoomAndPosterSettingsTest {

    /* ------------------------- 双击缩放的焦点计算 ------------------------- */

    private val box = Size(1000f, 2000f)

    @Test
    fun `点正中间放大时不需要平移`() {
        val center = Offset(500f, 1000f)
        val offset = ZoomMath.focalOffset(center, box, ZoomMath.DOUBLE_TAP_SCALE)
        assertEquals(0f, offset.x, 0.001f)
        assertEquals(0f, offset.y, 0.001f)
    }

    @Test
    fun `点在右侧时内容要向左移 让那个点留在原地`() {
        // 点在右边缘：相对中心 +500，放大 2.5 倍后应平移 500*(1-2.5) = -750
        val offset = ZoomMath.focalOffset(Offset(1000f, 1000f), box, 2.5f)
        assertEquals(-750f, offset.x, 0.001f)
        assertEquals(0f, offset.y, 0.001f)
    }

    @Test
    fun `点在左边时方向相反`() {
        val offset = ZoomMath.focalOffset(Offset(0f, 1000f), box, 2.5f)
        assertEquals(750f, offset.x, 0.001f)
    }

    @Test
    fun `焦点公式满足不变量 点击点的屏幕位置不变`() {
        val tap = Offset(300f, 400f)
        val scale = 2.5f
        val offset = ZoomMath.focalOffset(tap, box, scale)
        val center = Offset(box.width / 2f, box.height / 2f)
        // 屏幕位置 = 中心 + (点 - 中心) * scale + offset
        val relative = tap - center
        val screenX = center.x + relative.x * scale + offset.x
        val screenY = center.y + relative.y * scale + offset.y
        assertEquals("被点到的像素必须留在原地", tap.x, screenX, 0.001f)
        assertEquals("被点到的像素必须留在原地", tap.y, screenY, 0.001f)
    }

    @Test
    fun `倍率不超过 1 时不产生位移`() {
        assertEquals(Offset.Zero, ZoomMath.focalOffset(Offset(900f, 100f), box, 1f))
        assertEquals(Offset.Zero, ZoomMath.focalOffset(Offset(900f, 100f), box, 0.5f))
    }

    @Test
    fun `平移被限制在放大后多出来的范围内`() {
        val scale = 2f
        // 最多只能移动 (2-1)/2 * 宽 = 500
        val clamped = ZoomMath.clamp(Offset(9999f, -9999f), scale, box)
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(-1000f, clamped.y, 0.001f)
    }

    @Test
    fun `回到一倍时位移强制归零`() {
        assertEquals(Offset.Zero, ZoomMath.clamp(Offset(300f, 300f), 1f, box))
    }

    @Test
    fun `双指缩放会被夹在 1 到 6 倍之间`() {
        assertEquals(1f, ZoomMath.scaleBy(1f, 0.5f), 0.001f)
        assertEquals(6f, ZoomMath.scaleBy(3f, 10f), 0.001f)
        assertEquals(2f, ZoomMath.scaleBy(1f, 2f), 0.001f)
    }

    @Test
    fun `缓动曲线是单调推进的三次贝塞尔`() {
        val easing = ZoomMath.ZoomEasing
        assertEquals(0f, easing.transform(0f), 0.01f)
        assertEquals(1f, easing.transform(1f), 0.01f)
        // 起步快：前 20% 时间里应该已经走完超过 20% 的进度
        assertTrue("贝塞尔缓动应当起步更快，实际=${easing.transform(0.2f)}", easing.transform(0.2f) > 0.2f)
        // 全程单调不减
        var previous = -1f
        for (i in 0..20) {
            val value = easing.transform(i / 20f)
            assertTrue("缓动曲线必须单调不减", value >= previous - 0.0001f)
            previous = value
        }
    }

    /* --------------------------- 取帧秒数的解析 --------------------------- */

    @Test
    fun `合法的秒数会被接受并保留两位小数`() {
        assertEquals(0f, AppPreferences.parsePosterSeconds("0")!!, 0.0001f)
        assertEquals(0.5f, AppPreferences.parsePosterSeconds("0.5")!!, 0.0001f)
        assertEquals(3f, AppPreferences.parsePosterSeconds("3")!!, 0.0001f)
        assertEquals(1.24f, AppPreferences.parsePosterSeconds("1.239")!!, 0.0001f)
        assertEquals(600f, AppPreferences.parsePosterSeconds("600")!!, 0.0001f)
        // 前后空格不该让人白输一遍
        assertEquals(1.5f, AppPreferences.parsePosterSeconds(" 1.5 ")!!, 0.0001f)
        // 诚实记录一个浮点细节：Float 存不下 12.345，实际值略小于它，因此四舍五入到 12.34。
        // 取帧位置精确到 0.01 秒已经远超需要，不值得为此引入 BigDecimal。
        assertEquals(12.34f, AppPreferences.parsePosterSeconds("12.345")!!, 0.0001f)
    }

    @Test
    fun `非法输入一律拒绝保存`() {
        assertNull(AppPreferences.parsePosterSeconds(""))
        assertNull(AppPreferences.parsePosterSeconds("abc"))
        assertNull("负数没有意义", AppPreferences.parsePosterSeconds("-1"))
        assertNull("超过 10 分钟拒绝", AppPreferences.parsePosterSeconds("601"))
        assertNull(AppPreferences.parsePosterSeconds("NaN"))
    }

    @Test
    fun `默认取帧位置是零点五秒`() {
        val prefs = UiPrefs()
        assertEquals(0.5f, prefs.videoPosterSeconds, 0.0001f)
        assertTrue("默认开启真实帧封面", prefs.videoPosterEnabled)
        assertNotNull(AppPreferences.parsePosterSeconds(prefs.videoPosterSeconds.toString()))
    }
}

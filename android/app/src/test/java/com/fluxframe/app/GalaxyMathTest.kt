package com.fluxframe.app

import androidx.compose.ui.geometry.Offset
import com.fluxframe.app.ui.galaxy.GalaxyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星系图的坐标与交互数学。
 *
 * 这些断言对应网页端真踩过的两个 bug：y 轴没翻转（画面正常但手指点不准）、
 * 拖动两轴符号写反（上下拖和手相反）。它们在截图里看不出来，只能靠这里盯住。
 */
class GalaxyMathTest {

    private val viewportWidth = 1080f
    private val viewportHeight = 1920f

    private fun camera(centerX: Float = 0f, centerY: Float = 0f, zoom: Float = 400f) =
        GalaxyMath.Camera(centerX, centerY, zoom)

    @Test
    fun `world to screen flips y because world +y points up`() {
        val cam = camera()
        // 世界上方的点（y 更大）必须画在屏幕更靠上的位置（屏幕 y 更小）
        val upper = GalaxyMath.worldToScreen(0f, 0.5f, cam, viewportWidth, viewportHeight)
        val lower = GalaxyMath.worldToScreen(0f, -0.5f, cam, viewportWidth, viewportHeight)
        assertTrue("世界 +y 应该映射到屏幕更小的 y，实际 upper=${upper.y} lower=${lower.y}", upper.y < lower.y)
        // 原点落在画布正中
        val origin = GalaxyMath.worldToScreen(0f, 0f, cam, viewportWidth, viewportHeight)
        assertEquals(viewportWidth / 2f, origin.x, 0.001f)
        assertEquals(viewportHeight / 2f, origin.y, 0.001f)
    }

    @Test
    fun `screen to world is the exact inverse of world to screen`() {
        val cam = camera(centerX = 0.37f, centerY = -0.21f, zoom = 913f)
        for (world in listOf(Offset(0f, 0f), Offset(0.8f, -0.6f), Offset(-0.95f, 0.72f))) {
            val screen = GalaxyMath.worldToScreen(world.x, world.y, cam, viewportWidth, viewportHeight)
            val back = GalaxyMath.screenToWorld(screen.x, screen.y, cam, viewportWidth, viewportHeight)
            assertEquals(world.x, back.x, 0.001f)
            assertEquals(world.y, back.y, 0.001f)
        }
    }

    @Test
    fun `dragging down moves the content down`() {
        val cam = camera()
        val node = Offset(0f, 0f)
        val before = GalaxyMath.worldToScreen(node.x, node.y, cam, viewportWidth, viewportHeight)
        // 手指往下拖 120px（屏幕 y 增大）
        val panned = GalaxyMath.panCamera(cam, dragX = 0f, dragY = 120f)
        val after = GalaxyMath.worldToScreen(node.x, node.y, panned, viewportWidth, viewportHeight)
        assertEquals("向下拖动应该让内容也向下移动 120px", before.y + 120f, after.y, 0.01f)
        assertEquals(before.x, after.x, 0.01f)
    }

    @Test
    fun `dragging right moves the content right`() {
        val cam = camera()
        val before = GalaxyMath.worldToScreen(0f, 0f, cam, viewportWidth, viewportHeight)
        val panned = GalaxyMath.panCamera(cam, dragX = 140f, dragY = 0f)
        val after = GalaxyMath.worldToScreen(0f, 0f, panned, viewportWidth, viewportHeight)
        assertEquals(before.x + 140f, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun `fit camera keeps every node inside the viewport`() {
        val points = listOf(
            Offset(-1f, -0.75f), Offset(1f, 0.75f), Offset(-0.2f, 0.6f), Offset(0.9f, -0.1f),
        )
        val fit = GalaxyMath.fitCamera(GalaxyMath.bounds(points), viewportWidth, viewportHeight)
        for (point in points) {
            val screen = GalaxyMath.worldToScreen(point.x, point.y, fit, viewportWidth, viewportHeight)
            assertTrue("x 越界：$screen", screen.x >= 0f && screen.x <= viewportWidth)
            assertTrue("y 越界：$screen", screen.y >= 0f && screen.y <= viewportHeight)
        }
    }

    @Test
    fun `fit camera centres on the actual bounds not on the origin`() {
        val points = listOf(Offset(0.4f, 0.3f), Offset(0.8f, 0.5f))
        val fit = GalaxyMath.fitCamera(GalaxyMath.bounds(points), viewportWidth, viewportHeight)
        assertEquals(0.6f, fit.centerX, 0.001f)
        assertEquals(0.4f, fit.centerY, 0.001f)
    }

    @Test
    fun `zoom keeps the anchor point pinned`() {
        val fit = GalaxyMath.fitCamera(GalaxyMath.bounds(listOf(Offset(-1f, -1f), Offset(1f, 1f))), viewportWidth, viewportHeight)
        val cam = fit
        val anchor = Offset(300f, 1500f)
        val worldBefore = GalaxyMath.screenToWorld(anchor.x, anchor.y, cam, viewportWidth, viewportHeight)
        val zoomed = GalaxyMath.zoomCameraAt(cam, 2f, anchor.x, anchor.y, viewportWidth, viewportHeight, fit)
        val worldAfter = GalaxyMath.screenToWorld(anchor.x, anchor.y, zoomed, viewportWidth, viewportHeight)
        assertEquals("锚点下的世界坐标不该移动", worldBefore.x, worldAfter.x, 0.001f)
        assertEquals(worldBefore.y, worldAfter.y, 0.001f)
        assertEquals(cam.zoom * 2f, zoomed.zoom, 0.01f)
    }

    @Test
    fun `zoom is clamped to the allowed range`() {
        val fit = GalaxyMath.fitCamera(GalaxyMath.bounds(listOf(Offset(-1f, -1f), Offset(1f, 1f))), viewportWidth, viewportHeight)
        val tooFar = GalaxyMath.zoomCameraAt(fit, 1000f, 0f, 0f, viewportWidth, viewportHeight, fit)
        assertEquals(GalaxyMath.maxZoom(fit), tooFar.zoom, 0.01f)
        val tooSmall = GalaxyMath.zoomCameraAt(fit, 0.0001f, 0f, 0f, viewportWidth, viewportHeight, fit)
        assertEquals(GalaxyMath.minZoom(fit), tooSmall.zoom, 0.01f)
    }

    @Test
    fun `pick finds the node under the finger and ignores taps in empty space`() {
        val points = listOf(Offset(0f, 0f), Offset(0.5f, 0f), Offset(0f, 0.5f))
        val cam = camera()
        val target = GalaxyMath.worldToScreen(0.5f, 0f, cam, viewportWidth, viewportHeight)
        assertEquals(1, GalaxyMath.pick(points, cam, viewportWidth, viewportHeight, target.x + 6f, target.y - 4f))
        // 远离所有节点：不该命中任何一个
        assertEquals(-1, GalaxyMath.pick(points, cam, viewportWidth, viewportHeight, target.x + 400f, target.y + 400f))
    }

    @Test
    fun `pick uses screen space so it stays correct after panning`() {
        val points = listOf(Offset(0f, 0f), Offset(0.3f, 0.2f))
        val cam = camera()
        val panned = GalaxyMath.panCamera(cam, dragX = 60f, dragY = 90f)
        // 平移之后，节点在屏幕上也挪了 (60, 90)，命中判定要跟着走
        val before = GalaxyMath.worldToScreen(0.3f, 0.2f, cam, viewportWidth, viewportHeight)
        val after = GalaxyMath.worldToScreen(0.3f, 0.2f, panned, viewportWidth, viewportHeight)
        assertEquals(before.x + 60f, after.x, 0.01f)
        assertEquals(before.y + 90f, after.y, 0.01f)
        assertEquals(1, GalaxyMath.pick(points, panned, viewportWidth, viewportHeight, after.x + 3f, after.y + 3f))
    }

    @Test
    fun `thumbnail layer fades in with zoom and is capped`() {
        val fit = GalaxyMath.fitCamera(GalaxyMath.bounds(listOf(Offset(-1f, -1f), Offset(1f, 1f))), viewportWidth, viewportHeight)
        assertEquals("全景时不该显示缩略图", 0f, GalaxyMath.thumbAlpha(fit.zoom), 0.001f)
        assertEquals("缩略图尺寸必须封顶", GalaxyMath.THUMB_MAX_PX, GalaxyMath.thumbScreenPx(GalaxyMath.maxZoom(fit)), 0.01f)
        val mid = GalaxyMath.thumbScreenPx(fit.zoom * 6f)
        assertTrue("放大后应显示缩略图", GalaxyMath.thumbAlpha(fit.zoom * 6f) > 0.5f)
        assertTrue(mid > 0f)
    }

    @Test
    fun `character colour is stable and different characters differ`() {
        val first = GalaxyMath.characterColor("纳西妲")
        assertEquals("同一个角色每次必须是同一个颜色", first, GalaxyMath.characterColor("纳西妲"))
        assertNotEquals(first, GalaxyMath.characterColor("可莉"))
        assertNotEquals(first, GalaxyMath.characterColor(""))
    }

    @Test
    fun `edge alpha highlights the focused node and dims the rest`() {
        val normal = GalaxyMath.edgeAlpha(0.6f, touchesFocus = false, hasFocus = false, emphasis = 1f)
        val focused = GalaxyMath.edgeAlpha(0.6f, touchesFocus = true, hasFocus = true, emphasis = 1f)
        val dimmed = GalaxyMath.edgeAlpha(0.6f, touchesFocus = false, hasFocus = true, emphasis = 1f)
        assertTrue("连上关注点的边最亮", focused > dimmed)
        assertTrue("其余边被压暗", dimmed < normal)
        val filtered = GalaxyMath.edgeAlpha(0.6f, touchesFocus = true, hasFocus = true, emphasis = 0f)
        assertTrue("被搜索过滤掉的边几乎不可见", filtered < focused * 0.2f)
    }
}

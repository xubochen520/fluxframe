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

    /* --------------------- 画质：什么时候该重新解一张更大的 --------------------- */

    @Test
    fun `a bitmap loaded while the tile was tiny must be re-decoded after zooming in`() {
        /*
         * 这正是「星图糊得看不清」那个 bug：
         * 缩略图在屏幕边长 30px 时开始淡入，那时只请求到一张 30px 的小图；
         * 用户放大到 400px 时，如果不重新解码，就是把 30px 拉大十三倍。
         */
        assertTrue("30px 的图撑不住 400px 的格子，必须重新解", GalaxyMath.needsSharperBitmap(wantedPx = 400, availablePx = 30))
        assertTrue(GalaxyMath.needsSharperBitmap(wantedPx = 200, availablePx = 30))
        assertTrue("一张都还没有时当然要加载", GalaxyMath.needsSharperBitmap(wantedPx = 60, availablePx = 0))
    }

    @Test
    fun `small zoom steps do not trigger a pointless re-decode`() {
        /* 留 25% 余量：40→45px 这种差别肉眼看不出来，重下一遍只是白花流量 */
        assertTrue("小幅变化不该重下", !GalaxyMath.needsSharperBitmap(wantedPx = 45, availablePx = 40))
        assertTrue("刚好在余量内也不重下", !GalaxyMath.needsSharperBitmap(wantedPx = 50, availablePx = 40))
        assertTrue("超出余量才重下", GalaxyMath.needsSharperBitmap(wantedPx = 60, availablePx = 40))
        assertTrue("已经比需要的还清楚，不用动", !GalaxyMath.needsSharperBitmap(wantedPx = 100, availablePx = 400))
    }

    @Test
    fun `large tiles switch to the high resolution variant`() {
        assertTrue("小的格子用 320 档就够，没必要拉 768 的白流量", !GalaxyMath.prefersLargeThumb(GalaxyMath.THUMB_LARGE_AT - 1f))
        assertTrue("320 档铺到 400px 会发虚，这时候该换 768 档", GalaxyMath.prefersLargeThumb(GalaxyMath.THUMB_LARGE_AT + 1f))
        assertTrue(GalaxyMath.prefersLargeThumb(GalaxyMath.THUMB_MAX_PX))
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

    /* ------------------------------ 星云名称 ------------------------------ */

    @Test
    fun `tag anchors become labels sorted by how many images carry the tag`() {
        val anchors = mapOf(
            "白丝" to Offset(0.5f, 0.5f),
            "可莉" to Offset(-0.5f, -0.5f),
            "绯樱" to Offset(0f, 0f),
        )
        val counts = mapOf("白丝" to 21, "可莉" to 13, "绯樱" to 1)
        val labels = GalaxyMath.tagAnchorLabels(anchors, counts) { GalaxyMath.characterColor(it) }
        assertEquals(3, labels.size)
        assertEquals("数量多的排前面", "白丝", labels[0].text.substringBefore(" · "))
        assertEquals("白丝 · 21", labels[0].text)
        assertEquals(0.5f, labels[0].x, 0.001f)
    }

    @Test
    fun `group label sits on a member not on the empty centroid of a stretched chain`() {
        // 三个点挤在左边、一个点孤零零在右边：质心落在两点之间的空白处，
        // 中心点（medoid）必须落在左边那团里
        val points = mapOf(
            "a" to Offset(-0.9f, 0f),
            "b" to Offset(-0.8f, 0.05f),
            "c" to Offset(-0.85f, -0.05f),
            "d" to Offset(0.9f, 0f),
        )
        val labels = GalaxyMath.groupLabels(
            groups = listOf(listOf("a", "b", "c", "d")),
            pointOf = { points[it] },
            primaryOf = { "纳西妲" },
            colorOf = { GalaxyMath.characterColor(it) },
        )
        assertEquals(1, labels.size)
        assertTrue("标签必须落在左边那团里，而不是中间的空白处：x=${labels[0].x}", labels[0].x < -0.5f)
        assertEquals("纳西妲 · 4", labels[0].text)
    }

    @Test
    fun `group labels skip groups that are too small`() {
        val points = mapOf("a" to Offset(0f, 0f), "b" to Offset(0.1f, 0f))
        val labels = GalaxyMath.groupLabels(
            groups = listOf(listOf("a", "b")),
            pointOf = { points[it] },
            primaryOf = { "" },
            colorOf = { GalaxyMath.characterColor(it) },
            minSize = 3,
        )
        assertTrue("只有两张的分组不配拥有名字", labels.isEmpty())
    }

    @Test
    fun `culling keeps the bigger label and drops the one underneath it`() {
        val camera = camera()
        val overlapping = listOf(
            GalaxyMath.Label("big", "纳西妲 · 36", 0f, 0f, 36, GalaxyMath.characterColor("纳西妲")),
            GalaxyMath.Label("small", "七七 · 3", 0.01f, 0.01f, 3, GalaxyMath.characterColor("七七")),
            GalaxyMath.Label("far", "可莉 · 13", 0.9f, 0.9f, 13, GalaxyMath.characterColor("可莉")),
        )
        val kept = GalaxyMath.cullLabels(overlapping, camera, viewportWidth, viewportHeight, widthOf = { 80f })
        val keys = kept.map { it.key }
        assertTrue("被压住的小标签要丢掉", "big" in keys && "small" !in keys)
        assertTrue("离得远的照常保留", "far" in keys)
    }

    @Test
    fun `culling drops labels whose anchor is off screen`() {
        val camera = camera()
        val labels = listOf(
            GalaxyMath.Label("here", "白丝 · 21", 0f, 0f, 21, GalaxyMath.characterColor("白丝")),
            GalaxyMath.Label("way-off", "猫耳 · 14", 50f, 50f, 14, GalaxyMath.characterColor("猫耳")),
        )
        val kept = GalaxyMath.cullLabels(labels, camera, viewportWidth, viewportHeight, widthOf = { 80f })
        assertEquals(
            "锚点在屏幕外的标签要整个丢掉，不能贴到边上（那会让人以为那儿有团星云）",
            listOf("here"),
            kept.map { it.key },
        )
    }

    @Test
    fun `culling nudges an edge label inside instead of clipping or dropping it`() {
        val camera = camera()
        /* 锚点在画布最左边但仍在画布内：名字比锚点宽，会有一半伸出去 */
        val edge = GalaxyMath.screenToWorld(20f, viewportHeight / 2f, camera, viewportWidth, viewportHeight)
        val width = 200f
        val kept = GalaxyMath.cullLabels(
            labels = listOf(GalaxyMath.Label("edge", "知更鸟 · 2", edge.x, edge.y, 2, GalaxyMath.characterColor("知更鸟"))),
            camera = camera,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            widthOf = { width },
        )
        assertEquals("贴边的标签应该被挪进来，而不是丢掉", listOf("edge"), kept.map { it.key })
        val screen = GalaxyMath.worldToScreen(edge.x, edge.y, camera, viewportWidth, viewportHeight)
        val left = screen.x + kept[0].offsetX - width / 2f
        assertTrue("挪完之后左边不该越界：left=$left", left >= 0f)
        assertTrue("挪完之后右边也不该越界", left + width <= viewportWidth)
    }
}

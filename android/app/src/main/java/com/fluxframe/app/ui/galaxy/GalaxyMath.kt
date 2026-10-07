package com.fluxframe.app.ui.galaxy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 星系图的**纯计算**：坐标变换、取景、命中判定、配色。不依赖 Android，可直接单元测试。
 *
 * 之所以把这些从 Composable 里抽出来单独放：网页端那版在同一件事上连踩两个坑
 * （见下），而它们全都是"纯函数写错了"，用单元测试盯住比用眼睛看截图靠谱得多。
 *
 * 【坑一：y 轴方向】服务端给的坐标是数学坐标系（+y 朝上），屏幕坐标 +y 朝下。
 *   少翻这一次，画面看着完全正常，只有「手指点不准」和「上下拖动反向」两个症状。
 *   所以这里只留 [worldToScreen] / [screenToWorld] 两个出入口，别的代码不许自己算。
 * 【坑二：拖动的两轴符号相反】屏幕 x 与世界 x 同向、屏幕 y 与世界 y 反向。
 *   见 [panCamera]。
 */
object GalaxyMath {

    /* ------------------------------ 视觉常量 ------------------------------ */
    /* 与网页端保持一致，两端看起来才是同一张图 */

    /** 缩略图的世界边长；屏幕边长 = 这个值 × zoom */
    const val THUMB_WORLD = 0.042f

    /** 缩略图开始淡入 / 完全显示的屏幕边长（px） */
    const val THUMB_FADE_FROM = 30f
    const val THUMB_FADE_TO = 62f

    /** 缩略图的屏幕边长上限：不封的话继续放大一张图能铺满整个屏幕 */
    const val THUMB_MAX_PX = 210f

    /** 点光晕的世界半径 */
    const val POINT_RADIUS_WORLD = 0.012f

    /** 相对"刚好装下"最多能放大多少倍 */
    const val MAX_ZOOM_FACTOR = 60f

    /** 取景时四周留的边距比例 */
    private const val FIT_PADDING = 0.86f

    /** 命中判定的手指半径（px）——比鼠标大，手指没那么准 */
    const val TAP_SLOP_PX = 28f

    /** 世界坐标的矩形范围 */
    data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val spanX: Float get() = max(0.25f, maxX - minX)
        val spanY: Float get() = max(0.25f, maxY - minY)
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
    }

    /** 镜头：世界坐标中心 + 每世界单位多少像素 */
    data class Camera(val centerX: Float, val centerY: Float, val zoom: Float)

    /* ------------------------------ 取景 ------------------------------ */

    fun bounds(points: List<Offset>): Bounds {
        if (points.isEmpty()) return Bounds(-1f, -1f, 1f, 1f)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in points) {
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    /**
     * 「刚好装下整张图」的镜头。
     * 按节点实际包围盒算，而不是假设布局正好铺满 [-1,1] ——
     * 归一化只保证最长边到 ±1，短边可能只有 ±0.75，照方框算会白白空掉两条边。
     */
    fun fitCamera(bounds: Bounds, viewportWidth: Float, viewportHeight: Float): Camera {
        val zoom = min(
            viewportWidth / bounds.spanX,
            viewportHeight / bounds.spanY,
        ) * FIT_PADDING
        return Camera(bounds.centerX, bounds.centerY, max(1f, zoom))
    }

    fun minZoom(fit: Camera): Float = fit.zoom * 0.4f

    fun maxZoom(fit: Camera): Float = fit.zoom * MAX_ZOOM_FACTOR

    fun clampZoom(zoom: Float, fit: Camera): Float =
        zoom.coerceIn(minZoom(fit), maxZoom(fit))

    /* ------------------------------ 坐标变换 ------------------------------ */

    /**
     * 世界坐标 → 屏幕坐标（px，相对画布左上角）。
     * **y 必须翻一次**：世界 +y 朝上，屏幕 +y 朝下。
     */
    fun worldToScreen(worldX: Float, worldY: Float, camera: Camera, viewportWidth: Float, viewportHeight: Float): Offset =
        Offset(
            x = (worldX - camera.centerX) * camera.zoom + viewportWidth / 2f,
            y = viewportHeight / 2f - (worldY - camera.centerY) * camera.zoom,
        )

    /** [worldToScreen] 的逆变换 */
    fun screenToWorld(screenX: Float, screenY: Float, camera: Camera, viewportWidth: Float, viewportHeight: Float): Offset =
        Offset(
            x = (screenX - viewportWidth / 2f) / camera.zoom + camera.centerX,
            y = (viewportHeight / 2f - screenY) / camera.zoom + camera.centerY,
        )

    /**
     * 拖动：手指往哪边拖，星图就往哪边跟。
     * 两轴的符号是**相反**的 —— 屏幕 x 与世界 x 同向，屏幕 y 与世界 y 反向。
     * 第一版两轴都写成减号，结果上下拖动的方向和手相反。
     */
    fun panCamera(camera: Camera, dragX: Float, dragY: Float): Camera = Camera(
        centerX = camera.centerX - dragX / camera.zoom,
        centerY = camera.centerY + dragY / camera.zoom,
        zoom = camera.zoom,
    )

    /**
     * 以某个屏幕点为锚点缩放：锚点底下的世界坐标保持不动（双指捏合/双击放大都走这里）。
     * [factor] 是相对当前缩放的比例，>1 放大。
     */
    fun zoomCameraAt(
        camera: Camera,
        factor: Float,
        anchorX: Float,
        anchorY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        fit: Camera,
    ): Camera {
        val next = clampZoom(camera.zoom * factor, fit)
        if (abs(next - camera.zoom) < 1e-4f) return camera
        val world = screenToWorld(anchorX, anchorY, camera, viewportWidth, viewportHeight)
        // 缩放后让同一个世界点仍落在锚点上，反解出新的镜头中心
        return Camera(
            centerX = world.x - (anchorX - viewportWidth / 2f) / next,
            centerY = world.y - (viewportHeight / 2f - anchorY) / next,
            zoom = next,
        )
    }

    /* ------------------------------ 命中判定 ------------------------------ */

    /**
     * 找离手指最近的节点（在屏幕空间里比，[TAP_SLOP_PX] 以内才算命中）。
     * 返回节点下标，没命中返回 -1。
     */
    fun pick(
        positions: List<Offset>,
        camera: Camera,
        viewportWidth: Float,
        viewportHeight: Float,
        tapX: Float,
        tapY: Float,
        radius: Float = TAP_SLOP_PX,
    ): Int {
        var best = -1
        var bestDistance = radius * radius
        for (index in positions.indices) {
            val screen = worldToScreen(positions[index].x, positions[index].y, camera, viewportWidth, viewportHeight)
            val dx = screen.x - tapX
            val dy = screen.y - tapY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /* ------------------------------ 缩略图 LOD ------------------------------ */

    /** 缩略图当前该显示的屏幕边长（px，已封顶） */
    fun thumbScreenPx(zoom: Float): Float = min(THUMB_WORLD * zoom, THUMB_MAX_PX)

    /** 缩略图在屏幕上的透明度：0 = 还是个小星点，1 = 完全显示 */
    fun thumbAlpha(zoom: Float): Float {
        val size = THUMB_WORLD * zoom
        return ((size - THUMB_FADE_FROM) / (THUMB_FADE_TO - THUMB_FADE_FROM)).coerceIn(0f, 1f)
    }

    /** 星点半径（屏幕 px）：缩略图淡入时点同时淡出 */
    fun pointScreenRadius(zoom: Float): Float = POINT_RADIUS_WORLD * zoom

    /* ------------------------------ 配色 ------------------------------ */

    /**
     * 角色名 → 颜色。用哈希而不是调色板轮转：角色数量不定，哈希不会撞色也永远稳定
     * （同一个角色每次进页面都是同一个颜色）。与网页端同一套哈希与 HLS 参数。
     */
    fun characterColor(name: String): Color {
        if (name.isEmpty()) return Color(0.34f, 0.40f, 0.55f)
        var hash = 2166136261u
        for (char in name) {
            hash = hash xor char.code.toUInt()
            hash *= 16777619u
        }
        val hue = (hash % 360u).toFloat() / 360f
        return hsl(hue, 0.72f, 0.62f)
    }

    /** HSL → RGB（0~1）。自己写而不用 Color.hsl：后者在不同 Compose 版本里行为有差异 */
    internal fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
        fun channel(n: Int): Float {
            val k = (n + hue * 12f) % 12f
            val a = saturation * min(lightness, 1f - lightness)
            return lightness - a * max(-1f, min(k - 3f, min(9f - k, 1f)))
        }
        return Color(channel(0), channel(8), channel(4))
    }

    /**
     * 相似边的透明度：相似度越高越亮；有关注点（选中的那张）时，连着它的边点亮、其余压暗。
     * [emphasis] 是搜索/图例筛选给出的 0~1 权重。
     */
    fun edgeAlpha(score: Float, touchesFocus: Boolean, hasFocus: Boolean, emphasis: Float): Float {
        val strength = ((score - 0.25f) / 0.6f).coerceIn(0f, 1f)
        var alpha = 0.05f + strength * 0.16f
        if (hasFocus) alpha = if (touchesFocus) 0.42f + strength * 0.5f else alpha * 0.16f
        return alpha * (0.12f + emphasis.coerceIn(0f, 1f) * 0.88f)
    }

    /** 两点距离，标签重叠剔除用 */
    fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

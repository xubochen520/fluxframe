package com.fluxframe.app.ui.galaxy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

    /**
     * 缩略图的屏幕边长上限。
     *
     * 手机屏幕本来就小，封得太死就看不清图上是什么了 —— 第一版照搬网页端的 210px，
     * 结果放大到底也就占屏宽的五分之一，用户反馈"糊得看不清"。400px 大约是屏宽的 37%，
     * 一眼能认出是哪张图；再大就会把星图结构盖住。
     */
    const val THUMB_MAX_PX = 400f

    /**
     * 超过这个屏幕边长就换高清档（服务端的 768 变体）。
     * 320 档铺到 400px 就是 1.25 倍拉伸，手机上能看出来发虚。
     */
    const val THUMB_LARGE_AT = 280f

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
     * 命中判定要用的节点信息：世界坐标 + 原图长宽比。
     * 长宽比是给"整张图都算点击区"用的（见 [pick]）。
     */
    data class PickTarget(val worldX: Float, val worldY: Float, val aspect: Float)

    /**
     * 找手指点到/靠近的那个节点。返回节点下标，没命中返回 -1。
     *
     * 两套判定，按缩略图可不可见切换：
     *
     *  · **缩略图看得见时用整张图的矩形**（[thumbAlpha] 由 [camera] 的缩放推出来）。
     *    格子在屏幕上有 200~400px 见方，还按中心点 28px 判定的话，
     *    点在图上任何位置都会落空 —— 用户反馈的"不好交互"就是这个。
     *    矩形有重叠时取离手指最近的那个中心。
     *
     *  · **缩略图还没出来时（全景视角）退回中心点半径判定**。
     *    那时点只有十几个像素，矩形判定反而会互相抢。
     *
     * 缩略图的可见度与尺寸都从 [camera] 现算，不接受调用方传进来 ——
     * 曾经让调用方自己传，结果把"相对全景的倍数"当成了"每世界单位多少像素"传进来，
     * 判定静默失效（缩略图明明看得见，点击却只认中心点）。少一个参数就少一类错。
     */
    fun pick(
        targets: List<PickTarget>,
        camera: Camera,
        viewportWidth: Float,
        viewportHeight: Float,
        tapX: Float,
        tapY: Float,
        radius: Float = TAP_SLOP_PX,
    ): Int {
        val visible = thumbAlpha(camera.zoom)
        val thumbLongPx = thumbScreenPx(camera.zoom)
        if (visible > 0.5f && thumbLongPx > 0f) {
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (index in targets.indices) {
                val target = targets[index]
                val screen = worldToScreen(target.worldX, target.worldY, camera, viewportWidth, viewportHeight)
                val box = thumbBoxPx(target.aspect, thumbLongPx)
                val dx = kotlin.math.abs(screen.x - tapX)
                val dy = kotlin.math.abs(screen.y - tapY)
                if (dx > box.width / 2f || dy > box.height / 2f) continue
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            if (best >= 0) return best
        }

        var best = -1
        var bestDistance = radius * radius
        for (index in targets.indices) {
            val screen = worldToScreen(targets[index].worldX, targets[index].worldY, camera, viewportWidth, viewportHeight)
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

    /** 缩略图当前该显示的屏幕边长（px，已封顶）。指的是**长边** */
    fun thumbScreenPx(zoom: Float): Float = min(THUMB_WORLD * zoom, THUMB_MAX_PX)

    /**
     * 缩略图在屏幕上的实际宽高：**长边**固定为 [longPx]，短边按原比例。
     *
     * 不做成正方形。二次元插画大半是 2:3 的竖图，居中裁成正方形会把构图切掉三分之一；
     * 拉伸就更难看。保持原比例，竖图就是竖着的格子，横图就是横着的。
     *
     * 库里的实际比例在 0.47 ~ 2.02 之间，没有离谱的极端值；真遇到超宽横幅也照实画，
     * 只是会变成细细一条 —— 那本来就是那张图的样子。
     */
    fun thumbBoxPx(aspect: Float, longPx: Float): Size {
        val ratio = if (aspect.isFinite() && aspect > 0.05f) aspect else 1f
        return if (ratio >= 1f) Size(longPx, longPx / ratio) else Size(longPx * ratio, longPx)
    }

    /** 当前屏幕尺寸该不该换高清档（服务端的 768 变体） */
    fun prefersLargeThumb(thumbPx: Float): Boolean = thumbPx > THUMB_LARGE_AT

    /**
     * 手上这张位图够不够清楚，要不要重新解一张更大的。
     *
     * 这是「星图糊得看不清」那个 bug 的核心判断：第一版只在「缓存里没有」时才去加载，
     * 于是谁先加载就永远用谁的分辨率 —— 缩略图在屏幕边长 30px 时就开始淡入，
     * 那时请求到的是 30px 的小图；等用户放大到 400px，还是那张小图被拉大十几倍。
     *
     * [availablePx] 取「已就绪」与「正在加载」里**较大**的那个：已经在路上的大图
     * 不该被同一帧的又一次请求打断。
     *
     * 留 [SHARPEN_HEADROOM] 的余量是必要的：不留的话用户每滚一格缩放都会重下一遍
     * （40→45→50px…），流量和抖动都白白增加，画质却看不出区别。
     */
    fun needsSharperBitmap(wantedPx: Int, availablePx: Int): Boolean =
        availablePx <= 0 || wantedPx > availablePx * SHARPEN_HEADROOM

    /** 重新解码的触发余量：需要的尺寸超过现有尺寸这么多才值得重下 */
    const val SHARPEN_HEADROOM = 1.25f

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

    /**
     * 星云名称：世界坐标 + 文字 + 颜色。
     * [offsetX]/[offsetY] 是 [cullLabels] 把它往画布内挪的像素量，绘制时加上即可。
     */
    data class Label(
        val key: String,
        val text: String,
        val x: Float,
        val y: Float,
        val weight: Int,
        val color: Color,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
    )

    /**
     * 标签模式的星云名称：直接标在服务端给的**标签锚点**上。
     * 标签模式下的图本来就是围着标签聚起来的，标在锚点上既准确，也顺手解释了"这团为什么在这儿"。
     */
    fun tagAnchorLabels(
        anchors: Map<String, Offset>,
        counts: Map<String, Int>,
        limit: Int = 26,
        colorOf: (String) -> Color,
    ): List<Label> = anchors.entries
        .map { (tag, point) ->
            Label(
                key = "tag:$tag",
                text = "$tag · ${counts[tag] ?: 0}",
                x = point.x,
                y = point.y,
                weight = counts[tag] ?: 1,
                color = colorOf(tag),
            )
        }
        .sortedByDescending { it.weight }
        .take(limit)

    /**
     * 视觉模式的星云名称：取分组的**中心点（medoid）**，不是质心。
     *
     * 并查集分组可能是拉得很长的链，质心会落到两团之间的空白处 ——
     * 网页端实测「纳西妲 · 37」就飘到了画布另一边，离它那团星云十万八千里。
     * 取组内到其它成员距离和最小的那个点，标签一定落在最密的地方。
     */
    fun groupLabels(
        groups: List<List<String>>,
        pointOf: (String) -> Offset?,
        primaryOf: (String) -> String,
        minSize: Int = 3,
        limit: Int = 22,
        colorOf: (String) -> Color,
    ): List<Label> {
        val result = ArrayList<Label>()
        for (members in groups) {
            val points = members.mapNotNull { id -> pointOf(id)?.let { id to it } }
            if (points.size < minSize) continue

            /* 候选最多取 80 个：再大的分组也不需要全扫 */
            val step = maxOf(1, points.size / 80)
            var anchor = points[0].second
            var bestScore = Double.MAX_VALUE
            var index = 0
            while (index < points.size) {
                var sum = 0.0
                for (other in points) {
                    val dx = (other.second.x - points[index].second.x).toDouble()
                    val dy = (other.second.y - points[index].second.y).toDouble()
                    sum += dx * dx + dy * dy
                }
                if (sum < bestScore) { bestScore = sum; anchor = points[index].second }
                index += step
            }

            val tally = HashMap<String, Int>()
            for (id in members) {
                val primary = primaryOf(id)
                if (primary.isNotEmpty()) tally[primary] = (tally[primary] ?: 0) + 1
            }
            val best = tally.maxByOrNull { it.value }
            result.add(
                Label(
                    key = "${members.first()}-${points.size}",
                    text = if (best != null) "${best.key} · ${points.size}" else "${points.size} 张",
                    x = anchor.x,
                    y = anchor.y,
                    weight = points.size,
                    color = if (best != null) colorOf(best.key) else Color(0.80f, 0.84f, 0.88f),
                ),
            )
        }
        return result.sortedByDescending { it.weight }.take(limit)
    }

    /**
     * 把名称收进画布：互相压住的丢掉（大的优先保留），贴边的**往内挪**而不是丢掉。
     *
     * 为什么不是直接丢：最大的那团星云往往正好在画布边上，一丢就把最重要的名字丢了
     * （实测首屏「纳西妲 · 37」就是这么消失的）。只判相交再裁又会切掉半截
     * （「知更鸟 · 2」变成「更鸟 · 2」）。挪进来两个问题都没有，代价只是偏一点点。
     *
     * [widthOf] 传文字宽度估算 —— 十几个标签不值得真去测量排版。
     */
    fun cullLabels(
        labels: List<Label>,
        camera: Camera,
        viewportWidth: Float,
        viewportHeight: Float,
        widthOf: (Label) -> Float,
        height: Float = 22f,
    ): List<Label> {
        val placed = ArrayList<FloatArray>(labels.size)
        val kept = ArrayList<Label>(labels.size)
        for (label in labels) {
            val screen = worldToScreen(label.x, label.y, camera, viewportWidth, viewportHeight)
            /*
             * 锚点自己就在画布外：整个丢掉。
             * 不能"贴"到边上——那会让人以为那儿有团星云，其实目标在屏幕外很远。
             */
            if (screen.x < 0f || screen.x > viewportWidth || screen.y < 0f || screen.y > viewportHeight) continue

            val halfWidth = widthOf(label) / 2f
            var left = screen.x - halfWidth
            var top = screen.y - height / 2f
            val right = left + halfWidth * 2f
            val bottom = top + height

            var offsetX = 0f
            if (left < 0f) offsetX = -left else if (right > viewportWidth) offsetX = viewportWidth - right
            var offsetY = 0f
            if (top < 0f) offsetY = -top else if (bottom > viewportHeight) offsetY = viewportHeight - bottom

            left += offsetX
            top += offsetY
            val x1 = left
            val y1 = top
            val x2 = left + halfWidth * 2f
            val y2 = top + height
            if (placed.any { other -> !(x2 < other[0] || x1 > other[2] || y2 < other[1] || y1 > other[3]) }) continue
            placed.add(floatArrayOf(x1, y1, x2, y2))
            /* 偏移量随标签带回去，调用方直接用即可，不必再算一次 */
            kept.add(label.copy(offsetX = offsetX, offsetY = offsetY))
        }
        return kept
    }
}

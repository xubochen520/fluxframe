package com.fluxframe.app.ui.galaxy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 星系空间的相机与投影 —— **纯函数，不碰画布**。
 *
 * 这是网页端 `client/src/galaxy/camera3d.ts` 的逐条对照实现。两边必须一致：
 * 同一个手势在两端的旋转角度、取景松紧、推近手感都该一样，否则用户会觉得"手机版怪怪的"。
 * 那个文件里每条公式的理由都写在注释里，这里不重复，只标出容易写错的点。
 *
 * 安卓没有 WebGL，所以投影是在 Canvas 上手动算的；好在只有 yaw/pitch/distance/target
 * 四个自由度，一次投影就是十几次乘加，几百个点完全跑得动。
 */
object Galaxy3DMath {

    /** 俯仰上限（约 77°）：再往上相机翻到顶了，画面会突然镜像 */
    const val PITCH_LIMIT = 1.35f

    /** 焦距。和 distance 同一个量纲，比值决定透视强弱 */
    const val FOCAL = 1.65f

    const val MIN_DISTANCE = 0.35f
    const val MAX_DISTANCE = 9f

    /** 拖动多少像素转多少弧度。与视口大小无关，这样同一个手势在手机和桌面上转过的角度一样 */
    const val ORBIT_RADIANS_PER_PX = 0.0075f

    /** 缓慢自转：约 3.4°/s，转一圈约 105 秒 */
    const val AUTO_SPIN_RADIANS_PER_SEC = 0.06f

    /** 推近后相机停在这个距离 */
    const val FOCUS_DISTANCE = 0.62f

    /** 陀螺仪视差的最大偏移（弧度，约 7°）：加多了会跟用户自己的拖动打架，也容易晕 */
    const val GYRO_LIMIT = 0.12f

    /** 手机倾斜多少弧度算"到头"（约 29°） */
    const val GYRO_RANGE = 0.5f

    private const val HALF_PI = (Math.PI / 2).toFloat()

    data class Camera(
        val yaw: Float,
        val pitch: Float,
        val distance: Float,
        val targetX: Float = 0f,
        val targetY: Float = 0f,
        val targetZ: Float = 0f,
    )

    data class Projected(val x: Float, val y: Float, val depth: Float, val scale: Float)

    fun clampPitch(pitch: Float): Float = pitch.coerceIn(-PITCH_LIMIT, PITCH_LIMIT)

    fun clampDistance(distance: Float): Float = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)

    /**
     * 世界坐标 → 屏幕坐标（px）。
     *
     * 顺序：先绕 y 转（左右环绕），再绕 x 转（上下俯仰），最后平移出去做透视除法。
     * 屏幕 +y 朝下，所以 y 那一项要取负。
     *
     * 【深度轴的约定】depth = z2 + distance，也就是说**相机站在 -z 一侧朝 +z 看**：
     * z 越大的点 depth 越大、看起来越远。这跟「相机在 +z」的直觉相反，但整套东西
     * （布局、推近目标、命中优先级、网页端）用的是同一个约定，所以只是镜像了一下，
     * 观感没有区别。写测试的时候特别容易在这儿想当然 —— 已经踩过一次。
     */
    fun projectPoint(
        x: Float,
        y: Float,
        z: Float,
        camera: Camera,
        viewportWidth: Float,
        viewportHeight: Float,
        focal: Float = FOCAL,
    ): Projected {
        val dx = x - camera.targetX
        val dy = y - camera.targetY
        val dz = z - camera.targetZ

        val cosYaw = cos(camera.yaw)
        val sinYaw = sin(camera.yaw)
        val x1 = dx * cosYaw + dz * sinYaw
        val z1 = -dx * sinYaw + dz * cosYaw

        val cosPitch = cos(camera.pitch)
        val sinPitch = sin(camera.pitch)
        val y1 = dy * cosPitch - z1 * sinPitch
        val z2 = dy * sinPitch + z1 * cosPitch

        val depth = z2 + camera.distance
        /* 背后或贴脸的点给一个极小正数：除法不会炸，画出来也在屏幕外，调用方按 depth 剔掉 */
        val safeDepth = if (depth < 0.05f) 0.05f else depth
        val scale = focal / safeDepth * min(viewportWidth, viewportHeight) * 0.5f

        return Projected(
            x = viewportWidth * 0.5f + x1 * scale,
            y = viewportHeight * 0.5f - y1 * scale,
            depth = depth,
            scale = scale,
        )
    }

    /**
     * 取景：把半径 [radius] 的球正好塞进视口。
     *
     * **必须带上 focal**：投影里 屏幕尺寸 = 世界尺寸 × focal / distance × min(w,h)/2，
     * 所以要让「半径碰到视口半高的 fill 倍」，距离得是 `radius × focal / fill`。
     * 网页端就是漏了这一项，星系上下被切掉一截。
     */
    fun fitDistance(radius: Float, fill: Float = 0.78f, focal: Float = FOCAL): Float =
        clampDistance(radius.coerceAtLeast(0.0001f) * focal / max(0.2f, fill))

    /**
     * 取景半径：用 95 分位而不是最远点。
     * 一张离群的孤立图就能把包围球撑大一截，整个星系会缩成中间一小团。
     */
    fun fitRadius(radii: List<Float>, quantile: Float = 0.95f): Float {
        if (radii.isEmpty()) return 1f
        val sorted = radii.sorted()
        val index = min(sorted.size - 1, (sorted.size * quantile).toInt())
        return sorted[index].coerceAtLeast(0.0001f)
    }

    /** 拖动 → 环绕。横向反向：手指往右拖，星系跟着往右转（内容跟手） */
    fun orbit(camera: Camera, dxPixels: Float, dyPixels: Float): Camera = camera.copy(
        yaw = camera.yaw - dxPixels * ORBIT_RADIANS_PER_PX,
        pitch = clampPitch(camera.pitch + dyPixels * ORBIT_RADIANS_PER_PX),
    )

    /** 缩放：factor < 1 拉近。距离是乘性的，所以每一格缩放的"感觉"在任何距离下都一样 */
    fun dolly(camera: Camera, factor: Float): Camera = camera.copy(distance = clampDistance(camera.distance * factor))

    fun autoSpin(camera: Camera, deltaSeconds: Float): Camera =
        camera.copy(yaw = camera.yaw + AUTO_SPIN_RADIANS_PER_SEC * deltaSeconds)

    /* ------------------------------ 推近动画 ------------------------------ */

    fun easeInOutCubic(t: Float): Float {
        val value = t.coerceIn(0f, 1f)
        return if (value < 0.5f) 4f * value * value * value else 1f - Math.pow((-2f * value + 2f).toDouble(), 3.0).toFloat() / 2f
    }

    /**
     * 电影镜头：把相机推到某个节点前面。
     *
     * 「电影感」来自三点，不只是拉近：
     *   1. 目标点从当前看向的位置**平滑移**到那张图上，而不是瞬移；
     *   2. 俯仰角归零、环绕角**就近**取整到 0 —— 最后画面正对着，像定妆照；
     *   3. easeInOutCubic（两头慢中间快），起步和刹车都有分量。
     *
     * 「就近」很关键：已经转了 350° 就该再转 10° 回正，而不是倒着转 350°（那样会绕一大圈，很晕）。
     */
    fun cinematicTarget(from: Camera, x: Float, y: Float, z: Float, closeDistance: Float = FOCUS_DISTANCE): Camera {
        val turns = (from.yaw / (2f * Math.PI.toFloat())).roundToInt()
        return Camera(
            yaw = turns * 2f * Math.PI.toFloat(),
            pitch = 0f,
            distance = clampDistance(closeDistance),
            targetX = x,
            targetY = y,
            targetZ = z,
        )
    }

    fun lerpCamera(from: Camera, to: Camera, t: Float): Camera {
        val k = easeInOutCubic(t)
        return Camera(
            yaw = from.yaw + (to.yaw - from.yaw) * k,
            pitch = from.pitch + (to.pitch - from.pitch) * k,
            distance = from.distance + (to.distance - from.distance) * k,
            targetX = from.targetX + (to.targetX - from.targetX) * k,
            targetY = from.targetY + (to.targetY - from.targetY) * k,
            targetZ = from.targetZ + (to.targetZ - from.targetZ) * k,
        )
    }

    /* ------------------------------ 命中判定 ------------------------------ */

    data class PickCandidate(val screenX: Float, val screenY: Float, val depth: Float, val radiusPx: Float)

    /**
     * 找点击命中的节点，没命中返回 -1。
     *
     * 两个要点：
     *   · **优先近的**：3D 里前后会重叠，点到重叠区域应该给离相机最近的那个；
     *   · 命中半径取「节点半径」与 [minRadiusPx] 的较大者：远处的点再小，
     *     也要保证手指点得中（手机上尤其重要）。
     */
    fun pick(candidates: List<PickCandidate>, tapX: Float, tapY: Float, minRadiusPx: Float = 24f): Int {
        var best = -1
        var bestDepth = Float.MAX_VALUE
        for (index in candidates.indices) {
            val candidate = candidates[index]
            if (candidate.depth <= 0.06f) continue
            val dx = candidate.screenX - tapX
            val dy = candidate.screenY - tapY
            val radius = max(candidate.radiusPx, minRadiusPx)
            if (dx * dx + dy * dy > radius * radius) continue
            if (candidate.depth < bestDepth) {
                bestDepth = candidate.depth
                best = index
            }
        }
        return best
    }

    /** 深度 → 不透明度。远处的点淡出而不是直接消失：直接消失旋转时会"闪" */
    fun depthAlpha(depth: Float, near: Float, far: Float, minAlpha: Float = 0.18f): Float {
        if (depth <= near) return 1f
        if (depth >= far) return minAlpha
        val t = (depth - near) / (far - near)
        return 1f - t * (1f - minAlpha)
    }

    /* ------------------------------ 陀螺仪 ------------------------------ */

    /**
     * 设备姿态 → 视差偏移（弧度）。
     *
     * [roll] 是设备绕自身前后轴的倾斜（左右歪），[pitch] 是绕左右轴（前后仰）。
     * 竖着拿手机时 pitch 约等于 ±90°，所以「离 ±90° 有多远」才是前后倾斜量。
     *
     * 只给 ±[GYRO_LIMIT] 的一点点偏移 —— 这是「光栅画」那种随视角晃动的手感，
     * 给多了会跟用户自己的拖动打架。
     */
    fun gyroParallax(roll: Float, pitch: Float, limit: Float = GYRO_LIMIT, range: Float = GYRO_RANGE): Pair<Float, Float> {
        val tiltFront = abs(pitch) - HALF_PI
        val yaw = (roll / range).coerceIn(-1f, 1f) * limit
        val pitchOffset = (tiltFront / range).coerceIn(-1f, 1f) * limit
        return yaw to pitchOffset
    }

    /* ------------------------------ 标签分级 ------------------------------ */

    /**
     * 这个缩放级别下最多显示几个标签名。
     *
     * 缩小时满屏名字会糊成一片、还盖住星云本身，所以只留图最多的那几个；放大时一个个放出来。
     * [zoomRatio] = 全景距离 / 当前距离：1 是全景，越大越近。
     * 用 0.75 次方而不是线性：线性的话前几格缩放就放完了，后面再放大反而没有新东西。
     */
    fun labelBudget(zoomRatio: Float, total: Int, base: Int = 6, cap: Int = 30): Int {
        val ratio = if (zoomRatio.isFinite() && zoomRatio > 0f) zoomRatio else 1f
        val limit = min(cap, max(0, total))
        val wanted = (base * Math.pow(ratio.toDouble(), 0.75).toFloat()).roundToInt()
        return max(min(base, limit), min(wanted, limit))
    }

    /* ------------------------------ 杂项 ------------------------------ */

    /**
     * 已经投影到屏幕上的标签：位置 + 文字 + 颜色 + 权重（大的优先保留）。
     * 二维那套的 [GalaxyMath.Label] 存的是世界坐标，三维这边位置每帧都在变，
     * 所以单独用一个"已投影"的形状，免得每帧再反算回去。
     */
    data class ScreenLabel(
        val key: String,
        val text: String,
        val x: Float,
        val y: Float,
        val weight: Int,
        val color: androidx.compose.ui.graphics.Color,
    )

    /**
     * 剔除互相压住的标签（权重大的优先），并丢掉跑到画布外的；
     * 贴边的往内挪而不是直接丢（否则最大的那团名字最容易消失）。
     * 与二维的 [GalaxyMath.cullLabels] 同一条规则，只是输入的坐标已经投影好了。
     */
    fun cullScreenLabels(
        labels: List<ScreenLabel>,
        viewportWidth: Float,
        viewportHeight: Float,
        widthOf: (ScreenLabel) -> Float,
        height: Float,
    ): List<ScreenLabel> {
        val placed = ArrayList<FloatArray>(labels.size)
        val kept = ArrayList<ScreenLabel>(labels.size)
        for (label in labels) {
            if (label.x < 0f || label.x > viewportWidth || label.y < 0f || label.y > viewportHeight) continue
            val halfWidth = widthOf(label) / 2f
            var left = label.x - halfWidth
            var top = label.y - height / 2f
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
            kept.add(label.copy(x = label.x + offsetX, y = label.y + offsetY))
        }
        return kept
    }

    /** 缩略图在屏幕上的尺寸：长边固定 [longPx]，短边按原比例。与二维那套共用 */
    fun thumbBoxPx(aspect: Float, longPx: Float): Size = GalaxyMath.thumbBoxPx(aspect, longPx)

    /** 缩略图长边随透视缩放，并封顶 */
    fun thumbLongPx(worldSize: Float, scale: Float, maxPx: Float): Float = min(worldSize * scale, maxPx)

    /** 点到点的距离（测试与调试用） */
    fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

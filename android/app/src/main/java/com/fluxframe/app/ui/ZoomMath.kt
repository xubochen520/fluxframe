package com.fluxframe.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 全屏查看器里缩放与位移的**纯计算**（不依赖 Android，可直接单元测试）。
 *
 * 双击缩放要"放大对应的点击位置"，而不是永远放大正中间：手指点在哪儿，
 * 那个像素就应该留在原地。做法是把位移与缩放的焦点对齐 ——
 * 以容器中心为原点，被点到的点 p 在缩放 s 之后会跑到 p·s，
 * 因此把它拉回来需要平移 `p·(1 - s)`。
 */
object ZoomMath {

    /** 双击放大到的倍率 */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 手势允许的最大倍率 */
    const val MAX_SCALE = 6f

    /**
     * 双击缩放用的缓动曲线（三次贝塞尔）。
     * 起步快、收尾稳，是"内容被推到眼前"的手感；两端导数不激进，
     * 所以放大结束那一瞬间不会有生硬的顿挫。
     */
    val ZoomEasing: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    /**
     * 计算"让 [tap] 这个点保持不动"所需的平移量。
     *
     * @param tap 双击位置（相对容器左上角，单位像素）
     * @param box 容器尺寸（像素）
     * @param targetScale 目标倍率
     */
    fun focalOffset(tap: Offset, box: Size, targetScale: Float): Offset {
        if (targetScale <= 1f) return Offset.Zero
        val center = Offset(box.width / 2f, box.height / 2f)
        val relative = tap - center
        return clamp(relative * (1f - targetScale), targetScale, box)
    }

    /**
     * 把平移限制在"放大后多出来的那部分"之内，避免把图拖到屏幕外。
     * 倍率回到 1 时位移强制归零。
     */
    fun clamp(offset: Offset, scale: Float, box: Size): Offset {
        if (scale <= 1f) return Offset.Zero
        val maxX = (scale - 1f) / 2f * box.width
        val maxY = (scale - 1f) / 2f * box.height
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    /** 双指缩放后的新倍率 */
    fun scaleBy(current: Float, zoomChange: Float): Float =
        (current * zoomChange).coerceIn(1f, MAX_SCALE)
}

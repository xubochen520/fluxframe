package com.fluxframe.app.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.fluxframe.app.ui.theme.DarkBackground
import com.fluxframe.app.ui.theme.DefaultFluidColors
import com.fluxframe.app.ui.theme.LightBackground
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalFluidCanvasSize
import com.fluxframe.app.ui.theme.LocalFluidColors
import com.fluxframe.app.ui.theme.LocalFluidPhase
import com.fluxframe.app.ui.theme.STATIC_FLUID_PHASE
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 全屏流体背景 + 相位分发。
 *
 * 背景是三团缓慢漂移的径向光斑叠在竖向渐变上 —— 纯 Canvas 绘制，
 * 不依赖 WebGL，任何机型都能跑，且与服务器端 `fluidColors` 同色。
 *
 * 性能上有三条硬约束（旧版在这里吃了大亏）：
 *  1. **相位按 ~12fps 量化**。原本用 `rememberInfiniteTransition` 逐帧推进，
 *     30 秒的缓慢漂移却让整屏以 60fps 重绘，纯属浪费；12fps 下人眼看不出差别，
 *     重绘次数直接降到 1/5。
 *  2. **相位通过 [LocalFluidPhase] 以 `State` 形式下发**，各组件只在绘制 lambda 里
 *     读 `.value`。这样相位变化只触发**重绘**，不会让读取者重组。
 *  3. **不在前台时完全不推进**。用 `withFrameNanos` 让推进跟随帧时钟
 *     （界面不可见时 Compose 会暂停帧分发），再用生命周期观察者兜一层，
 *     退到后台后不再有任何唤醒。
 */
@Composable
fun FluidBackgroundHost(
    colors: List<Color>,
    darkTheme: Boolean,
    animationsEnabled: Boolean,
    backgroundImageUri: String? = null,
    backgroundImageOpacity: Float = 0.72f,
    content: @Composable () -> Unit,
) {
    val phase = remember { mutableFloatStateOf(STATIC_FLUID_PHASE) }
    val resumed = rememberAppResumed()

    LaunchedEffect(animationsEnabled, resumed) {
        if (!animationsEnabled || !resumed) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        var lastDrawn = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - lastDrawn < DRIFT_FRAME_NANOS) continue
            lastDrawn = now
            val seconds = (now - startNanos) / 1_000_000_000.0
            phase.floatValue = trianglePhase(seconds / DRIFT_PERIOD_SECONDS)
        }
    }

    val base = if (darkTheme) DarkBackground else LightBackground
    val palette = colors.ifEmpty { DefaultFluidColors }

    // 背景画布的真实像素尺寸：下发给玻璃卡片，让它们内部的"背景副本"能精确对齐
    val canvasSize = remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(base)
            .onGloballyPositioned { coordinates -> canvasSize.value = coordinates.size.toSize() },
    ) {
        CompositionLocalProvider(
            LocalFluidPhase provides phase,
            LocalFluidCanvasSize provides canvasSize,
        ) {
            FluidBackdrop(colors = palette, modifier = Modifier.matchParentSize())
            if (!backgroundImageUri.isNullOrBlank()) {
                AsyncImage(
                    model = backgroundImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = backgroundImageOpacity.coerceIn(0.2f, 1f) },
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            if (darkTheme) Color.Black.copy(alpha = 0.26f)
                            else Color.White.copy(alpha = 0.16f),
                        ),
                )
            }
            content()
        }
    }
}

/** 单程 30 秒（0→1→0 共 60 秒），与旧版 `tween(30_000, Reverse)` 完全一致 */
private const val DRIFT_PERIOD_SECONDS = 30.0

/** 约 12fps：缓慢漂移根本不需要逐帧 */
private const val DRIFT_FRAME_NANOS = 83_000_000L

/** 0→1→0 的三角波，保证折返处速度连续、没有跳变 */
private fun trianglePhase(input: Double): Float {
    val t = input % 2.0
    val v = if (t <= 1.0) t else 2.0 - t
    return v.toFloat().coerceIn(0f, 1f)
}

/** 应用是否处于前台（RESUMED 之前都算"不该耗电"） */
@Composable
private fun rememberAppResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * 流体背景本体（全屏）。
 *
 * 只读相位、只重绘。卡片内部会以同一函数绘制"同一相位的副本"，
 * 从而在没有原生 backdrop blur 的前提下得到一致的折射感。
 */
@Composable
fun FluidBackdrop(
    colors: List<Color> = LocalFluidColors.current,
    modifier: Modifier = Modifier,
    alphaScale: Float = 1f,
) {
    val phaseState = LocalFluidPhase.current
    val dark = LocalDarkTheme.current
    Canvas(modifier = modifier) {
        drawFluidGradient(
            colors = colors,
            dark = dark,
            phase = phaseState.value,
            alphaScale = alphaScale,
            canvasSize = size,
        )
    }
}

/**
 * 流体渐变的唯一实现。
 *
 * [canvasSize] 允许调用方声明"逻辑画布"尺寸：玻璃卡片会先把画布平移
 * `-卡片窗口坐标`，再以窗口尺寸调用本函数，于是卡片里出现的正是它背后
 * 那一块背景 —— 与旧版"整屏副本 + 偏移 + 模糊"的视觉结果一致，
 * 但代价只与卡片面积成正比，且**没有任何离屏纹理与 RenderEffect**。
 */
fun DrawScope.drawFluidGradient(
    colors: List<Color>,
    dark: Boolean,
    phase: Float,
    alphaScale: Float = 1f,
    canvasSize: Size = size,
) {
    val width = canvasSize.width
    val height = canvasSize.height
    if (width <= 0f || height <= 0f) return

    val palette = colors.ifEmpty { DefaultFluidColors }
    val glowAlpha = (if (dark) 0.55f else 0.34f) * alphaScale
    val top = if (dark) Color(0xFF0B0B14) else Color(0xFFFBFBFE)
    val bottom = if (dark) Color(0xFF05050A) else Color(0xFFEDEEF8)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(top, bottom),
            startY = 0f,
            endY = height,
        ),
        size = canvasSize,
    )

    palette.forEachIndexed { index, color ->
        val phaseX = wrap(phase + index * 0.29f)
        val phaseY = wrap(phase * 0.83f + index * 0.41f)
        val centerX = width * (0.12f + 0.76f * ease(phaseX))
        val centerY = height * (0.10f + 0.72f * ease(phaseY))
        val radius = max(width, height) * (0.44f + 0.10f * index)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = glowAlpha), Color.Transparent),
                center = Offset(centerX, centerY),
                radius = radius,
            ),
            radius = radius,
            center = Offset(centerX, centerY),
        )
    }

    // 底部暗角，让内容区与系统栏过渡更自然
    drawRect(
        brush = Brush.verticalGradient(
            0.55f to Color.Transparent,
            1f to (if (dark) Color(0x66000000) else Color(0x14000000)),
        ),
        size = canvasSize,
    )
}

/** 0..1 的平滑往复（正弦），让光斑漂移没有折返的突兀感 */
private fun ease(t: Float): Float = ((sin(t * 2.0 * PI) + 1.0) / 2.0).toFloat()

private fun wrap(value: Float): Float {
    val v = value % 1f
    return if (v < 0f) v + 1f else v
}

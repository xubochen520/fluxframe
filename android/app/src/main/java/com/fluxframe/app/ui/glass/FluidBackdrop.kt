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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.fluxframe.app.ui.theme.DefaultFluidColors
import com.fluxframe.app.ui.theme.LocalFluidCanvasSize
import com.fluxframe.app.ui.theme.LocalFluidPhase
import com.fluxframe.app.ui.theme.STATIC_FLUID_PHASE
import com.fluxframe.app.ui.theme.fluxPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * 全屏流体背景 + 相位分发。
 *
 * 背景是四团缓慢漂移的椭圆光斑叠在竖向渐变上 —— 纯 Canvas 绘制，
 * 不依赖 WebGL，任何机型都能跑，且与服务器端 `fluidColors` 同色。
 *
 * 性能上有四条硬约束：
 *  1. **相位按 ~12fps 量化**。30 秒的缓慢漂移不需要逐帧重绘，12fps 下人眼看不出差别。
 *  2. **相位通过 [LocalFluidPhase] 以 `State` 形式下发**，各组件只在绘制 lambda 里
 *     读 `.value`。这样相位变化只触发**重绘**，不会让读取者重组。
 *  3. **不在前台时完全不推进**。推进跟随帧时钟，再用生命周期观察者兜一层。
 *  4. **画刷只建一次**。[FluidPainter] 把着色器按调色板缓存起来，绘制阶段不分配对象。
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
            phase.floatValue = trianglePhase(STATIC_FLUID_PHASE + seconds / DRIFT_PERIOD_SECONDS)
        }
    }

    val palette = colors.ifEmpty { DefaultFluidColors }
    val painter = remember(palette, darkTheme) { FluidPainter(palette, darkTheme) }

    // 背景画布的真实像素尺寸：下发给玻璃卡片，让它们内部的"背景副本"能精确对齐
    val canvasSize = remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(fluxPalette(darkTheme).bg0)
            .onGloballyPositioned { coordinates -> canvasSize.value = coordinates.size.toSize() },
    ) {
        CompositionLocalProvider(
            LocalFluidPhase provides phase,
            LocalFluidCanvasSize provides canvasSize,
            LocalFluidPainter provides painter,
        ) {
            FluidBackdrop(modifier = Modifier.matchParentSize())
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

/** 单程 30 秒（0→1→0 共 60 秒） */
private const val DRIFT_PERIOD_SECONDS = 30.0

/** 约 12fps：缓慢漂移根本不需要逐帧 */
private const val DRIFT_FRAME_NANOS = 83_000_000L

/** 0→1→0 的三角波；光斑轨迹在两端速度恰好为零，折返时没有跳变 */
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
 * 只读相位、只重绘。玻璃卡片会用同一个 [FluidPainter] 画出「同一相位的副本」，
 * 从而在没有原生 backdrop blur 的前提下得到一致的折射感。
 */
@Composable
fun FluidBackdrop(
    modifier: Modifier = Modifier,
    alphaScale: Float = 1f,
) {
    val phaseState = LocalFluidPhase.current
    val painter = LocalFluidPainter.current
    Canvas(modifier = modifier) {
        painter.draw(this, phase = phaseState.value, alphaScale = alphaScale)
    }
}

/** 当前主题的流体画笔；恒为深色的舞台（查看器、星系）会换成深色那一支 */
val LocalFluidPainter = staticCompositionLocalOf { FluidPainter(DefaultFluidColors, dark = true) }

/** 一团光斑：位置与半径都是画布尺寸的比例，轨迹是两个整数倍频余弦合成的小弧线 */
private class Blob(
    val first: Int,
    val second: Int,
    val mix: Float,
    val centerX: Float,
    val centerY: Float,
    val driftX: Float,
    val driftY: Float,
    val waveX: Int,
    val waveY: Int,
    val radius: Float,
    val stretchX: Float,
    val stretchY: Float,
    val darkAlpha: Float,
    val lightAlpha: Float,
)

/** 左上主色、右侧次色、左下第三色，再在顶部补一团主次混色当「光源」 */
private val BLOBS = arrayOf(
    Blob(0, 0, 0f, 0.14f, 0.08f, 0.10f, 0.05f, 1, 2, 0.60f, 1.28f, 0.84f, 0.50f, 0.34f),
    Blob(1, 1, 0f, 0.96f, 0.42f, 0.08f, 0.09f, 2, 1, 0.50f, 0.84f, 1.22f, 0.40f, 0.30f),
    Blob(2, 2, 0f, 0.20f, 0.94f, 0.12f, 0.05f, 1, 3, 0.54f, 1.32f, 0.80f, 0.42f, 0.30f),
    Blob(0, 1, 0.5f, 0.72f, -0.02f, 0.09f, 0.03f, 3, 2, 0.34f, 1.42f, 0.70f, 0.26f, 0.22f),
)

/**
 * 流体渐变的唯一实现。
 *
 * 着色器在画布尺寸变化时才重建，之后每一帧只做平移与缩放：光斑的径向渐变以原点为圆心，
 * 靠画布变换移到当前位置并压成椭圆。玻璃卡片会先把画布平移 `-卡片窗口坐标`，再以背景
 * 画布尺寸调用 [draw]，于是卡片里出现的正是它背后那一块背景，代价只与卡片面积成正比。
 */
class FluidPainter(colors: List<Color>, private val dark: Boolean) {

    private val palette = colors.ifEmpty { DefaultFluidColors }
    private val blobColors = Array(BLOBS.size) { index ->
        val blob = BLOBS[index]
        lerp(palette[blob.first % palette.size], palette[blob.second % palette.size], blob.mix)
    }
    private var builtFor = Size.Zero
    private var base: Brush? = null
    private var vignette: Brush? = null
    private val blobBrushes = arrayOfNulls<Brush>(BLOBS.size)
    private val blobRadii = FloatArray(BLOBS.size)

    private fun ensure(canvas: Size) {
        if (canvas == builtFor) return
        builtFor = canvas
        val neutral = fluxPalette(dark)
        base = ShaderBrush(
            LinearGradientShader(
                from = Offset.Zero,
                to = Offset(0f, canvas.height),
                colors = listOf(neutral.bg1, neutral.bg0),
            ),
        )
        vignette = ShaderBrush(
            LinearGradientShader(
                from = Offset(0f, canvas.height * 0.58f),
                to = Offset(0f, canvas.height),
                colors = listOf(Color.Transparent, if (dark) Color(0x70000000) else Color(0x141E1B4B)),
            ),
        )
        val span = max(canvas.width, canvas.height)
        for (index in BLOBS.indices) {
            val blob = BLOBS[index]
            val color = blobColors[index]
            val alpha = if (dark) blob.darkAlpha else blob.lightAlpha
            val radius = span * blob.radius
            blobRadii[index] = radius
            blobBrushes[index] = ShaderBrush(
                RadialGradientShader(
                    center = Offset.Zero,
                    radius = radius,
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.52f),
                        color.copy(alpha = alpha * 0.14f),
                        color.copy(alpha = 0f),
                    ),
                    colorStops = listOf(0f, 0.36f, 0.70f, 1f),
                ),
            )
        }
    }

    fun draw(scope: DrawScope, phase: Float, alphaScale: Float = 1f, canvasSize: Size = scope.size) {
        val width = canvasSize.width
        val height = canvasSize.height
        if (width <= 0f || height <= 0f) return
        ensure(canvasSize)
        val angle = phase * TWO_PI
        with(scope) {
            base?.let { drawRect(brush = it, size = canvasSize) }
            for (index in BLOBS.indices) {
                val blob = BLOBS[index]
                val brush = blobBrushes[index] ?: continue
                val x = width * (blob.centerX + blob.driftX * cos(angle * blob.waveX))
                val y = height * (blob.centerY + blob.driftY * cos(angle * blob.waveY))
                withTransform({
                    translate(x, y)
                    scale(blob.stretchX, blob.stretchY, pivot = Offset.Zero)
                }) {
                    drawCircle(brush = brush, radius = blobRadii[index], center = Offset.Zero, alpha = alphaScale)
                }
            }
            vignette?.let { drawRect(brush = it, size = canvasSize) }
        }
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()
    }
}

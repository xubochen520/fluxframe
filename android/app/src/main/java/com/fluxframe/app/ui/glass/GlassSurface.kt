package com.fluxframe.app.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalFluidCanvasSize
import com.fluxframe.app.ui.theme.LocalFluidColors
import com.fluxframe.app.ui.theme.LocalFluidPhase
import com.fluxframe.app.ui.theme.LocalGlassTokens
import kotlin.math.roundToInt

/**
 * 玻璃拟态容器 —— 三种风格共用一套代码。
 *
 * | 风格 | 表现 |
 * |---|---|
 * | 默认 | 接近实色的卡片，只有极细描边，省电、对比度最高 |
 * | 亚克力 | 半透明底 + 背景副本 + 噪点颗粒 + 细描边 |
 * | 液态玻璃 | 更强通透 + 高光沿对角扫过 + 边缘高亮，有厚度 |
 *
 * ### [backdrop] 的代价模型（这一版重写过）
 * 旧实现让每张"铬"部件都绘制一份**整屏**背景副本并套 `Modifier.blur(32.dp)`：
 * 每个部件一张全屏离屏纹理 + 三遍模糊，顶栏/底栏/胶囊/弹窗同时存在时就是
 * 4~5 次全屏模糊，这是滑动卡顿与耗电的头号原因。
 *
 * 现在改为：先把画布平移 `-本节点的窗口坐标`，再以**窗口尺寸**绘制同一份流体渐变。
 * 因为节点已被 `clip(shape)` 裁掉，实际着色面积只有部件自身，视觉结果与
 * "整屏副本 + 偏移"逐像素一致，但**没有离屏纹理、没有 RenderEffect**，
 * 代价与部件面积成正比。列表里的卡片依旧保持 `backdrop = false`（零成本）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    tint: Color? = null,
    backdrop: Boolean = false,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val dark = LocalDarkTheme.current
    val resolvedShape = shape ?: RoundedCornerShape(tokens.cornerRadius)
    val surfaceColor = tint ?: MaterialTheme.colorScheme.surface
    val fluidColors = LocalFluidColors.current
    val phaseState = LocalFluidPhase.current

    val useBackdrop = backdrop && tokens.drawsBackdrop
    // 背景画布的真实尺寸（由 FluidBackgroundHost 实测下发）。
    // 还在测量阶段时回退到"按屏幕 dp 换算"的估算值，保证第一帧也能画对。
    val fluidCanvasSize = LocalFluidCanvasSize.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fallbackWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val fallbackHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 只在需要背景副本时记录窗口坐标；并且这个 State 只在绘制阶段被读取，
    // 因此滚动时只会触发"重绘"而不是重组 —— 这是列表流畅度的关键。
    var positionInWindow by remember { mutableStateOf(Offset.Zero) }

    val positionModifier = if (useBackdrop) {
        Modifier.onGloballyPositioned { coordinates -> positionInWindow = coordinates.positionInWindow() }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(positionModifier)
            .clip(resolvedShape),
    ) {
        if (useBackdrop) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val measured = fluidCanvasSize.value
                        val window = if (measured.width > 0f && measured.height > 0f) {
                            measured
                        } else {
                            Size(fallbackWidthPx, fallbackHeightPx)
                        }
                        if (window.width <= 0f || window.height <= 0f) return@drawBehind
                        translate(left = -positionInWindow.x, top = -positionInWindow.y) {
                            drawFluidGradient(
                                colors = fluidColors,
                                dark = dark,
                                phase = phaseState.value,
                                alphaScale = 0.85f,
                                canvasSize = window,
                            )
                        }
                    },
            )
            // 通透风格下用一层极淡的白纱替代原来的模糊，压住背景对比度
            if (tokens.backdropSoftness > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = tokens.backdropSoftness * (if (dark) 0.05f else 0.10f))),
                )
            }
        }

        // 玻璃底色：默认风格近乎不透明，亚克力 / 液态玻璃逐级通透
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(surfaceColor.copy(alpha = tokens.tintAlpha)),
        )

        // 顶部到中部的一点高光渐变，制造"厚度"
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.05f else 0.22f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 噪点与高光扫过都只在"铬"部件上绘制：
        // 网格里的卡片数量以百计，逐卡叠一遍 BlendMode.Overlay 的噪点得不偿失。
        if (useBackdrop && tokens.drawsNoise) {
            NoiseOverlay(modifier = Modifier.matchParentSize(), alpha = tokens.noiseAlpha)
        }

        if (useBackdrop && tokens.drawsSpecular) {
            SpecularSweep(modifier = Modifier.matchParentSize(), alpha = tokens.specularAlpha)
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = borderWidth,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = tokens.borderAlpha),
                            Color.White.copy(alpha = tokens.borderAlpha * 0.25f),
                            Color.White.copy(alpha = tokens.borderAlpha * 0.6f),
                        ),
                    ),
                    shape = resolvedShape,
                ),
        )

        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 亚克力颗粒：一张 512×512 的静态噪点图，按卡片尺寸铺开，全应用共享同一份
 */
@Composable
fun NoiseOverlay(modifier: Modifier = Modifier, alpha: Float = 0.05f) {
    if (alpha <= 0f) return
    val noise = rememberNoiseImage()
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        drawImage(
            image = noise,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(noise.width, noise.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alpha,
            blendMode = BlendMode.Overlay,
            filterQuality = FilterQuality.None,
        )
    }
}

/** 液态玻璃的高光扫过：一条沿对角方向的柔光带随全局相位移动 */
@Composable
fun SpecularSweep(modifier: Modifier = Modifier, alpha: Float = 0.16f) {
    if (alpha <= 0f) return
    val phaseState = LocalFluidPhase.current
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val phase = phaseState.value
        val span = width + height
        val head = -span * 0.5f + phase * span * 1.6f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = alpha * 0.55f),
                    Color.White.copy(alpha = alpha),
                    Color.Transparent,
                ),
                start = Offset(head, 0f),
                end = Offset(head + span * 0.38f, height),
            ),
        )
    }
}

/* --------------------------- 共享噪点纹理 --------------------------- */

private var cachedNoise: ImageBitmap? = null

@Composable
private fun rememberNoiseImage(): ImageBitmap = remember {
    cachedNoise ?: buildNoiseImage().also { cachedNoise = it }
}

private fun buildNoiseImage(size: Int = 512): ImageBitmap {
    val pixels = IntArray(size * size)
    // 固定种子：每次启动的颗粒完全一致，避免视觉跳变
    val random = java.util.Random(0x5EEDL)
    for (index in pixels.indices) {
        val value = random.nextInt(256)
        pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

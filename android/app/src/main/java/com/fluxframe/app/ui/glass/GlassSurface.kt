package com.fluxframe.app.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.ui.theme.DarkFluxPalette
import com.fluxframe.app.ui.theme.IndigoInk
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalFluidCanvasSize
import com.fluxframe.app.ui.theme.LocalFluidColors
import com.fluxframe.app.ui.theme.LocalFluidPhase
import com.fluxframe.app.ui.theme.LocalGlassTokens
import com.fluxframe.app.ui.theme.fluxColorScheme
import com.fluxframe.app.ui.theme.fluxPalette
import com.fluxframe.app.ui.theme.glassTokensFor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 玻璃拟态容器 —— 五种风格共用一套代码。
 *
 * | 风格 | 表现 |
 * |---|---|
 * | 默认 | 不透明实色卡片，只有极细描边，省电、对比度最高 |
 * | 亚克力 | 中等通透 + 噪点颗粒 |
 * | 液态玻璃 | 最通透 + 高光沿对角扫过 |
 * | 柔雾 | 更厚的染色，文字最稳 |
 * | 霓虹 | 三色描边与更强的辉光 |
 *
 * 背景流体是唯一的光源：面板自己不带颜色，只有染色、顶边高光和一圈细描边。
 * 整张面板在**一个绘制节点**里画完（底色、高光、辉光、噪点、高光扫过），不再为每一层
 * 单独建一个子节点；渐变画刷按尺寸缓存，绘制阶段不分配对象。
 *
 * ### [backdrop] 的代价模型
 * 浮在内容之上的部件（顶栏、底栏、弹窗、胶囊）需要盖住身后的内容，所以先画一份
 * 流体副本：把画布平移 `-本节点的窗口坐标`，再以背景画布尺寸绘制同一份流体。节点已被
 * `clip(shape)` 裁掉，实际着色面积只有部件自身，**没有离屏纹理、没有 RenderEffect**。
 * 列表里的卡片保持 `backdrop = false`：它们本来就直接叠在真正的背景上。
 *
 * [glow] 给面板一团同色的内侧光晕；悬浮件（[backdrop] 为真）还会多一圈彩色投影。
 * [raised] 用更亮的一档染色（glass-2），表示叠在别的面板之上。
 * [onClick] 的水波纹会被裁到圆角内，比在外面套 `clickable` 干净。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    tint: Color? = null,
    backdrop: Boolean = false,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    glow: Color? = null,
    raised: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val dark = LocalDarkTheme.current
    val palette = fluxPalette(dark)
    val scheme = MaterialTheme.colorScheme
    val resolvedShape = shape ?: RoundedCornerShape(tokens.cornerRadius)
    val painter = LocalFluidPainter.current
    val phaseState = LocalFluidPhase.current
    val fluidCanvasSize = LocalFluidCanvasSize.current

    val flat = tokens.style == AppThemeStyle.DEFAULT
    val useBackdrop = backdrop && tokens.drawsBackdrop
    val glowColor = glow?.takeIf { tokens.glowAlpha > 0f }

    // 背景画布还在测量时回退到按屏幕 dp 换算的估算值，保证第一帧也能画对
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fallbackWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val fallbackHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 窗口坐标只在绘制阶段读取：滚动时只重绘、不重组
    val positionInWindow = remember { mutableStateOf(Offset.Zero) }

    val fill = remember(tint, flat, raised, tokens, palette) {
        val base = tint ?: when {
            !flat -> palette.glassTint
            raised -> palette.surfaceRaised
            else -> palette.surface
        }
        val veil = when {
            flat -> Color.Transparent
            raised -> palette.glass2
            else -> palette.glass1
        }
        veil.compositeOver(base.copy(alpha = base.alpha * tokens.tintAlpha))
    }
    val softVeil = if (useBackdrop && tokens.backdropSoftness > 0f) {
        Color.White.copy(alpha = tokens.backdropSoftness * (if (dark) 0.05f else 0.10f))
    } else {
        Color.Transparent
    }

    val fadePx = with(density) { 30.dp.toPx() }
    val stroke: Brush = remember(tokens, dark, glowColor, scheme.primary, scheme.secondary, scheme.tertiary, fadePx) {
        val neutral = (if (dark) Color.White else IndigoInk).copy(alpha = tokens.borderAlpha)
        when {
            tokens.style == AppThemeStyle.NEON -> Brush.linearGradient(
                listOf(
                    scheme.primary.copy(alpha = tokens.borderAlpha * 1.6f),
                    scheme.secondary.copy(alpha = tokens.borderAlpha),
                    scheme.tertiary.copy(alpha = tokens.borderAlpha * 1.3f),
                ),
            )

            tokens.highlightAlpha <= 0f -> SolidColor(neutral)

            else -> {
                val side = if (glowColor != null) {
                    lerp(neutral, glowColor.copy(alpha = max(tokens.borderAlpha, tokens.glowAlpha)), 0.45f)
                } else {
                    neutral
                }
                Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = tokens.highlightAlpha), side),
                    startY = 0f,
                    endY = fadePx,
                )
            }
        }
    }

    val noise = if (useBackdrop && tokens.drawsNoise) rememberNoiseImage() else null
    val specularAlpha = if (useBackdrop && tokens.drawsSpecular) tokens.specularAlpha else 0f
    val sheenAlpha = tokens.highlightAlpha * 0.22f
    val glowAlpha = tokens.glowAlpha

    // 只有实心的悬浮件才给彩色投影：通透面板底下的阴影会透上来，显得发脏
    val shadowModifier = if (glowColor != null && useBackdrop) {
        Modifier.shadow(
            elevation = 18.dp,
            shape = resolvedShape,
            clip = false,
            ambientColor = glowColor,
            spotColor = glowColor,
        )
    } else {
        Modifier
    }
    val positionModifier = if (useBackdrop) {
        Modifier.onGloballyPositioned { coordinates -> positionInWindow.value = coordinates.positionInWindow() }
    } else {
        Modifier
    }
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier = modifier
            .then(shadowModifier)
            .then(positionModifier)
            .clip(resolvedShape)
            .then(clickModifier)
            .drawWithCache {
                val sheen = if (sheenAlpha > 0f) {
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = sheenAlpha), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.46f,
                    )
                } else {
                    null
                }
                val glowRadius = max(size.width, size.height) * 0.9f
                val glowBrush = if (glowColor != null && glowRadius > 0f) {
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to glowColor.copy(alpha = glowAlpha),
                            0.45f to glowColor.copy(alpha = glowAlpha * 0.36f),
                            1f to glowColor.copy(alpha = 0f),
                        ),
                        center = Offset(size.width * 0.10f, 0f),
                        radius = glowRadius,
                    )
                } else {
                    null
                }
                // 高光带以原点为起点建一次，扫动时只平移画布
                val span = size.width + size.height
                val specular = if (specularAlpha > 0f && span > 0f) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = specularAlpha * 0.55f),
                            Color.White.copy(alpha = specularAlpha),
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(span * 0.38f, size.height),
                    )
                } else {
                    null
                }
                val noiseSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))

                onDrawBehind {
                    if (useBackdrop) {
                        val measured = fluidCanvasSize.value
                        val window = if (measured.width > 0f && measured.height > 0f) {
                            measured
                        } else {
                            Size(fallbackWidthPx, fallbackHeightPx)
                        }
                        if (window.width > 0f && window.height > 0f) {
                            val origin = positionInWindow.value
                            translate(left = -origin.x, top = -origin.y) {
                                painter.draw(this, phase = phaseState.value, alphaScale = 0.85f, canvasSize = window)
                            }
                        }
                        if (softVeil.alpha > 0f) drawRect(softVeil)
                    }
                    drawRect(fill)
                    if (glowBrush != null) drawRect(glowBrush)
                    if (sheen != null) drawRect(sheen)
                    if (noise != null) {
                        drawImage(
                            image = noise,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(noise.width, noise.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = noiseSize,
                            alpha = tokens.noiseAlpha,
                            blendMode = BlendMode.Overlay,
                            filterQuality = FilterQuality.None,
                        )
                    }
                    if (specular != null) {
                        val head = -span * 0.5f + phaseState.value * span * 1.6f
                        translate(left = head) {
                            drawRect(brush = specular, topLeft = Offset(-head, 0f), size = size)
                        }
                    }
                }
            }
            .border(width = borderWidth, brush = stroke, shape = resolvedShape),
    ) {
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 恒为深色的舞台（全屏查看器、星系图）。
 *
 * 这些画面的底永远是黑的，浅色主题下如果照旧取浅色令牌，就会出现白玻璃压在黑底上、
 * 深色字压在深空上。这里把主题、玻璃参数和流体画笔一起切到深色那一套。
 */
@Composable
fun DarkStage(content: @Composable () -> Unit) {
    val tokens = LocalGlassTokens.current
    val colors = LocalFluidColors.current
    val darkTokens = remember(tokens.style, tokens.drawsNoise) {
        glassTokensFor(tokens.style, dark = true, noiseEnabled = tokens.drawsNoise)
    }
    val darkScheme = remember(colors) { fluxColorScheme(dark = true, accents = colors) }
    val darkPainter = remember(colors) { FluidPainter(colors, dark = true) }
    CompositionLocalProvider(
        LocalGlassTokens provides darkTokens,
        LocalDarkTheme provides true,
        LocalFluidPainter provides darkPainter,
    ) {
        MaterialTheme(
            colorScheme = darkScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides DarkFluxPalette.text1, content = content)
        }
    }
}

/* --------------------------- 共享噪点纹理 --------------------------- */

private var cachedNoise: ImageBitmap? = null

/** 亚克力颗粒：一张 512×512 的静态噪点图，按卡片尺寸铺开，全应用共享同一份 */
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

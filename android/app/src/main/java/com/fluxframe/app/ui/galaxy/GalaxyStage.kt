package com.fluxframe.app.ui.galaxy

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fluxframe.app.ui.theme.DarkFluxPalette
import com.fluxframe.app.ui.theme.LocalAnimationsEnabled
import com.fluxframe.app.ui.theme.DefaultFluidColors
import com.fluxframe.app.ui.theme.LocalFluidColors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* ============================================================================
 * 星系「深空舞台」的共享绘制件。
 *
 * 这里只有**外观**：底色与三色雾、尘埃染色、预渲染的节点光晕纹理、低端机降级判断。
 * 布局、物理、相机数学一行都不碰 —— 那些仍然全在 GalaxyMath / Galaxy3DMath 里。
 *
 * 性能三条硬约束：
 *  1. 画刷（底色、雾、暗角）按画布尺寸缓存，尺寸不变时一帧也不重建；
 *  2. 光晕是**一张预渲染纹理**，绘制阶段只做 drawImage，不 new 任何对象；
 *  3. 节点各自的 ColorFilter 由调用方在 remember 里一次性建好，绘制阶段零分配。
 * ========================================================================== */

/**
 * 是否走「降级档」：低端机或用户关掉了背景动效。
 *
 * 判断思路与网页端 `FluidCanvas.vue` 的 `lowEnd` 一致（那边是 `hw <= 4 || mem <= 4`）：
 * 核心数 ≤ 4 或系统自报低内存 → 降级。降级后星系**关掉选中脉冲与尘埃**，
 * 只留深空底色、边与节点本体 —— 那三样才是"这是张关系图"的必要信息。
 */
@Composable
fun rememberGalaxyLowEnd(): Boolean {
    val context = LocalContext.current
    val animationsEnabled = LocalAnimationsEnabled.current
    val weakDevice = remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = manager?.isLowRamDevice == true
        lowRam || Runtime.getRuntime().availableProcessors() <= 4
    }
    return weakDevice || !animationsEnabled
}

/* ------------------------------ 深空底色 + 三色雾 ------------------------------ */

/** 三团雾的固定相对位置（与网页端深空舞台同一套构图：左上、右上、左下） */
private val FOG_SPOTS = arrayOf(
    floatArrayOf(0.18f, 0.12f, 0.62f),
    floatArrayOf(0.92f, 0.30f, 0.52f),
    floatArrayOf(0.30f, 0.92f, 0.56f),
)

/**
 * 深空舞台背景：竖向底色渐变 + 三团同色系雾 + 底部暗角。
 *
 * 底色取 [DarkFluxPalette] 的 bg1 → bg0，再压一层更深的收尾 —— 星系的底永远比卡片更黑，
 * 这样玻璃面板浮上来才有"悬在太空里"的感觉。三团雾的颜色来自服务端 `fluidColors`，
 * 与网页端、与 App 背景流体是同一套配色。
 *
 * 用法：在 `remember(accents) { GalaxyBackdrop(accents) }` 里建一次，
 * 每帧只调 [draw]（画刷按画布尺寸内部缓存，不重复分配）。
 */
class GalaxyBackdrop(accents: List<Color>) {

    private val fogColors: List<Color> = accents.takeIf { it.size >= 3 } ?: DefaultFluidColors

    private var builtFor = Size.Zero
    private var base: Brush? = null
    private var vignette: Brush? = null
    private val fogBrushes = arrayOfNulls<Brush>(FOG_SPOTS.size)
    private val fogRadii = FloatArray(FOG_SPOTS.size)

    private fun ensure(canvas: Size) {
        if (canvas == builtFor) return
        builtFor = canvas
        base = ShaderBrush(
            LinearGradientShader(
                from = Offset.Zero,
                to = Offset(0f, canvas.height),
                colors = listOf(
                    Color(0xFF070A14),
                    DarkFluxPalette.bg0,
                    Color(0xFF03040A),
                ),
                colorStops = listOf(0f, 0.52f, 1f),
            ),
        )
        vignette = ShaderBrush(
            LinearGradientShader(
                from = Offset(0f, canvas.height * 0.55f),
                to = Offset(0f, canvas.height),
                colors = listOf(Color.Transparent, Color(0x99000000)),
            ),
        )
        val span = max(canvas.width, canvas.height)
        for (index in FOG_SPOTS.indices) {
            val color = fogColors[index % fogColors.size]
            val radius = span * FOG_SPOTS[index][2]
            fogRadii[index] = radius
            fogBrushes[index] = ShaderBrush(
                RadialGradientShader(
                    center = Offset.Zero,
                    radius = radius,
                    colors = listOf(
                        color.copy(alpha = 0.17f),
                        color.copy(alpha = 0.085f),
                        color.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                    colorStops = listOf(0f, 0.34f, 0.68f, 1f),
                ),
            )
        }
    }

    fun draw(scope: DrawScope, canvasSize: Size = scope.size) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return
        ensure(canvasSize)
        with(scope) {
            base?.let { drawRect(brush = it, size = canvasSize) }
            for (index in FOG_SPOTS.indices) {
                val brush = fogBrushes[index] ?: continue
                drawCircle(
                    brush = brush,
                    radius = fogRadii[index],
                    center = Offset(
                        canvasSize.width * FOG_SPOTS[index][0],
                        canvasSize.height * FOG_SPOTS[index][1],
                    ),
                )
            }
            vignette?.let { drawRect(brush = it, size = canvasSize) }
        }
    }
}

/* ------------------------------ 尘埃染色 ------------------------------ */

/**
 * 尘埃（背景星点）的颜色。
 *
 * 不再是清一色的白：按 [variant] 在三色之间循环，再按 [depth]（0 远 ~ 1 近）
 * 往白里提一点 —— 远景偏冷、近景偏亮，纯静态画面也能读出纵深。
 * 颜色是算出来的（[lerp] 不分配对象），所以绘制阶段直接调它没有代价；调用方也可以
 * 在 remember 里预生成一张小调色板。
 */
fun galaxyDustColor(accents: List<Color>, variant: Int, depth: Float): Color {
    val palette = accents.takeIf { it.size >= 3 } ?: DefaultFluidColors
    val a = palette[variant % palette.size]
    val b = palette[(variant + 1) % palette.size]
    val base = lerp(a, b, (variant * 0.37f) % 1f)
    return lerp(base, Color.White, (depth * 0.45f).coerceIn(0f, 0.45f))
}

/**
 * 预生成尘埃调色板：固定 12 档颜色 × 4 档深度亮度。
 * 在 `remember(accents)` 里建一次，绘制时按下标取，**绘制阶段一次分配都没有**。
 */
fun galaxyDustPalette(accents: List<Color>): Array<Array<Color>> {
    val palette = accents.takeIf { it.size >= 3 } ?: DefaultFluidColors
    return Array(12) { variant ->
        Array(4) { depth ->
            galaxyDustColor(palette, variant, depth / 3f)
        }
    }
}

/* ------------------------------ 节点光晕纹理 ------------------------------ */

private var cachedGlow: ImageBitmap? = null

/**
 * 节点光晕纹理：一张白色径向衰减贴图，全应用只生成一次。
 *
 * 为什么用纹理而不是每帧画三层同心圆：同心圆在密集处会叠出一片"雾"，
 * 而且一个节点三次 drawCircle，两百个节点就是六百次调用。预渲染纹理 + 加色混合
 * 一次调用就得到同样的发光感，形状也更接近真实的光斑（三次衰减，边缘迅速消失）。
 *
 * 颜色由调用方通过 `ColorFilter.tint(color, BlendMode.SrcIn)` 上色，
 * 所以**一张纹理能服务所有分组颜色**。
 */
fun galaxyGlowSprite(size: Int = 128): ImageBitmap {
    cachedGlow?.let { return it }
    val pixels = IntArray(size * size)
    val center = (size - 1) / 2f
    val radius = size / 2f
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = x - center
            val dy = y - center
            val distance = sqrt(dx * dx + dy * dy) / radius
            val alpha = if (distance >= 1f) {
                0
            } else {
                val t = 1f - distance
                // 三次衰减：中心一个亮核，外圈迅速收干净
                ((t * t * t) * 255f).toInt().coerceIn(0, 255)
            }
            pixels[y * size + x] = (alpha shl 24) or 0x00FFFFFF
        }
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap().also { cachedGlow = it }
}

/** 光晕绘制参数：目标圆心、屏幕半径。抽出来是为了两个页面共用同一段 drawImage 调用。 */
fun DrawScope.drawGalaxyGlow(
    sprite: ImageBitmap,
    centerX: Float,
    centerY: Float,
    radius: Float,
    alpha: Float,
    colorFilter: ColorFilter,
) {
    if (alpha <= 0.01f || radius <= 0.5f) return
    val diameter = (radius * 2f).roundToInt().coerceAtLeast(2)
    drawImage(
        image = sprite,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sprite.width, sprite.height),
        dstOffset = IntOffset((centerX - radius).roundToInt(), (centerY - radius).roundToInt()),
        dstSize = IntSize(diameter, diameter),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = colorFilter,
        // 加色混合：重叠处自然叠亮，和网页端 WebGL 的加色一致
        blendMode = BlendMode.Plus,
    )
}

/* ------------------------------ 相似边的配色 ------------------------------ */

/**
 * 一条相似边的颜色：**同组同色，跨组取两端中间色**。
 *
 * 以前的实现一律取 A 端的颜色，于是"这张和那张像"这件事在跨组时只能看到一半信息；
 * 中间色至少把两端都带上了，而且不需要为每条边建渐变画刷（那会让绘制阶段产生垃圾）。
 */
fun galaxyEdgeColor(colorA: Color, colorB: Color, sameGroup: Boolean): Color =
    if (sameGroup) colorA else lerp(colorA, colorB, 0.5f)

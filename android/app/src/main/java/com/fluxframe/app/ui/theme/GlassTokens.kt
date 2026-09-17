package com.fluxframe.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxframe.app.core.prefs.AppThemeStyle

/**
 * 一套风格的「玻璃参数」。
 * 三种风格只在参数上不同，组件代码完全共用 —— 这样切换风格是零成本的。
 */
@Immutable
data class GlassTokens(
    val style: AppThemeStyle,
    /**
     * 背景副本的柔化程度（0 = 不柔化）。
     *
     * 注意：这里**不再使用 `Modifier.blur`**。全屏 RenderEffect 模糊是整个应用
     * 最贵的一笔开销（每张"铬"部件都要一张全屏离屏纹理 + 三遍模糊），实测是
     * 滑动卡顿与耗电的主因。背景本体是平滑渐变，模糊几乎看不出差别，因此改为
     * "只按卡片自身区域重绘一份流体副本"，代价与卡片面积成正比。
     */
    val backdropSoftness: Float,
    /** 玻璃底色不透明度：越低越通透 */
    val tintAlpha: Float,
    val borderAlpha: Float,
    /** 亚克力噪点强度 */
    val noiseAlpha: Float,
    /** 液态玻璃的高光扫过强度 */
    val specularAlpha: Float,
    val cornerRadius: Dp,
) {
    val drawsBackdrop: Boolean get() = style != AppThemeStyle.DEFAULT
    val drawsSpecular: Boolean get() = style == AppThemeStyle.LIQUID_GLASS
    val drawsNoise: Boolean get() = style != AppThemeStyle.DEFAULT && noiseAlpha > 0f
}

fun glassTokensFor(
    style: AppThemeStyle,
    dark: Boolean,
    noiseEnabled: Boolean = true,
): GlassTokens = when (style) {
    AppThemeStyle.DEFAULT -> GlassTokens(
        style = style,
        backdropSoftness = 0f,
        tintAlpha = if (dark) 0.96f else 1f,
        borderAlpha = if (dark) 0.07f else 0.05f,
        noiseAlpha = 0f,
        specularAlpha = 0f,
        cornerRadius = 18.dp,
    )

    AppThemeStyle.ACRYLIC -> GlassTokens(
        style = style,
        backdropSoftness = 0.35f,
        tintAlpha = if (dark) 0.60f else 0.68f,
        borderAlpha = if (dark) 0.16f else 0.10f,
        noiseAlpha = if (noiseEnabled) 0.055f else 0f,
        specularAlpha = 0f,
        cornerRadius = 20.dp,
    )

    AppThemeStyle.LIQUID_GLASS -> GlassTokens(
        style = style,
        backdropSoftness = 0.55f,
        tintAlpha = if (dark) 0.40f else 0.50f,
        borderAlpha = if (dark) 0.26f else 0.18f,
        noiseAlpha = if (noiseEnabled) 0.035f else 0f,
        specularAlpha = if (dark) 0.16f else 0.22f,
        cornerRadius = 24.dp,
    )
}

val LocalGlassTokens = staticCompositionLocalOf {
    glassTokensFor(AppThemeStyle.LIQUID_GLASS, dark = true)
}

val LocalDarkTheme = staticCompositionLocalOf { true }

val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** 背景流体的三个颜色（与服务器 SystemSetting.fluidColors 同步） */
val LocalFluidColors = staticCompositionLocalOf { DefaultFluidColors }

/** 关闭动效或应用不在前台时的固定相位 */
const val STATIC_FLUID_PHASE = 0.35f

/**
 * 背景流体的实际画布尺寸（像素）。
 *
 * 玻璃卡片的"背景副本"必须先平移 `-卡片窗口坐标`、再以**背景画布尺寸**绘制同一份
 * 渐变，才能与背后的背景严丝合缝。这里由 [com.fluxframe.app.ui.glass.FluidBackgroundHost]
 * 实测自己的尺寸后下发，比用 `Configuration.screenWidthDp × density` 推算更准
 * （后者在带刘海/异形屏的机型上会差几个像素）。
 */
val LocalFluidCanvasSize: ProvidableCompositionLocal<State<Size>> =
    staticCompositionLocalOf<State<Size>> { mutableStateOf(Size.Zero) }

/**
 * 全局共享的流体动画相位（0..1）。
 *
 * 这里刻意暴露成 [State] 而不是裸 `Float`：
 * `staticCompositionLocalOf` 的值一旦变化，**所有读取者都会重组**。玻璃卡片遍布
 * 整个界面，如果相位在组合阶段被读取，背景每动一下就会触发整棵树重组（这是旧版
 * 卡顿与耗电的第二个原因）。暴露成 State 后，各组件只在 `Canvas` / `drawBehind`
 * 的绘制 lambda 里读 `.value`，相位变化只会让它们**重绘**，不会重组。
 */
val LocalFluidPhase: ProvidableCompositionLocal<State<Float>> =
    staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(STATIC_FLUID_PHASE) }

/** 当前玻璃卡片内的文字颜色：通透背景下统一提亮一档以保证对比度 */
fun onGlassColor(dark: Boolean, emphasis: Boolean): Color = when {
    dark && emphasis -> Color(0xFFF5F5FA)
    dark -> Color(0xFFC9C9DA)
    emphasis -> Color(0xFF101018)
    else -> Color(0xFF4A4A60)
}

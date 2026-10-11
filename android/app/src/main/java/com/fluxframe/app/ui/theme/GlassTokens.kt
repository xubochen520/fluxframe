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
 * 五种风格只在参数上不同，组件代码完全共用 —— 这样切换风格是零成本的。
 */
@Immutable
data class GlassTokens(
    val style: AppThemeStyle,
    /**
     * 背景副本的柔化程度（0 = 不柔化）。
     *
     * 这里**不使用 `Modifier.blur`**：全屏 RenderEffect 模糊是整个应用最贵的一笔开销，
     * 实测是滑动卡顿与耗电的主因。背景本体是平滑渐变，模糊几乎看不出差别，因此改为
     * 「只按卡片自身区域重绘一份流体副本」，代价与卡片面积成正比。
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
    /** 顶边高光描边的不透明度（0 = 没有高光） */
    val highlightAlpha: Float = 0f,
    /** 辉光强度：面板内侧的径向光晕与悬浮件的彩色投影都按它缩放 */
    val glowAlpha: Float = 0f,
) {
    val drawsBackdrop: Boolean get() = style != AppThemeStyle.DEFAULT
    val drawsSpecular: Boolean get() = style == AppThemeStyle.LIQUID_GLASS || style == AppThemeStyle.NEON
    val drawsNoise: Boolean get() = style != AppThemeStyle.DEFAULT && noiseAlpha > 0f
}

fun glassTokensFor(
    style: AppThemeStyle,
    dark: Boolean,
    noiseEnabled: Boolean = true,
): GlassTokens = when (style) {
    /* 扁平实色：不透明，无高光 */
    AppThemeStyle.DEFAULT -> GlassTokens(
        style = style,
        backdropSoftness = 0f,
        tintAlpha = 1f,
        borderAlpha = if (dark) 0.09f else 0.08f,
        noiseAlpha = 0f,
        specularAlpha = 0f,
        cornerRadius = 22.dp,
        highlightAlpha = 0f,
        glowAlpha = 0f,
    )

    /* 亚克力：中等透明 + 噪点 */
    AppThemeStyle.ACRYLIC -> GlassTokens(
        style = style,
        backdropSoftness = 0.35f,
        tintAlpha = if (dark) 0.56f else 0.50f,
        borderAlpha = if (dark) 0.12f else 0.10f,
        noiseAlpha = if (noiseEnabled) 0.055f else 0f,
        specularAlpha = 0f,
        cornerRadius = 22.dp,
        highlightAlpha = if (dark) 0.16f else 0.70f,
        glowAlpha = if (dark) 0.10f else 0.06f,
    )

    /* 液态玻璃：更透明 + 高光扫过 */
    AppThemeStyle.LIQUID_GLASS -> GlassTokens(
        style = style,
        backdropSoftness = 0.55f,
        tintAlpha = if (dark) 0.30f else 0.22f,
        borderAlpha = if (dark) 0.16f else 0.14f,
        noiseAlpha = if (noiseEnabled) 0.03f else 0f,
        specularAlpha = if (dark) 0.14f else 0.20f,
        cornerRadius = 28.dp,
        highlightAlpha = if (dark) 0.22f else 0.90f,
        glowAlpha = if (dark) 0.16f else 0.10f,
    )

    /* 柔雾：柔和 tint */
    AppThemeStyle.FROSTED -> GlassTokens(
        style = style,
        backdropSoftness = 0.50f,
        tintAlpha = if (dark) 0.68f else 0.62f,
        borderAlpha = if (dark) 0.10f else 0.08f,
        noiseAlpha = if (noiseEnabled) 0.02f else 0f,
        specularAlpha = 0f,
        cornerRadius = 26.dp,
        highlightAlpha = if (dark) 0.12f else 0.60f,
        glowAlpha = if (dark) 0.07f else 0.05f,
    )

    /* 霓虹：描边辉光加强 */
    AppThemeStyle.NEON -> GlassTokens(
        style = style,
        backdropSoftness = 0.25f,
        tintAlpha = if (dark) 0.46f else 0.40f,
        borderAlpha = if (dark) 0.36f else 0.26f,
        noiseAlpha = if (noiseEnabled) 0.02f else 0f,
        specularAlpha = if (dark) 0.22f else 0.14f,
        cornerRadius = 20.dp,
        highlightAlpha = if (dark) 0.26f else 0.80f,
        glowAlpha = if (dark) 0.34f else 0.20f,
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

/** 玻璃卡片内的文字颜色：emphasis 取 text-1，否则取 text-2 */
fun onGlassColor(dark: Boolean, emphasis: Boolean): Color {
    val palette = fluxPalette(dark)
    return if (emphasis) palette.text1 else palette.text2
}

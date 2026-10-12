package com.fluxframe.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxframe.app.R
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.ui.glass.FluidBackgroundHost

/* ------------------------------- 字体 -------------------------------
 * 三款字体都随包自带（SIL OFL）；中文不另带字体，缺字自动回退到系统字体。
 */

/** 正文与界面文字：DM Sans 400 / 500 / 600 */
val BodyFontFamily = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
)

/** 大标题与大数字：Space Grotesk 600 */
val DisplayFontFamily = FontFamily(Font(R.font.space_grotesk_semibold, FontWeight.SemiBold))

/** 数据与小字：DM Mono 500 */
val MonoFontFamily = FontFamily(Font(R.font.dm_mono_medium, FontWeight.Medium))

private fun body(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) = TextStyle(
    fontFamily = BodyFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private fun display(size: Float, line: Float, spacing: Float) = TextStyle(
    fontFamily = DisplayFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private val FluxTypography = Typography(
    displayLarge = display(52f, 58f, -1.2f),
    displayMedium = display(44f, 50f, -1.0f),
    displaySmall = display(36f, 42f, -0.8f),
    headlineLarge = display(30f, 36f, -0.5f),
    headlineMedium = display(26f, 32f, -0.4f),
    headlineSmall = display(22f, 28f, -0.2f),
    titleLarge = body(20f, 26f, FontWeight.SemiBold),
    titleMedium = body(16f, 22f, FontWeight.SemiBold),
    titleSmall = body(14f, 20f, FontWeight.SemiBold),
    bodyLarge = body(16f, 24f),
    bodyMedium = body(14f, 20f),
    bodySmall = body(12f, 17f, spacing = 0.1f),
    labelLarge = body(14f, 20f, FontWeight.Medium, 0.1f),
    labelMedium = body(12f, 16f, FontWeight.Medium, 0.1f),
    labelSmall = body(11f, 15f, FontWeight.Medium, 0.2f),
)

/** Material 排版之外的几种专用字样 */
object FluxType {
    /** 页面大标题（顶栏） */
    val pageTitle = display(22f, 28f, -0.2f)

    /** 统计卡、余额这类大数字；tnum 让数字等宽，跳动时不抖 */
    val numeralLarge = display(34f, 38f, -1.0f).copy(fontFeatureSettings = "tnum")
    val numeral = display(26f, 30f, -0.6f).copy(fontFeatureSettings = "tnum")
    val numeralSmall = display(18f, 22f, -0.3f).copy(fontFeatureSettings = "tnum")

    /** 时间、IP、计数等数据小字 */
    val mono = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val monoSmall = mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp)

    /** 区块上方的眉标 */
    val eyebrow = body(11f, 14f, FontWeight.SemiBold, 1.4f)
}

/** 圆角令牌：sm / md / lg / xl */
object FluxRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
}

private val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(FluxRadius.xs),
    small = RoundedCornerShape(FluxRadius.sm),
    medium = RoundedCornerShape(FluxRadius.md),
    large = RoundedCornerShape(FluxRadius.lg),
    extraLarge = RoundedCornerShape(FluxRadius.xl),
)

/** 全应用共用的一条缓动与三档时长 */
object FluxMotion {
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    const val FAST = 150
    const val BASE = 240
    const val SLOW = 420

    fun <T> tween(durationMillis: Int = BASE): FiniteAnimationSpec<T> = tween(durationMillis, easing = Ease)

    /** 指示器、开关这类「滑过去再轻轻回弹」的动作 */
    fun <T> spring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)
}

private val FluxDarkScheme = darkColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF23212F),
    onPrimaryContainer = Color(0xFFE8E4FF),
    secondary = BrandCyan,
    onSecondary = Color(0xFF04222A),
    secondaryContainer = Color(0xFF132A2E),
    onSecondaryContainer = Color(0xFFCFF4FB),
    tertiary = BrandPink,
    onTertiary = Color(0xFF3A0B22),
    background = DarkFluxPalette.bg0,
    onBackground = DarkFluxPalette.text1,
    surface = DarkFluxPalette.surface,
    onSurface = DarkFluxPalette.text1,
    surfaceVariant = DarkFluxPalette.surfaceRaised,
    onSurfaceVariant = DarkFluxPalette.text2,
    surfaceContainerLowest = DarkFluxPalette.bg0,
    surfaceContainerLow = DarkFluxPalette.bg1,
    surfaceContainer = DarkFluxPalette.surface,
    surfaceContainerHigh = DarkFluxPalette.surfaceRaised,
    surfaceContainerHighest = Color(0xFF212228),
    outline = Color(0xFF33343A),
    outlineVariant = Color(0xFF232428),
    error = Danger,
    onError = Color(0xFF3B0A14),
)

private val FluxLightScheme = lightColorScheme(
    primary = Color(0xFF6455E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E5FF),
    onPrimaryContainer = Color(0xFF211C4B),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3F9),
    onSecondaryContainer = Color(0xFF0B3A47),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    background = LightFluxPalette.bg0,
    onBackground = LightFluxPalette.text1,
    surface = LightFluxPalette.surface,
    onSurface = LightFluxPalette.text1,
    surfaceVariant = LightFluxPalette.surfaceRaised,
    onSurfaceVariant = LightFluxPalette.text2,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightFluxPalette.bg1,
    surfaceContainer = LightFluxPalette.surfaceRaised,
    surfaceContainerHigh = LightFluxPalette.bg0,
    surfaceContainerHighest = Color(0xFFECECE8),
    outline = Color(0xFFD5D4CC),
    outlineVariant = Color(0xFFEAEAE5),
    error = Color(0xFFE11D48),
    onError = Color.White,
)

/** 服务端同步来的三色覆盖 primary / secondary / tertiary，其余中性色沿用令牌 */
internal fun fluxColorScheme(dark: Boolean, accents: List<Color>): ColorScheme {
    val base = if (dark) FluxDarkScheme else FluxLightScheme
    val primary = accents.getOrElse(0) { base.primary }
    val secondary = accents.getOrElse(1) { base.secondary }
    val tertiary = accents.getOrElse(2) { base.tertiary }
    return base.copy(
        primary = primary,
        onPrimary = contentOn(primary),
        primaryContainer = primary.copy(alpha = if (dark) 0.26f else 0.16f).compositeOver(base.surface),
        secondary = secondary,
        onSecondary = contentOn(secondary),
        secondaryContainer = secondary.copy(alpha = if (dark) 0.20f else 0.14f).compositeOver(base.surface),
        tertiary = tertiary,
        onTertiary = contentOn(tertiary),
    )
}

/**
 * 应用主题。
 *
 * [style] 决定玻璃质感（默认 / 亚克力 / 液态玻璃 / 柔雾 / 霓虹），
 * [fluidColors] 由服务器设置同步而来，保证手机端与网页端同一套配色。
 */
@Composable
fun FluxFrameTheme(
    style: AppThemeStyle,
    darkTheme: Boolean,
    animationsEnabled: Boolean = true,
    noiseEnabled: Boolean = true,
    fluidColors: List<Color> = DefaultFluidColors,
    backgroundImageUri: String? = null,
    backgroundImageOpacity: Float = 0.72f,
    content: @Composable () -> Unit,
) {
    val tokens = remember(style, darkTheme, noiseEnabled) { glassTokensFor(style, darkTheme, noiseEnabled) }
    val colors = remember(fluidColors) { fluidColors.ifEmpty { DefaultFluidColors } }
    val colorScheme = remember(darkTheme, colors) { fluxColorScheme(darkTheme, colors) }

    CompositionLocalProvider(
        LocalGlassTokens provides tokens,
        LocalDarkTheme provides darkTheme,
        LocalAnimationsEnabled provides animationsEnabled,
        LocalFluidColors provides colors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FluxTypography,
            shapes = FluxShapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides fluxPalette(darkTheme).text1) {
                FluidBackgroundHost(
                    colors = colors,
                    darkTheme = darkTheme,
                    animationsEnabled = animationsEnabled,
                    backgroundImageUri = backgroundImageUri,
                    backgroundImageOpacity = backgroundImageOpacity,
                    content = content,
                )
            }
        }
    }
}

package com.fluxframe.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/* ============================================================================
 * 极光玻璃（Aurora Glass）色板。令牌与网页端 CSS 变量同名同值。
 *
 * 强调色 accent-1 / 2 / 3 来自服务端 `fluidColors`（默认 #4f46e5 / #06b6d4 / #f472b6）。
 * 下面的 Brand* 只是还没拿到服务端设置时的本机兜底，比服务端默认值亮一档：
 *   #6366F1 靛 / #22D3EE 青 / #F472B6 粉
 * ========================================================================== */

val BrandIndigo = Color(0xFF6366F1)
val BrandCyan = Color(0xFF22D3EE)
val BrandPink = Color(0xFFF472B6)
val BrandViolet = Color(0xFFA78BFA)

/** danger / success / warn：深浅色共用 */
val Danger = Color(0xFFFB7185)
val Success = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)

val BrandRed = Danger
val BrandGreen = Success
val BrandAmber = Warn

val DefaultFluidColors = listOf(BrandIndigo, BrandCyan, BrandPink)

/** 浅色主题里描边与文字用的靛色墨 */
val IndigoInk = Color(0xFF1E1B4B)

/** 一套主题下的全部中性令牌 */
@Immutable
class FluxPalette(
    val dark: Boolean,
    val bg0: Color,
    val bg1: Color,
    val glass1: Color,
    val glass2: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val highlight: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    /** 「默认」风格的实色卡片 */
    val surface: Color,
    val surfaceRaised: Color,
    /** 通透风格里叠在流体上的那层染色 */
    val glassTint: Color,
    /** 压在图片上的角标底色 */
    val scrim: Color,
) {
    val danger: Color get() = Danger
    val success: Color get() = Success
    val warn: Color get() = Warn
}

val DarkFluxPalette = FluxPalette(
    dark = true,
    bg0 = Color(0xFF05060D),
    bg1 = Color(0xFF0A0D1C),
    glass1 = Color.White.copy(alpha = 0.055f),
    glass2 = Color.White.copy(alpha = 0.085f),
    stroke = Color.White.copy(alpha = 0.09f),
    strokeStrong = Color.White.copy(alpha = 0.16f),
    highlight = Color.White.copy(alpha = 0.22f),
    text1 = Color(0xFFF2F4FB),
    text2 = Color(0xFFA6ACC8),
    text3 = Color(0xFF6E7596),
    surface = Color(0xFF0F1326),
    surfaceRaised = Color(0xFF171C33),
    glassTint = Color(0xFF0A0D1C),
    scrim = Color(0xFF05060D).copy(alpha = 0.46f),
)

val LightFluxPalette = FluxPalette(
    dark = false,
    bg0 = Color(0xFFEEF1FB),
    bg1 = Color(0xFFF7F8FE),
    glass1 = Color.White.copy(alpha = 0.62f),
    glass2 = Color.White.copy(alpha = 0.80f),
    stroke = IndigoInk.copy(alpha = 0.08f),
    strokeStrong = IndigoInk.copy(alpha = 0.14f),
    highlight = Color.White.copy(alpha = 0.90f),
    text1 = Color(0xFF111427),
    text2 = Color(0xFF4B5276),
    text3 = Color(0xFF7A81A3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF1F3FC),
    glassTint = Color.White,
    scrim = Color(0xFF05060D).copy(alpha = 0.42f),
)

fun fluxPalette(dark: Boolean): FluxPalette = if (dark) DarkFluxPalette else LightFluxPalette

/** 当前主题的中性令牌；查看器、星系这类恒为深色的舞台会把它切到深色那一套 */
@Composable
@ReadOnlyComposable
fun fluxPalette(): FluxPalette = fluxPalette(LocalDarkTheme.current)

/* 旧名字保留给仍按「背景色」取值的地方 */
val DarkBackground = DarkFluxPalette.bg0
val LightBackground = LightFluxPalette.bg0

/**
 * 把强调色调到能当文字用。
 *
 * 强调色由服务端或配色预设决定：深靛色压在深色底上、亮青色压在浅色底上都读不清，
 * 所以只在亮度不够拉开时才往白 / 墨色靠一点，够用的颜色原样返回。
 */
fun readableAccent(color: Color, dark: Boolean): Color {
    val lum = color.luminance()
    return when {
        dark && lum < 0.30f -> lerp(color, Color.White, 0.20f + (0.30f - lum) * 1.2f)
        !dark && lum > 0.22f -> lerp(color, IndigoInk, (0.18f + (lum - 0.22f) * 0.75f).coerceAtMost(0.62f))
        else -> color
    }.copy(alpha = color.alpha)
}

/** 压在这个颜色上的文字该用白还是墨色 */
fun contentOn(color: Color): Color = if (color.luminance() > 0.56f) Color(0xFF111427) else Color.White

/* ------------------------- 语义色（日志 tone / 状态） ------------------------- */

fun toneColor(tone: String, dark: Boolean): Color = when (tone) {
    "red" -> Danger
    "violet" -> BrandViolet
    "green" -> Success
    "orange" -> Warn
    else -> if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
}

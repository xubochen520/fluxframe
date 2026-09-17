package com.fluxframe.app.ui.theme

import androidx.compose.ui.graphics.Color

/* ============================================================================
 * brand 色板：与网页端 defaultSettings.fluidColors 一致
 *   #4f46e5 靛蓝 / #06b6d4 青 / #f472b6 粉
 * ========================================================================== */

val BrandIndigo = Color(0xFF6366F1)
val BrandCyan = Color(0xFF22D3EE)
val BrandPink = Color(0xFFF472B6)
val BrandViolet = Color(0xFFA78BFA)
val BrandGreen = Color(0xFF22C55E)
val BrandRed = Color(0xFFEF4444)
val BrandAmber = Color(0xFFF59E0B)

val DefaultFluidColors = listOf(BrandIndigo, BrandCyan, BrandPink)

/* ------------------------------- 深色（默认） ------------------------------- */

val DarkBackground = Color(0xFF07070B)
val DarkBackgroundElevated = Color(0xFF0B0B12)
val DarkSurface = Color(0xFF12121B)
val DarkSurfaceVariant = Color(0xFF1C1C28)
val DarkOutline = Color(0xFF2A2A3A)
val DarkOnSurface = Color(0xFFE8E8F0)
val DarkOnSurfaceVariant = Color(0xFF9E9EB3)

/* --------------------------------- 浅色 --------------------------------- */

val LightBackground = Color(0xFFF4F5FA)
val LightBackgroundElevated = Color(0xFFEDEEF6)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F2F8)
val LightOutline = Color(0xFFD8DAE6)
val LightOnSurface = Color(0xFF14141C)
val LightOnSurfaceVariant = Color(0xFF5A5A70)

/* ------------------------- 语义色（日志 tone / 状态） ------------------------- */

fun toneColor(tone: String, dark: Boolean): Color = when (tone) {
    "red" -> BrandRed
    "violet" -> BrandViolet
    "green" -> BrandGreen
    "orange" -> BrandAmber
    else -> if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
}

package com.fluxframe.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.ui.glass.FluidBackgroundHost

private val FluxDarkScheme = darkColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2A55),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = BrandCyan,
    onSecondary = Color(0xFF00232B),
    tertiary = BrandPink,
    onTertiary = Color(0xFF3A0B22),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF23232F),
    error = BrandRed,
    onError = Color.White,
)

private val FluxLightScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E1FF),
    onPrimaryContainer = Color(0xFF1B1B4B),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE4E6F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val FluxTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        bodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp,
            fontWeight = FontWeight.Normal,
        ),
    )
}

private val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * 应用主题。
 *
 * [style] 决定玻璃质感（默认 / 亚克力 / 液态玻璃），
 * [fluidColors] 由服务器设置同步而来，保证手机端与网页端同一套配色。
 */
@Composable
fun FluxFrameTheme(
    style: AppThemeStyle,
    darkTheme: Boolean,
    animationsEnabled: Boolean = true,
    noiseEnabled: Boolean = true,
    fluidColors: List<Color> = DefaultFluidColors,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) FluxDarkScheme else FluxLightScheme
    val tokens = remember(style, darkTheme, noiseEnabled) { glassTokensFor(style, darkTheme, noiseEnabled) }
    val colors = remember(fluidColors) { fluidColors.ifEmpty { DefaultFluidColors } }

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
            FluidBackgroundHost(
                colors = colors,
                darkTheme = darkTheme,
                animationsEnabled = animationsEnabled,
                content = content,
            )
        }
    }
}

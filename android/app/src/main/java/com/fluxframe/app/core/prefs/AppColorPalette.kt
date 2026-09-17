package com.fluxframe.app.core.prefs

/** 手机端背景与强调色预设。SERVER 会继续跟随服务端的 fluidColors。 */
enum class AppColorPalette(
    val label: String,
    val description: String,
    val hexColors: List<String>,
) {
    SERVER("跟随服务端", "与网页端使用同一套流体颜色", emptyList()),
    AURORA("极光", "靛蓝、青色与玫红", listOf("#6366F1", "#22D3EE", "#EC4899")),
    OCEAN("深海", "海蓝、湖绿与天青", listOf("#2563EB", "#14B8A6", "#38BDF8")),
    SUNSET("日落", "橙金、珊瑚与紫红", listOf("#F97316", "#F43F5E", "#A855F7")),
    FOREST("森林", "翠绿、青柠与土金", listOf("#16A34A", "#84CC16", "#D97706")),
    MONO("黑白", "克制的银灰层次", listOf("#A1A1AA", "#D4D4D8", "#71717A")),
    ;

    companion object {
        fun fromKey(key: String?): AppColorPalette =
            entries.firstOrNull { it.name == key } ?: SERVER
    }
}

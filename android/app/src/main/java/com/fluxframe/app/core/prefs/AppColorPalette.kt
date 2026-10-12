package com.fluxframe.app.core.prefs

/** 手机端背景与强调色预设。SERVER 会继续跟随服务端的 fluidColors。 */
enum class AppColorPalette(
    val label: String,
    val description: String,
    val hexColors: List<String>,
) {
    SERVER("跟随服务端", "与网页端使用同一套流体颜色", emptyList()),
    AURORA("极光", "紫罗兰、青色与玫红", listOf("#7C6CFF", "#22D3EE", "#F472B6")),
    EMBER("炽橙", "炽橙、琥珀与暖橘", listOf("#F0603C", "#F5A524", "#FF8A5C")),
    MONO("银盐", "克制的无彩度层次", listOf("#D4D4D0", "#F0F0EC", "#8A8A86")),
    NEBULA("星云", "紫红、洋红与电青", listOf("#9B5CF6", "#F0509B", "#35D6E8")),
    FOREST("苔绿", "苔绿、青柠与湖碧", listOf("#3ECF6E", "#B3E054", "#2DD4BF")),
    ROSE("玫红", "玫红、珊瑚与浅粉", listOf("#F04E6A", "#FF8AA0", "#FDA4AF")),
    OCEAN("深海", "海蓝、水青与天蓝", listOf("#2E9BE6", "#5CE1E6", "#60A5FA")),
    SOLAR("日光", "琥珀、柠檬与橙阳", listOf("#F5A623", "#FDE047", "#FB923C")),
    SLATE("石板", "晴空蓝、雾蓝与靛青", listOf("#64A2FF", "#93C5FD", "#818CF8")),
    MATCHA("抹茶", "抹茶黄绿、嫩芽与翠色", listOf("#A8C93A", "#D6E06A", "#6EE7A0")),
    SAKURA("樱", "樱粉、藤紫与浅樱", listOf("#F078B8", "#C084FC", "#FDA4AF")),
    COPPER("铜版", "铜金、麦秆与赭石", listOf("#D98E4A", "#E8C07A", "#C2703D")),
    SUNSET("日落", "橙金、珊瑚与紫红（旧版保留）", listOf("#F97316", "#F43F5E", "#A855F7")),
    ;

    companion object {
        fun fromKey(key: String?): AppColorPalette =
            entries.firstOrNull { it.name == key } ?: SERVER
    }
}

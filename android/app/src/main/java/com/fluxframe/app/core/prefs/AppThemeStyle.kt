package com.fluxframe.app.core.prefs

/** 三套界面风格 —— 用户可在「外观」里实时切换 */
enum class AppThemeStyle(val label: String, val description: String) {
    /** 默认：扁平、实色卡片，追求清晰与省电 */
    DEFAULT("默认", "扁平实色，清晰利落，最低功耗"),

    /** 亚克力：半透明 + 磨砂噪点 + 细描边，类似 Windows Acrylic */
    ACRYLIC("亚克力", "半透磨砂 + 噪点纹理，层次柔和"),

    /** 液态玻璃：强背景模糊 + 高光扫过 + 色散边缘，类似 iOS 26 Liquid Glass */
    LIQUID_GLASS("液态玻璃", "强模糊折射 + 高光流动，通透有质感"),
    ;

    companion object {
        fun fromKey(key: String?): AppThemeStyle =
            entries.firstOrNull { it.name == key } ?: LIQUID_GLASS
    }
}

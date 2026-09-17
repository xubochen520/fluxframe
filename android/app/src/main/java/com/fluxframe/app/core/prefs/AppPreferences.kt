package com.fluxframe.app.core.prefs

import android.content.Context
import com.fluxframe.app.core.net.ServerEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 本地界面偏好（与服务器上的系统设置相互独立：这里是「这台手机怎么看」） */
data class UiPrefs(
    val themeStyle: AppThemeStyle = AppThemeStyle.LIQUID_GLASS,
    val darkMode: Boolean = true,
    val animationsEnabled: Boolean = true,
    /** 图片库网格列数：2 / 3 / 4 / 5 */
    val gridColumns: Int = 3,
    /** 是否启用流体云 / 实况通知胶囊 */
    val fluidCloudEnabled: Boolean = true,
    /** 亚克力与液态玻璃是否叠加噪点（低端机可关掉省一点） */
    val noiseEnabled: Boolean = true,
    /** 视频默认静音自动播放预览 */
    val muteVideoPreview: Boolean = true,
    /** 沉浸式：自动隐藏手势小白条（导航栏），上滑可临时唤出 */
    val immersiveMode: Boolean = true,
    /** 视频缩略图用真实帧（0.5s 处）代替渐变色块；关掉可省流量与电 */
    val videoPosterEnabled: Boolean = true,
    /** 取帧位置（秒）。0.5 秒避开片头黑场，可按需改成任意时刻 */
    val videoPosterSeconds: Float = 0.5f,
    /** 顶栏随滚动自动收起（连同状态栏一起让位给内容） */
    val autoHideHeader: Boolean = true,
)

/**
 * 轻量偏好存储。
 *
 * 用 SharedPreferences 而不是 DataStore：会话 Cookie、服务器地址这些值需要在
 * OkHttp 的拦截器线程里**同步读取**，SharedPreferences 的内存缓存正好满足，
 * 也避免了在拦截器里 runBlocking。
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _server = MutableStateFlow(ServerEndpoint.parse(prefs.getString(KEY_SERVER, null)))
    val server: StateFlow<ServerEndpoint?> = _server.asStateFlow()

    private val _ui = MutableStateFlow(readUi())
    val ui: StateFlow<UiPrefs> = _ui.asStateFlow()

    /** 最近一次登录成功的用户名，用于登录页预填 */
    var lastUsername: String
        get() = prefs.getString(KEY_LAST_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_USER, value).apply()

    val hasServer: Boolean get() = _server.value != null

    fun saveServer(endpoint: ServerEndpoint?) {
        prefs.edit().putString(KEY_SERVER, endpoint?.authority).apply()
        _server.value = endpoint
    }

    fun updateUi(transform: (UiPrefs) -> UiPrefs) {
        val next = transform(_ui.value)
        prefs.edit()
            .putString(KEY_THEME_STYLE, next.themeStyle.name)
            .putBoolean(KEY_DARK, next.darkMode)
            .putBoolean(KEY_ANIM, next.animationsEnabled)
            .putInt(KEY_GRID, next.gridColumns)
            .putBoolean(KEY_FLUID_CLOUD, next.fluidCloudEnabled)
            .putBoolean(KEY_NOISE, next.noiseEnabled)
            .putBoolean(KEY_MUTE_PREVIEW, next.muteVideoPreview)
            .putBoolean(KEY_IMMERSIVE, next.immersiveMode)
            .putBoolean(KEY_VIDEO_POSTER, next.videoPosterEnabled)
            .putFloat(KEY_VIDEO_POSTER_SECONDS, next.videoPosterSeconds)
            .putBoolean(KEY_AUTO_HIDE_HEADER, next.autoHideHeader)
            .apply()
        _ui.value = next
    }

    /** 退出登录后保留界面偏好与服务器地址，只清账号相关 */
    fun clearAccount() {
        prefs.edit().remove(KEY_LAST_USER).apply()
    }

    private fun readUi() = UiPrefs(
        themeStyle = AppThemeStyle.fromKey(prefs.getString(KEY_THEME_STYLE, null)),
        darkMode = prefs.getBoolean(KEY_DARK, true),
        animationsEnabled = prefs.getBoolean(KEY_ANIM, true),
        gridColumns = prefs.getInt(KEY_GRID, 3).coerceIn(2, 5),
        fluidCloudEnabled = prefs.getBoolean(KEY_FLUID_CLOUD, true),
        noiseEnabled = prefs.getBoolean(KEY_NOISE, true),
        muteVideoPreview = prefs.getBoolean(KEY_MUTE_PREVIEW, true),
        immersiveMode = prefs.getBoolean(KEY_IMMERSIVE, true),
        videoPosterEnabled = prefs.getBoolean(KEY_VIDEO_POSTER, true),
        videoPosterSeconds = prefs.getFloat(KEY_VIDEO_POSTER_SECONDS, 0.5f).coerceIn(MIN_POSTER_SECONDS, MAX_POSTER_SECONDS),
        autoHideHeader = prefs.getBoolean(KEY_AUTO_HIDE_HEADER, true),
    )

    companion object {
        const val PREFS = "fluxframe_prefs"
        const val KEY_SERVER = "server_authority"
        const val KEY_LAST_USER = "last_username"
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_DARK = "dark_mode"
        const val KEY_ANIM = "animations"
        const val KEY_GRID = "grid_columns"
        const val KEY_FLUID_CLOUD = "fluid_cloud"
        const val KEY_NOISE = "noise"
        const val KEY_MUTE_PREVIEW = "mute_video_preview"
        const val KEY_IMMERSIVE = "immersive_mode"
        const val KEY_VIDEO_POSTER = "video_poster"
        const val KEY_VIDEO_POSTER_SECONDS = "video_poster_seconds"
        const val KEY_AUTO_HIDE_HEADER = "auto_hide_header"

        /** 取帧位置的可选范围：0 秒（首帧）到 10 分钟 */
        const val MIN_POSTER_SECONDS = 0f
        const val MAX_POSTER_SECONDS = 600f

        /**
         * 把用户输入的秒数解析成合法值；非法输入返回 null 以示"不要保存"。
         * 抽出成函数是为了能直接单测边界（负数、超上限、空串、小数位过多）。
         */
        fun parsePosterSeconds(input: String): Float? {
            val value = input.trim().toFloatOrNull() ?: return null
            if (value.isNaN() || value < MIN_POSTER_SECONDS || value > MAX_POSTER_SECONDS) return null
            // 保留两位小数，避免 0.3333333 这种难看的缓存键
            return kotlin.math.round(value * 100f) / 100f
        }
    }
}

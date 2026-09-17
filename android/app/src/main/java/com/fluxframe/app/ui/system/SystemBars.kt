package com.fluxframe.app.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 系统栏（状态栏 / 手势小白条）的统一控制。
 *
 * 三个诉求：
 *  1. **状态栏图标要跟着明暗走**。主题 XML 里写死了 `windowLightStatusBar=false`，
 *     浅色主题下白字白底 —— 时间就是"看不见"。这里按当前主题实时切换。
 *  2. **小白条自动隐藏**。手势导航条常驻会一直占着最下面一条，看图/看视频很碍眼。
 *     用 `hide(navigationBars)` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：默认不显示，
 *     从底部边缘上滑才临时浮出，随后自动收回去。
 *  3. **顶栏收起时连状态栏一起收起**（[hideStatusBar]），把时间那一行也让给内容。
 *
 * 系统在 `onResume`、窗口获得焦点、以及从后台返回时会重置系统栏可见性，
 * 因此这里除了参数变化外，还在 ON_RESUME 上重新下发一次 —— 否则会出现
 * "切出去再回来，小白条又出来了"这种典型问题。
 */
@Composable
fun SystemBarsEffect(
    darkTheme: Boolean,
    hideStatusBar: Boolean,
    hideGestureBar: Boolean,
) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(window, view, darkTheme, hideStatusBar, hideGestureBar, lifecycleOwner) {
        if (window == null) return@DisposableEffect onDispose { }

        fun apply() = applySystemBars(window, view, darkTheme, hideStatusBar, hideGestureBar)

        apply()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = apply()
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(attachListener)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.removeOnAttachStateChangeListener(attachListener)
        }
    }
}

private fun applySystemBars(
    window: Window,
    view: View,
    darkTheme: Boolean,
    hideStatusBar: Boolean,
    hideGestureBar: Boolean,
) {
    val controller = WindowCompat.getInsetsController(window, view)
    // 浅色主题 → 系统栏用深色图标（true = "light appearance" 即深色前景）
    controller.isAppearanceLightStatusBars = !darkTheme
    controller.isAppearanceLightNavigationBars = !darkTheme
    // 隐藏后仍允许从边缘临时唤出，且不要因为唤出而永久取消隐藏
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    if (hideStatusBar) {
        controller.hide(WindowInsetsCompat.Type.statusBars())
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
    if (hideGestureBar) {
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    } else {
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

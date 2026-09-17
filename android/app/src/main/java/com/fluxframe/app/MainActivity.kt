package com.fluxframe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.data.store.SessionPhase
import com.fluxframe.app.ui.AppShell
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.parseColor
import com.fluxframe.app.ui.screens.LoginScreen
import com.fluxframe.app.ui.screens.ServerSetupScreen
import com.fluxframe.app.ui.system.SystemBarsEffect
import com.fluxframe.app.ui.theme.DefaultFluidColors
import com.fluxframe.app.ui.theme.FluxFrameTheme
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.LocalDarkTheme

/**
 * 唯一的 Activity。
 *
 * 根界面按会话阶段三选一：配置服务器 → 登录 → 主界面。
 * 主题参数来自本机偏好（外观）与服务器设置（流体配色），
 * 因此切换主题、切换明暗、甚至服务器换配色，都是即时生效的。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FluxFrameApp).container

        setContent {
            val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
            val session by container.sessionStore.state.collectAsStateWithLifecycle()
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()

            // 流体背景配色优先用服务器设置（与网页端同色），拿不到时用内置品牌色
            val fluidColors = remember(settings?.fluidColors) {
                settings?.fluidColors
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { parseColor(it) }
                    ?: DefaultFluidColors
            }

            LaunchedEffect(Unit) {
                container.fluidCloud.enabled = uiPrefs.fluidCloudEnabled
                container.sessionStore.bootstrap()
            }

            // 顶栏收起时连状态栏一起让位；查看器打开时彻底全屏（含手势小白条）
            var headerHidden by remember { mutableStateOf(false) }
            var contentFullscreen by remember { mutableStateOf(false) }

            // 回调用 remember 包住：否则每次重组都会生成新的 lambda 实例，
            // 会让 AppShell 变得"不可跳过"，顶栏收放时白白重组整棵界面。
            val onHeaderHiddenChange: (Boolean) -> Unit = remember { { headerHidden = it } }
            val onFullscreenChange: (Boolean) -> Unit = remember { { contentFullscreen = it } }

            FluxFrameTheme(
                style = uiPrefs.themeStyle,
                darkTheme = uiPrefs.darkMode,
                animationsEnabled = uiPrefs.animationsEnabled,
                noiseEnabled = uiPrefs.noiseEnabled,
                fluidColors = fluidColors,
            ) {
                SystemBarsEffect(
                    darkTheme = uiPrefs.darkMode,
                    hideStatusBar = contentFullscreen || headerHidden,
                    hideGestureBar = contentFullscreen || uiPrefs.immersiveMode,
                )
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (session.phase) {
                            SessionPhase.BOOTING -> BootScreen()

                            SessionPhase.NEED_SERVER -> ServerSetupScreen(
                                onConfigured = { container.sessionStore.onServerConfigured() },
                            )

                            SessionPhase.NEED_LOGIN -> LoginScreen(
                                onSwitchServer = { container.sessionStore.requestServerChange() },
                            )

                            SessionPhase.READY -> AppShell(
                                onHeaderHiddenChange = onHeaderHiddenChange,
                                onFullscreenChange = onFullscreenChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BootScreen() {
    val dark = LocalDarkTheme.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "正在连接 fluxframe…",
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = false),
            )
        }
    }
}

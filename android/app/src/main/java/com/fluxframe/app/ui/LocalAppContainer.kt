package com.fluxframe.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.fluxframe.app.core.di.AppContainer

/**
 * 依赖容器在 Compose 树中的传递点。
 * 不引入 Hilt/Koin：本项目只有一个容器，用 CompositionLocal 最直观。
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer 未提供：请确认已在 MainActivity 中 CompositionLocalProvider 注入")
}

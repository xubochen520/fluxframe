package com.fluxframe.app.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.media.VideoPosterLoader
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer

/**
 * 取视频封面所需的最小上下文。
 *
 * 抽成一个小对象而不是让组件直接依赖仓库层，是为了让 [MediaCard] 在预览/测试里
 * 也能不传封面（`poster = null`）就正常渲染。
 */
@Immutable
data class VideoPosterRequest(val item: ImageItem)

/**
 * 异步取一帧视频封面。
 *
 * 初值直接取内存缓存（命中时首帧就有图，不会先闪一下渐变），
 * 未命中则后台解码，完成后自动重绘。缓存键不变就不会重复请求。
 */
@Composable
fun rememberVideoPoster(
    request: VideoPosterRequest,
    fallbackWidthPx: Int,
): Bitmap? {
    val container = LocalAppContainer.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val gridColumns = uiPrefs.gridColumns

    val key = remember(request.item.id, uiPrefs.videoPosterSeconds) {
        container.mediaRepository.videoPosterCacheKey(request.item)
    }

    // 目标解码宽度取"卡片在一行里大概占多少像素"，避免把 4K 帧整张解出来
    val targetWidth = remember(configuration.screenWidthDp, gridColumns, fallbackWidthPx) {
        val screenPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val perCard = (screenPx / gridColumns.coerceAtLeast(2)) - with(density) { 18.dp.toPx() }
        perCard.toInt().coerceIn(160, 720).takeIf { it > 0 } ?: fallbackWidthPx
    }

    val state = produceState(initialValue = VideoPosterLoader.peek(key), key) {
        value = VideoPosterLoader.peek(key)
        if (value == null) {
            value = container.mediaRepository.loadVideoPoster(request.item, targetWidth)
        }
    }
    return state.value
}

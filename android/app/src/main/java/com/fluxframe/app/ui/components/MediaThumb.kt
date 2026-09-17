package com.fluxframe.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer

/**
 * 用真实媒体的缩略图当图标。
 *
 * 用在两处需要"看得出是什么"的小方块上：
 *  - 底部导航的「图片库 / 视频库」——半透明地显示库里的一张图 / 一段视频的封面；
 *  - 标签页的人物卡片——用该人物的第一张图片，替换原来那个通用的人脸矢量图。
 *
 * [item] 为空时退回矢量占位图标（库空着、或该标签还没有可见媒体）。
 * 视频走 `rememberVideoPoster`，与网格里的封面共用同一套取帧与缓存。
 */
@Composable
fun MediaThumb(
    item: ImageItem?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    alpha: Float = 1f,
    shape: Shape = RoundedCornerShape(7.dp),
    placeholderIcon: ImageVector = Icons.Filled.Image,
    placeholderTint: Color = Color(0xFF22C55E),
    placeholderBackground: Color = placeholderTint.copy(alpha = 0.18f),
) {
    if (item == null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(placeholderBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = placeholderTint,
                modifier = Modifier.size(size * 0.55f),
            )
        }
        return
    }

    val container = LocalAppContainer.current
    val posterBitmap = if (item.isVideo) {
        rememberVideoPoster(request = VideoPosterRequest(item), fallbackWidthPx = 96)
    } else {
        null
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        when {
            posterBitmap != null -> Image(
                bitmap = posterBitmap.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            item.isVideo -> AsyncImage(
                // 取帧还没回来时，先不显示任何东西（渐变占位在这么小的方块里没有意义）
                model = null,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )

            else -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(container.mediaRepository.gridUrl(item))
                    .crossfade(false)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

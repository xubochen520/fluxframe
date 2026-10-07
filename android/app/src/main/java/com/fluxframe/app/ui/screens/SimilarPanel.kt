package com.fluxframe.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size as CoilSize
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.components.HairLine
import com.fluxframe.app.ui.glass.GlassSurface

/**
 * 相似图面板的加载状态。
 *
 * 服务端语义（见 server/src/index.ts 的 `/api/images/:id/similar`）：
 *  - 已建指纹 → 直接返回列表（可能为空，表示库里没有够像的图）；
 *  - 没建指纹的静态图 → 服务端**当场算完再返回**（约 0.7~3 秒），所以「慢一次」是正常的；
 *  - 视频 → 永远没有指纹，直接告诉用户不支持，不要让他干等。
 */
sealed interface SimilarUiState {
    data object Idle : SimilarUiState
    data object Loading : SimilarUiState
    data class Ready(val items: List<ImageItem>, val threshold: Double) : SimilarUiState
    data class Message(val title: String, val detail: String) : SimilarUiState
}

/**
 * 触发上滑所需的纵向位移（像素）。
 * 60 与网页端的 SWIPE_UP_TRIGGER 一致：太灵敏会误触，太钝会让人觉得"滑了没反应"。
 *
 * 注意：判定不在本文件里做，而是搭在 [ViewerOverlay] 已有的 detectTransformGestures 上 ——
 * 单独挂 pointerInput 会被子节点（AsyncImage）的手势检测器先消耗掉，实测完全没反应。
 */
internal const val SWIPE_UP_PX = 60f

/**
 * 下滑多少像素算「收起相似图」。比上滑的阈值小一点：
 * 面板已经打开时，用户的意图很明确，不需要滑那么远；而且下滑误触的代价只是把面板收起来，
 * 比上滑误触（突然弹出一整屏）轻得多。
 */
internal const val SWIPE_DOWN_PX = 48f

/**
 * 相似图面板（底部升起）。
 *
 * 复用 [GlassSurface] 保持与全应用一致的毛玻璃外观；网格两列 ——
 * 手机上三列会把图片名截断成一两个词，实测两列可读性好得多。
 */
@Composable
fun SimilarPanel(
    state: SimilarUiState,
    onClose: () -> Unit,
    onPick: (ImageItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val visible = state !is SimilarUiState.Idle

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            backdrop = true,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "相似图片",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when (state) {
                                is SimilarUiState.Ready ->
                                    if (state.items.isEmpty()) "没有足够相似的图片"
                                    else "${state.items.size} 张 · 相似度 ≥ ${(state.threshold * 100).toInt()}%"
                                is SimilarUiState.Loading -> "正在比对视觉指纹…"
                                is SimilarUiState.Message -> state.title
                                SimilarUiState.Idle -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "收起相似图",
                        onClick = onClose,
                    )
                }

                HairLine(alpha = 0.10f)

                // ---- 内容 ----
                Box(modifier = Modifier.fillMaxWidth()) {
                    when (state) {
                        is SimilarUiState.Loading -> SimilarLoading()
                        is SimilarUiState.Message -> SimilarMessage(state.title, state.detail)
                        is SimilarUiState.Ready ->
                            if (state.items.isEmpty()) {
                                SimilarMessage(
                                    title = "暂时没有相似的图片",
                                    detail = "库里还没有和这张视觉上接近的图。\n相似度阈值 ${(state.threshold * 100).toInt()}%，可在设置里调整。",
                                )
                            } else {
                                SimilarGrid(
                                    items = state.items,
                                    thumbUrl = { container.embedRepository.thumbUrl(it) },
                                    onPick = onPick,
                                )
                            }
                        SimilarUiState.Idle -> Spacer(Modifier.height(0.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarGrid(
    items: List<ImageItem>,
    thumbUrl: (ImageItem) -> String,
    onPick: (ImageItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            // 限高：面板不该把整张大图顶掉，留出上面的空间继续看图
            .heightIn(max = 360.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { item ->
            SimilarCard(item = item, thumbUrl = thumbUrl(item), onClick = { onPick(item) })
        }
    }
}

@Composable
private fun SimilarCard(item: ImageItem, thumbUrl: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbUrl)
                    .size(CoilSize(512, 512))
                    .precision(Precision.INEXACT)
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            // 相似度角标：直接用分数，比进度条更直观
            Text(
                text = "${item.similarPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.tags.isNotEmpty()) {
            Text(
                text = item.tags.take(2).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SimilarLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在比对视觉指纹…",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SimilarMessage(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.94f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.58f),
            textAlign = TextAlign.Center,
        )
    }
}

/** 图片放大后拖动是在看细节，这时不该把上滑解释成「看相似图」 */
internal fun shouldHandleSwipe(scale: Float): Boolean = scale <= 1.02f

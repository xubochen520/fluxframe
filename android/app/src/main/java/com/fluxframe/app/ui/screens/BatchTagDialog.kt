package com.fluxframe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.launch

/**
 * 批量把多张图片加进同一个标签（对应网页端的「批量添加图片」弹窗）。
 *
 * 走服务端 `POST /api/tags/:id/images`：一次最多 500 个 id，
 * 服务端会跳过已存在的关联，只返回真正新增的数量。
 */
@Composable
fun BatchTagDialog(
    tag: TagItem,
    onDismiss: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val images by container.mediaStore.liveImages.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val picked = remember(tag.id) { mutableStateListOf<String>() }
    var submitting by remember { mutableStateOf(false) }

    // 已经有这个标签的图片不必再展示（服务端也会去重，但列表干净一些）
    val candidates = remember(images, tag.name, query) {
        val text = query.trim().lowercase()
        images.filter { image ->
            tag.name !in image.tags &&
                (text.isEmpty() ||
                    image.name.lowercase().contains(text) ||
                    image.tags.any { it.lowercase().contains(text) })
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "批量添加图片",
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                Text(
                    text = "选择要加进「${tag.name}」的图片，可多选。已带此标签的图片不会列出。",
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )

                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索图片名称或标签",
                    leadingIcon = Icons.Filled.Search,
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "全选当前结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val ids = candidates.map { it.id }
                                picked.clear()
                                picked.addAll(ids)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = onGlassColor(dark, emphasis = false),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { picked.clear() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "已选 ${picked.size} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                }

                if (candidates.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (images.isEmpty()) "图片库是空的" else "没有可添加的图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = { it.id }) { image ->
                            BatchPickTile(
                                image = image,
                                picked = image.id in picked,
                                thumbUrl = container.mediaRepository.gridUrl(image),
                                onToggle = {
                                    if (image.id in picked) picked.remove(image.id) else picked.add(image.id)
                                },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1.4f)) {
                        PrimaryActionButton(
                            text = "添加所选 ${picked.size} 张",
                            loading = submitting,
                            enabled = picked.isNotEmpty(),
                            onClick = {
                                submitting = true
                                scope.launch {
                                    container.tagRepository.addToImages(tag.id, picked.toList())
                                        .onSuccess { added ->
                                            container.mediaStore.refreshAll()
                                            onToast(
                                                if (added > 0) {
                                                    "已为「${tag.name}」添加 $added 张图片"
                                                } else {
                                                    "这些图片都已带该标签，未新增"
                                                },
                                            )
                                            onDismiss()
                                        }
                                        .onFailure { onToast(it.message) }
                                    submitting = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchPickTile(
    image: ImageItem,
    picked: Boolean,
    thumbUrl: String,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(
                width = if (picked) 2.dp else 0.6.dp,
                color = if (picked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onToggle() },
    ) {
        if (image.isVideo) {
            // 视频不拉原文件当缩略图（服务端对视频没有变体），用色块 + 播放标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        Text(
            text = image.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 5.dp, vertical = 4.dp),
        )

        // 勾选态
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (picked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.42f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

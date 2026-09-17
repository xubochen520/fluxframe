package com.fluxframe.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.core.util.formatBytes
import com.fluxframe.app.data.model.UploadAnalyzeItem
import com.fluxframe.app.data.model.UploadCompleteItem
import com.fluxframe.app.data.store.UploadPhase
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor

/** 上传确认行：名称可改、标签可增删、是否入库可勾选 */
private class ReviewRow(val source: UploadAnalyzeItem) {
    var include by mutableStateOf(!source.duplicate && source.tempIdOrNull != null)
    var name by mutableStateOf(source.name)
    val tags = mutableStateListOf<String>().apply { addAll(source.tags) }
    val duplicate: Boolean get() = source.duplicate
}

/**
 * 上传确认覆盖层。
 *
 * 对应服务端三步式上传的第 2 步：`analyze` 已经把原文件传到服务端 temp 目录，
 * 并返回（可能由本地 AI 模型给出的）推荐标签与查重结果；用户在这里改名、
 * 调整标签、决定哪些真正入库，再调 `complete` 落库并生成缩略图。
 */
@Composable
fun UploadReviewOverlay(
    onDismiss: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val task by container.taskStore.upload.collectAsStateWithLifecycle()
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current

    val analyze = task?.analyze ?: return
    if (task?.phase != UploadPhase.REVIEW) return

    val rows = remember(analyze) { analyze.items.map { ReviewRow(it) } }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    val selected = rows.filter { it.include && it.name.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            backdrop = true,
            contentPadding = PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "确认上传（${selected.size}/${rows.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        color = onGlassColor(dark, emphasis = true),
                    )
                    Text(
                        text = if (analyze.aiEnabled) {
                            "AI 已用 ${analyze.aiModel} 预打标签，可自由修改"
                        } else {
                            "AI 打标未启用，请手动确认名称与标签"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                }
                com.fluxframe.app.ui.components.GlassIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "取消本次上传",
                    onClick = {
                        container.taskStore.discardUpload()
                        onToast("已放弃本次上传")
                    },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            items(rows.size) { index ->
                val row = rows[index]
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (row.include) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            onGlassColor(dark, emphasis = false).copy(alpha = 0.25f)
                                        },
                                    )
                                    .clickable(enabled = !row.duplicate) { row.include = !row.include },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (row.include) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Text(
                                text = row.source.fileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = onGlassColor(dark, emphasis = true),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            MiniBadge(
                                text = if (row.source.isVideo) "视频" else "图片",
                                color = if (row.source.isVideo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                text = formatBytes(row.source.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }

                        if (row.duplicate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "内容与「${row.source.duplicateName ?: "库中已有文件"}」完全相同，将自动跳过",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        GlassTextField(
                            value = row.name,
                            onValueChange = { row.name = it },
                            placeholder = "入库名称（不带扩展名）",
                            enabled = row.include,
                        )

                        if (row.source.aiError != null) {
                            Text(
                                text = "AI 分析失败：${row.source.aiError}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        // 标签区
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.tags.forEach { name ->
                                val tag = tags.firstOrNull { it.name == name }
                                TagChip(
                                    name = name,
                                    colorHex = tag?.color ?: "#A78BFA",
                                    onClick = { row.tags.remove(name) },
                                )
                            }
                            TagChip(
                                name = "+ 添加",
                                colorHex = "#6366F1",
                                showDot = false,
                                onClick = { editingIndex = index },
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                SecondaryActionButton(
                    text = "全部取消",
                    onClick = {
                        container.taskStore.discardUpload()
                        onToast("已放弃本次上传")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.weight(1.4f)) {
                PrimaryActionButton(
                    text = "保存 ${selected.size} 个文件",
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        val payload = selected.map { row ->
                            UploadCompleteItem(
                                tempId = row.source.tempIdOrNull.orEmpty(),
                                name = row.name.trim(),
                                tags = row.tags.toList(),
                            )
                        }.filter { it.tempId.isNotEmpty() }
                        container.taskStore.completeUpload(payload)
                    },
                )
            }
        }
    }

    editingIndex?.let { index ->
        val row = rows.getOrNull(index)
        if (row != null) {
            TagPickerDialog(
                ownedNames = row.tags.toSet(),
                onConfirm = { picked ->
                    picked.forEach { tag -> if (!row.tags.contains(tag.name)) row.tags.add(tag.name) }
                    editingIndex = null
                },
                onDismiss = { editingIndex = null },
            )
        }
    }
}

package com.fluxframe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.ClickableRow
import com.fluxframe.app.ui.components.ConfirmDialog
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.components.TextPromptDialog
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.launch

/**
 * 媒体操作面板：重命名 / 打标签 / 下载 / 删除（回收站与永久删除分流）。
 * 列表长按与全屏查看器共用同一个面板，保证两处行为完全一致。
 */
@Composable
fun MediaActionDialog(
    item: ImageItem,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val session by container.sessionStore.state.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    var showRename by remember { mutableStateOf(false) }
    var showAddTag by remember { mutableStateOf(false) }
    var showRemoveTag by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = onGlassColor(dark, emphasis = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (item.isVideo) "视频" else "图片")
                        if (item.size.isNotBlank()) append(" · ${item.size}")
                        if (item.width > 0) append(" · ${item.width}×${item.height}")
                        append(" · ${item.views} 次浏览")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )

                if (item.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.tags.take(3).forEach { name ->
                            val tag = tags.firstOrNull { it.name == name }
                            TagChip(name = name, colorHex = tag?.color ?: "#A78BFA", showDot = true)
                        }
                        if (item.tags.size > 3) {
                            Text(
                                text = "+${item.tags.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    ClickableRow(
                        title = "重命名",
                        icon = Icons.Filled.DriveFileRenameOutline,
                        onClick = { showRename = true },
                    )
                    ClickableRow(
                        title = "添加标签",
                        icon = Icons.Filled.NewLabel,
                        onClick = { showAddTag = true },
                    )
                    if (item.tags.isNotEmpty()) {
                        ClickableRow(
                            title = "移除标签",
                            icon = Icons.Filled.LabelOff,
                            onClick = { showRemoveTag = true },
                        )
                    }
                    ClickableRow(
                        title = "下载原文件",
                        description = "交给系统下载器，可在通知栏看进度",
                        icon = Icons.Filled.Download,
                        onClick = {
                            scope.launch {
                                container.mediaDownloader.enqueue(item)
                                    .onSuccess { onToast("已加入系统下载队列") }
                                    .onFailure { onToast(it.message) }
                            }
                            onDismiss()
                        },
                    )
                    if (item.inTrash) {
                        ClickableRow(
                            title = "恢复",
                            icon = Icons.Filled.Restore,
                            onClick = {
                                scope.launch {
                                    container.mediaRepository.restore(item.id)
                                        .onSuccess {
                                            container.mediaStore.restoreLocally(item.id)
                                            container.mediaStore.refreshTrash()
                                            onToast("已恢复")
                                        }
                                        .onFailure { onToast(it.message) }
                                }
                                onDismiss()
                            },
                        )
                        if (isAdmin) {
                            ClickableRow(
                                title = "永久删除",
                                description = "同时删除物理文件与缩略图，不可恢复",
                                icon = Icons.Filled.DeleteForever,
                                destructive = true,
                                onClick = { confirmPurge = true },
                            )
                        }
                    } else {
                        ClickableRow(
                            title = "移入回收站",
                            icon = Icons.Filled.Delete,
                            destructive = true,
                            onClick = { confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (showRename) {
        TextPromptDialog(
            title = "重命名",
            initialValue = item.name,
            label = "名称（不需要扩展名）",
            onConfirm = { value ->
                showRename = false
                val name = value.trim()
                if (name.isEmpty()) return@TextPromptDialog
                scope.launch {
                    container.mediaRepository.rename(item.id, name)
                        .onSuccess { clean ->
                            container.mediaStore.applyRename(item.id, clean)
                            onToast("已重命名")
                        }
                        .onFailure { onToast(it.message) }
                }
            },
            onDismiss = { showRename = false },
        )
    }

    if (showAddTag) {
        TagPickerDialog(
            ownedNames = item.tags.toSet(),
            onConfirm = { picked ->
                showAddTag = false
                scope.launch {
                    var added = 0
                    var failure: String? = null
                    picked.forEach { tag ->
                        container.tagRepository.addToImage(item.id, tagId = tag.id)
                            .onSuccess {
                                container.mediaStore.applyTagAdded(item.id, tag.name, tag.id)
                                added++
                            }
                            .onFailure { failure = it.message }
                    }
                    onToast(failure ?: "已添加 $added 个标签")
                }
            },
            onDismiss = { showAddTag = false },
        )
    }

    if (showRemoveTag) {
        TagRemovalDialog(
            item = item,
            onRemove = { tagId, tagName ->
                showRemoveTag = false
                scope.launch {
                    container.tagRepository.removeFromImage(item.id, tagId)
                        .onSuccess {
                            container.mediaStore.applyTagRemoved(item.id, tagId)
                            onToast("已移除「$tagName」")
                        }
                        .onFailure { onToast(it.message) }
                }
            },
            onDismiss = { showRemoveTag = false },
        )
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            ConfirmDialog(
                title = "移入回收站？",
                text = "「${item.name}」会进入回收站，可在回收站里恢复或永久删除。",
                confirmLabel = "移入回收站",
                destructive = true,
                onConfirm = {
                    confirmDelete = false
                    onDismiss()
                    scope.launch {
                        container.mediaRepository.moveToTrash(item.id)
                            .onSuccess {
                                container.mediaStore.moveToTrashLocally(item.id)
                                onToast("已移入回收站")
                            }
                            .onFailure { onToast(it.message) }
                    }
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }

    if (confirmPurge) {
        Dialog(onDismissRequest = { confirmPurge = false }) {
            ConfirmDialog(
                title = "永久删除？",
                text = "将删除服务端的原文件与全部缩略图，且无法恢复。",
                confirmLabel = "永久删除",
                destructive = true,
                onConfirm = {
                    confirmPurge = false
                    onDismiss()
                    scope.launch {
                        container.mediaRepository.purge(item.id)
                            .onSuccess {
                                container.mediaStore.removeLocally(item.id)
                                container.mediaStore.refreshAll()
                                onToast("已永久删除")
                            }
                            .onFailure { onToast(it.message) }
                    }
                },
                onDismiss = { confirmPurge = false },
            )
        }
    }

    // 会话里的 R18 状态用于抑制未开启时的 R-18 提示（本面板不展示 R-18 逻辑，仅保持订阅以便刷新）
    @Suppress("UNUSED_EXPRESSION")
    session
}

/** 选择要给图片添加的标签 */
@Composable
fun TagPickerDialog(
    ownedNames: Set<String>,
    onConfirm: (List<TagItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    var query by remember { mutableStateOf("") }
    // 与网页端一致：可一次勾选多个标签再统一提交
    val picked = remember { mutableStateListOf<TagItem>() }

    val candidates = remember(tags, ownedNames, query) {
        tags.filter { it.name !in ownedNames }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "添加标签",
                    style = MaterialTheme.typography.titleSmall,
                    color = onGlassColor(dark, emphasis = true),
                )
                com.fluxframe.app.ui.components.GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索标签",
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = if (tags.isEmpty()) "还没有任何标签" else "没有可添加的标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = onGlassColor(dark, emphasis = false),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = { it.id }) { tag ->
                            val selected = picked.any { it.id == tag.id }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (selected) picked.removeAll { it.id == tag.id } else picked.add(tag)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                onGlassColor(dark, emphasis = false).copy(alpha = 0.2f)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                                TagChip(name = tag.name, colorHex = tag.color, count = tag.count)
                                if (tag.r18) {
                                    Text(
                                        text = "R18",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFEF4444),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        com.fluxframe.app.ui.components.SecondaryActionButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(modifier = Modifier.weight(1.3f)) {
                        com.fluxframe.app.ui.components.PrimaryActionButton(
                            text = "添加所选 ${picked.size} 个标签",
                            enabled = picked.isNotEmpty(),
                            onClick = { onConfirm(picked.toList()) },
                        )
                    }
                }
            }
        }
    }
}

/** 从已拥有的标签里挑一个移除 */
@Composable
private fun TagRemovalDialog(
    item: ImageItem,
    onRemove: (tagId: String, tagName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current

    val owned = remember(item.tagIds, tags) {
        item.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id } }
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "移除标签",
                    style = MaterialTheme.typography.titleSmall,
                    color = onGlassColor(dark, emphasis = true),
                )
                if (owned.isEmpty()) {
                    Text(
                        text = "这张媒体还没有标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                } else {
                    owned.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRemove(tag.id, tag.name) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TagChip(name = tag.name, colorHex = tag.color)
                            Box(modifier = Modifier.weight(1f))
                            Text(
                                text = "移除",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                com.fluxframe.app.ui.components.SecondaryActionButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

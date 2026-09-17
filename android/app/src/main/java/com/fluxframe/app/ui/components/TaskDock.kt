package com.fluxframe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxframe.app.data.store.ImportTask
import com.fluxframe.app.data.store.UploadPhase
import com.fluxframe.app.data.store.UploadTask
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor

/**
 * 全局任务坞（对应网页端的 TaskDock）。
 *
 * 无论当前在哪个页面，后台任务都会有反馈：上传（分析/入库）与视频提取。
 * 「本机下载」不在这里 —— 原生端交给系统 DownloadManager，进度在通知栏，
 * 比在应用内自绘一份更可靠（锁屏可见、可续传、点开即用）。
 *
 * 最多同时显示 [MAX_CARDS] 张卡片，避免挡住内容。
 */
@Composable
fun TaskDock(
    upload: UploadTask?,
    imports: List<ImportTask>,
    modifier: Modifier = Modifier,
    onOpenLibrary: () -> Unit,
    onCancelUpload: () -> Unit,
    onRetryUpload: () -> Unit,
    onDismissUpload: () -> Unit,
    onCancelImport: (String) -> Unit,
    onRetryImport: (String) -> Unit,
    onDismissImport: (String) -> Unit,
) {
    // 待用户确认的上传走全屏确认层，这里不重复显示
    val showUpload = upload != null && upload.phase != UploadPhase.REVIEW
    val visibleImports = imports.take(MAX_CARDS)

    AnimatedVisibility(
        visible = showUpload || visibleImports.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showUpload && upload != null) {
                UploadTaskCard(
                    task = upload,
                    onOpenLibrary = onOpenLibrary,
                    onCancel = onCancelUpload,
                    onRetry = onRetryUpload,
                    onDismiss = onDismissUpload,
                )
            }
            visibleImports.forEach { task ->
                ImportTaskCard(
                    task = task,
                    onOpenLibrary = onOpenLibrary,
                    onCancel = { onCancelImport(task.id) },
                    onRetry = { onRetryImport(task.id) },
                    onDismiss = { onDismissImport(task.id) },
                )
            }
        }
    }
}

@Composable
private fun UploadTaskCard(
    task: UploadTask,
    onOpenLibrary: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val working = task.inFlight
    val failed = task.phase == UploadPhase.FAILED
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        task.phase == UploadPhase.DONE -> Color(0xFF22C55E)
        else -> MaterialTheme.colorScheme.primary
    }
    val title = when (task.phase) {
        UploadPhase.ANALYZING -> "上传中 · ${task.totalFiles} 个文件"
        UploadPhase.SAVING -> "保存到图片库"
        UploadPhase.DONE -> "已保存 ${task.saved.size} 个文件"
        UploadPhase.FAILED -> "上传失败"
        UploadPhase.REVIEW -> "待确认"
    }
    val detail = when {
        failed -> task.error ?: "未知错误"
        task.phase == UploadPhase.DONE -> "可在图片库中查看"
        task.message.isNotBlank() -> task.message
        else -> "处理中…"
    }

    TaskCardShell(
        accent = accent,
        icon = if (failed) Icons.Filled.ErrorOutline else Icons.Filled.CloudUpload,
        working = working,
        title = title,
        detail = detail,
        progress = task.progress.takeIf { it > 0f && task.phase == UploadPhase.ANALYZING },
        tone = when {
            failed -> TaskTone.ERROR
            task.phase == UploadPhase.DONE -> TaskTone.DONE
            else -> TaskTone.WORKING
        },
        actions = {
            when (task.phase) {
                UploadPhase.DONE -> {
                    TaskButton("知道了", onClick = onDismiss)
                    TaskButton("去图片库查看", primary = true, icon = Icons.Filled.Image, onClick = {
                        onDismiss()
                        onOpenLibrary()
                    })
                }
                UploadPhase.FAILED -> {
                    TaskButton("关闭", onClick = onDismiss)
                    if (task.lastPayload.isNotEmpty()) {
                        TaskButton("重试", primary = true, icon = Icons.Filled.Refresh, onClick = onRetry)
                    }
                }
                else -> TaskButton("取消", onClick = onCancel)
            }
        },
        onClose = {
            if (working) onCancel() else onDismiss()
        },
    )
}

@Composable
private fun ImportTaskCard(
    task: ImportTask,
    onOpenLibrary: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = when {
        task.isError -> MaterialTheme.colorScheme.error
        task.isDone -> Color(0xFF22C55E)
        else -> Color(0xFF22D3EE)
    }
    TaskCardShell(
        accent = accent,
        icon = Icons.Filled.Movie,
        working = task.isWorking,
        title = "保存到图片库 · ${task.title}",
        detail = task.message.ifBlank { "处理中…" },
        progress = task.progress.takeIf { task.isWorking && it != null && it > 0f },
        tone = when {
            task.isError -> TaskTone.ERROR
            task.isDone -> TaskTone.DONE
            else -> TaskTone.WORKING
        },
        actions = {
            when {
                task.isDone -> {
                    TaskButton("知道了", onClick = onDismiss)
                    TaskButton("去图片库查看", primary = true, icon = Icons.Filled.Image, onClick = {
                        onDismiss()
                        onOpenLibrary()
                    })
                }
                task.isError -> {
                    TaskButton("关闭", onClick = onDismiss)
                    if (task.request != null) {
                        TaskButton("重试", primary = true, icon = Icons.Filled.Refresh, onClick = onRetry)
                    }
                }
                else -> TaskButton("取消", onClick = onCancel)
            }
        },
        onClose = {
            if (task.isWorking) onCancel() else onDismiss()
        },
    )
}

private enum class TaskTone { WORKING, DONE, ERROR }

@Composable
private fun TaskCardShell(
    accent: Color,
    icon: ImageVector,
    working: Boolean,
    title: String,
    detail: String,
    progress: Float?,
    tone: TaskTone,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        backdrop = true,
        borderWidth = 1.dp,
        contentPadding = PaddingValues(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        working && progress == null -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        tone == TaskTone.DONE -> Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        else -> Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = onGlassColor(dark, emphasis = true),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tone == TaskTone.WORKING) {
                            onGlassColor(dark, emphasis = false)
                        } else {
                            accent
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (working) "取消" else "关闭",
                    tint = onGlassColor(dark, emphasis = false),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onClose() },
                )
            }

            if (tone == TaskTone.WORKING) {
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.16f),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.16f),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = if (tone == TaskTone.DONE) 0.85f else 0.5f)),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                actions()
            }
        }
    }
}

@Composable
private fun RowScope.TaskButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    icon: ImageVector? = null,
) {
    val dark = LocalDarkTheme.current
    val background = if (primary) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    }
    val contentColor = if (primary) MaterialTheme.colorScheme.primary else onGlassColor(dark, emphasis = false)

    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
            Box(modifier = Modifier.size(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private const val MAX_CARDS = 3

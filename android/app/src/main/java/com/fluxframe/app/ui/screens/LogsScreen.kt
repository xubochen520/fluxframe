package com.fluxframe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.util.formatDateTime
import com.fluxframe.app.core.util.formatRelative
import com.fluxframe.app.data.store.LogsStore
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.FluxRadius
import com.fluxframe.app.ui.theme.FluxType
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.Success
import com.fluxframe.app.ui.theme.Warn
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.toneColor

/**
 * 访问日志（仅 ADMIN）：服务端固定返回最近 500 条，分类筛选在本地完成。
 *
 * 视觉：统计条 + 分组胶囊 + 玻璃行表格。
 * 每一行都按"表格"来排：操作图标（按分类取）、操作与目标、等宽 IP + 内网/外网标签、等宽时间。
 * IP 与时间刻意用 DM Mono —— 一列等宽数字扫起来比比例字体快得多。
 */
@Composable
fun LogsScreen(onToast: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val logs by container.logsStore.logs.collectAsStateWithLifecycle()
    val filter by container.logsStore.filter.collectAsStateWithLifecycle()
    val loading by container.logsStore.loading.collectAsStateWithLifecycle()
    val error by container.logsStore.error.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current

    LaunchedEffect(Unit) {
        if (logs.isEmpty()) container.logsStore.refresh()
    }

    val visible = if (filter == LogsStore.LOG_GROUPS.first()) {
        logs
    } else {
        logs.filter { LogsStore.logGroupOf(it.action) == filter }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        /* ------------------------------ 统计条 ------------------------------ */
        val innerCount = logs.count { it.scope != "外网" }
        val outerCount = logs.size - innerCount
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiniStat(label = "记录", value = logs.size.toString(), accent = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            MiniStat(label = "内网", value = innerCount.toString(), accent = Success, modifier = Modifier.weight(1f))
            MiniStat(label = "外网", value = outerCount.toString(), accent = Warn, modifier = Modifier.weight(1f))
        }
        // 加载进度：一条渐变细条，比转圈安静，也不挡住已有内容
        if (loading) {
            val accent = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(3.dp)
                    .background(accent.copy(alpha = 0.35f), RoundedCornerShape(50)),
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LogsStore.LOG_GROUPS) { group ->
                TagChip(
                    name = group,
                    colorHex = "#6366F1",
                    selected = filter == group,
                    showDot = false,
                    count = when (group) {
                        LogsStore.LOG_GROUPS.first() -> logs.size
                        else -> logs.count { LogsStore.logGroupOf(it.action) == group }
                    },
                    onClick = { container.logsStore.setFilter(group) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${visible.size} 条（服务端最多保留 500 条 / 30 天）",
                style = FluxType.monoSmall,
                color = onGlassColor(dark, emphasis = false),
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "刷新",
                onClick = { container.logsStore.refresh() },
                withSurface = false,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (error != null) {
            Box(modifier = Modifier.padding(horizontal = 14.dp)) {
                ErrorBar(
                    message = error!!,
                    onRetry = { container.logsStore.refresh() },
                    onDismiss = {},
                )
            }
        }

        when {
            visible.isEmpty() && loading -> LoadingBox(text = "正在读取访问日志…")
            visible.isEmpty() -> EmptyState(
                title = "没有记录",
                description = if (logs.isEmpty()) "还没有产生访问日志。" else "该分类下没有记录。",
                icon = Icons.Filled.Description,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { log ->
                    val accent = toneColor(log.tone, dark)
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        borderWidth = 0.6.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 操作图标：方形底座 + 同色薄底，色相来自后端给的 tone
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(FluxRadius.xs))
                                    .background(accent.copy(alpha = if (dark) 0.16f else 0.10f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = logIcon(log.action),
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = log.action,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onGlassColor(dark, emphasis = true),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (log.target.isNotBlank()) {
                                    Text(
                                        text = log.target,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = onGlassColor(dark, emphasis = false),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = log.user,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = onGlassColor(dark, emphasis = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (log.ip.isNotBlank()) {
                                        Text(
                                            text = log.ip,
                                            style = FluxType.monoSmall,
                                            color = onGlassColor(dark, emphasis = false),
                                            maxLines = 1,
                                        )
                                    }
                                    ScopeBadge(scope = log.scope, dark = dark)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = formatRelative(log.time),
                                    style = FluxType.monoSmall,
                                    color = onGlassColor(dark, emphasis = true),
                                )
                                Text(
                                    text = formatDateTime(log.time).substringAfter(' '),
                                    style = FluxType.monoSmall,
                                    color = onGlassColor(dark, emphasis = false).copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

/** 统计条里的一格：小标签 + 大数字 */
@Composable
private fun MiniStat(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = modifier,
        borderWidth = 0.6.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = label,
                style = FluxType.eyebrow,
                color = onGlassColor(dark, emphasis = false),
            )
            Text(
                text = value,
                style = FluxType.numeralSmall,
                color = accent,
            )
        }
    }
}

/** 内网 / 外网标签：两色胶囊，等宽小字 */
@Composable
private fun ScopeBadge(scope: String, dark: Boolean) {
    val inner = scope != "外网"
    val color = if (inner) Success else Warn
    Text(
        text = if (inner) "内网" else "外网",
        style = FluxType.monoSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(FluxRadius.xs))
            .background(color.copy(alpha = if (dark) 0.16f else 0.12f))
            .border(0.6.dp, color.copy(alpha = 0.32f), RoundedCornerShape(FluxRadius.xs))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 动作 → 图标。
 *
 * 与 [LogsStore.logGroupOf] 的分组规则一一对应：分类胶囊上说「下载」，
 * 行首就该是一枚下载图标，两边对不上会让人怀疑筛选是不是坏了。
 */
private fun logIcon(action: String): ImageVector = when (LogsStore.logGroupOf(action)) {
    "查看" -> Icons.Filled.Visibility
    "上传" -> Icons.Filled.CloudUpload
    "下载" -> Icons.Filled.Download
    "提取" -> Icons.Filled.Movie
    "改名" -> Icons.Filled.DriveFileRenameOutline
    "标签" -> Icons.Filled.Label
    "删除" -> Icons.Filled.DeleteOutline
    else -> Icons.Filled.Description
}

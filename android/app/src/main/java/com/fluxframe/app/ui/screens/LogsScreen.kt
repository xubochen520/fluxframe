package com.fluxframe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.util.formatDateTime
import com.fluxframe.app.core.util.formatRelative
import com.fluxframe.app.data.store.LogsStore
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.toneColor

/** 访问日志（仅 ADMIN）：服务端固定返回最近 500 条，分类筛选在本地完成 */
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
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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
                style = MaterialTheme.typography.labelSmall,
                color = onGlassColor(dark, emphasis = false),
                modifier = Modifier.weight(1f),
            )
            com.fluxframe.app.ui.components.GlassIconButton(
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
                    GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accent),
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                Text(
                                    text = "${log.user} · ${log.scope} · ${log.ip}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = formatRelative(log.time),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                                Text(
                                    text = formatDateTime(log.time).substringAfter(' '),
                                    style = MaterialTheme.typography.labelSmall,
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

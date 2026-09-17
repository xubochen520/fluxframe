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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.util.formatMoney
import com.fluxframe.app.core.util.formatRelative
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MediaCard
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.StatTile
import com.fluxframe.app.ui.components.VideoPosterRequest
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.BrandCyan
import com.fluxframe.app.ui.theme.BrandGreen
import com.fluxframe.app.ui.theme.BrandIndigo
import com.fluxframe.app.ui.theme.BrandPink
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.toneColor

/** 总览：数据概览 + 快捷入口 + 最近/最热 + 最近动态 */
@Composable
fun OverviewScreen(
    onOpenLibrary: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenDeepseek: () -> Unit,
    onOpenParse: () -> Unit,
    onOpenImage: (String) -> Unit,
    onUpload: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val dashboard by container.mediaStore.dashboard.collectAsStateWithLifecycle()
    val loading by container.mediaStore.loading.collectAsStateWithLifecycle()
    val error by container.mediaStore.error.collectAsStateWithLifecycle()
    val deepseek by container.deepseekStore.summary.collectAsStateWithLifecycle()
    val media by container.mediaStore.liveImages.collectAsStateWithLifecycle()
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current

    val stats = dashboard?.stats

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) {
            item {
                ErrorBar(
                    message = error!!,
                    onRetry = { container.mediaStore.refreshAll() },
                    onDismiss = { container.mediaStore.clearError() },
                )
            }
        }

        if (stats == null && loading) {
            item { LoadingBox(text = "正在读取服务器数据…") }
        }

        // ---- 指标 ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "媒体总数",
                        value = (stats?.imageCount ?: media.size).toString(),
                        hint = "含视频",
                        icon = Icons.Filled.Image,
                        accent = BrandIndigo,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "标签",
                        value = (stats?.tagCount ?: 0).toString(),
                        hint = "人物组 ${container.mediaStore.personTags.size} 个",
                        icon = Icons.Filled.Label,
                        accent = BrandCyan,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "总浏览",
                        value = (stats?.totalViews ?: 0).toString(),
                        hint = "服务端累计",
                        icon = Icons.Filled.RemoveRedEye,
                        accent = BrandPink,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "占用",
                        value = stats?.storage ?: "--",
                        hint = "数据库记录 ${stats?.databaseImageBytes ?: "--"}",
                        icon = Icons.Filled.Storage,
                        accent = BrandGreen,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 磁盘占用 ----
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "存储空间",
                            style = MaterialTheme.typography.labelLarge,
                            color = onGlassColor(dark, emphasis = true),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = buildString {
                                append(stats?.storage ?: "--")
                                append(" / ")
                                append(stats?.storageCapacity ?: "未知")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ((stats?.storagePercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    )
                }
            }
        }

        // ---- DeepSeek 记账入口（有配置才显示）----
        if (deepseek?.configured == true) {
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenDeepseek() },
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DeepSeek 余额",
                                style = MaterialTheme.typography.labelMedium,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            Text(
                                text = formatMoney(deepseek?.stats?.balance ?: 0.0, deepseek?.stats?.currency),
                                style = MaterialTheme.typography.titleMedium,
                                color = onGlassColor(dark, emphasis = true),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "今日 ${formatMoney(deepseek?.stats?.today ?: 0.0, deepseek?.stats?.currency)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            Text(
                                text = "本月 ${formatMoney(deepseek?.stats?.month ?: 0.0, deepseek?.stats?.currency)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                    }
                }
            }
        }

        // ---- 快捷入口 ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("上传", Icons.Filled.CloudUpload, BrandIndigo, Modifier.weight(1f), onUpload)
                QuickAction("视频提取", Icons.Filled.Movie, BrandPink, Modifier.weight(1f), onOpenParse)
                QuickAction("标签", Icons.Filled.Label, BrandCyan, Modifier.weight(1f), onOpenTags)
            }
        }

        // ---- 最近上传 ----
        val recent = dashboard?.recent.orEmpty()
        if (recent.isNotEmpty()) {
            item {
                SectionTitle(
                    text = "最近上传",
                    trailing = {
                        Text(
                            text = "查看全部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenLibrary() },
                        )
                    },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.id }) { item ->
                        MediaCard(
                            name = item.name,
                            imageUrl = container.mediaRepository.gridUrl(item),
                            isVideo = item.isVideo,
                            aspectRatio = if (item.isVideo) 16f / 9f else item.aspectRatio,
                            views = item.views,
                            r18 = item.r18,
                            tags = item.tags,
                            modifier = Modifier.width(132.dp),
                            onClick = { onOpenImage(item.id) },
                            poster = if (item.isVideo && uiPrefs.videoPosterEnabled) {
                                VideoPosterRequest(item)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        // ---- 最多浏览 ----
        val top = dashboard?.top.orEmpty()
        if (top.isNotEmpty()) {
            item { SectionTitle(text = "最多浏览") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(top, key = { it.id }) { item ->
                        MediaCard(
                            name = item.name,
                            imageUrl = container.mediaRepository.gridUrl(item),
                            isVideo = item.isVideo,
                            aspectRatio = if (item.isVideo) 16f / 9f else item.aspectRatio,
                            views = item.views,
                            r18 = item.r18,
                            modifier = Modifier.width(132.dp),
                            onClick = { onOpenImage(item.id) },
                            poster = if (item.isVideo && uiPrefs.videoPosterEnabled) {
                                VideoPosterRequest(item)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        // ---- 最近动态 ----
        val logs = dashboard?.logs.orEmpty()
        if (logs.isNotEmpty()) {
            item { SectionTitle(text = "最近动态") }
            items(logs, key = { it.id }) { log ->
                val accent = toneColor(log.tone, dark)
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = log.action + (if (log.target.isNotBlank()) " · ${log.target}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = onGlassColor(dark, emphasis = true),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${log.user} · ${log.scope}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                        Text(
                            text = formatRelative(log.time),
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                    }
                }
            }
        }

        if (stats == null && !loading && error == null) {
            item {
                EmptyState(
                    title = "还没有数据",
                    description = "上传第一张图片后，这里会显示统计信息。",
                    actionLabel = "开始上传",
                    onAction = onUpload,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = modifier.clickable { onClick() },
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onGlassColor(dark, emphasis = true),
            )
        }
    }
}

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.fluxframe.app.ui.components.MediaThumb
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.StatTile
import com.fluxframe.app.ui.components.VideoPosterRequest
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.BrandCyan
import com.fluxframe.app.ui.theme.BrandGreen
import com.fluxframe.app.ui.theme.BrandIndigo
import com.fluxframe.app.ui.theme.BrandPink
import com.fluxframe.app.ui.theme.BrandViolet
import com.fluxframe.app.ui.theme.FluxRadius
import com.fluxframe.app.ui.theme.FluxType
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.Success
import com.fluxframe.app.ui.theme.contentOn
import com.fluxframe.app.ui.theme.fluxPalette
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.toneColor
import java.util.Calendar

/**
 * 总览：bento 网格。
 *
 * 版式（与网页端 OverviewView 对齐）：
 *   Hero 卡（问候 + 系统状态胶囊 + 上传 CTA + 主色光晕）
 *   → 4 张统计卡（大数字）
 *   → DeepSeek 细玻璃带
 *   → 热门图片（01–04 大号排名）
 *   → 最近动态（时间线）
 *   → 最近上传（横向卡片）
 *
 * 全部业务逻辑（dashboard / deepseek summary / liveImages 三个数据源与它们的空态、错误态）
 * 与改造前完全一致，只换了排版与观感。
 */
@Composable
fun OverviewScreen(
    onOpenLibrary: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenDeepseek: () -> Unit,
    onOpenParse: () -> Unit,
    onOpenSimilar: () -> Unit,
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
    val accent = MaterialTheme.colorScheme.primary

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

        /* ------------------------------ Hero ------------------------------ */
        item {
            /*
             * 问候语与网页端逐字一致（按小时分档），所以两端打开总览看到的头一句是一样的。
             * 用户名单独用强调色，是被"点亮"的那一笔 —— 整张卡只有它和光晕带颜色。
             */
            val greeting = remember {
                when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                    in 0..4 -> "夜深了"
                    in 5..11 -> "早上好"
                    in 12..13 -> "中午好"
                    in 14..18 -> "下午好"
                    else -> "晚上好"
                }
            }
            HeroCard(
                greeting = greeting,
                username = container.sessionStore.state.value.user?.username,
                connected = error == null,
                onUpload = onUpload,
                onOpenLibrary = onOpenLibrary,
            )
        }

        /* ------------------------------ 指标（bento） ------------------------------ */
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

        /* ------------------------------ 磁盘占用 ------------------------------ */
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
                                append(stats?.storageUsed ?: "--")
                                append(" / ")
                                append(stats?.storageCapacity ?: "未知")
                            },
                            style = FluxType.monoSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                    }
                    // 自绘渐变进度条：Material 的三段式轨道在玻璃面板上显得"外挂"
                    val fraction = ((stats?.storagePercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.15f))
                            .drawWithCache {
                                val head = Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.55f), accent),
                                )
                                val width = size.width * fraction
                                onDrawBehind {
                                    if (width > 0f) {
                                        drawRoundRect(
                                            brush = head,
                                            size = androidx.compose.ui.geometry.Size(width, size.height),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                                        )
                                    }
                                }
                            },
                    )
                    Text(
                        text = "FluxFrame 媒体库占用 ${stats?.storage ?: "--"}",
                        style = FluxType.monoSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                }
            }
        }

        /* ------------------------------ DeepSeek 细玻璃带 ------------------------------ */
        if (deepseek?.configured == true) {
            item {
                val tertiary = MaterialTheme.colorScheme.tertiary
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenDeepseek() },
                    borderWidth = 0.8.dp,
                    glow = tertiary,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(tertiary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                tint = tertiary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = "DeepSeek 余额",
                                style = FluxType.eyebrow,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            // 余额是大数字：Space Grotesk + 等宽数字，刷新时不会左右抖
                            Text(
                                text = formatMoney(deepseek?.stats?.balance ?: 0.0, deepseek?.stats?.currency),
                                style = FluxType.numeralSmall,
                                color = onGlassColor(dark, emphasis = true),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "今日 ${formatMoney(deepseek?.stats?.today ?: 0.0, deepseek?.stats?.currency)}",
                                style = FluxType.monoSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            Text(
                                text = "本月 ${formatMoney(deepseek?.stats?.month ?: 0.0, deepseek?.stats?.currency)}",
                                style = FluxType.monoSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                    }
                }
            }
        }

        /* ------------------------------ 快捷入口 ------------------------------ */
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("上传", Icons.Filled.CloudUpload, BrandIndigo, Modifier.weight(1f), onUpload)
                QuickAction("视频提取", Icons.Filled.Movie, BrandPink, Modifier.weight(1f), onOpenParse)
                QuickAction("标签", Icons.Filled.Label, BrandCyan, Modifier.weight(1f), onOpenTags)
            }
        }

        // ---- 星系图：单独一行，和上面三个"动作"区分开（它是"去看"，不是"去做"）----
        item {
            QuickAction(
                label = "相似图星系图",
                icon = Icons.Filled.Hub,
                accent = BrandViolet,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSimilar,
            )
        }

        /* ------------------------------ 热门图片（01–04 大号排名） ------------------------------ */
        val top = dashboard?.top.orEmpty()
        if (top.isNotEmpty()) {
            item {
                SectionTitle(
                    text = "热门图片",
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
            items(top.take(4).withIndex().toList(), key = { it.value.id }) { (index, item) ->
                RankedMediaRow(
                    rank = index + 1,
                    item = item,
                    onClick = { onOpenImage(item.id) },
                )
            }
        }

        /* ------------------------------ 最近动态（时间线） ------------------------------ */
        val logs = dashboard?.logs.orEmpty()
        if (logs.isNotEmpty()) {
            item { SectionTitle(text = "最近动态") }
            items(logs, key = { it.id }) { log ->
                TimelineRow(
                    accent = toneColor(log.tone, dark),
                    title = log.action + (if (log.target.isNotBlank()) " · ${log.target}" else ""),
                    subtitle = "${log.user} · ${log.scope}",
                    time = formatRelative(log.time),
                )
            }
        }

        /* ------------------------------ 最近上传 ------------------------------ */
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

/* ------------------------------ Hero ------------------------------ */

/**
 * Hero 卡：问候 + 系统状态胶囊 + 上传 CTA，右上角一团主色光晕。
 *
 * 光晕是整页唯一"大块用色"的地方 —— 它落在卡片外沿、朝右上方散开，
 * 和背景流体的光源方向一致，像被背景照亮而不是自己发光。
 * 画刷在 drawWithCache 里按尺寸建一次，绘制阶段不分配对象、也不用 Modifier.blur。
 */
@Composable
private fun HeroCard(
    greeting: String,
    username: String?,
    connected: Boolean,
    onUpload: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val palette = fluxPalette(dark)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val statusColor = if (connected) Success else MaterialTheme.colorScheme.error

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        borderWidth = 0.8.dp,
        glow = primary,
        contentPadding = PaddingValues(18.dp),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val radius = maxOf(size.width, size.height) * 0.95f
                    val brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to primary.copy(alpha = if (dark) 0.34f else 0.20f),
                            0.42f to secondary.copy(alpha = if (dark) 0.13f else 0.08f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width, 0f),
                        radius = radius,
                    )
                    onDrawBehind { drawRect(brush) }
                },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 系统状态胶囊：有错误条时不装"运行正常"
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.glass2)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = if (connected) "系统运行正常" else "服务端连接异常",
                    style = FluxType.monoSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (username.isNullOrBlank()) greeting else "$greeting，$username",
                    style = MaterialTheme.typography.headlineSmall,
                    color = onGlassColor(dark, emphasis = true),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "这是你的媒体库今天的概览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    PrimaryActionButton(
                        text = "上传媒体",
                        icon = Icons.Filled.CloudUpload,
                        onClick = onUpload,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    HeroSecondaryButton(text = "浏览图片库", onClick = onOpenLibrary)
                }
            }
        }
    }
}

/** Hero 里的次级按钮：中性玻璃 + 细描边，和主按钮等宽等高 */
@Composable
private fun HeroSecondaryButton(text: String, onClick: () -> Unit) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(44.dp).clickable { onClick() },
        shape = RoundedCornerShape(FluxRadius.md),
        raised = true,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = onGlassColor(dark, emphasis = true),
            )
        }
    }
}

/* ------------------------------ 热门图片行 ------------------------------ */

/**
 * 排名行：`01` 这样的大号等宽序号 + 缩略图 + 名称/标签 + 观看数。
 *
 * 序号用 Space Grotesk 且**只有前三位带主色** —— 第四名之后视觉重量立刻落下去，
 * 一眼看到的就是前三名。名次色只是文字颜色，不额外加底板。
 */
@Composable
private fun RankedMediaRow(
    rank: Int,
    item: ImageItem,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rank.toString().padStart(2, '0'),
                style = FluxType.numeral,
                color = if (rank <= 3) primary else onGlassColor(dark, emphasis = false).copy(alpha = 0.55f),
            )
            MediaThumb(
                item = item,
                size = 44.dp,
                shape = RoundedCornerShape(FluxRadius.xs),
                placeholderTint = BrandViolet,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGlassColor(dark, emphasis = true),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.tags.isEmpty()) "未分类" else item.tags.take(3).joinToString(" · "),
                    style = FluxType.monoSmall,
                    color = onGlassColor(dark, emphasis = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Filled.RemoveRedEye,
                    contentDescription = null,
                    tint = onGlassColor(dark, emphasis = false),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = item.views.toString(),
                    style = FluxType.mono,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
            if (item.isVideo) {
                MiniBadge(text = "视频", color = BrandPink)
            }
        }
    }
}

/* ------------------------------ 动态时间线 ------------------------------ */

/**
 * 一条动态。左侧是带光晕的色点 + 向下的竖线（最后一条的竖线由调用方序列决定不了，
 * 这里一律画 —— 在密集列表里"断掉最后一根"反而显得突兀），右侧是两行文字与等宽时间。
 */
@Composable
private fun TimelineRow(
    accent: Color,
    title: String,
    subtitle: String,
    time: String,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        borderWidth = 0.6.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .drawWithCache {
                        val halo = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        )
                        onDrawBehind {
                            drawRect(halo)
                            drawCircle(accent, radius = size.minDimension * 0.30f)
                        }
                    },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGlassColor(dark, emphasis = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = FluxType.monoSmall,
                    color = onGlassColor(dark, emphasis = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = time,
                style = FluxType.monoSmall,
                color = onGlassColor(dark, emphasis = false),
            )
        }
    }
}

/* ------------------------------ 快捷入口 ------------------------------ */

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
        borderWidth = 0.8.dp,
        glow = accent,
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
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

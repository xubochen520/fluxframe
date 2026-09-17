package com.fluxframe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalGlassTokens
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.core.util.hueFromKey
import kotlin.math.roundToInt

/* ============================================================================
 * 通用 UI 组件。所有页面都从这里取件，保证三套主题下的观感一致。
 * ========================================================================== */

/** 极细分割线，比 Material 的 Divider 更克制 */
@Composable
fun HairLine(modifier: Modifier = Modifier, alpha: Float = 0.08f) {
    val dark = LocalDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (dark) Color.White.copy(alpha = alpha) else Color.Black.copy(alpha = alpha * 0.7f)),
    )
}

/** 区块标题 + 右侧可选操作 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = onGlassColor(dark, emphasis = true),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 可折叠的区块标题（只画标题行）。
 *
 * 与 [CollapsibleSection] 分开是为了让**长列表保持懒加载**：
 * 标签列表可以直接把这一行当成 `LazyColumn` 的一个 item，
 * 折叠时干脆不生成后续 item，而不是把所有行一次性组合出来再隐藏。
 */
@Composable
fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "sectionChevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (count != null) "$title（$count）" else title,
            style = MaterialTheme.typography.titleSmall,
            color = onGlassColor(dark, emphasis = true),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            tint = onGlassColor(dark, emphasis = false),
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * 可折叠的区块：标题 + 内容。
 *
 * 标签页里"人物"与"全部标签"两组都可能很长，展开/收起交给用户自己决定。
 */
@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CollapsibleHeader(
            title = title,
            expanded = expanded,
            onToggle = onToggle,
            count = count,
            trailing = trailing,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** 玻璃态图标按钮 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    withSurface: Boolean = true,
) {
    val dark = LocalDarkTheme.current
    val button: @Composable () -> Unit = {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint ?: onGlassColor(dark, emphasis = true),
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (withSurface) {
        GlassSurface(
            modifier = modifier.size(40.dp),
            shape = CircleShape,
            backdrop = false,
            contentPadding = PaddingValues(0.dp),
        ) { button() }
    } else {
        Box(modifier = modifier) { button() }
    }
}

/** 标签胶囊 */
@Composable
fun TagChip(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showDot: Boolean = true,
    count: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    val accent = parseColor(colorHex)
    val background = if (selected) accent.copy(alpha = if (dark) 0.26f else 0.16f) else {
        if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    }
    val borderColor = if (selected) accent.copy(alpha = 0.6f) else {
        if (dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.06f)
    }
    val textColor = if (selected) {
        if (dark) Color.White else accent
    } else {
        onGlassColor(dark, emphasis = false)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showDot) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accent))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.65f),
            )
        }
    }
}

/** 指标卡（总览页用） */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(modifier = modifier, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = onGlassColor(dark, emphasis = true),
                fontWeight = FontWeight.SemiBold,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 媒体网格卡片。
 *
 * 视频封面用**真实帧**：`VideoPosterLoader` 在 0.5 秒处取一帧，
 * 优先用「平台 HTTP 栈 + 会话 Cookie 请求头」，失败再退回带鉴权的 Range 数据源。
 * 取不到时自动退回「渐变色块 + 播放按钮」，并且**失败原因会记录到设置页**，
 * 不再是"静默不出图"。
 *
 * 网格里刻意关掉 `crossfade`：Coil 的全局淡入是每一张卡片一层额外动画与透明度层，
 * 快速滚动时是实打实的掉帧来源；淡入只在全屏查看器里保留。
 */
@Composable
fun MediaCard(
    name: String,
    imageUrl: String?,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 1f,
    views: Int? = null,
    r18: Boolean = false,
    tags: List<String> = emptyList(),
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    /** 视频封面：提供取帧所需的上下文（地址 + 缓存键），为空则退回渐变占位 */
    poster: VideoPosterRequest? = null,
) {
    val dark = LocalDarkTheme.current
    val tokens = LocalGlassTokens.current
    val ratio = aspectRatio.coerceIn(0.45f, 2.2f)
    val density = LocalDensity.current
    val cardWidthPx = with(density) { 132.dp.toPx() }.roundToInt()

    val posterBitmap = if (isVideo && poster != null) {
        rememberVideoPoster(request = poster, fallbackWidthPx = cardWidthPx)
    } else {
        null
    }
    val posterReady = isVideo && posterBitmap != null

    GlassSurface(
        modifier = modifier.aspectRatio(ratio),
        backdrop = false,
        borderWidth = 0.6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ---- 底层：图片本身，或视频的渐变色兜底 ----
            when {
                isVideo || imageUrl.isNullOrBlank() -> VideoOrPlaceholderTile(
                    name = name,
                    isVideo = isVideo,
                    // 有真实帧时不再画占位播放键，避免和上层的播放键重影
                    showPlayIcon = !posterReady,
                )

                else -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---- 视频真实帧（0.5s 处）覆盖在渐变之上；取不到就自动露出渐变 ----
            if (posterBitmap != null) {
                Image(
                    bitmap = posterBitmap.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 底部信息渐隐层
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.62f),
                        ),
                    ),
            )

            if (isVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "播放视频",
                        tint = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (views != null && views > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "▶ $views",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            if (r18) {
                Text(
                    text = "R18",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty()) {
                    Text(
                        text = tags.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(tokens.cornerRadius)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onLongClick != null) {
                            Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
                        } else {
                            Modifier.clickable { onClick() }
                        },
                    ),
            )

            // 液态玻璃下给卡片描一圈内高光，网格里立刻有"玻璃片"的层次
            if (tokens.style != com.fluxframe.app.core.prefs.AppThemeStyle.DEFAULT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 0.8.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = tokens.borderAlpha * (if (dark) 1f else 0.7f)),
                                    Color.Transparent,
                                ),
                            ),
                            shape = RoundedCornerShape(tokens.cornerRadius),
                        ),
                )
            }
        }
    }
}

/** 视频 / 无图时的占位块：由名称派生稳定色相，同一张图颜色永远一致 */
@Composable
private fun VideoOrPlaceholderTile(name: String, isVideo: Boolean, showPlayIcon: Boolean = true) {
    val hue = hueFromKey(name)
    val top = Color.hsl(hue, 0.42f, 0.34f)
    val bottom = Color.hsl((hue + 38f) % 360f, 0.46f, 0.16f)
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo && showPlayIcon) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (!isVideo) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** `combinedClickable` 需要 ExperimentalFoundationApi，这里收敛在一个地方 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** 空状态 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Image,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = onGlassColor(dark, emphasis = true),
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = onGlassColor(dark, emphasis = false),
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(2.dp))
            PrimaryActionButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** 加载态 */
@Composable
fun LoadingBox(text: String = "加载中…", modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = onGlassColor(dark, emphasis = false),
        )
    }
}

/** 错误条（可重试） */
@Composable
fun ErrorBar(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tint = MaterialTheme.colorScheme.error,
        borderWidth = 1.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable { onRetry() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (onDismiss != null) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp).clickable { onDismiss() },
                )
            }
        }
    }
}

/** 主操作按钮（走品牌渐变） */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.45f, label = "buttonAlpha")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(Color.Gray.copy(alpha = 0.5f), Color.Gray.copy(alpha = 0.5f)),
                    )
                },
            )
            .clickable(enabled = enabled && !loading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 次级按钮（玻璃底） */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = modifier.height(40.dp).then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(12.dp),
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(40.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = onGlassColor(dark, emphasis = true),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = onGlassColor(dark, enabled),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 分段选择器（玻璃胶囊） */
@Composable
fun <T> PillSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Transparent,
                label = "pillBg",
            )
            Text(
                text = label(option),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) Color.White else onGlassColor(dark, emphasis = false),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 带标签的进度行 */
@Composable
fun ProgressRow(
    label: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val dark = LocalDarkTheme.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = onGlassColor(dark, emphasis = true),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
        }
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
    }
}

/** 小徽标 */
@Composable
fun MiniBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 键值行（设置页大量使用） */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val dark = LocalDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = onGlassColor(dark, emphasis = false),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: onGlassColor(dark, emphasis = true),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 把后端返回的 `#rrggbb` 安全地转成 Compose Color */
fun parseColor(hex: String, fallback: Color = Color(0xFFA78BFA)): Color = runCatching {
    val clean = hex.trim().removePrefix("#")
    when (clean.length) {
        6 -> Color(0xFF000000 or clean.toLong(16))
        8 -> Color(clean.toLong(16))
        else -> fallback
    }
}.getOrDefault(fallback)

/** 数字进度对应的强调色 */
@Composable
fun progressAccent(value: Float?): Color = when {
    value == null -> MaterialTheme.colorScheme.primary
    value >= 1f -> Color(0xFF22C55E)
    else -> MaterialTheme.colorScheme.primary
}

/** 横向间距helper */
@Composable
fun HGap(width: Int) = Spacer(modifier = Modifier.width(width.dp))

/** 纵向间距 helper */
@Composable
fun VGap(height: Int) = Spacer(modifier = Modifier.height(height.dp))

package com.fluxframe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.core.util.hueFromKey
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.FluxMotion
import com.fluxframe.app.ui.theme.FluxRadius
import com.fluxframe.app.ui.theme.FluxType
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalGlassTokens
import com.fluxframe.app.ui.theme.Success
import com.fluxframe.app.ui.theme.contentOn
import com.fluxframe.app.ui.theme.fluxPalette
import com.fluxframe.app.ui.theme.onGlassColor
import com.fluxframe.app.ui.theme.readableAccent
import kotlin.math.roundToInt

/* ============================================================================
 * 通用 UI 组件。所有页面都从这里取件，保证五种玻璃风格、深浅两色下的观感一致。
 *
 * 视觉规则（与 Chrome.kt 同一套）：
 *  1. 光源只有背景流体 —— 面板不带自己的颜色，只有染色、顶边高光与细描边。
 *  2. 大数字用 Space Grotesk（[FluxType.numeralLarge] 等），数据用 DM Mono
 *     （[FluxType.mono]），正文用 DM Sans。
 *  3. 强调色只出现在色点、角标、进度与辉光上；面板本体保持中性。
 *
 * 性能：不使用 Modifier.blur；渐变画刷一律在 drawWithCache / remember(依赖) 里
 * 建，绘制阶段不分配对象；圆角一律取 [FluxRadius]。
 * ========================================================================== */

/** 最淡的一档中性填充，用于标签、分段控件轨道这类"非面板"的表面 */
private fun neutralFill(dark: Boolean, strong: Boolean = false): Color =
    if (dark) {
        Color.White.copy(alpha = if (strong) 0.09f else 0.055f)
    } else {
        Color.Black.copy(alpha = if (strong) 0.055f else 0.035f)
    }

/** 极细分割线，比 Material 的 Divider 更克制 */
@Composable
fun HairLine(modifier: Modifier = Modifier, alpha: Float = 0.08f) {
    val dark = LocalDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                if (dark) {
                    Color.White.copy(alpha = alpha)
                } else {
                    fluxPalette(false).stroke.copy(alpha = alpha * 1.1f)
                },
            ),
    )
}

/**
 * 区块标题 + 右侧可选操作。
 *
 * 视觉：左侧一根 3dp 的主色圆角短竖条（区块的"锚点"），标题用 DM Sans 半粗，
 * 字号比正文略小、字距略开 —— 更像分区标签而不是又一层标题。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(accent, accent.copy(alpha = 0.25f)),
                    ),
                ),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = onGlassColor(dark, emphasis = true),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
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
        animationSpec = FluxMotion.tween(FluxMotion.BASE),
        label = "sectionChevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.sm))
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = onGlassColor(dark, emphasis = true),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = FluxType.monoSmall,
                color = onGlassColor(dark, emphasis = false),
                modifier = Modifier
                    .clip(RoundedCornerShape(FluxRadius.xs))
                    .background(neutralFill(dark, strong = true))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
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
            enter = expandVertically(
                animationSpec = FluxMotion.tween(FluxMotion.BASE),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = FluxMotion.tween(FluxMotion.BASE)),
            exit = shrinkVertically(
                animationSpec = FluxMotion.tween(FluxMotion.FAST),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = FluxMotion.tween(FluxMotion.FAST)),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * 玻璃态图标按钮。
 *
 * 视觉：圆形玻璃片，40dp 命中区、20dp 图标。`withSurface = false` 时退化成
 * 一个"裸"命中区（查看器顶部那排就是这种，直接压在图片上）。
 */
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
                tint = (tint ?: onGlassColor(dark, emphasis = true))
                    .copy(alpha = if (enabled) 1f else 0.42f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (withSurface) {
        GlassSurface(
            modifier = modifier.size(40.dp),
            shape = CircleShape,
            backdrop = false,
            borderWidth = 0.8.dp,
            contentPadding = PaddingValues(0.dp),
        ) { button() }
    } else {
        Box(modifier = modifier) { button() }
    }
}

/**
 * 标签胶囊：色点 + 名称 + 可选计数。
 *
 * 视觉：选中态用标签自己的颜色做填充与描边，色点在未选中时也保留
 * （那是标签的唯一身份标识），只是把光晕收掉；计数用 DM Mono。
 */
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
    val shape = RoundedCornerShape(50)
    val background = if (selected) {
        accent.copy(alpha = if (dark) 0.24f else 0.15f)
    } else {
        neutralFill(dark)
    }
    val borderColor = if (selected) {
        accent.copy(alpha = if (dark) 0.62f else 0.46f)
    } else {
        fluxPalette(dark).stroke
    }
    val textColor = if (selected) {
        if (dark) Color.White else accent
    } else {
        onGlassColor(dark, emphasis = false)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = if (showDot) 8.dp else 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showDot) {
            // 未选中时色点带一圈同色光晕：远看是一颗"有颜色的点"，
            // 而不是一个 6dp 的实心方块
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (selected) 1f else 0.30f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.White.copy(alpha = 0.92f) else accent),
                )
            }
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
                style = FluxType.monoSmall,
                color = textColor.copy(alpha = 0.62f),
            )
        }
    }
}

/**
 * 指标卡（总览页 / 标签页用）。
 *
 * 视觉：**大数字**（Space Grotesk 34sp，等宽数字，跳动时不抖）压在标签之下，
 * 右上角一枚同色光晕的图标；卡片内左上有一团同色径向辉光，
 * 让四张统计卡各自带上自己的强调色，而卡片本体仍是中性玻璃。
 */
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
    GlassSurface(
        modifier = modifier,
        glow = accent,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        // 角落辉光：画刷按尺寸缓存，绘制阶段不分配对象
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val radius = maxOf(size.width, size.height) * 0.86f
                    val brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = if (dark) 0.20f else 0.13f),
                            0.5f to accent.copy(alpha = if (dark) 0.06f else 0.04f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(0f, 0f),
                        radius = radius,
                    )
                    onDrawBehind { drawRect(brush) }
                },
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = label,
                    // 这里**不能**用 FluxType.eyebrow：那是给拉丁文眉标准备的 1.4sp 字距，
                    // 套在中文上会把两三个字拆得七零八落（真机截图里"媒体总数"已经散开了）。
                    // 11sp + 0.2sp 是中文小标签能读清的上限。
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (icon != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = value,
                style = FluxType.numeralLarge,
                color = onGlassColor(dark, emphasis = true),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = FluxType.monoSmall,
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
 *
 * 视觉（极光玻璃）：
 *  - **渐变遮罩**两段式：上半一点极淡的冷色压顶（让浅色图片上的白色角标也读得清），
 *    下半是信息的承托层，从 38% 开始渐入，到 92% 收满。
 *  - **玻璃角标**：观看数、R18 都是半透明胶囊，描边极细，
 *    压在图上时四周自带一点暗色柔化，任何底图上都不会糊。
 *  - 底部名称用 DM Sans 半粗白字，标签行用 DM Mono 小字，两行之间有 3dp。
 *  - 选中态：主色薄纱 + 2dp 主色描边 + 右上角的对勾圆片。
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
    val context = LocalContext.current
    val palette = fluxPalette(dark)
    val tokens = LocalGlassTokens.current
    val ratio = aspectRatio.coerceIn(0.45f, 2.2f)
    val density = LocalDensity.current
    val cardWidthPx = with(density) { 132.dp.toPx() }.roundToInt()
    val shape = RoundedCornerShape(tokens.cornerRadius)

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
                    /*
                     * ImageRequest 用 remember 包住。不包的话每次重组都会 new 一个（网格滚动时
                     * 每帧都要重组十几张卡片），虽然 Coil 会因为相等而不会重启请求，
                     * 但这些对象全是白白产生的垃圾，滚动时持续给 GC 添活。
                     */
                    model = remember(imageUrl) {
                        ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(false)
                            .build()
                    },
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

            // ---- 渐变遮罩：顶部一点点冷色压顶 + 底部信息承托 ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        // 两段遮罩合并成一次绘制；画刷在尺寸变化时才重建
                        val top = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = if (dark) 0.36f else 0.24f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = size.height * 0.34f,
                        )
                        val bottom = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.30f to Color.Transparent,
                                0.70f to Color.Black.copy(alpha = 0.34f),
                                1f to Color.Black.copy(alpha = 0.78f),
                            ),
                            startY = 0f,
                            endY = size.height,
                        )
                        onDrawBehind {
                            drawRect(bottom)
                            drawRect(top)
                        }
                    },
            )

            if (isVideo) {
                GlassBadge(
                    modifier = Modifier.align(Alignment.Center).size(38.dp),
                    circle = true,
                    fillAlpha = 0.42f,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "播放视频",
                        tint = Color.White.copy(alpha = 0.96f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (views != null && views > 0) {
                GlassBadge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    fillAlpha = 0.40f,
                ) {
                    Text(
                        text = "▶ $views",
                        style = FluxType.monoSmall,
                        color = Color.White.copy(alpha = 0.94f),
                    )
                }
            }

            if (r18) {
                GlassBadge(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    pill = false,
                    fill = palette.danger.copy(alpha = if (dark) 0.88f else 0.92f),
                    fillAlpha = 0.90f,
                ) {
                    Text(
                        text = "R18",
                        style = FluxType.monoSmall,
                        color = contentOn(palette.danger),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty()) {
                    Text(
                        text = tags.take(3).joinToString(" · "),
                        style = FluxType.monoSmall,
                        color = Color.White.copy(alpha = 0.76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 液态玻璃下给卡片描一圈内高光，网格里立刻有"玻璃片"的层次。
            // 放在交互层之前：高光只是画，不该压在命中区之上。
            if (tokens.style != AppThemeStyle.DEFAULT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            // 画刷按尺寸缓存；高光只在顶边一带，往下迅速消失
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = tokens.highlightAlpha * (if (dark) 0.9f else 0.7f)),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height * 0.30f,
                            )
                            onDrawBehind { drawRect(brush) }
                        },
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, shape),
                )
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "已选择",
                    tint = contentOn(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
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
        }
    }
}

/**
 * 压在图片上的玻璃角标。
 *
 * 用半透明暗底 + 极细白描边，而不是纯色块 —— 这样无论底下是亮图还是暗图，
 * 角标都能读清，同时不会在网格里形成一堆刺眼的小色块。
 * [fill] 传入时用它做底色（R18 那种需要语义色的场合）。
 */
@Composable
private fun GlassBadge(
    modifier: Modifier = Modifier,
    pill: Boolean = true,
    /** 圆形底座（视频播放键那种），优先于 [pill] */
    circle: Boolean = false,
    fill: Color? = null,
    fillAlpha: Float = 0.42f,
    content: @Composable () -> Unit,
) {
    val shape = when {
        circle -> CircleShape
        pill -> RoundedCornerShape(50)
        else -> RoundedCornerShape(FluxRadius.xs)
    }
    val background = fill ?: Color.Black.copy(alpha = fillAlpha)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(0.6.dp, Color.White.copy(alpha = 0.16f), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.36f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White.copy(alpha = 0.94f),
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

/**
 * 空状态。
 *
 * 视觉：图标不再是"圆底 + 图标"，而是**线稿插画** ——
 * 四条虚线角括号围出一块空画框，中间是"一条地平线 + 两座远山 + 一颗低悬的日",
 * 日轮外圈带同色光晕，角上再点三粒星。整幅画只有描边，
 * 深浅色都从当前强调色与中性令牌里取，不引入硬编码色值。
 *
 * 性能：所有路径在 `drawWithCache` 里按尺寸构建，尺寸不变时不重建；
 * 绘制阶段只做 `drawPath`，不分配对象。
 */
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
    val palette = fluxPalette(dark)
    val rawAccent = MaterialTheme.colorScheme.primary
    // 强调色可能来自服务端配色，深浅底上都要能当描边用
    val accent = readableAccent(rawAccent, dark)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EmptyStateIllustration(accent = accent, ink = palette.text3, icon = icon)
        Spacer(modifier = Modifier.height(4.dp))
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
            Spacer(modifier = Modifier.height(4.dp))
            PrimaryActionButton(text = actionLabel, onClick = onAction, modifier = Modifier.width(200.dp))
        }
    }
}

private val EMPTY_ART_WIDTH = 168.dp
private val EMPTY_ART_HEIGHT = 116.dp

/** 空状态的线稿插画：虚线画框 + 地平线 + 远山 + 日轮 + 星点 */
@Composable
private fun EmptyStateIllustration(
    accent: Color,
    ink: Color,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier.size(EMPTY_ART_WIDTH, EMPTY_ART_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val stroke = 1.6f
                    val inset = stroke * 2f

                    val brackets = Path().apply {
                        val armX = w * 0.16f
                        val armY = h * 0.20f
                        // 左上
                        moveTo(inset, inset + armY); lineTo(inset, inset); lineTo(inset + armX, inset)
                        // 右上
                        moveTo(w - inset - armX, inset); lineTo(w - inset, inset); lineTo(w - inset, inset + armY)
                        // 右下
                        moveTo(w - inset, h - inset - armY); lineTo(w - inset, h - inset); lineTo(w - inset - armX, h - inset)
                        // 左下
                        moveTo(inset + armX, h - inset); lineTo(inset, h - inset); lineTo(inset, h - inset - armY)
                    }

                    val horizonY = h * 0.68f
                    val left = w * 0.16f
                    val right = w * 0.84f
                    val ground = Path().apply {
                        moveTo(left, horizonY)
                        lineTo(right, horizonY)
                    }
                    // 两座远山：一个来回的折线，收在同一水平线上
                    val ridge = Path().apply {
                        moveTo(left, horizonY)
                        lineTo(w * 0.34f, h * 0.40f)
                        lineTo(w * 0.46f, h * 0.56f)
                        lineTo(w * 0.60f, h * 0.28f)
                        lineTo(right, horizonY)
                    }
                    val sunCenter = Offset(w * 0.66f, h * 0.31f)
                    val sunRadius = h * 0.085f

                    val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f)
                    val bracketColor = ink.copy(alpha = 0.34f)
                    val groundColor = ink.copy(alpha = 0.42f)
                    val ridgeColor = accent.copy(alpha = 0.62f)
                    val sunColor = accent
                    val haloBrush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0.30f),
                            0.55f to accent.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                        ),
                        center = sunCenter,
                        radius = sunRadius * 3.4f,
                    )
                    val starColor = ink.copy(alpha = 0.55f)

                    onDrawBehind {
                        drawPath(brackets, bracketColor, style = Stroke(width = stroke, pathEffect = dash))
                        drawRect(
                            brush = haloBrush,
                            topLeft = Offset(sunCenter.x - sunRadius * 3.4f, sunCenter.y - sunRadius * 3.4f),
                            size = Size(sunRadius * 6.8f, sunRadius * 6.8f),
                        )
                        drawCircle(color = sunColor, radius = sunRadius, center = sunCenter)
                        drawCircle(
                            color = accent.copy(alpha = 0.45f),
                            radius = sunRadius * 1.9f,
                            center = sunCenter,
                            style = Stroke(width = stroke * 0.8f),
                        )
                        drawPath(ground, groundColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                        drawPath(ridge, ridgeColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                        // 三粒星：位置固定，不用随机数，避免每次重组跳位
                        drawCircle(starColor, radius = 1.6f, center = Offset(w * 0.26f, h * 0.22f))
                        drawCircle(starColor, radius = 1.2f, center = Offset(w * 0.46f, h * 0.14f))
                        drawCircle(starColor, radius = 1.4f, center = Offset(w * 0.82f, h * 0.52f))
                    }
                },
        )
        // 调用方给的图标只作为画框中心的一枚淡印记，保持各页面语义上的区分
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ink.copy(alpha = 0.30f),
            modifier = Modifier.size(22.dp),
        )
    }
}


/** 加载态：一枚带同色光晕的细环 + DM Mono 小字 */
@Composable
fun LoadingBox(text: String = "加载中…", modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .drawWithCache {
                    val brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.55f to Color.Transparent,
                            0.78f to accent.copy(alpha = 0.18f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f,
                    )
                    onDrawBehind { drawRect(brush) }
                },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.dp,
                color = accent,
                trackColor = accent.copy(alpha = 0.14f),
            )
        }
        Text(
            text = text,
            style = FluxType.monoSmall,
            color = onGlassColor(dark, emphasis = false),
        )
    }
}

/** 错误条（可重试）：危险色玻璃，右侧动作收成小胶囊 */
@Composable
fun ErrorBar(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluxRadius.md),
        tint = MaterialTheme.colorScheme.error,
        borderWidth = 1.dp,
        glow = MaterialTheme.colorScheme.error,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
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

/**
 * 主操作按钮（走品牌渐变）。
 *
 * 视觉：主色 → 次色横向渐变，上方叠一层白色微光（让按钮像一块"被照亮的玻璃"），
 * 底部有一道同色辉光投影；禁用时整体降到中性灰、文字与图标一起淡出；
 * 加载时左侧换成细环。按压有轻微缩放（动效只动 transform 与 opacity）。
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val ink = contentOn(primary)
    val shape = RoundedCornerShape(FluxRadius.md)
    val active = enabled && !loading
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = FluxMotion.tween(FluxMotion.FAST),
        label = "buttonAlpha",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && active) 0.975f else 1f,
        animationSpec = FluxMotion.tween(FluxMotion.FAST),
        label = "buttonPress",
    )
    val cornerPx = with(LocalDensity.current) { FluxRadius.md.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer {
                // 动效只动 transform：按压时轻微缩小
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onClick() },
            )
            .drawBehind {
                val fill = if (enabled) {
                    Brush.horizontalGradient(listOf(primary, secondary))
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color.Gray.copy(alpha = 0.55f),
                            Color.Gray.copy(alpha = 0.42f),
                        ),
                    )
                }
                drawRoundRect(brush = fill, cornerRadius = CornerRadius(cornerPx))
                // 顶部一层白色微光，让按钮像一块"被照亮的玻璃"
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        startY = 0f,
                        endY = size.height,
                    ),
                    cornerRadius = CornerRadius(cornerPx),
                )
            },
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
                    color = ink,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = ink.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 次级按钮（玻璃底）：中性玻璃 + 细描边，与主按钮同高时视觉上退一档 */
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
        modifier = modifier
            .height(40.dp)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(FluxRadius.sm),
        raised = true,
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
                    tint = onGlassColor(dark, enabled),
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

/**
 * 分段选择器（玻璃胶囊）。
 *
 * 视觉：一条中性玻璃轨道 + 3dp 内边距，选中段用主色实心药丸；
 * 底色用 animateColorAsState 渐变过去，切换时不会"闪一下"。
 */
@Composable
fun <T> PillSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    val ink = contentOn(accent)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(neutralFill(dark, strong = true))
            .border(0.8.dp, fluxPalette(dark).stroke, RoundedCornerShape(50))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) accent else Color.Transparent,
                animationSpec = FluxMotion.tween(FluxMotion.FAST),
                label = "pillBg",
            )
            Text(
                text = label(option),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) ink else onGlassColor(dark, emphasis = false),
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

/**
 * 带标签的进度行。
 *
 * 视觉：标签用 DM Sans、右侧细节（"3 / 12"这类）用 DM Mono；
 * 进度条 5dp，头部带一点同色辉光，确定进度时用动画平滑推进。
 */
@Composable
fun ProgressRow(
    label: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val dark = LocalDarkTheme.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    style = FluxType.monoSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
        }
        val fraction by animateFloatAsState(
            targetValue = (progress ?: 0f).coerceIn(0f, 1f),
            animationSpec = FluxMotion.tween(FluxMotion.BASE),
            label = "progressFraction",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.16f))
                .drawWithCache {
                    val head = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.62f), accent),
                    )
                    val width = size.width
                    onDrawBehind {
                        // 不确定进度画成一条走动的短光带，确定进度按比例填满
                        if (progress == null) {
                            val band = width * 0.34f
                            val start = (width + band) * fraction - band
                            drawRoundRect(
                                brush = head,
                                topLeft = Offset(start.coerceIn(-band, width), 0f),
                                size = Size(band.coerceAtMost(width).coerceAtLeast(0f), size.height),
                                cornerRadius = CornerRadius(size.height / 2f),
                            )
                        } else {
                            val lit = width * fraction
                            if (lit > 0f) {
                                drawRoundRect(
                                    brush = head,
                                    size = Size(lit.coerceAtMost(width), size.height),
                                    cornerRadius = CornerRadius(size.height / 2f),
                                )
                            }
                        }
                    }
                },
        )
    }
}

/** 小徽标：DM Mono + 同色极淡底，比 Material 的 Badge 安静得多 */
@Composable
fun MiniBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = FluxType.monoSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(FluxRadius.xs))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 键值行（设置页大量使用）：左标签 DM Sans、右值 DM Mono */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val dark = LocalDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = onGlassColor(dark, emphasis = false),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = FluxType.mono,
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
    value >= 1f -> Success
    else -> MaterialTheme.colorScheme.primary
}

/** 横向间距helper */
@Composable
fun HGap(width: Int) = Spacer(modifier = Modifier.width(width.dp))

/** 纵向间距 helper */
@Composable
fun VGap(height: Int) = Spacer(modifier = Modifier.height(height.dp))

package com.fluxframe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxframe.app.fluidcloud.CapsuleState
import com.fluxframe.app.fluidcloud.CapsuleTone
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.Danger
import com.fluxframe.app.ui.theme.FluxMotion
import com.fluxframe.app.ui.theme.FluxRadius
import com.fluxframe.app.ui.theme.FluxType
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalGlassTokens
import com.fluxframe.app.ui.theme.Success
import com.fluxframe.app.ui.theme.contentOn
import com.fluxframe.app.ui.theme.fluxPalette
import com.fluxframe.app.ui.theme.onGlassColor

/* ============================================================================
 * 极光玻璃（Aurora Glass）· 壳层组件
 *
 * 顶栏、底栏、胶囊、提示、弹窗与设置行。视觉规则统一为三条：
 *  1. 光源只有背景流体 —— 面板自己不带颜色，只有染色、顶边高光与一圈细描边。
 *  2. 层次靠透明度与留白，不靠粗边框与堆色；强调色只在指示器、角标、辉光上出现。
 *  3. 数字与数据用 DM Mono，大标题与大数字用 Space Grotesk，正文用 DM Sans。
 *
 * 性能：不使用 Modifier.blur；渐变画刷一律 remember(依赖) 缓存；
 * 绘制阶段不分配对象；相位等 State 只在绘制 lambda 里读取。
 * ========================================================================== */

/** 顶栏与底栏里图标按钮的统一命中区 */
private val CHROME_ACTION_SIZE = 32.dp
private val CHROME_ICON_SIZE = 19.dp

/**
 * 顶部栏（大标题版）。
 *
 * 与旧版的区别：
 *  - **不再常驻玻璃底板**。只有 [scrolled]（内容已滚动）时才浮出一层玻璃，
 *    停在顶部时完全透明 —— 视觉上"没有上栏"，内容直接顶到状态栏下面。
 *  - **大标题 + 副标题**。标题用 Space Grotesk 22sp（[FluxType.pageTitle]），
 *    副标题是 DM Mono 小字，用来放"共 128 项""只读"这类计数与状态；
 *    没有副标题时整栏退回单行高度，不白白占两行。
 *  - **玻璃搜索**（[search]）与大标题同排的那一行之下，是一个圆角胶囊：
 *    DM Sans 输入、前置放大镜、右侧清空。它只在调用方传入时出现，
 *    所以不需要搜索的页面（标签、设置）完全不受影响。
 *  - **有返回键时标题左对齐**，无返回键时标题仍在左（不居中），
 *    这样长标题不会被右侧按钮挤掉。
 *  - 动作按钮 32dp，最多三个；再多请用 [overflow]。
 */
@Composable
fun FluxTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrolled: Boolean = false,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    search: TopBarSearch? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(bottomStart = FluxRadius.lg, bottomEnd = FluxRadius.lg)
    Box(modifier = modifier.fillMaxWidth()) {
        if (scrolled) {
            GlassSurface(
                modifier = Modifier.matchParentSize(),
                shape = shape,
                backdrop = true,
                borderWidth = 0.6.dp,
                contentPadding = PaddingValues(0.dp),
            ) {}
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, bottom = if (subtitle != null || search != null) 8.dp else 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (onBack != null) {
                    CompactIconButton(
                        icon = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                }
                leading?.invoke()
                Text(
                    text = title,
                    style = FluxType.pageTitle,
                    color = onGlassColor(dark, emphasis = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    content = actions,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = FluxType.monoSmall,
                    color = onGlassColor(dark, emphasis = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, bottom = if (search != null) 4.dp else 0.dp),
                )
            }
            if (search != null) {
                GlassSearchField(
                    search = search,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp).padding(top = 6.dp),
                )
            }
        }
    }
}

private val BAR_HEIGHT = 46.dp

/** 顶栏搜索的状态与回调。用一个小数据类打包，免得组件签名越加越长。 */
data class TopBarSearch(
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String = "搜索",
    val onSubmit: (() -> Unit)? = null,
)

/**
 * 顶栏里的玻璃搜索胶囊。
 *
 * 用 BasicTextField 自绘而不是 Material 的 TextField：后者的浮标标签与底部描边
 * 在任何一种玻璃风格下都显得"外挂"。前置放大镜与右侧清空键都是 [CHROME_ICON_SIZE]，
 * 高度 38dp 正好落在拇指区。
 */
@Composable
private fun GlassSearchField(
    search: TopBarSearch,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val palette = fluxPalette(dark)
    val shape = RoundedCornerShape(50)
    val fill = if (dark) palette.glass2 else palette.glass1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, palette.stroke, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = onGlassColor(dark, emphasis = false),
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (search.value.isEmpty()) {
                Text(
                    text = search.placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGlassColor(dark, emphasis = false).copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = search.value,
                onValueChange = search.onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = onGlassColor(dark, emphasis = true)),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { search.onSubmit?.invoke() },
                    onDone = { search.onSubmit?.invoke() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            visible = search.value.isNotEmpty(),
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清空",
                tint = onGlassColor(dark, emphasis = false),
                modifier = Modifier.size(15.dp).clickable { search.onValueChange("") },
            )
        }
    }
}

/** 紧凑图标按钮：32dp 命中区，无底板，专供紧凑顶栏使用 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val dark = LocalDarkTheme.current
    Box(
        modifier = modifier
            .size(CHROME_ACTION_SIZE)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: onGlassColor(dark, emphasis = true),
            modifier = Modifier.size(CHROME_ICON_SIZE),
        )
    }
}

/**
 * 底部导航栏（悬浮胶囊 + 弹性滑动指示器）。
 *
 * 造型：一条**脱离屏幕边缘的圆角胶囊**，两侧留 20dp。
 *
 * 与旧版（每项自己画药丸）的区别是**只有一个指示器**：
 * 一枚玻璃质感的圆角片，在 [selectedKey] 变化时用 [FluxMotion.spring] 滑到新位置，
 * 换页时眼睛能跟着它走。指示器宽度按格子的 68% 算，格子数由 [items] 决定，
 * 所以 3 格与 5 格都不会跑偏。选中项图标换成主色并把标签展开，
 * 未选中项只留图标 —— 整条栏的视觉重量集中在当前位置。
 *
 * `GlassSurface` 负责玻璃底、顶边高光与彩色投影；滑动切换页面的手势在这里处理，
 * 与原实现一致（向左滑 = 去右侧页面）。
 */
@Composable
fun FluxBottomBar(
    items: List<BottomNavItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    /** -1 = 去左侧页面，+1 = 去右侧页面 */
    onSwipe: (Int) -> Unit = {},
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val haptics = LocalHapticFeedback.current
    var dragDistance by remember { mutableStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 44.dp.toPx() }

    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val slotCount = items.size.coerceAtLeast(1)

    // 指示器只跟"选中项中心"这一个数走：格子是等分的，中心即 (index + .5) / count
    val fraction by animateDpAsState(
        targetValue = ((selectedIndex + 0.5f) / slotCount).dp,
        animationSpec = FluxMotion.spring(),
        label = "navIndicator",
    )
    val trackWidth = with(LocalDensity.current) { trackWidthPx.toDp() }
    val indicatorWidth = (trackWidth / slotCount) * 0.68f
    val indicatorOffset = (trackWidth * fraction.value - indicatorWidth / 2f).coerceAtLeast(0.dp)
    val indicatorVisible = trackWidthPx > 0

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            // 悬浮件必须离开屏幕下沿。navigationBarsPadding 在沉浸式/手势导航下可能为 0，
            // 那样胶囊会贴着底边；这里垫一层固定下边距，让"浮起来"这件事不依赖系统栏状态。
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)
            .pointerInput(onSwipe, swipeThreshold) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragCancel = { dragDistance = 0f },
                    onDragEnd = {
                        when {
                            dragDistance <= -swipeThreshold -> {
                                if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSwipe(1)
                            }
                            dragDistance >= swipeThreshold -> {
                                if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSwipe(-1)
                            }
                        }
                        dragDistance = 0f
                    },
                )
            },
        shape = RoundedCornerShape(FluxRadius.xl),
        backdrop = true,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (indicatorVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = indicatorOffset)
                        .size(width = indicatorWidth, height = 42.dp)
                        .clip(RoundedCornerShape(FluxRadius.md))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.18f else 0.12f))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.34f else 0.22f),
                            shape = RoundedCornerShape(FluxRadius.md),
                        ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onSizeChanged { trackWidthPx = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val selected = item.key == selectedKey
                    val contentColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        onGlassColor(dark, emphasis = false)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(FluxRadius.md))
                            .clickable {
                                if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(item.key)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            // 底栏一律用矢量图标。曾经试过把图片库/视频库换成库里的缩略图，
                            // 但那会让五个图标风格不一致（三个线稿 + 两个照片），
                            // 而且在深色磨砂底上照片会显得很脏 —— 改回统一图标。
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            if (item.badge != null && item.badge > 0) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = 7.dp, y = (-4).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(
                                animationSpec = FluxMotion.tween(FluxMotion.BASE),
                                expandFrom = Alignment.Start,
                            ) + fadeIn(animationSpec = FluxMotion.tween(FluxMotion.FAST)),
                            exit = shrinkHorizontally(
                                animationSpec = FluxMotion.tween(FluxMotion.FAST),
                                shrinkTowards = Alignment.Start,
                            ) + fadeOut(animationSpec = FluxMotion.tween(FluxMotion.FAST)),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class BottomNavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val badge: Int? = null,
)

/**
 * 应用内「流体云」胶囊。
 *
 * 这是 OPPO 流体云 / 系统实况通知的应用内镜像：同一份 [CapsuleState]，
 * 因此三处显示的进度永远一致。即使设备不支持流体云，用户在前台也能看到
 * 同样的即时反馈 —— 这也是三套主题里最出彩的一个组件。
 *
 * 视觉：状态色只在**图标底座的光晕、百分比角标与进度条**上出现，
 * 胶囊本体保持中性玻璃；进度条改成自绘，头部带一点渐隐，比 Material 的
 * 三段式轨道更安静。
 */
@Composable
fun FluidCapsule(
    state: CapsuleState?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(animationSpec = FluxMotion.tween(FluxMotion.BASE)) { -it } +
            fadeIn(animationSpec = FluxMotion.tween(FluxMotion.BASE)),
        exit = slideOutVertically(animationSpec = FluxMotion.tween(FluxMotion.FAST)) { -it } +
            fadeOut(animationSpec = FluxMotion.tween(FluxMotion.FAST)),
        modifier = modifier,
    ) {
        val current = state ?: return@AnimatedVisibility
        val dark = LocalDarkTheme.current
        val accent = when (current.tone) {
            CapsuleTone.SUCCESS -> Success
            CapsuleTone.ERROR -> Danger
            CapsuleTone.INFO -> MaterialTheme.colorScheme.primary
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            tint = accent.copy(alpha = if (dark) 0.10f else 0.06f),
            shape = RoundedCornerShape(FluxRadius.lg),
            backdrop = true,
            borderWidth = 1.dp,
            glow = accent,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapsuleGlyph(active = current.active, indeterminate = current.indeterminate, accent = accent) {
                    Icon(
                        imageVector = current.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = current.title.ifBlank { current.kind.label },
                            style = MaterialTheme.typography.labelLarge,
                            color = onGlassColor(dark, emphasis = true),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (current.percentText.isNotEmpty()) {
                            Text(
                                text = current.percentText,
                                style = FluxType.monoSmall,
                                color = accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FluxRadius.xs))
                                    .background(accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (current.subtitle.isNotBlank()) {
                        Text(
                            text = current.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current.active) {
                        Spacer(modifier = Modifier.height(2.dp))
                        CapsuleProgress(accent = accent, progress = current.progress)
                    }
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "收起",
                    tint = onGlassColor(dark, emphasis = false),
                    modifier = Modifier.size(16.dp).clickable { onDismiss() },
                )
            }
        }
    }
}

/** 胶囊左侧的状态图标底座：光晕 + 可选的不确定进度环 */
@Composable
private fun CapsuleGlyph(
    active: Boolean,
    indeterminate: Boolean,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val pulse by animateFloatAsState(
        targetValue = if (active && indeterminate) 1f else 0.72f,
        animationSpec = FluxMotion.tween(FluxMotion.SLOW),
        label = "capsulePulse",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (dark) 0.16f else 0.11f))
            .drawWithCache {
                // 光晕画刷随尺寸缓存；绘制阶段只读 pulse 的当前值
                val brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension * 0.62f,
                )
                onDrawBehind { drawRect(brush) }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (active && indeterminate) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = accent.copy(alpha = pulse),
            )
        } else {
            content()
        }
    }
}

/** 自绘的细进度条：轨道极淡、填充色两端渐隐，比 Material 的三段式更安静 */
@Composable
private fun CapsuleProgress(accent: Color, progress: Float?) {
    val dark = LocalDarkTheme.current
    val fraction by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = FluxMotion.tween(FluxMotion.BASE),
        label = "capsuleProgress",
    )
    val track = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .drawWithCache {
                // 填充画刷只跟强调色有关，尺寸变化时才重建
                val fill = Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.55f),
                        accent,
                        accent.copy(alpha = 0.86f),
                    ),
                )
                val width = size.width
                onDrawBehind {
                    val lit = if (progress == null) width * 0.32f else width * fraction
                    if (lit > 0f) {
                        drawRoundRect(
                            brush = fill,
                            size = androidx.compose.ui.geometry.Size(lit.coerceAtMost(width), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                    }
                }
            },
    )
}

/**
 * 悬浮提示（替代 Toast，观感与玻璃主题统一）。
 *
 * 形状是胶囊而不是方角，尺寸跟着文字走，所以短提示不会拉成一条长条。
 */
@Composable
fun FluxToast(
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(animationSpec = FluxMotion.tween(FluxMotion.BASE)) +
            slideInVertically(animationSpec = FluxMotion.tween(FluxMotion.BASE)) { it / 3 },
        exit = fadeOut(animationSpec = FluxMotion.tween(FluxMotion.FAST)) +
            slideOutVertically(animationSpec = FluxMotion.tween(FluxMotion.FAST)) { it / 3 },
        modifier = modifier,
    ) {
        val dark = LocalDarkTheme.current
        GlassSurface(
            shape = RoundedCornerShape(FluxRadius.md),
            backdrop = true,
            borderWidth = 1.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = true),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 通用确认对话框。
 *
 * 视觉：玻璃面板 + 顶部一条主色细辉光；标题用 Space Grotesk，
 * 双键并排且等宽。危险操作把主色换成 [Danger]，
 * 并按底色亮度自动选白字或墨字（深色主题下危险色偏亮，白字会糊）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val tokens = LocalGlassTokens.current
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val ink = if (destructive) contentOn(accent) else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp)
            .clip(RoundedCornerShape(tokens.cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = true,
            shape = RoundedCornerShape(tokens.cornerRadius),
            borderWidth = 1.dp,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = onGlassColor(dark, emphasis = true),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGlassColor(dark, emphasis = false),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = dismissLabel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (destructive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(FluxRadius.sm))
                                    .background(accent)
                                    .clickable { onConfirm() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = confirmLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ink,
                                )
                            }
                        } else {
                            PrimaryActionButton(text = confirmLabel, onClick = onConfirm)
                        }
                    }
                }
            }
            // 面板顶边的主色细辉光：让弹窗在深色背景上"浮起来"，比整片染色克制
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val brush = Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.42f,
                        )
                        onDrawBehind { drawRect(brush) }
                    },
            )
        }
    }
}

/** 通用文本输入对话框 */
@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String = "",
    label: String = "",
    confirmLabel: String = "确定",
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val dark = LocalDarkTheme.current
    val tokens = LocalGlassTokens.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = true,
            shape = RoundedCornerShape(tokens.cornerRadius),
            borderWidth = 1.dp,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = onGlassColor(dark, emphasis = true),
                )
                GlassTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = label,
                    singleLine = singleLine,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(
                            text = confirmLabel,
                            onClick = { onConfirm(value) },
                            enabled = value.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}

/** 卡片的强调描边（用于危险操作或选中态） */
@Composable
fun accentBorder(color: Color): Modifier = Modifier.border(
    width = 1.dp,
    brush = Brush.linearGradient(listOf(color.copy(alpha = 0.7f), color.copy(alpha = 0.2f))),
    shape = RoundedCornerShape(LocalGlassTokens.current.cornerRadius),
)

/**
 * 玻璃态输入框。
 * 不用 Material 的 OutlinedTextField：它的浮标标签与描边在三套玻璃主题下
 * 都会显得"外挂"，这里用 BasicTextField 自绘，视觉完全统一。
 *
 * 视觉：圆角 [FluxRadius.sm]，1dp 中性描边 + 极淡填充；
 * 前置图标、占位文字与正文分三层灰阶。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    maxLines: Int = if (singleLine) 1 else 4,
) {
    val dark = LocalDarkTheme.current
    val palette = fluxPalette(dark)
    val shape = RoundedCornerShape(FluxRadius.sm)
    val textColor = onGlassColor(dark, emphasis = true)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) palette.glass1 else palette.glass1.copy(alpha = 0.72f))
            .border(1.dp, palette.stroke, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = onGlassColor(dark, emphasis = false),
                    modifier = Modifier.size(17.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onGlassColor(dark, emphasis = false).copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (enabled) textColor else textColor.copy(alpha = 0.45f),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onImeAction?.invoke() },
                        onGo = { onImeAction?.invoke() },
                        onSend = { onImeAction?.invoke() },
                        onSearch = { onImeAction?.invoke() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 开关行（设置页用）。
 *
 * 视觉：整行可点，右侧用 Material3 的 [Switch] 但把颜色收进令牌 ——
 * 选中的轨道用主色、滑块用底色，未选中只留中性轨道，避免默认配色里的紫色。
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val dark = LocalDarkTheme.current
    val palette = fluxPalette(dark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.sm))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = onGlassColor(dark, enabled),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = contentOn(MaterialTheme.colorScheme.primary),
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = if (dark) palette.text2 else Color.White,
                uncheckedTrackColor = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.09f),
                uncheckedBorderColor = palette.stroke,
            ),
        )
    }
}

/**
 * 可点击的设置项行。
 *
 * 视觉：左侧方形图标底座（可选），中间标题 + 说明，右侧当前值 + 箭头。
 * 值用 DM Mono，与标题的 DM Sans 拉开层次；箭头只在非危险项出现。
 */
@Composable
fun ClickableRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    tint: Color? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        else -> onGlassColor(dark, emphasis = true)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.sm))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(FluxRadius.xs))
                    .background((tint ?: contentColor).copy(alpha = if (dark) 0.16f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint ?: contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = FluxType.monoSmall,
                color = onGlassColor(dark, emphasis = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!destructive) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = onGlassColor(dark, emphasis = false).copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

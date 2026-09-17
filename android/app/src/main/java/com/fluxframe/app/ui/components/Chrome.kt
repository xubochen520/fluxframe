package com.fluxframe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.fluidcloud.CapsuleState
import com.fluxframe.app.fluidcloud.CapsuleTone
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalGlassTokens
import com.fluxframe.app.ui.theme.onGlassColor

/**
 * 顶部栏（紧凑版）。
 *
 * 与旧版的区别：
 *  - **不再常驻玻璃底板**。只有在 [scrolled]（内容已滚动）时才浮出一层玻璃，
 *    停在顶部时完全透明 —— 视觉上"没有上栏"，内容直接顶到状态栏下面。
 *  - **高度固定 48dp**（旧版是 68dp 且带副标题）。副标题把账号信息塞在标题下，
 *    实际没人看，却常年占掉两行高度；账号信息移到设置页。
 *  - **有返回键时标题左对齐**，无返回键时标题仍在左（不再居中），
 *    这样长标题不会被右侧按钮挤掉。
 *  - 动作按钮收窄到 34dp，最多三个；再多请用 [overflow]。
 */
@Composable
fun FluxTopBar(
    title: String,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .padding(horizontal = 6.dp),
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
                style = MaterialTheme.typography.titleMedium,
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
    }
}

/** 紧凑图标按钮：34dp 命中区，无底板，专供紧凑顶栏使用 */
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
            .size(34.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: onGlassColor(dark, emphasis = true),
            modifier = Modifier.size(19.dp),
        )
    }
}

private val BAR_HEIGHT = 48.dp

/**
 * 底部导航栏（悬浮胶囊）。
 *
 * 造型参考 SukiSU Ultra 的底栏：一条**脱离屏幕边缘的圆角胶囊**，
 * 选中项展开成「图标 + 文字」的实心药丸，未选中项只留图标 ——
 * 于是整条栏的视觉重量集中在当前位置，比五个"图标+文字"平均用力要清爽得多。
 */
@Composable
fun FluxBottomBar(
    items: List<BottomNavItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        backdrop = true,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
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
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.20f else 0.13f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(item.key) }
                        .padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        if (item.thumbnail != null) {
                            // 半透明地露出库里的内容：选中时提亮，未选中时压低，
                            // 既看得出"这是图片/视频"，又不会抢当前页的注意力
                            MediaThumb(
                                item = item.thumbnail,
                                size = 22.dp,
                                alpha = if (selected) 0.95f else 0.55f,
                                shape = RoundedCornerShape(6.dp),
                                placeholderIcon = item.icon,
                                placeholderTint = contentColor,
                            )
                        } else {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut(),
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 0.dp),
                        )
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
    /**
     * 用一张真实媒体缩略图代替矢量图标（图片库 / 视频库用）。
     * 为空时退回 [icon]，所以库空着也不会出现一个空洞。
     */
    val thumbnail: ImageItem? = null,
)

/**
 * 应用内「流体云」胶囊。
 *
 * 这是 OPPO 流体云 / 系统实况通知的应用内镜像：同一份 [CapsuleState]，
 * 因此三处显示的进度永远一致。即使设备不支持流体云，用户在前台也能看到
 * 同样的即时反馈 —— 这也是三套主题里最出彩的一个组件。
 */
@Composable
fun FluidCapsule(
    state: CapsuleState?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val current = state ?: return@AnimatedVisibility
        val dark = LocalDarkTheme.current
        val accent = when (current.tone) {
            CapsuleTone.SUCCESS -> Color(0xFF22C55E)
            CapsuleTone.ERROR -> Color(0xFFEF4444)
            CapsuleTone.INFO -> MaterialTheme.colorScheme.primary
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            borderWidth = 1.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (current.active && current.indeterminate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                    } else {
                        Icon(
                            imageVector = current.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp),
                        )
                    }
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
                            MiniBadge(text = current.percentText, color = accent)
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
                        if (current.indeterminate) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                                color = accent,
                                trackColor = accent.copy(alpha = 0.16f),
                            )
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (current.progress ?: 0f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                                color = accent,
                                trackColor = accent.copy(alpha = 0.16f),
                            )
                        }
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

/** 悬浮提示（替代 Toast，观感与玻璃主题统一） */
@Composable
fun FluxToast(
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        val dark = LocalDarkTheme.current
        GlassSurface(
            shape = RoundedCornerShape(14.dp),
            backdrop = true,
            borderWidth = 1.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = true),
            )
        }
    }
}

/** 通用确认对话框 */
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp)
            .clip(RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop = true,
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.error)
                                    .clickable { onConfirm() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = confirmLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            PrimaryActionButton(text = confirmLabel, onClick = onConfirm)
                        }
                    }
                }
            }
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
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.04f),
                        )
                        .border(
                            1.dp,
                            if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isEmpty() && label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onGlassColor(dark, emphasis = false).copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = singleLine,
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyMedium.copy(color = onGlassColor(dark, emphasis = true)),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    maxLines: Int = if (singleLine) 1 else 4,
) {
    val dark = LocalDarkTheme.current
    val textColor = onGlassColor(dark, emphasis = true)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.035f))
            .border(
                1.dp,
                if (dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.08f),
                RoundedCornerShape(13.dp),
            )
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
                        color = onGlassColor(dark, emphasis = false).copy(alpha = 0.55f),
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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (isPassword) {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { onImeAction?.invoke() },
                        onGo = { onImeAction?.invoke() },
                        onSend = { onImeAction?.invoke() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 开关行（设置页用） */
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
    Row(
        modifier = modifier
            .fillMaxWidth()
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
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 可点击的设置项行 */
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
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint ?: contentColor,
                modifier = Modifier.size(18.dp),
            )
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
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = onGlassColor(dark, emphasis = false).copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}


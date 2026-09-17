package com.fluxframe.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.core.prefs.AppColorPalette
import com.fluxframe.app.core.util.formatBytes
import com.fluxframe.app.data.model.AiStatus
import com.fluxframe.app.data.model.EngineStatus
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.ClickableRow
import com.fluxframe.app.ui.components.ConfirmDialog
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.KeyValueRow
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.ProgressRow
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.components.SwitchRow
import com.fluxframe.app.ui.components.TextPromptDialog
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统设置。
 *
 * 这里刻意把「本机偏好」（外观、主题、网格列数）与「服务端配置」（上传上限、
 * AI 引擎、B站登录）分开：前者立即生效、不需要网络；后者只有管理员能改，
 * 保存后会重新拉取一次以显示真实的服务端状态。
 */
@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit,
    onOpenDeepseek: () -> Unit,
    onLoggedOut: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val server by container.prefs.server.collectAsStateWithLifecycle()
    val session by container.sessionStore.state.collectAsStateWithLifecycle()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    val saving by container.settingsStore.saving.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()
    val isAdmin = session.user?.isAdmin == true

    var showPassword by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var ffmpeg by remember { mutableStateOf<EngineStatus?>(null) }
    var aiStatus by remember { mutableStateOf<AiStatus?>(null) }
    var showBili by remember { mutableStateOf(false) }
    var uploadLimitInput by remember { mutableStateOf("") }
    var recycleDaysInput by remember { mutableStateOf("") }
    var siteNameInput by remember { mutableStateOf("") }
    var platformTokenInput by remember { mutableStateOf("") }
    // 视频缩略帧位置（秒）。用户输入即生效，非法输入不覆盖已保存的值
    var posterSecondsInput by remember { mutableStateOf(uiPrefs.videoPosterSeconds.toString()) }

    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            container.prefs.updateUi { it.copy(backgroundImageUri = uri.toString()) }
            onToast("背景图片已应用")
        }
    }

    // 通知权限：Android 13+ 必须显式授予，否则实况更新/通知都发不出去
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onToast(if (granted) "已授予通知权限" else "未授予，进度只在应用内显示")
    }
    var permissionAsked by remember { mutableStateOf(false) }
    val askNotificationPermission: () -> Unit = {
        permissionAsked = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onToast("当前系统无需该权限")
        }
    }

    LaunchedEffect(Unit) {
        // /api/settings 只对管理员开放，普通用户不必打这一枪
        if (isAdmin) {
            container.settingsStore.refresh()
            container.adminRepository.ffmpegStatus().onSuccess { ffmpeg = it }
            container.adminRepository.aiStatus().onSuccess { aiStatus = it }
        }
    }

    LaunchedEffect(settings) {
        settings?.let {
            if (uploadLimitInput.isBlank()) uploadLimitInput = it.uploadLimitMb.toString()
            if (recycleDaysInput.isBlank()) recycleDaysInput = it.recycleRetentionDays.toString()
            if (siteNameInput.isBlank()) siteNameInput = it.siteName
        }
    }

    // 第一次进设置页时顺手把通知权限要一次 —— 没有它，实况更新与通知都发不出去
    LaunchedEffect(Unit) {
        if (!permissionAsked && !container.fluidCloud.capability.notificationsAllowed) {
            askNotificationPermission()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /* ------------------------------ 外观 ------------------------------ */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        Text(
                            text = "外观",
                            style = MaterialTheme.typography.titleSmall,
                            color = onGlassColor(dark, emphasis = true),
                        )
                    }
                    AppThemeStyle.entries.forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { container.prefs.updateUi { it.copy(themeStyle = style) } }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiPrefs.themeStyle == style) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            onGlassColor(dark, false).copy(alpha = 0.18f)
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiPrefs.themeStyle == style) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onGlassColor(dark, emphasis = true),
                                    fontWeight = if (uiPrefs.themeStyle == style) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    text = style.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                            }
                        }
                    }
                    com.fluxframe.app.ui.components.HairLine(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "配色",
                        style = MaterialTheme.typography.labelMedium,
                        color = onGlassColor(dark, emphasis = true),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        AppColorPalette.entries.forEach { palette ->
                            val selected = uiPrefs.colorPalette == palette
                            Text(
                                text = palette.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) Color.White else onGlassColor(dark, emphasis = true),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else onGlassColor(dark, false).copy(alpha = 0.10f),
                                    )
                                    .clickable { container.prefs.updateUi { it.copy(colorPalette = palette) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Text(
                        text = uiPrefs.colorPalette.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryActionButton(
                                text = if (uiPrefs.backgroundImageUri == null) "选择背景图片" else "更换背景图片",
                                onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (uiPrefs.backgroundImageUri != null) {
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "移除背景",
                                    onClick = {
                                        container.prefs.updateUi { it.copy(backgroundImageUri = null) }
                                        onToast("已恢复流体背景")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    if (uiPrefs.backgroundImageUri != null) {
                        Text(
                            text = "背景图片亮度 ${(uiPrefs.backgroundImageOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        Slider(
                            value = uiPrefs.backgroundImageOpacity,
                            onValueChange = { value ->
                                container.prefs.updateUi { it.copy(backgroundImageOpacity = value) }
                            },
                            valueRange = 0.2f..1f,
                        )
                    }
                    com.fluxframe.app.ui.components.HairLine(modifier = Modifier.padding(vertical = 4.dp))
                    SwitchRow(
                        title = "深色模式",
                        checked = uiPrefs.darkMode,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(darkMode = value) } },
                    )
                    SwitchRow(
                        title = "背景动效",
                        description = "关闭可省电，背景停在静态构图",
                        checked = uiPrefs.animationsEnabled,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(animationsEnabled = value) } },
                    )
                    SwitchRow(
                        title = "底栏震动反馈",
                        description = "点击底栏项目或按住横向切换时轻微震动",
                        checked = uiPrefs.bottomBarHapticsEnabled,
                        onCheckedChange = { value ->
                            container.prefs.updateUi { it.copy(bottomBarHapticsEnabled = value) }
                        },
                    )
                    SwitchRow(
                        title = "玻璃噪点纹理",
                        description = "亚克力与液态玻璃的颗粒感",
                        checked = uiPrefs.noiseEnabled,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(noiseEnabled = value) } },
                    )
                    SwitchRow(
                        title = "视频预览静音",
                        checked = uiPrefs.muteVideoPreview,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(muteVideoPreview = value) } },
                    )
                    SwitchRow(
                        title = "视频缩略图取真实帧",
                        description = "按需分段读取视频取一帧作为封面；关掉可省流量",
                        checked = uiPrefs.videoPosterEnabled,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(videoPosterEnabled = value) } },
                    )
                    if (uiPrefs.videoPosterEnabled) {
                        // 取帧位置可自定义：默认 0.5 秒（避开片头黑场），想要片中画面就改大
                        GlassTextField(
                            value = posterSecondsInput,
                            onValueChange = { input ->
                                posterSecondsInput = input
                                com.fluxframe.app.core.prefs.AppPreferences.parsePosterSeconds(input)?.let { parsed ->
                                    if (parsed != uiPrefs.videoPosterSeconds) {
                                        container.prefs.updateUi { it.copy(videoPosterSeconds = parsed) }
                                        // 取帧位置变了，旧封面作废（缓存键里也带了秒数，这里顺手清盘）
                                        container.mediaRepository.clearVideoPosterCache()
                                    }
                                }
                            },
                            placeholder = "缩略帧位置（秒，0–600，支持小数，如 0.5 / 3 / 12.5）",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0f, 0.5f, 1f, 3f, 5f).forEach { preset ->
                                val label = if (preset == 0f) "首帧" else "${preset}s"
                                val selected = kotlin.math.abs(uiPrefs.videoPosterSeconds - preset) < 0.001f
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary else onGlassColor(dark, false),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            } else {
                                                onGlassColor(dark, false).copy(alpha = 0.10f)
                                            },
                                        )
                                        .clickable {
                                            posterSecondsInput = preset.toString()
                                            container.prefs.updateUi { it.copy(videoPosterSeconds = preset) }
                                            container.mediaRepository.clearVideoPosterCache()
                                            onToast("缩略帧位置已设为 ${if (preset == 0f) "首帧" else "$preset 秒"}")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                        val posterInvalid = com.fluxframe.app.core.prefs.AppPreferences
                            .parsePosterSeconds(posterSecondsInput) == null
                        if (posterInvalid) {
                            Text(
                                text = "请输入 0–600 之间的秒数（可带小数）；当前不合法，已保留上一次的值。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    SwitchRow(
                        title = "顶栏随滚动收起",
                        description = "滚动时收起应用顶栏；系统状态栏保持稳定，避免下拉黑闪",
                        checked = uiPrefs.autoHideHeader,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(autoHideHeader = value) } },
                    )
                    SwitchRow(
                        title = "隐藏手势小白条",
                        description = "沉浸式全屏；从屏幕底部边缘上滑可临时唤出",
                        checked = uiPrefs.immersiveMode,
                        onCheckedChange = { value -> container.prefs.updateUi { it.copy(immersiveMode = value) } },
                    )
                    ClickableRow(
                        title = "每行图片数",
                        value = "${uiPrefs.gridColumns} 张",
                        onClick = {
                            val next = if (uiPrefs.gridColumns >= 5) 2 else uiPrefs.gridColumns + 1
                            container.prefs.updateUi { it.copy(gridColumns = next) }
                        },
                    )
                }
            }
        }

        /* ---------------------------- 流体云 ---------------------------- */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Waves, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(17.dp))
                        Text(
                            text = "流体云 / 实况通知",
                            style = MaterialTheme.typography.titleSmall,
                            color = onGlassColor(dark, emphasis = true),
                            modifier = Modifier.weight(1f),
                        )
                        val capability = container.fluidCloud.capability
                        MiniBadge(
                            text = capability.badge,
                            color = if (capability.oppoBound) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = container.fluidCloud.capability.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                    // 诊断行：流体云不出卡时，这一行会直接指出卡在哪一步
                    Text(
                        text = container.fluidCloud.diagnostic,
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = true),
                    )
                    SwitchRow(
                        title = "上传/提取时显示实时进度",
                        description = "顶部胶囊 + 通知栏（+ 流体云 / 实况更新）三处同步",
                        checked = uiPrefs.fluidCloudEnabled,
                        onCheckedChange = { value ->
                            container.prefs.updateUi { it.copy(fluidCloudEnabled = value) }
                            container.fluidCloud.enabled = value
                            if (value && !container.fluidCloud.capability.notificationsAllowed) {
                                askNotificationPermission()
                            }
                        },
                    )
                    if (!container.fluidCloud.capability.notificationsAllowed) {
                        SecondaryActionButton(
                            text = "授予通知权限",
                            onClick = { askNotificationPermission() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryActionButton(
                                text = "发送测试",
                                onClick = {
                                    if (!container.fluidCloud.capability.notificationsAllowed) {
                                        askNotificationPermission()
                                    } else {
                                        container.fluidCloud.enabled = true
                                        if (!uiPrefs.fluidCloudEnabled) {
                                            container.prefs.updateUi { it.copy(fluidCloudEnabled = true) }
                                        }
                                        container.fluidCloud.runTest()
                                        onToast("已发送，请观察状态栏")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryActionButton(
                                text = "系统通知设置",
                                onClick = {
                                    if (!container.fluidCloud.openSystemSettings()) onToast("无法打开系统设置")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text(
                        text = "无需配置：应用会自动选用可用的链路 —— 优先按 Google 的实况更新（Live Updates）" +
                            "规范提交进度，ColorOS 上再并行尝试 OPPO 的意图共享通道，哪一个生效由系统决定。",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                }
            }
        }

        /* ---------------------------- 账户 ---------------------------- */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(text = "账户")
                    KeyValueRow(label = "用户名", value = session.user?.username ?: "--")
                    KeyValueRow(
                        label = "角色",
                        value = if (isAdmin) "管理员" else "普通用户",
                        valueColor = if (isAdmin) MaterialTheme.colorScheme.primary else null,
                    )
                    KeyValueRow(
                        label = "R18 可见性",
                        value = if (session.user?.r18Mode == true) "已开启" else "已隐藏",
                    )
                    ClickableRow(
                        title = "修改密码",
                        onClick = { showPassword = true },
                    )
                    ClickableRow(
                        title = "退出登录",
                        icon = Icons.Filled.Logout,
                        destructive = true,
                        onClick = { confirmLogout = true },
                    )
                }
            }
        }

        /* ---------------------------- 服务端 ---------------------------- */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle(text = "服务连接")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Dns, contentDescription = null, tint = onGlassColor(dark, false), modifier = Modifier.size(16.dp))
                        Text(
                            text = server?.authority ?: "未配置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onGlassColor(dark, emphasis = true),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ClickableRow(
                        title = "更换服务器地址",
                        onClick = { container.sessionStore.requestServerChange() },
                    )
                    if (isAdmin) {
                        com.fluxframe.app.ui.components.HairLine(modifier = Modifier.padding(vertical = 4.dp))
                        KeyValueRow(label = "存储目录", value = settings?.storageDir ?: "--")
                        KeyValueRow(label = "服务端口", value = settings?.port?.toString() ?: "--")
                        KeyValueRow(label = "AI 模型", value = settings?.aiModel ?: "--")
                        KeyValueRow(
                            label = "B站 Cookie",
                            value = if (settings?.biliSessdataConfigured == true) "已配置" else "未配置",
                            valueColor = if (settings?.biliSessdataConfigured == true) Color(0xFF22C55E) else null,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        GlassTextField(
                            value = siteNameInput,
                            onValueChange = { siteNameInput = it },
                            placeholder = "站点名（显示在网页标题与总览里）",
                        )
                        GlassTextField(
                            value = uploadLimitInput,
                            onValueChange = { uploadLimitInput = it },
                            placeholder = "单文件上传上限（MB，1–2048）",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        )
                        GlassTextField(
                            value = recycleDaysInput,
                            onValueChange = { recycleDaysInput = it },
                            placeholder = "回收站保留天数（0–3650）",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        )
                        PrimaryActionButton(
                            text = if (saving) "保存中…" else "保存服务端配置",
                            loading = saving,
                            onClick = {
                                val limit = uploadLimitInput.toIntOrNull()
                                val days = recycleDaysInput.toIntOrNull()
                                val siteName = siteNameInput.trim()
                                val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
                                    // 数值必须以 JSON number 提交：发字符串能过服务端校验却不被读取
                                    if (limit != null && limit in 1..2048) {
                                        put("uploadLimitMb", kotlinx.serialization.json.JsonPrimitive(limit))
                                    }
                                    if (days != null && days in 0..3650) {
                                        put("recycleRetentionDays", kotlinx.serialization.json.JsonPrimitive(days))
                                    }
                                    if (siteName.isNotEmpty() && siteName != settings?.siteName) {
                                        put("siteName", kotlinx.serialization.json.JsonPrimitive(siteName))
                                    }
                                }
                                if (fields.isEmpty()) {
                                    onToast("没有需要保存的改动")
                                } else {
                                    container.settingsStore.save(fields, "服务端配置已保存")
                                    onToast("已提交保存")
                                }
                            },
                        )
                    }
                }
            }
        }

        /* ---------------------------- 引擎（ADMIN） ---------------------------- */
        if (isAdmin) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(text = "本地 AI 引擎（llama.cpp + Qwen2.5-VL）")
                        val status = aiStatus
                        KeyValueRow(
                            label = "运行状态",
                            value = when {
                                status == null -> "未知"
                                status.running -> "运行中（端口 ${status.port}）"
                                else -> "未运行"
                            },
                            valueColor = if (status?.running == true) Color(0xFF22C55E) else null,
                        )
                        KeyValueRow(
                            label = "文件完整度",
                            value = when {
                                status == null -> "未知"
                                status.files.server && status.files.model -> "已就绪（${status.files.variant ?: "-"}）"
                                status.files.server -> "仅引擎，缺模型"
                                else -> "未下载"
                            },
                        )
                        if (status != null && status.progress.phase != "idle" && status.progress.phase != "ready") {
                            ProgressRow(
                                label = "${status.progress.phase}${if (status.progress.modelName.isNotBlank()) " · ${status.progress.modelName}" else ""}",
                                progress = status.progress.fraction.takeIf { it > 0f },
                                detail = if (status.progress.modelTotal > 0) {
                                    "${formatBytes(status.progress.modelDone)} / ${formatBytes(status.progress.modelTotal)}"
                                } else {
                                    null
                                },
                            )
                            if (status.progress.error != null) {
                                Text(
                                    text = status.progress.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(
                            text = "AI 打标会显著提升上传体验，但需要下载数 GB 模型。4 核 / 8 GB 的机器建议选 3B。",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "下载 3B",
                                    icon = Icons.Filled.Download,
                                    onClick = {
                                        scope.launch {
                                            container.adminRepository.downloadAi("3b")
                                                .onSuccess { onToast("已开始下载 3B（火忘式任务，请稍后查看状态）") }
                                                .onFailure { onToast(it.message) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "下载 7B",
                                    icon = Icons.Filled.Download,
                                    onClick = {
                                        scope.launch {
                                            container.adminRepository.downloadAi("7b")
                                                .onSuccess { onToast("已开始下载 7B") }
                                                .onFailure { onToast(it.message) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                PrimaryActionButton(
                                    text = "启动引擎",
                                    icon = Icons.Filled.PlayArrow,
                                    onClick = {
                                        scope.launch {
                                            onToast("正在启动，最多需要 150 秒…")
                                            container.adminRepository.startAi()
                                                .onSuccess {
                                                    onToast("AI 引擎已就绪")
                                                    container.adminRepository.aiStatus().onSuccess { aiStatus = it }
                                                }
                                                .onFailure { onToast(it.message) }
                                        }
                                    },
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "停止",
                                    icon = Icons.Filled.Stop,
                                    onClick = {
                                        scope.launch {
                                            container.adminRepository.stopAi()
                                                .onSuccess {
                                                    onToast("已停止")
                                                    container.adminRepository.aiStatus().onSuccess { aiStatus = it }
                                                }
                                                .onFailure { onToast(it.message) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        SecondaryActionButton(
                            text = "刷新状态",
                            onClick = {
                                scope.launch {
                                    container.adminRepository.aiStatus().onSuccess { aiStatus = it }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(text = "ffmpeg（B站高清合并）")
                        KeyValueRow(
                            label = "状态",
                            value = ffmpeg?.let { if (it.found) "已就绪 ${it.version ?: ""}" else "未找到" } ?: "未知",
                            valueColor = if (ffmpeg?.found == true) Color(0xFF22C55E) else null,
                        )
                        ffmpeg?.path?.let {
                            KeyValueRow(label = "路径", value = it)
                        }
                        // progress 是 {phase, done, total} 对象（由契约测试锁定）
                        if (ffmpeg?.progress?.active == true || ffmpeg?.busy == true) {
                            ProgressRow(
                                label = when (ffmpeg?.progress?.phase) {
                                    "downloading" -> "正在下载 ffmpeg…"
                                    "extracting" -> "正在解压…"
                                    else -> ffmpeg?.progress?.phase ?: "处理中…"
                                },
                                progress = ffmpeg?.progress?.fraction?.takeIf { (ffmpeg?.progress?.total ?: 0L) > 0L },
                                accent = MaterialTheme.colorScheme.primary,
                            )
                        }
                        SecondaryActionButton(
                            text = "一键下载 ffmpeg",
                            icon = Icons.Filled.Download,
                            onClick = {
                                scope.launch {
                                    container.adminRepository.downloadFfmpeg()
                                        .onSuccess { onToast("已提交下载任务") }
                                        .onFailure { onToast(it.message) }
                                    delay(1500)
                                    container.adminRepository.ffmpegStatus().onSuccess { ffmpeg = it }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(text = "B站账号（获取 1080P+ 权限）")
                        Text(
                            text = if (settings?.biliSessdataConfigured == true) {
                                "已登录，服务端可以用你的账号拿高清 DASH 双流。"
                            } else {
                                "未登录时 B站最高只有 720P。扫码登录后可得 1080P+（保存时用 ffmpeg 合并音视频）。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        SecondaryActionButton(
                            text = "扫码登录 B站",
                            icon = Icons.Filled.QrCode2,
                            onClick = { showBili = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(text = "DeepSeek 平台用量（可选）")
                        Text(
                            text = if (settings?.deepseekPlatformConfigured == true) {
                                "已配置平台令牌，用量按 KEY 维度同步。"
                            } else {
                                "只靠余额差值记账也能用；粘贴 DeepSeek 平台网页令牌可拿到精确的 Token 与请求数。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        GlassTextField(
                            value = platformTokenInput,
                            onValueChange = { platformTokenInput = it },
                            placeholder = "平台会话令牌（留空提交 = 清除）",
                            isPassword = true,
                        )
                        SecondaryActionButton(
                            text = "保存令牌",
                            onClick = {
                                container.deepseekStore.configure(
                                    enabled = null,
                                    refreshSeconds = null,
                                    platformToken = platformTokenInput,
                                )
                                platformTokenInput = ""
                                onToast("已提交")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        /* ---------------------------- 入口 ---------------------------- */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column {
                    ClickableRow(
                        title = "访问日志",
                        description = if (isAdmin) "查看谁在什么时候看了什么" else "仅管理员可见",
                        icon = Icons.Filled.Description,
                        onClick = { if (isAdmin) onOpenLogs() else onToast("需要管理员权限") },
                    )
                    ClickableRow(
                        title = "DeepSeek 记账",
                        description = "余额、每日用量、密钥管理",
                        icon = Icons.Filled.AccountBalanceWallet,
                        onClick = onOpenDeepseek,
                    )
                }
            }
        }

        /* ---------------------------- 关于 ---------------------------- */
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(text = "关于")
                    KeyValueRow(label = "客户端版本", value = "${container.appVersionName} (${container.appVersionCode})")
                    KeyValueRow(label = "服务地址", value = server?.baseUrl ?: "--")
                    KeyValueRow(label = "服务端站点名", value = settings?.siteName ?: "--")
                    KeyValueRow(
                        label = "流体云 / 实况通知",
                        value = container.fluidCloud.capability.badge,
                    )
                    // 视频封面：成功/失败都给出可核对的数字，避免"为什么还是渐变"无从下手
                    KeyValueRow(
                        label = "视频封面",
                        value = run {
                            val loader = com.fluxframe.app.core.media.VideoPosterLoader
                            val strategy = loader.lastStrategy
                            when {
                                loader.failureCount == 0 && loader.successCount == 0 -> "尚未取帧"
                                strategy == null -> "失败 ${loader.failureCount} 次"
                                else -> "$strategy · 成功 ${loader.successCount}"
                            }
                        },
                    )
                    com.fluxframe.app.core.media.VideoPosterLoader.lastError?.let { posterError ->
                        Text(
                            text = "封面取帧失败：$posterError",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = "原生 Android 客户端（Kotlin + Jetpack Compose），直接调用 fluxframe REST 接口，" +
                            "不依赖网页前端。支持五种界面质感、自定义配色与背景图片。",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(6.dp)) }
    }

    if (showPassword) {
        ChangePasswordDialog(
            onDismiss = { showPassword = false },
            onToast = onToast,
        )
    }

    if (confirmLogout) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmLogout = false }) {
            ConfirmDialog(
                title = "退出登录？",
                text = "会清除本机保存的会话，服务器上的数据不受影响。",
                confirmLabel = "退出",
                destructive = true,
                onConfirm = {
                    confirmLogout = false
                    scope.launch {
                        container.sessionStore.logout()
                        onLoggedOut()
                        onToast("已退出登录")
                    }
                },
                onDismiss = { confirmLogout = false },
            )
        }
    }

    if (showBili) {
        BiliQrDialog(
            onDismiss = { showBili = false },
            onToast = onToast,
        )
    }
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onToast: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val dark = LocalDarkTheme.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "修改密码",
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                GlassTextField(value = current, onValueChange = { current = it }, placeholder = "当前密码", isPassword = true)
                GlassTextField(value = next, onValueChange = { next = it }, placeholder = "新密码（至少 8 位）", isPassword = true)
                GlassTextField(value = confirm, onValueChange = { confirm = it }, placeholder = "再输一次新密码", isPassword = true)
                val mismatch = confirm.isNotEmpty() && next != confirm
                if (mismatch) {
                    Text(
                        text = "两次输入的新密码不一致",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(
                            text = "保存",
                            enabled = current.isNotEmpty() && next.length >= 8 && next == confirm,
                            onClick = {
                                scope.launch {
                                    container.authRepository.changePassword(current, next)
                                        .onSuccess {
                                            onToast("密码已修改")
                                            onDismiss()
                                        }
                                        .onFailure { onToast(it.message) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** B站扫码登录：显示二维码并轮询状态（服务端扫码会话是进程级共享的，这里串行使用） */
@Composable
private fun BiliQrDialog(onDismiss: () -> Unit, onToast: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val dark = LocalDarkTheme.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在生成二维码…") }
    var qrKey by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(generation) {
        status = "正在生成二维码…"
        bitmap = null
        qrKey = null
        container.adminRepository.biliQrCreate()
            .onSuccess { result ->
                if (result.ok && result.qrcodeKey != null && result.image != null) {
                    qrKey = result.qrcodeKey
                    bitmap = decodeDataUrl(result.image)
                    status = "请用 B站 App 扫描二维码"
                } else {
                    status = result.error ?: "生成二维码失败"
                }
            }
            .onFailure { status = it.message ?: "生成二维码失败" }
    }

    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        // 与服务端/网页端一致的轮询策略：2 秒一次，总共不超过 175 秒
        val deadline = System.currentTimeMillis() + 175_000
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val result = container.adminRepository.biliQrPoll(key).getOrNull() ?: continue
            when (result.status) {
                "waiting" -> status = "等待扫码…"
                "scanned" -> status = "已扫码，请在手机上确认"
                "expired" -> {
                    status = result.error ?: "二维码已过期，请刷新"
                    return@LaunchedEffect
                }
                "ok" -> {
                    status = "登录成功${result.nickname?.let { "（$it）" } ?: ""}"
                    container.settingsStore.refresh()
                    delay(1200)
                    onToast("B站登录成功")
                    onDismiss()
                    return@LaunchedEffect
                }
                else -> {
                    status = result.error ?: "登录失败"
                    return@LaunchedEffect
                }
            }
        }
        status = "二维码已过期，请点击刷新"
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "B站扫码登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = bitmap
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "B站登录二维码",
                            modifier = Modifier.size(190.dp),
                        )
                    } else {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color(0xFF10162A),
                        )
                    }
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGlassColor(dark, emphasis = true),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(text = "刷新二维码", onClick = { generation++ })
                    }
                }
            }
        }
    }
}

/** `data:image/png;base64,...` → Bitmap */
private fun decodeDataUrl(dataUrl: String): Bitmap? = runCatching {
    val base64 = dataUrl.substringAfter(',', "")
    if (base64.isBlank()) return null
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

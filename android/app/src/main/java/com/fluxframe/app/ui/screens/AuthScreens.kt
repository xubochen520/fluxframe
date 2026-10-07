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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.net.ServerEndpoint
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 首次启动 / 更换服务器：自动扫描同网段 + 手动输入。
 *
 * 自动扫描与旧版 Capacitor 客户端保持一致的语义：手机连上同一个 WiFi 后，
 * 扫描 `192.168.x.1-254` 的 4311 与 5173 端口，用 `/api/health` 确认。
 */
@Composable
fun ServerSetupScreen(onConfigured: () -> Unit) {
    val container = LocalAppContainer.current
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    var found by remember { mutableStateOf<List<ServerEndpoint>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanRound by remember { mutableStateOf(0) }

    val prefixes = remember { container.discovery.subnetPrefixes() }
    val localIps = remember { container.discovery.localIpv4() }

    LaunchedEffect(scanRound) {
        found = emptyList()
        scanning = true
        error = null
        scanJob = scope.launch {
            container.discovery.scan(prefixes).collect { endpoint ->
                if (found.none { it == endpoint }) found = found + endpoint
            }
            scanning = false
        }
    }

    /**
     * 逐个探测候选地址，采用第一个应答 `/api/health` 的。
     *
     * 手动输入时端口往往是猜的（域名挂在 80/443、局域网直连在 4311），
     * 所以由 [ServerEndpoint.candidates] 给出优先级列表，这里按序试，
     * 而不是赌一个默认端口。
     */
    fun connect(candidates: List<ServerEndpoint>) {
        if (candidates.isEmpty()) {
            error = "地址格式不正确，示例：192.168.1.100:4311 或 fluxframe.example.com"
            return
        }
        connecting = true
        error = null
        scope.launch {
            val hit = candidates.firstOrNull { container.discovery.probe(it) }
            connecting = false
            if (hit != null) {
                container.prefs.saveServer(hit)
                onConfigured()
            } else {
                val tried = candidates.joinToString("、") { it.authority }
                error = "无法连接 $tried：请确认服务已启动、手机能访问该地址"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 64.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Text(
                text = "连接 fluxframe 服务器",
                style = MaterialTheme.typography.headlineSmall,
                color = onGlassColor(dark, emphasis = true),
            )
            Text(
                text = "手机与服务器连同一个 WiFi 后可自动发现；也可以手动填写地址。",
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = false),
            )
        }

        if (error != null) {
            ErrorBar(message = error!!, onDismiss = { error = null })
        }

        // ---- 自动扫描 ----
        GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "自动发现",
                        style = MaterialTheme.typography.titleSmall,
                        color = onGlassColor(dark, emphasis = true),
                        modifier = Modifier.weight(1f),
                    )
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = if (prefixes.isEmpty()) {
                        "未检测到局域网地址，请确认已连接 WiFi"
                    } else {
                        "本机地址 ${localIps.joinToString("、").ifBlank { "未知" }}｜正在扫描 ${prefixes.joinToString("、") { "$it.0/24" }} 的 4311、5173 端口"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )

                if (found.isEmpty()) {
                    Text(
                        text = if (scanning) "正在扫描…" else "没有发现服务。请确认服务器已启动，或用下面的手动输入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = onGlassColor(dark, emphasis = false),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    found.forEach { endpoint ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .clickable(enabled = !connecting) { connect(listOf(endpoint)) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF22C55E)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = endpoint.authority,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onGlassColor(dark, emphasis = true),
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "fluxframe 服务",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                            }
                            Text(
                                text = if (connecting) "连接中…" else "连接",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                SecondaryActionButton(
                    text = if (scanning) "重新扫描" else "再扫一次",
                    icon = Icons.Filled.Refresh,
                    onClick = { scanRound++ },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 手动输入 ----
        GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "手动输入地址",
                        style = MaterialTheme.typography.titleSmall,
                        color = onGlassColor(dark, emphasis = true),
                    )
                }
                GlassTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    placeholder = "192.168.1.100:4311 或 fluxframe.example.com",
                    leadingIcon = Icons.Filled.Dns,
                )
                Text(
                    text = "填 IP 默认 4311 端口；填域名默认 80/443，会自动试出可用的那个。",
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
                PrimaryActionButton(
                    text = "连接",
                    loading = connecting,
                    enabled = manual.isNotBlank(),
                    onClick = { connect(ServerEndpoint.candidates(manual)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 登录页 */
@Composable
fun LoginScreen(onSwitchServer: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionStore.state.collectAsStateWithLifecycle()
    val server by container.prefs.server.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current

    var username by remember { mutableStateOf(container.prefs.lastUsername) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        localError = null
        if (username.isBlank()) {
            localError = "请输入用户名"
            return
        }
        if (password.isEmpty()) {
            localError = "请输入密码"
            return
        }
        if (registerMode) {
            // 与网页端一致的注册校验（服务端同样要求 3/8 位）
            if (username.trim().length < 3) {
                localError = "用户名至少需要 3 个字符"
                return
            }
            if (password.length < 8) {
                localError = "密码至少需要 8 个字符"
                return
            }
            if (password != confirmPassword) {
                localError = "两次输入的密码不一致"
                return
            }
            container.sessionStore.register(username, password)
        } else {
            container.sessionStore.login(username, password)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 96.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "fluxframe",
                style = MaterialTheme.typography.headlineMedium,
                color = onGlassColor(dark, emphasis = true),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "内网图片管理器 · 原生移动端",
                style = MaterialTheme.typography.bodySmall,
                color = onGlassColor(dark, emphasis = false),
            )
        }

        GlassSurface(modifier = Modifier.fillMaxWidth(), backdrop = true, contentPadding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "用户名",
                    leadingIcon = Icons.Filled.Person,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                )
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = if (registerMode) "密码（至少 8 位）" else "密码",
                    leadingIcon = Icons.Filled.Lock,
                    isPassword = true,
                    imeAction = if (registerMode) {
                        androidx.compose.ui.text.input.ImeAction.Next
                    } else {
                        androidx.compose.ui.text.input.ImeAction.Go
                    },
                    onImeAction = { if (!registerMode) submit() },
                )
                if (registerMode) {
                    GlassTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        placeholder = "再输一次密码",
                        leadingIcon = Icons.Filled.Lock,
                        isPassword = true,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                        onImeAction = { submit() },
                    )
                }
                val shownError = localError ?: session.error
                if (shownError != null) {
                    ErrorBar(
                        message = shownError,
                        onDismiss = {
                            localError = null
                            container.sessionStore.clearError()
                        },
                    )
                }
                PrimaryActionButton(
                    text = if (registerMode) "注册并登录" else "登录",
                    loading = session.loading,
                    enabled = username.isNotBlank() && password.isNotEmpty(),
                    onClick = { submit() },
                )
                Text(
                    text = if (registerMode) "已有账户？返回登录" else "还没有账户？注册一个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            registerMode = !registerMode
                            localError = null
                            container.sessionStore.clearError()
                        }
                        .padding(vertical = 6.dp),
                )
            }
        }

        GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = onGlassColor(dark, emphasis = false),
                    modifier = Modifier.size(17.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "服务器",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                    Text(
                        text = server?.authority ?: "未配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onGlassColor(dark, emphasis = true),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "切换服务器",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp).clickable { onSwitchServer() },
                )
            }
        }

        Text(
            text = "账号由服务端管理。忘记密码请在服务器上重置，或使用管理员账号登录后修改。",
            style = MaterialTheme.typography.labelSmall,
            color = onGlassColor(dark, emphasis = false),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

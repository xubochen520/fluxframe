package com.fluxframe.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.core.util.formatMoney
import com.fluxframe.app.core.util.formatPercent
import com.fluxframe.app.core.util.formatRelative
import com.fluxframe.app.data.model.DeepseekKeyItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.ConfirmDialog
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.SwitchRow
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.BrandCyan
import com.fluxframe.app.ui.theme.BrandGreen
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor

/** DeepSeek 余额与用量记账 */
@Composable
fun DeepseekScreen(onToast: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val summary by container.deepseekStore.summary.collectAsStateWithLifecycle()
    val loading by container.deepseekStore.loading.collectAsStateWithLifecycle()
    val busy by container.deepseekStore.busy.collectAsStateWithLifecycle()
    val message by container.deepseekStore.message.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val isAdmin = container.sessionStore.isAdmin

    var showAddKey by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeepseekKeyItem?>(null) }
    var mergeTarget by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        if (summary == null) container.deepseekStore.refresh()
    }

    LaunchedEffect(message) {
        if (message != null) {
            onToast(message)
            kotlinx.coroutines.delay(2400)
            container.deepseekStore.clearMessage()
        }
    }

    val data = summary

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (data == null && loading) item { LoadingBox(text = "正在读取 DeepSeek 记账数据…") }

        if (data != null && !data.configured) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "还没有配置 DeepSeek 密钥",
                            style = MaterialTheme.typography.titleSmall,
                            color = onGlassColor(dark, emphasis = true),
                        )
                        Text(
                            text = "添加一个 API KEY 后，服务端会按间隔观测余额并记账，这里就能看到余额与每日用量。",
                            style = MaterialTheme.typography.bodySmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        if (isAdmin) {
                            PrimaryActionButton(
                                text = "添加密钥",
                                icon = Icons.Filled.Add,
                                onClick = { showAddKey = true },
                            )
                        }
                    }
                }
            }
        }

        if (data != null && data.error.isNotBlank()) {
            item { ErrorBar(message = data.error, onDismiss = {}) }
        }

        // ---- 余额总览 ----
        if (data != null && data.configured) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), backdrop = true, contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "总余额",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                                Text(
                                    text = formatMoney(data.stats.balance, data.stats.currency),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = onGlassColor(dark, emphasis = true),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            GlassIconButton(
                                icon = Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                onClick = { container.deepseekStore.forceRefresh() },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniBadge(text = "今日 ${formatMoney(data.stats.today, data.stats.currency)}", color = BrandCyan)
                            MiniBadge(text = "本月 ${formatMoney(data.stats.month, data.stats.currency)}", color = BrandGreen)
                            MiniBadge(text = "${data.stats.accountCount} 账户", color = MaterialTheme.colorScheme.primary)
                            MiniBadge(
                                text = if (data.stats.source == "platform") "平台数据" else "余额差值记账",
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (data.stats.cacheHitRate != null) {
                            Text(
                                text = "缓存命中率 ${formatPercent(data.stats.cacheHitRate)}｜本月请求 ${data.stats.requestsMonth}｜Tokens ${data.stats.tokensMonth}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                        if (data.updatedAt != null) {
                            Text(
                                text = "上次刷新 ${formatRelative(data.updatedAt)}${if (data.refreshing) "（刷新中…）" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                    }
                }
            }

            // ---- 30 天用量 ----
            if (data.chart.isNotEmpty()) {
                item { SectionTitle(text = "近 30 天用量") }
                item {
                    GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UsageChart(
                                values = data.chart.map { it.amount.toFloat() },
                                accent = MaterialTheme.colorScheme.primary,
                            )
                            Row {
                                Text(
                                    text = data.chart.firstOrNull()?.day.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = data.chart.lastOrNull()?.day.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                            }
                        }
                    }
                }
            }

            // ---- 合并建议 ----
            if (isAdmin && data.mergeHints.isNotEmpty()) {
                item { SectionTitle(text = "疑似同一账户") }
                items(data.mergeHints) { hint ->
                    GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = hint.names.joinToString("、"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = onGlassColor(dark, emphasis = true),
                            )
                            Text(
                                text = "余额与币种完全一致（${formatMoney(hint.balance, hint.currency)}），可能是同一个 DeepSeek 账户",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            SecondaryActionButton(
                                text = "合并为一个账户",
                                icon = Icons.Filled.Merge,
                                onClick = { mergeTarget = hint.keyIds },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- KEY 列表 ----
            item {
                SectionTitle(
                    text = "密钥（${data.keys.size}）",
                    trailing = {
                        if (isAdmin) {
                            Text(
                                text = "+ 添加",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showAddKey = true },
                            )
                        }
                    },
                )
            }
            items(data.keys, key = { it.id }) { key ->
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = key.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onGlassColor(dark, emphasis = true),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (key.isAccountOwner) {
                                MiniBadge(text = "记账主 KEY", color = BrandGreen)
                            }
                            MiniBadge(
                                text = if (key.enabled) "启用" else "停用",
                                color = if (key.enabled) MaterialTheme.colorScheme.primary else onGlassColor(dark, false),
                            )
                            if (isAdmin) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { deleteTarget = key },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.material3.Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${key.masked}　账户：${key.accountNameResolved}",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            key.balance?.let {
                                MiniBadge(text = formatMoney(it, key.currency), color = BrandCyan)
                            }
                            key.todayAmount?.let {
                                MiniBadge(text = "今日 ${formatMoney(it, key.currency)}", color = MaterialTheme.colorScheme.primary)
                            }
                            key.monthAmount?.let {
                                MiniBadge(text = "本月 ${formatMoney(it, key.currency)}", color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        if (key.lastError != null) {
                            Text(
                                text = "⚠ ${key.lastError}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (isAdmin) {
                            SwitchRow(
                                title = "参与记账",
                                checked = key.enabled,
                                onCheckedChange = { container.deepseekStore.toggleKey(key.id, it) },
                            )
                        }
                    }
                }
            }

            // ---- 记账设置 ----
            if (isAdmin) {
                item { SectionTitle(text = "记账设置") }
                item {
                    GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SwitchRow(
                                title = "启用后台记账",
                                description = "按间隔调用 DeepSeek 官方余额接口，用余额差值推算用量",
                                checked = data.enabled,
                                onCheckedChange = { container.deepseekStore.configure(enabled = it, refreshSeconds = null, platformToken = null) },
                            )
                            Text(
                                text = "刷新间隔 ${data.refreshSeconds} 秒（服务端允许 30–3600）",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(60, 300, 900).forEach { seconds ->
                                    MiniBadge(
                                        text = if (seconds < 60) "${seconds}s" else "${seconds / 60}min",
                                        color = if (data.refreshSeconds == seconds) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            onGlassColor(dark, false)
                                        },
                                        modifier = Modifier.clickable {
                                            container.deepseekStore.configure(enabled = null, refreshSeconds = seconds, platformToken = null)
                                        },
                                    )
                                }
                            }
                            if (data.platform.configured) {
                                Text(
                                    text = "平台用量已配置${if (data.platform.error.isNotBlank()) "（${data.platform.error}）" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (data.platform.error.isNotBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        BrandGreen
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(6.dp)) }
    }

    if (showAddKey) {
        AddDeepseekKeyDialog(
            onDismiss = { showAddKey = false },
            onConfirm = { name, apiKey, account ->
                showAddKey = false
                container.deepseekStore.addKey(name, apiKey, account)
            },
        )
    }

    deleteTarget?.let { key ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { deleteTarget = null }) {
            ConfirmDialog(
                title = "删除「${key.name}」？",
                text = "会同时删除该密钥的历史记账数据，不可恢复。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    container.deepseekStore.deleteKey(key.id)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null },
            )
        }
    }

    mergeTarget?.let { ids ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { mergeTarget = null }) {
            ConfirmDialog(
                title = "合并账户？",
                text = "这些密钥会被归到同一个账户，历史记账流水会迁移到最早创建的那个密钥名下。此操作不可撤销。",
                confirmLabel = "合并",
                onConfirm = {
                    container.deepseekStore.merge(ids, null)
                    mergeTarget = null
                },
                onDismiss = { mergeTarget = null },
            )
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    busy
}

/** 极简柱状图：30 天用量 */
@Composable
private fun UsageChart(values: List<Float>, accent: Color) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        if (values.isEmpty()) return@Canvas
        val gap = 2f
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val ratio = (value / max).coerceIn(0f, 1f)
            val barHeight = (size.height * ratio).coerceAtLeast(if (value > 0f) 3f else 1.5f)
            drawRoundRect(
                color = if (value > 0f) accent.copy(alpha = 0.85f) else accent.copy(alpha = 0.18f),
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun AddDeepseekKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, apiKey: String, accountName: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    val dark = LocalDarkTheme.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "添加 DeepSeek 密钥",
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                GlassTextField(value = name, onValueChange = { name = it }, placeholder = "名称（用于识别，1–40 字）")
                GlassTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "API KEY（sk-…）",
                    isPassword = true,
                )
                GlassTextField(
                    value = account,
                    onValueChange = { account = it },
                    placeholder = "账户名（可留空；填相同值 = 同一账户）",
                )
                Text(
                    text = "密钥只保存在你自己的服务器上，客户端不会回显明文。",
                    style = MaterialTheme.typography.labelSmall,
                    color = onGlassColor(dark, emphasis = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(
                            text = "保存",
                            enabled = name.isNotBlank() && apiKey.length >= 8,
                            onClick = { onConfirm(name.trim(), apiKey.trim(), account.trim()) },
                        )
                    }
                }
            }
        }
    }
}

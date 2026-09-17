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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.core.util.formatCount
import com.fluxframe.app.core.util.formatDuration
import com.fluxframe.app.data.model.ParseData
import com.fluxframe.app.data.repo.ParseRepository
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.ProgressRow
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.launch

/**
 * 视频提取：粘贴分享链接 → 服务端解析 → 选择保存视频/封面/图文图片。
 *
 * 保存动作交给服务端后台任务（直连片源、sha256 查重、生成缩略图、写审计），
 * 客户端只轮询进度并同步到「流体云」胶囊。
 */
@Composable
fun ParseScreen(onToast: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()
    val imports by container.taskStore.imports.collectAsStateWithLifecycle()

    var link by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<ParseData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var savingCover by remember { mutableStateOf(false) }
    var savingVideo by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = "粘贴分享链接或整段口令",
                            style = MaterialTheme.typography.titleSmall,
                            color = onGlassColor(dark, emphasis = true),
                        )
                    }
                    GlassTextField(
                        value = link,
                        onValueChange = { link = it },
                        placeholder = "https://b23.tv/xxxx 或整段分享文案",
                        singleLine = false,
                        maxLines = 3,
                    )
                    Text(
                        text = "支持 B站 / 抖音 / 快手（小红书、微博、西瓜暂未内置解析）。保存由服务端完成，可退到后台。",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                    PrimaryActionButton(
                        text = "解析",
                        loading = parsing,
                        enabled = link.isNotBlank(),
                        onClick = {
                            error = null
                            data = null
                            parsing = true
                            scope.launch {
                                container.parseRepository.parse(link)
                                    .onSuccess { data = it }
                                    .onFailure { error = it.message }
                                parsing = false
                            }
                        },
                    )
                }
            }
        }

        if (error != null) {
            item { ErrorBar(message = error!!, onDismiss = { error = null }) }
        }

        if (parsing) {
            item { LoadingBox(text = "正在解析，抖音可能要多试几次…") }
        }

        // ---- 进行中的任务 ----
        if (imports.isNotEmpty()) {
            item {
                SectionTitle(
                    text = "后台任务（${imports.count { it.isWorking }} 进行中）",
                    trailing = {
                        Text(
                            text = "清空已完成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { container.taskStore.clearFinishedImports() },
                        )
                    },
                )
            }
            items(imports, key = { it.id }) { task ->
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onGlassColor(dark, emphasis = true),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            MiniBadge(
                                text = when {
                                    task.isWorking -> "进行中"
                                    task.isDone -> "完成"
                                    else -> "失败"
                                },
                                color = when {
                                    task.isWorking -> MaterialTheme.colorScheme.primary
                                    task.isDone -> Color(0xFF22C55E)
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                            if (task.isWorking) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "取消",
                                    tint = onGlassColor(dark, emphasis = false),
                                    modifier = Modifier.size(16.dp).clickable { container.taskStore.cancelImport(task.id) },
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除",
                                    tint = onGlassColor(dark, emphasis = false),
                                    modifier = Modifier.size(16.dp).clickable { container.taskStore.dismissImport(task.id) },
                                )
                            }
                        }
                        ProgressRow(
                            label = task.message,
                            progress = if (task.isWorking) task.progress else if (task.isDone) 1f else 0f,
                            accent = if (task.isError) MaterialTheme.colorScheme.error else Color(0xFF22D3EE),
                        )
                    }
                }
            }
        }

        // ---- 解析结果 ----
        data?.let { result ->
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (result.cover.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(14.dp)),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(container.parseRepository.absolute(result.cover))
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = result.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                MiniBadge(
                                    text = result.platformLabel,
                                    color = Color.White,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = onGlassColor(dark, emphasis = true),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(result.author.name.ifBlank { "未知作者" })
                                if (result.author.tag.isNotBlank()) append(" · ${result.author.tag}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniBadge(text = result.qualityLabel.ifBlank { "未知清晰度" }, color = MaterialTheme.colorScheme.secondary)
                            if (result.duration > 0) {
                                MiniBadge(text = formatDuration(result.duration), color = MaterialTheme.colorScheme.primary)
                            }
                            if (result.watermarkFree) {
                                MiniBadge(text = "无水印", color = Color(0xFF22C55E))
                            }
                        }
                        Text(
                            text = "♥ ${formatCount(result.stats.like)}　💬 ${formatCount(result.stats.comment)}　▶ ${formatCount(result.stats.view)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                        if (result.high != null) {
                            Text(
                                text = "检测到 ${result.high.label} 高清双流，保存时将用 ffmpeg 无损合并。",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF22C55E),
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        val videoRequest = remember(result) {
                            ParseRepository.videoImportOf(result, result.title.take(180), useHighQuality = true)
                        }
                        val coverRequest = remember(result) {
                            ParseRepository.coverImportOf(result, result.title.take(180) + "-封面")
                        }

                        if (videoRequest != null) {
                            PrimaryActionButton(
                                text = "保存视频到图片库",
                                icon = Icons.Filled.Download,
                                loading = savingVideo,
                                onClick = {
                                    savingVideo = true
                                    container.taskStore.startImport(videoRequest, result.title)
                                    onToast("已加入后台提取任务")
                                    savingVideo = false
                                },
                            )
                        }

                        if (coverRequest != null) {
                            SecondaryActionButton(
                                text = "保存封面",
                                icon = Icons.Filled.Movie,
                                onClick = {
                                    savingCover = true
                                    container.taskStore.startImport(coverRequest, result.title + "-封面")
                                    onToast("已加入后台提取任务")
                                    savingCover = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (result.isImageCollection && result.images.isNotEmpty()) {
                            SecondaryActionButton(
                                text = "保存全部 ${result.images.size} 张图片",
                                icon = Icons.Filled.Download,
                                onClick = {
                                    result.images.indices.forEach { index ->
                                        val request = ParseRepository.imageImportOf(result, index, result.title)
                                        if (request != null) {
                                            container.taskStore.startImport(request, "${result.title}-${index + 1}")
                                        }
                                    }
                                    onToast("已加入 ${result.images.size} 个提取任务")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        if (data == null && !parsing && error == null) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "使用提示",
                            style = MaterialTheme.typography.labelLarge,
                            color = onGlassColor(dark, emphasis = true),
                        )
                        Text(
                            text = "· B站未登录时最高 720P；在「设置 → B站」扫码登录后可得 1080P+（需服务端装有 ffmpeg）。\n" +
                                "· 抖音有风控，失败时等 10–30 秒再试通常可恢复。\n" +
                                "· 提取的视频会进入图片库并自动带「视频」标签。",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(6.dp)) }
    }
}

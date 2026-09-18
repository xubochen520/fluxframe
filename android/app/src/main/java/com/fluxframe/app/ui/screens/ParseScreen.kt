package com.fluxframe.app.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.core.util.formatCount
import com.fluxframe.app.core.util.formatDuration
import com.fluxframe.app.data.model.ParseData
import com.fluxframe.app.data.model.ParseImage
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
    var selectedImages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var previewImageIndex by remember { mutableStateOf<Int?>(null) }

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
                            selectedImages = emptySet()
                            previewImageIndex = null
                            parsing = true
                            scope.launch {
                                container.parseRepository.parse(link)
                                    .onSuccess {
                                        data = it
                                        selectedImages = it.images.indices.toSet()
                                    }
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
            item { LoadingBox(text = "正在建立平台会话并解析媒体…") }
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
                        val video = result.primaryMedia
                        if (video != null && video.url.isNotBlank()) {
                            ParsedVideoPreview(
                                url = container.parseRepository.absolute(video.url),
                                ratio = if (video.width > 0 && video.height > 0) {
                                    (video.width.toFloat() / video.height.toFloat()).coerceIn(0.65f, 2.2f)
                                } else {
                                    16f / 9f
                                },
                            )
                            Text(
                                text = "可直接播放预览，支持拖动进度和全屏",
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        } else if (result.isImageCollection && result.images.isNotEmpty()) {
                            ParsedImagePicker(
                                images = result.images,
                                selected = selectedImages,
                                onToggle = { index ->
                                    selectedImages = if (index in selectedImages) {
                                        selectedImages - index
                                    } else {
                                        selectedImages + index
                                    }
                                },
                                onPreview = { previewImageIndex = it },
                                onSelectAll = { selectedImages = result.images.indices.toSet() },
                                onClear = { selectedImages = emptySet() },
                            )
                        } else if (result.cover.isNotBlank()) {
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
                                text = if (selectedImages.isEmpty()) {
                                    "请先选择要保存的图片"
                                } else {
                                    "保存所选 ${selectedImages.size} / ${result.images.size} 张图片"
                                },
                                icon = Icons.Filled.Download,
                                enabled = selectedImages.isNotEmpty(),
                                onClick = {
                                    var queued = 0
                                    selectedImages.sorted().forEach { index ->
                                        val request = ParseRepository.imageImportOf(result, index, result.title)
                                        if (request != null) {
                                            container.taskStore.startImport(request, "${result.title}-${index + 1}")
                                            queued++
                                        }
                                    }
                                    onToast("已加入 $queued 个图片保存任务")
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
                                "· 抖音由服务端建立同会话浏览器指纹，首次解析可能需要数秒。\n" +
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

    val previewResult = data
    val previewIndex = previewImageIndex
    if (previewResult != null && previewIndex != null && previewResult.images.isNotEmpty()) {
        ParsedImageViewer(
            title = previewResult.title,
            images = previewResult.images,
            initialIndex = previewIndex,
            selected = selectedImages,
            onToggle = { index ->
                selectedImages = if (index in selectedImages) selectedImages - index else selectedImages + index
            },
            onSaveOne = { index ->
                ParseRepository.imageImportOf(previewResult, index, previewResult.title)?.let { request ->
                    container.taskStore.startImport(request, "${previewResult.title}-${index + 1}")
                    onToast("第 ${index + 1} 张图片已加入保存任务")
                }
            },
            onClose = { previewImageIndex = null },
        )
    }
}

/** 解析结果里的视频直接用与图库一致的 OkHttp + ExoPlayer 播放，代理流可拖动 Range。 */
@Composable
private fun ParsedVideoPreview(url: String, ratio: Float) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val player = remember(url) {
        val httpFactory = OkHttpDataSource.Factory(container.okHttp)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    )
}

/** 原生图片选择网格：点缩略图预览，点右上角复选圆圈选择要保存的图片。 */
@Composable
private fun ParsedImagePicker(
    images: List<ParseImage>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onPreview: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    val container = LocalAppContainer.current
    val dark = LocalDarkTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "已选 ${selected.size} / ${images.size} 张",
                style = MaterialTheme.typography.labelMedium,
                color = onGlassColor(dark, emphasis = true),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "全选",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSelectAll() }.padding(5.dp),
            )
            Text(
                text = "清空",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable { onClear() }.padding(5.dp),
            )
        }
        images.indices.chunked(3).forEach { rowIndexes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowIndexes.forEach { index ->
                    val image = images[index]
                    val picked = index in selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (picked) 2.dp else 1.dp,
                                color = if (picked) Color(0xFF34D399) else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onPreview(index) },
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(container.parseRepository.absolute(image.url))
                                .crossfade(true)
                                .build(),
                            contentDescription = "预览第 ${index + 1} 张图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(7.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (picked) Color(0xFF34D399) else Color.Black.copy(alpha = 0.58f))
                                .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                .clickable { onToggle(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) Icon(Icons.Filled.Check, contentDescription = "已选择", tint = Color(0xFF052E1B), modifier = Modifier.size(17.dp))
                        }
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(7.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.58f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                repeat(3 - rowIndexes.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        Text(
            text = "点图片进入全屏预览并左右滑动；右上角勾选决定保存哪些图片",
            style = MaterialTheme.typography.labelSmall,
            color = onGlassColor(dark, emphasis = false),
        )
    }
}

/** 解析图集专用全屏查看器，不要求图片先入库。 */
@Composable
private fun ParsedImageViewer(
    title: String,
    images: List<ParseImage>,
    initialIndex: Int,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSaveOne: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) { images.size }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(container.parseRepository.absolute(images[page].url))
                        .crossfade(true)
                        .build(),
                    contentDescription = "$title · 第 ${page + 1} 张",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(vertical = 72.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = pagerState.currentPage
                SecondaryActionButton(
                    text = if (current in selected) "取消选择" else "选择这张",
                    icon = Icons.Filled.Check,
                    onClick = { onToggle(current) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = "保存这张",
                    icon = Icons.Filled.Download,
                    onClick = { onSaveOne(current) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

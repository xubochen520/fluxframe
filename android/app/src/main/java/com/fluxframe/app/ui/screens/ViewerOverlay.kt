package com.fluxframe.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
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
import coil.size.Precision
import coil.size.Size as CoilSize
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.repo.EmbedRepository
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.core.util.formatDateTime
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.ZoomMath
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.glass.GlassSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏查看器。
 *
 * - 图片：1600 宽变体 + 双指缩放/双击放大
 * - 视频：ExoPlayer 直接播放 `/api/images/:id/file`（服务端支持 Range，可拖动进度）
 * - 翻页时自动记一次浏览（后端据此统计「查看图片 / 查看视频」并累加浏览次数）
 *
 * ### 顶栏与底部标签会自动隐藏（v2.1.2）
 * 看图看视频时最不需要的就是压在上面的一堆文字。现在：
 *  - 打开后 [CHROME_TIMEOUT_MS] 毫秒自动淡出，点一下图片 / 点一下视频重新唤出；
 *  - 视频走 ExoPlayer 自己的控制器：控制器出现=显示我们的浮层，控制器收起=一起收起，
 *    两边不会一个显示一个隐藏。
 */
@Composable
fun ViewerOverlay(
    mediaId: String?,
    images: List<ImageItem>,
    onClose: () -> Unit,
    onMediaIdChange: (String) -> Unit,
    onToast: (String?) -> Unit,
) {
    if (mediaId == null || images.isEmpty()) return
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val safeIndex = images.indexOfFirst { it.id == mediaId }
    if (safeIndex < 0) {
        LaunchedEffect(mediaId, images) { onClose() }
        return
    }
    val pagerState = rememberPagerState(initialPage = safeIndex) { images.size }
    val scope = rememberCoroutineScope()
    var showActions by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val current = images.getOrNull(pagerState.currentPage.coerceIn(0, images.lastIndex))

    /* ---- 相似图面板：上滑拉出，也可点顶部「相似图」按钮 ----
       服务端对没建过指纹的图会当场算完再返回（约 0.7~3 秒），所以偶尔慢一次是正常的，
       期间用 Loading 状态兜住，不要让它看起来像卡死。 */
    var similarState by remember { mutableStateOf<SimilarUiState>(SimilarUiState.Idle) }
    var similarForId by remember { mutableStateOf<String?>(null) }

    fun loadSimilar(item: ImageItem) {
        if (similarForId == item.id && similarState is SimilarUiState.Ready) return
        similarForId = item.id
        similarState = SimilarUiState.Loading
        scope.launch {
            val result = container.embedRepository.similarWithRetry(item.id)
            // 期间用户可能已经翻到别的图，丢弃过期结果
            if (similarForId != item.id) return@launch
            similarState = result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is EmbedRepository.SimilarOutcome.Ok ->
                            SimilarUiState.Ready(outcome.items, outcome.threshold)
                        is EmbedRepository.SimilarOutcome.Analyzing ->
                            SimilarUiState.Message("还在建立指纹", "这张图的视觉指纹正在计算，稍等几秒再上滑一次即可。")
                        is EmbedRepository.SimilarOutcome.Unsupported ->
                            SimilarUiState.Message("这张图没有相似图", outcome.reason)
                    }
                },
                onFailure = { error ->
                    SimilarUiState.Message("读取失败", error.toApiException().message)
                },
            )
        }
    }

    /** 翻页 / 关闭面板时清掉上一张图的结果，避免看到错位的相似图 */
    fun closeSimilar() {
        similarState = SimilarUiState.Idle
        similarForId = null
    }

    /** 当前这一页的图片是否被放大（放大时拖动是看细节，不该被上滑抢走） */
    var currentZoomed by remember { mutableStateOf(false) }

    if (uiPrefs.predictiveBackEnabled) {
        PredictiveBackHandler { progress ->
            try {
                progress.collect { event -> backProgress = event.progress }
                onClose()
            } finally {
                backProgress = 0f
            }
        }
    } else {
        BackHandler { onClose() }
    }

    LaunchedEffect(pagerState.currentPage) {
        images.getOrNull(pagerState.currentPage)?.let { onMediaIdChange(it.id) }
        // 翻到新的一页时把浮层重新亮出来，让人知道自己在看第几张
        chromeVisible = true
        // 也把上一张的相似图收起来：留着会让人以为看的是当前这张的相似图
        closeSimilar()
        current?.let { item ->
            // 记一次浏览（失败静默：这不该打扰看图）
            container.mediaRepository.markViewed(item.id)
                .onSuccess { views -> container.mediaStore.applyViewed(item.id, views) }
        }
    }

    // 自动刷新或排序改变列表位置时，继续跟随同一个媒体 ID，而不是沿用旧下标。
    LaunchedEffect(images.map { it.id }, mediaId) {
        val target = images.indexOfFirst { it.id == mediaId }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }

    // Android 14+ Ultra HDR gain map、HDR10/HDR10+ 与 Dolby Vision 都需要 HDR 窗口
    // 才能输出完整动态范围；不支持的设备会由系统安全回落到 SDR。
    DisposableEffect(uiPrefs.hdrDisplayEnabled) {
        val activity = context.findActivity()
        val oldMode = activity?.window?.colorMode
        if (uiPrefs.hdrDisplayEnabled) activity?.window?.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { if (oldMode != null) activity.window.colorMode = oldMode }
    }

    // 自动隐藏：只要浮层是显示的，就起一个倒计时；任何一次唤出都会重置它
    LaunchedEffect(chromeVisible, pagerState.currentPage) {
        if (chromeVisible) {
            delay(CHROME_TIMEOUT_MS)
            chromeVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = size.width * 0.28f * backProgress
                scaleX = 1f - 0.06f * backProgress
                scaleY = 1f - 0.06f * backProgress
                alpha = 1f - 0.22f * backProgress
            }
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp,
        ) { page ->
            val item = images.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo || item.isMotionPhoto) {
                VideoPage(
                    item = item,
                    onChromeVisibilityChange = { visible -> chromeVisible = visible },
                )
            } else {
                ImagePage(
                    item = item,
                    isCurrent = page == pagerState.currentPage,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onZoomChange = { zoomed -> if (page == pagerState.currentPage) currentZoomed = zoomed },
                    onSwipeUp = { if (page == pagerState.currentPage) loadSimilar(item) },
                )
            }
        }

        // ---- 顶部信息栏（自动隐藏）----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "关闭",
                    onClick = onClose,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current?.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = buildString {
                            append("${pagerState.currentPage + 1} / ${images.size}")
                            current?.let {
                                if (it.width > 0) append(" · ${it.width}×${it.height}")
                                append(" · ${it.size}")
                                append(" · ${it.views} 次浏览")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassIconButton(
                    icon = Icons.Filled.Download,
                    contentDescription = "下载",                    onClick = {
                        current?.let { item ->
                            onToast("正在准备原文件…")
                            scope.launch {
                                container.mediaDownloader.enqueue(item)
                                    .onSuccess { onToast("已开始下载，完成后自动保存到图库") }
                                    .onFailure { onToast(it.message) }
                            }
                        }
                    },
                )
                GlassIconButton(
                    icon = Icons.Filled.Share,
                    contentDescription = "分享原文件",
                    onClick = {
                        current?.let { item ->
                            scope.launch {
                                container.mediaSharer.share(context, listOf(item))
                                    .onFailure { onToast(it.message ?: "分享失败") }
                            }
                        }
                    },
                )
                // 相似图入口：上滑也能拉出，但手势看不见，留个按钮让人发现这个功能。
                // 视频没有视觉指纹，直接不给入口，避免点开只看到「不支持」。
                if (current != null && !current.isVideo) {
                    GlassIconButton(
                        icon = Icons.Filled.AutoAwesome,
                        contentDescription = if (similarState is SimilarUiState.Idle) "查看相似图片" else "收起相似图",
                        onClick = {
                            if (similarState is SimilarUiState.Idle) loadSimilar(current)
                            else closeSimilar()
                        },
                    )
                }
                GlassIconButton(
                    icon = Icons.Filled.MoreHoriz,
                    contentDescription = "更多",
                    onClick = { showActions = true },
                )
            }
        }

        // ---- 底部标签栏（自动隐藏）----
        current?.let { item ->
            if (item.tags.isNotEmpty() || item.capturedAt.isNotBlank()) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(16.dp),
                        backdrop = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.tags.isNotEmpty()) {
                                Text(
                                    text = item.tags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.92f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "上传于 ${formatDateTime(item.uploadedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }

        // ---- 相似图面板（底部升起；上滑或点顶部按钮拉出）----
        SimilarPanel(
            state = similarState,
            onClose = { closeSimilar() },
            onPick = { picked ->
                /* 点相似图里的一张：如果它本来就在当前翻页列表里就直接翻过去，
                   否则提示用户 —— 不去动 pager 的数据源，避免打乱当前的浏览上下文。 */
                val target = images.indexOfFirst { it.id == picked.id }
                if (target >= 0) {
                    scope.launch { pagerState.animateScrollToPage(target) }
                    closeSimilar()
                } else {
                    onToast("「${picked.name}」不在当前列表里，可在图片库中打开")
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showActions && current != null) {
        MediaActionDialog(
            item = current,
            isAdmin = container.sessionStore.isAdmin,
            onDismiss = { showActions = false },
            onToast = onToast,
        )
    }
}

/** 浮层自动隐藏的等待时间 */
private const val CHROME_TIMEOUT_MS = 3200L

/** 双击缩放的动画时长 */
private const val ZOOM_DURATION_MS = 340

/** 图片页：双指缩放 + 双击以「点击点」为焦点放大/复位 + 单击切换浮层 + 未放大时上滑看相似图 */
@Composable
private fun ImagePage(
    item: ImageItem,
    isCurrent: Boolean,
    onToggleChrome: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onSwipeUp: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(item.id) { mutableStateOf(Size.Zero) }
    var zoomJob by remember(item.id) { mutableStateOf<Job?>(null) }

    /**
     * 用三次贝塞尔缓动把缩放与位移一起推到目标值。
     *
     * 只驱动一个 0→1 的进度值，再线性插值出 scale 与 offset ——
     * 这样两者天然同步，不会出现"图像先放大再挪位"的割裂感，
     * 而且只需要一条贝塞尔曲线、一个动画协程。
     */
    fun animateZoom(targetScale: Float, targetOffset: Offset) {
        zoomJob?.cancel()
        val startScale = scale
        val startOffset = offset
        zoomJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(ZOOM_DURATION_MS, easing = ZoomMath.ZoomEasing),
            ) { progress, _ ->
                scale = startScale + (targetScale - startScale) * progress
                offset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * progress,
                )
            }
        }
    }

    /** 把「是否处于放大状态」同步给外层：放大时不接管上滑，拖动手势要留给看细节 */
    LaunchedEffect(scale) { onZoomChange(scale > 1.02f) }

    /* 上滑看相似图的累计位移。
       注意：**必须搭在下面那个 detectTransformGestures 里判断**，不能另挂一个 pointerInput ——
       子节点（AsyncImage）的手势检测器会先拿到事件并消耗掉，挂在外层 Box 上的聆听器收不到任何东西
       （实测：真机手势完全没反应）。搭在现有检测器里则不存在手势竞争。 */
    var swipeUpAccum by remember(item.id) { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(container.mediaRepository.previewUrl(item))
                // 不让 Coil 按屏幕尺寸降采样。查看器允许继续放大，必须保留原始像素。
                .size(CoilSize.ORIGINAL)
                .precision(Precision.EXACT)
                .allowHardware(true)
                .crossfade(true)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it.toSize() }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(item.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // 手动捏合时取消正在跑的双击动画，避免两边抢同一个状态
                        zoomJob?.cancel()
                        val next = ZoomMath.scaleBy(scale, zoom)
                        scale = next
                        if (next > 1.02f) {
                            offset = ZoomMath.clamp(offset + pan, next, boxSize)
                            // 放大状态下拖动是在看细节，不当作「看相似图」
                            swipeUpAccum = 0f
                        } else {
                            offset = Offset.Zero
                            /* 未放大时：把竖直方向的累计位移用来判定「上滑看相似图」。
                               只用竖直分量，横向拖动交给 HorizontalPager 翻页。 */
                            swipeUpAccum = if (kotlin.math.abs(pan.y) >= kotlin.math.abs(pan.x)) {
                                swipeUpAccum + pan.y
                            } else {
                                0f
                            }
                            if (isCurrent && swipeUpAccum < -SWIPE_UP_PX) {
                                /* 先清零再回调：detectTransformGestures 在一次滑动里会连发多次，
                                   不清零就会连续触发（实测一次上滑触发 8 次）。
                                   清零靠的是 Compose 的快照状态，回调里的重活另起协程，不阻塞手势。 */
                                swipeUpAccum = 0f
                                onSwipeUp()
                            }
                        }
                    }
                }
                .pointerInput(item.id) {
                    val box = size.toSize()
                    detectTapGestures(
                        onTap = { onToggleChrome() },
                        onDoubleTap = { tap ->
                            if (scale > 1.05f) {
                                // 已经在放大状态 → 复位
                                animateZoom(1f, Offset.Zero)
                            } else {
                                // 以点击位置为焦点放大：那个像素留在原地
                                val target = ZoomMath.DOUBLE_TAP_SCALE
                                animateZoom(target, ZoomMath.focalOffset(tap, box, target))
                            }
                        },
                    )
                },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 视频页：ExoPlayer，复用带会话 Cookie 的 OkHttp 数据源 */
@Composable
private fun VideoPage(item: ImageItem, onChromeVisibilityChange: (Boolean) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current

    val player = remember(item.id) {
        val httpFactory = OkHttpDataSource.Factory(container.okHttp)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(container.mediaRepository.fileUrl(item)))
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
    }

    DisposableEffect(item.id) {
        onDispose { player.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    // 播放器控制器就是视频页的"浮层开关"：它出现我们就显示标签栏，
                    // 它收起我们就一起收起，避免一个在动一个不动
                    controllerShowTimeoutMs = CHROME_TIMEOUT_MS.toInt()
                    // 两个重载（新的 PlayerView.ControllerVisibilityListener 与旧的
                    // PlayerControlView.VisibilityListener）需要显式指明，否则有歧义
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            onChromeVisibilityChange(visibility == android.view.View.VISIBLE)
                        },
                    )
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

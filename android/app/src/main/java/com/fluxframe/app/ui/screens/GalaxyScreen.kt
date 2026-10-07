package com.fluxframe.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.data.model.EmbedGraph
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.galaxy.GalaxyMath
import com.fluxframe.app.ui.galaxy.GalaxyThumbCache
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/** 星系图里的一个节点（已把接口数据摊平成画图要用的形状） */
private data class GalaxyNode(
    val item: ImageItem,
    val x: Float,
    val y: Float,
    val color: Color,
    val character: String,
    val degree: Int,
)

/** 一条相似边，存的是节点下标 */
private data class GalaxyEdgeRef(val a: Int, val b: Int, val score: Float)

private data class GalaxyLayout(
    val nodes: List<GalaxyNode>,
    val edges: List<GalaxyEdgeRef>,
    val points: List<Offset>,
)

/**
 * 相似图星系图（原生版）。
 *
 * 与网页端同一套数据、同一套视觉：服务端把 768 维 CCIP 指纹降到二维，这里用 Compose 的
 * Canvas 把每张图画成一个星点，放大后淡入缩略图。
 *
 * 【为什么不用 WebView 直接嵌网页那版】星图要跟手，手势与缩放必须走原生；
 * 而且 WebView 会把整页的登录态、主题、流体背景重新实现一遍，两套东西迟早不一致。
 * 计算部分已经抽到 [GalaxyMath] 里，两端共用同一套公式与常量。
 */
@Composable
fun GalaxyScreen(
    onOpenImage: (String) -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    var graph by remember { mutableStateOf<EmbedGraph?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean = false) {
        scope.launch {
            loading = true
            container.embedRepository.graph(force = force)
                .onSuccess { graph = it; error = null }
                .onFailure { error = it.message ?: "读取关系网失败" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val layout = remember(graph) { buildLayout(graph, container) }

    /* ------------------------------ 相机 ------------------------------ */
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var fit by remember { mutableStateOf(GalaxyMath.Camera(0f, 0f, 1f)) }
    var camera by remember { mutableStateOf(fit) }
    var animateJob by remember { mutableStateOf<Job?>(null) }

    /** 飞到某个镜头位置（短缓动，不做复杂物理） */
    fun flyTo(target: GalaxyMath.Camera, durationMs: Float = 420f) {
        animateJob?.cancel()
        animateJob = scope.launch {
            val start = camera
            var elapsed = 0f
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) elapsed += (now - last) / 1_000_000f
                    last = now
                }
                val t = (elapsed / durationMs).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t)
                camera = GalaxyMath.Camera(
                    centerX = start.centerX + (target.centerX - start.centerX) * eased,
                    centerY = start.centerY + (target.centerY - start.centerY) * eased,
                    zoom = start.zoom + (target.zoom - start.zoom) * eased,
                )
                if (t >= 1f) break
            }
        }
    }

    // 画布尺寸或数据变化时重新取景。取景按节点实际包围盒算，不假设布局铺满 [-1,1]
    LaunchedEffect(canvasSize, layout) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f || layout.points.isEmpty()) return@LaunchedEffect
        val next = GalaxyMath.fitCamera(GalaxyMath.bounds(layout.points), canvasSize.width, canvasSize.height)
        fit = next
        camera = next
    }

    /* ------------------------------ 选中 / 搜索 ------------------------------ */
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var activeCharacter by remember { mutableStateOf<String?>(null) }

    val selectedIndex = remember(layout, selectedId) { layout.nodes.indexOfFirst { it.item.id == selectedId } }
    val focusIndex = if (selectedIndex >= 0) selectedIndex else -1

    /** 每个节点的明暗权重：搜索与角色图例都会压暗无关的点 */
    val emphasis = remember(layout, query, activeCharacter) {
        FloatArray(layout.nodes.size) { index ->
            val node = layout.nodes[index]
            var hit = activeCharacter == null || node.character == activeCharacter
            val text = query.trim().lowercase()
            if (hit && text.isNotEmpty()) {
                hit = node.item.name.lowercase().contains(text) ||
                    node.character.lowercase().contains(text) ||
                    node.item.tags.any { it.lowercase().contains(text) }
            }
            if (hit) 1f else 0.1f
        }
    }
    val filtering = query.isNotBlank() || activeCharacter != null
    val hitCount = if (!filtering) layout.nodes.size else emphasis.count { it > 0.5f }

    val legend = remember(layout) {
        layout.nodes.filter { it.character.isNotEmpty() }
            .groupingBy { it.character }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key to it.value }
    }

    /** 标签颜色：详情卡片里的小标签用真实颜色，不是清一色的紫 */
    val allTags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val tagColors = remember(allTags) { allTags.associate { it.name to it.color } }

    /* ------------------------------ 缩略图 ------------------------------ */
    val thumbs = remember { GalaxyThumbCache(context) }
    DisposableEffect(Unit) { onDispose { thumbs.clear() } }

    val thumbAlpha = GalaxyMath.thumbAlpha(camera.zoom)
    val thumbPx = GalaxyMath.thumbScreenPx(camera.zoom)

    /*
     * 缩略图按需加载：每 200ms 扫一遍当前屏幕里、且已经放大到该显示缩略图的节点，
     * 把需要的那几张排进队列。
     *
     * 两个刻意的写法：
     *  - 不用 LaunchedEffect(camera)：拖动/捏合时相机每帧都在变，那样会疯狂重启协程，
     *    每次重启又会立刻扫一遍，等于每帧发一批请求。固定节奏轮询更省也更可控。
     *  - 钥匙里不放 thumbAlpha：它是 zoom 的连续函数，捏合时每帧都变 —— 同样会每帧重启。
     *    改成在循环里读当前缩放，效果一样但完全可控。
     */
    LaunchedEffect(layout, canvasSize) {
        if (canvasSize.width <= 0f) return@LaunchedEffect
        while (true) {
            val zoomNow = camera.zoom
            if (GalaxyMath.thumbAlpha(zoomNow) > 0.05f) {
                val viewportWidth = canvasSize.width
                val viewportHeight = canvasSize.height
                val thumbPxNow = GalaxyMath.thumbScreenPx(zoomNow)
                val cameraNow = camera
                var started = 0
                for (index in layout.nodes.indices) {
                    if (started >= MAX_REQUESTS_PER_TICK) break
                    val node = layout.nodes[index]
                    val screen = GalaxyMath.worldToScreen(node.x, node.y, cameraNow, viewportWidth, viewportHeight)
                    if (screen.x < -thumbPxNow || screen.x > viewportWidth + thumbPxNow) continue
                    if (screen.y < -thumbPxNow || screen.y > viewportHeight + thumbPxNow) continue
                    val key = node.item.id
                    if (thumbs.has(key)) continue
                    thumbs.request(key, container.embedRepository.thumbUrl(node.item), thumbPxNow.roundToInt(), scope)
                    started++
                }
                thumbs.trim()
            }
            delay(200)
        }
    }

    /* ------------------------------ 手势 ------------------------------ */
    /*
     * 最近一次「变形手势」（拖动/捏合）的时刻。
     * 两个手势检测器各自独立收事件，拖动结束后 detectTapGestures 仍可能补一个 onTap，
     * 于是"拖完星图手一松就选中了某个节点"。用这个时间戳把紧跟其后的 tap 挡掉。
     */
    var lastGestureAt by remember { mutableStateOf(0L) }
    val gestureModifier = Modifier
        .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(fit, layout) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                animateJob?.cancel()
                lastGestureAt = System.currentTimeMillis()
                val panned = GalaxyMath.panCamera(camera, pan.x, pan.y)
                camera = if (zoom != 1f) {
                    GalaxyMath.zoomCameraAt(panned, zoom, centroid.x, centroid.y, size.width.toFloat(), size.height.toFloat(), fit)
                } else {
                    panned
                }
            }
        }
        .pointerInput(layout, fit) {
            detectTapGestures { tap ->
                if (System.currentTimeMillis() - lastGestureAt < TAP_AFTER_GESTURE_MS) return@detectTapGestures
                val index = GalaxyMath.pick(
                    positions = layout.points,
                    camera = camera,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                    tapX = tap.x,
                    tapY = tap.y,
                )
                selectedId = if (index >= 0) layout.nodes[index].item.id else null
            }
        }

    /* ------------------------------ 画面 ------------------------------ */
    Box(modifier = Modifier.fillMaxSize()) {
        val background = remember(dark) {
            Brush.verticalGradient(listOf(Color(0xFF070B16), Color(0xFF0A0F1F), Color(0xFF05070F)))
        }
        val stars = remember { starField() }
        val thumbnailPath = remember { Path() }
        val cornerRadius = remember { 10f }

        Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
            drawRect(brush = background)

            // 远景星尘：固定屏幕分布 + 跟着镜头轻微平移，制造一点纵深
            val parallaxX = -camera.centerX * 26f
            val parallaxY = camera.centerY * 26f
            for (star in stars) {
                val sx = (((star.x * size.width + parallaxX) % size.width) + size.width) % size.width
                val sy = (((star.y * size.height + parallaxY) % size.height) + size.height) % size.height
                drawCircle(Color.White.copy(alpha = star.alpha), radius = star.radius, center = Offset(sx, sy))
            }

            if (layout.nodes.isEmpty()) return@Canvas

            // ---- 相似边 ----
            val edgeAlphaArray = FloatArray(layout.edges.size)
            for (i in layout.edges.indices) {
                val edge = layout.edges[i]
                val touches = focusIndex == edge.a || focusIndex == edge.b
                edgeAlphaArray[i] = GalaxyMath.edgeAlpha(
                    score = edge.score,
                    touchesFocus = touches,
                    hasFocus = focusIndex >= 0,
                    emphasis = minOf(emphasis[edge.a], emphasis[edge.b]),
                )
            }
            for (i in layout.edges.indices) {
                val alpha = edgeAlphaArray[i]
                if (alpha < 0.01f) continue
                val edge = layout.edges[i]
                val a = GalaxyMath.worldToScreen(layout.nodes[edge.a].x, layout.nodes[edge.a].y, camera, size.width, size.height)
                val b = GalaxyMath.worldToScreen(layout.nodes[edge.b].x, layout.nodes[edge.b].y, camera, size.width, size.height)
                drawLine(
                    color = layout.nodes[edge.a].color.copy(alpha = alpha),
                    start = a,
                    end = b,
                    strokeWidth = 1f,
                    blendMode = BlendMode.Plus,
                )
            }

            // ---- 星点（缩略图淡入时同时淡出）----
            val pointRadius = GalaxyMath.pointScreenRadius(camera.zoom)
                .coerceIn(1.5f, 26f) * (1f - thumbAlpha * 0.72f)
            val pointAlpha = 1f - thumbAlpha * 0.72f
            if (pointRadius > 0.2f) {
                for (index in layout.nodes.indices) {
                    val node = layout.nodes[index]
                    val center = GalaxyMath.worldToScreen(node.x, node.y, camera, size.width, size.height)
                    if (center.x < -40f || center.x > size.width + 40f || center.y < -40f || center.y > size.height + 40f) continue
                    var alpha = emphasis[index] * pointAlpha
                    if (focusIndex >= 0 && index != focusIndex) alpha *= 0.35f
                    if (alpha <= 0.01f) continue
                    val radius = pointRadius * (1f + node.degree.coerceAtMost(12) * 0.045f)
                    /*
                     * 三层同心圆 + 加色混合（BlendMode.Plus）：网页端是 WebGL 的加色混合，
                     * Compose 这边用 Plus 能得到同样的"星点自己会发光"的效果 ——
                     * 普通 alpha 叠加会变成一片糊住的半透明圆盘，密集处看起来像雾。
                     */
                    drawCircle(node.color.copy(alpha = alpha * 0.16f), radius = radius * 3.4f, center = center, blendMode = BlendMode.Plus)
                    drawCircle(node.color.copy(alpha = alpha * 0.34f), radius = radius * 1.8f, center = center, blendMode = BlendMode.Plus)
                    drawCircle(node.color.copy(alpha = alpha), radius = radius, center = center, blendMode = BlendMode.Plus)
                }
            }

            // ---- 缩略图 ----
            if (thumbAlpha > 0.05f) {
                val half = thumbPx / 2f
                for (index in layout.nodes.indices) {
                    val node = layout.nodes[index]
                    val center = GalaxyMath.worldToScreen(node.x, node.y, camera, size.width, size.height)
                    if (center.x < -half || center.x > size.width + half) continue
                    if (center.y < -half || center.y > size.height + half) continue
                    val bitmap = thumbs.get(node.item.id) ?: continue
                    var alpha = thumbAlpha * emphasis[index]
                    if (focusIndex >= 0 && index != focusIndex) alpha *= 0.42f
                    if (alpha <= 0.02f) continue

                    val left = center.x - half
                    val top = center.y - half
                    thumbnailPath.rewind()
                    thumbnailPath.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = left,
                            top = top,
                            right = left + thumbPx,
                            bottom = top + thumbPx,
                            radiusX = cornerRadius,
                            radiusY = cornerRadius,
                        ),
                    )
                    clipPath(thumbnailPath) {
                        drawImage(
                            image = bitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(thumbPx.roundToInt(), thumbPx.roundToInt()),
                            alpha = alpha,
                        )
                    }
                    // 内侧亮边：缩略图挨在一起时才分得开
                    drawRoundRect(
                        color = node.color.copy(alpha = alpha * 0.5f),
                        topLeft = Offset(left, top),
                        size = Size(thumbPx, thumbPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                        style = Stroke(width = 1.2f),
                    )
                }
            }
        }

        /* ------------------------------ 叠层 UI ------------------------------ */

        if (loading && graph == null) {
            LoadingBox(text = "正在读取关系网…", modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        val payload = graph
        if (payload == null || !payload.ready || payload.nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (payload?.ready == false) "服务端还没准备好相似图索引" else "还没有建立视觉指纹",
                    description = "视觉指纹是「找相似图」的依据，每张图算一次（约 0.7 秒）。" +
                        "上传新图会自动算；库里的历史图片需要在网页端或服务端的设置里点一次「建立索引」。",
                    icon = Icons.Filled.CenterFocusStrong,
                    actionLabel = "重新读取",
                    onAction = { load(force = true) },
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (error != null) {
                ErrorBar(
                    message = error!!,
                    onRetry = { load(force = true) },
                    onDismiss = { error = null },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.isNotBlank()) activeCharacter = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜图片名 / 角色 / 标签…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            modifier = Modifier.size(18.dp).clickable { query = "" },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            if (filtering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "高亮 $hitCount / ${layout.nodes.size} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "定位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val matched = layout.nodes.filterIndexed { index, _ -> emphasis[index] > 0.5f }
                            if (matched.isEmpty()) {
                                onToast("没有匹配的图片")
                            } else {
                                val avgX = matched.sumOf { it.x.toDouble() }.toFloat() / matched.size
                                val avgY = matched.sumOf { it.y.toDouble() }.toFloat() / matched.size
                                flyTo(GalaxyMath.Camera(avgX, avgY, fit.zoom * 2.4f))
                            }
                        }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "全部显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { query = ""; activeCharacter = null }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (legend.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(legend, key = { it.first }) { entry ->
                        CharacterChip(
                            name = entry.first,
                            count = entry.second,
                            active = activeCharacter == entry.first,
                            onClick = {
                                activeCharacter = if (activeCharacter == entry.first) null else entry.first
                                query = ""
                                if (activeCharacter != null) {
                                    val target = layout.nodes.firstOrNull { it.character == activeCharacter }
                                    if (target != null) {
                                        selectedId = target.item.id
                                        flyTo(GalaxyMath.Camera(target.x, target.y, fit.zoom * 3.4f))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // 缩放控件
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "放大",
                onClick = {
                    animateJob?.cancel()
                    camera = GalaxyMath.zoomCameraAt(camera, 1.35f, canvasSize.width / 2f, canvasSize.height / 2f, canvasSize.width, canvasSize.height, fit)
                },
            )
            GlassIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = "缩小",
                onClick = {
                    animateJob?.cancel()
                    camera = GalaxyMath.zoomCameraAt(camera, 1f / 1.35f, canvasSize.width / 2f, canvasSize.height / 2f, canvasSize.width, canvasSize.height, fit)
                },
            )
            GlassIconButton(
                icon = Icons.Filled.CenterFocusStrong,
                contentDescription = "回到全景",
                onClick = { flyTo(fit) },
            )
        }

        // 提示 / 统计
        Text(
            text = if (thumbAlpha < 0.5f) {
                "双指缩放 · 拖动平移 · 点一下看详情（再放大些就显示缩略图）"
            } else {
                "双指缩放 · 拖动平移 · 点一下看详情"
            },
            style = MaterialTheme.typography.labelSmall,
            color = onGlassColor(dark, emphasis = false),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
        )

        // 选中卡片
        val selectedItem = if (selectedIndex >= 0) layout.nodes[selectedIndex].item else null
        if (selectedItem != null) {
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                backdrop = true,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedItem.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = buildString {
                                    val character = layout.nodes[selectedIndex].character
                                    if (character.isNotEmpty()) append("$character · ")
                                    append("${layout.nodes[selectedIndex].degree} 张相似")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = onGlassColor(dark, emphasis = false),
                            )
                        }
                        Text(
                            text = "打开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenImage(selectedItem.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (selectedItem.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedItem.tags.take(4).forEach { tag ->
                                TagChip(
                                    name = tag,
                                    colorHex = tagColors[tag] ?: "#a78bfa",
                                    onClick = null,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/* ------------------------------ 小工具 ------------------------------ */

@Composable
private fun CharacterChip(name: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val color = GalaxyMath.characterColor(name)
    GlassSurface(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onClick() },
        backdrop = false,
        borderWidth = if (active) 1.4.dp else 0.6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text = name, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = onGlassColor(LocalDarkTheme.current, emphasis = false),
            )
        }
    }
}

/** 星尘的坐标是 0~1 的相对值，绘制时乘上画布尺寸 */
private data class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/**
 * 背景星尘。固定种子生成，每次进页面都是同一片天空（不会一闪一闪地"重新洗牌"）。
 * 位置用 0~1 的相对值存，绘制时再乘画布尺寸，这样旋转屏幕也不会挤成一团。
 */
private fun starField(): List<Star> {
    val random = Random(0x5EED)
    return List(150) {
        Star(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.6f + random.nextFloat() * 1.5f,
            alpha = 0.10f + random.nextFloat() * 0.35f,
        )
    }
}

/** 每个 200ms 周期最多发起多少张缩略图请求：太多会挤掉正在滑动的列表 */
private const val MAX_REQUESTS_PER_TICK = 12

/** 拖动/捏合结束后这么久内的「点击」一律忽略（那是手指离开时的抖动，不是点选） */
private const val TAP_AFTER_GESTURE_MS = 220L

/** 把接口数据摊平成画图要用的形状 */
private fun buildLayout(graph: EmbedGraph?, container: com.fluxframe.app.core.di.AppContainer): GalaxyLayout {
    if (graph == null) return GalaxyLayout(emptyList(), emptyList(), emptyList())

    val nodes = ArrayList<GalaxyNode>(graph.nodes.size)
    val indexById = HashMap<String, Int>(graph.nodes.size * 2)
    for (item in graph.nodes) {
        val position = graph.positions[item.id] ?: continue
        if (position.size < 2) continue
        val character = graph.characters[item.id].orEmpty()
        indexById[item.id] = nodes.size
        nodes.add(
            GalaxyNode(
                item = item,
                x = position[0].toFloat(),
                y = position[1].toFloat(),
                color = GalaxyMath.characterColor(character),
                character = character,
                degree = 0,
            ),
        )
    }

    val edges = ArrayList<GalaxyEdgeRef>(graph.edges.size)
    val degree = IntArray(nodes.size)
    for (edge in graph.edges) {
        val a = indexById[edge.a] ?: continue
        val b = indexById[edge.b] ?: continue
        edges.add(GalaxyEdgeRef(a, b, edge.score.toFloat()))
        degree[a]++
        degree[b]++
    }

    val decorated = nodes.mapIndexed { index, node -> node.copy(degree = degree[index]) }
    return GalaxyLayout(
        nodes = decorated,
        edges = edges,
        points = decorated.map { Offset(it.x, it.y) },
    )
}

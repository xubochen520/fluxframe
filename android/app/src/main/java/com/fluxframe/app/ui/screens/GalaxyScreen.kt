package com.fluxframe.app.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.fluxframe.app.data.model.EmbedGraphMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.data.model.EmbedGraph
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MediaThumb
import com.fluxframe.app.ui.components.PillSelector
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.galaxy.Galaxy3DMath
import com.fluxframe.app.ui.galaxy.GalaxyMath
import com.fluxframe.app.ui.galaxy.GalaxyThumbCache
import com.fluxframe.app.ui.galaxy.GalaxyBackdrop
import com.fluxframe.app.ui.galaxy.drawGalaxyGlow
import com.fluxframe.app.ui.galaxy.galaxyDustPalette
import com.fluxframe.app.ui.galaxy.galaxyEdgeColor
import com.fluxframe.app.ui.galaxy.galaxyGlowSprite
import com.fluxframe.app.ui.galaxy.rememberGalaxyLowEnd
import com.fluxframe.app.ui.glass.DarkStage
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.FluxRadius
import com.fluxframe.app.ui.theme.FluxType
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.LocalFluidColors
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 星系图里的一个节点（已把接口数据摊平成画图要用的形状） */
private data class GalaxyNode(
    val item: ImageItem,
    val x: Float,
    val y: Float,
    val color: Color,
    val character: String,
    val degree: Int,
    /** 原图长宽比（宽/高）。缩略图按它定形状，不裁成正方形也不拉伸 */
    val aspect: Float = 1f,
    /**
     * 缩略图下面那枚标签芯片的文本，**在布局阶段就建好**。
     * 每帧给十几个节点各 new 一个 AnnotatedString 是绘制阶段不该有的垃圾。
     */
    val chip: AnnotatedString? = null,
)

/** 一条相似边，存的是节点下标 */
private data class GalaxyEdgeRef(val a: Int, val b: Int, val score: Float)

private data class GalaxyLayout(
    val nodes: List<GalaxyNode>,
    val edges: List<GalaxyEdgeRef>,
    /** 命中判定用：世界坐标 + 原图长宽比 */
    val targets: List<GalaxyMath.PickTarget>,
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
    /** 关系的依据。由外层持有，这样在二维/三维之间切换时不会丢掉用户的选择 */
    mode: EmbedGraphMode,
    onModeChange: (EmbedGraphMode) -> Unit,
) {
    /*
     * 星系永远是深空：整页切到深色令牌。
     * 不切的话，浅色主题下会出现"浅色玻璃压在近黑的星图上、深色字压在深空上"——
     * 舞台底是黑的，浮在上面的玻璃与文字就必须按深色那一套走。
     */
    DarkStage {
        GalaxyStageContent(
            onOpenImage = onOpenImage,
            onToast = onToast,
            mode = mode,
            onModeChange = onModeChange,
        )
    }
}

@Composable
private fun GalaxyStageContent(
    onOpenImage: (String) -> Unit,
    onToast: (String?) -> Unit,
    /** 关系的依据。由外层持有，这样在二维/三维之间切换时不会丢掉用户的选择 */
    mode: EmbedGraphMode,
    onModeChange: (EmbedGraphMode) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()
    /* 画星云名称要它来排版；Compose 的文字测量有缓存，逐帧调用不会重复排版 */
    val textMeasurer = rememberTextMeasurer()

    /* ------------------------- 深空舞台的绘制件（全部只建一次） ------------------------- */
    val fluidColors = LocalFluidColors.current
    /** 低端机或关掉动效：关脉冲与尘埃，只留底色、边与节点 */
    val lowEnd = rememberGalaxyLowEnd()
    val backdrop = remember(fluidColors) { GalaxyBackdrop(fluidColors) }
    val dustPalette = remember(fluidColors) { galaxyDustPalette(fluidColors) }
    /** 预渲染的光晕纹理：全应用一张，绘制阶段只 drawImage */
    val glowSprite = remember { galaxyGlowSprite() }
    /** 标签芯片的字样：样式固定，TextMeasurer 的缓存才命中 */
    val chipStyle = remember {
        TextStyle(fontSize = CHIP_FONT_SP.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }

    var graph by remember { mutableStateOf<EmbedGraph?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    /*
     * 「请求的是哪个模式」单独用一个 State 记着，**不能拿外面的 mode 参数来比**：
     * mode 现在是外层传进来的（切二维/三维时不会被重置），它要等重组之后才是新值，
     * 而协程回来的时候读到的还是旧的那一份 —— 于是结果被当成"过期响应"丢掉，
     * 页面永远停在「正在载入」。这个是状态上提时引入的，踩过一次。
     */
    var pendingMode by remember { mutableStateOf(mode) }

    fun load(force: Boolean = false, target: EmbedGraphMode = pendingMode) {
        pendingMode = target
        scope.launch {
            loading = true
            container.embedRepository.graph(mode = target, force = force)
                .onSuccess { if (pendingMode == target) { graph = it; error = null } }
                .onFailure { if (pendingMode == target) error = it.message ?: "读取关系网失败" }
            if (pendingMode == target) loading = false
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
        if (canvasSize.width <= 0f || canvasSize.height <= 0f || layout.targets.isEmpty()) return@LaunchedEffect
        val points = layout.targets.map { Offset(it.worldX, it.worldY) }
        val next = GalaxyMath.fitCamera(GalaxyMath.bounds(points), canvasSize.width, canvasSize.height)
        fit = next
        camera = next
    }

    /* ------------------------------ 选中 / 搜索 ------------------------------ */
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var activeCharacter by remember { mutableStateOf<String?>(null) }

    val selectedIndex = remember(layout, selectedId) { layout.nodes.indexOfFirst { it.item.id == selectedId } }
    val focusIndex = if (selectedIndex >= 0) selectedIndex else -1

    /*
     * 选中脉冲。
     *
     * 推进脉冲的帧循环**只在"有选中点、且没有降级"时才跑**：没有选中的时候一帧都不推，
     * 画面依然完全静止（也就依然省电）。pulseTick 只在绘制阶段被读，
     * 所以它只让 Canvas 重绘，不会触发重组。
     */
    var pulseTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lowEnd, focusIndex) {
        if (lowEnd || focusIndex < 0) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            pulseTick++
        }
    }

    /** 每个节点的光晕颜色滤镜：在 remember 里建好，绘制阶段一次分配都没有 */
    val glowFilters = remember(layout) {
        layout.nodes.map { ColorFilter.tint(it.color, BlendMode.SrcIn) }
    }

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

    /**
     * 星云名称。
     *   · 标签模式：直接用服务端给的标签锚点 —— 图就是围着标签聚起来的，标在锚点上最直观。
     *   · 视觉模式：服务端没给锚点，退回「分组的中心点 + 组里最多的那个标签」。
     */
    val labels = remember(layout, graph, mode) {
        val payload = graph
        if (payload == null) emptyList()
        else if (mode == EmbedGraphMode.TAG && payload.tagAnchors.isNotEmpty()) {
            val anchors = payload.tagAnchors.mapNotNull { (tag, point) ->
                if (point.size < 2) null else tag to Offset(point[0].toFloat(), point[1].toFloat())
            }.toMap()
            val counts = HashMap<String, Int>()
            for (node in layout.nodes) for (tag in node.item.tags) counts[tag] = (counts[tag] ?: 0) + 1
            GalaxyMath.tagAnchorLabels(anchors, counts) { GalaxyMath.characterColor(it) }
        } else {
            val points = layout.nodes.associate { it.item.id to Offset(it.x, it.y) }
            val primary = layout.nodes.associate { it.item.id to it.character }
            GalaxyMath.groupLabels(
                groups = payload.groups.map { it.members },
                pointOf = { points[it] },
                primaryOf = { primary[it].orEmpty() },
                colorOf = { GalaxyMath.characterColor(it) },
            )
        }
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
                /*
                 * 放大到一定程度就换 768 档：320 档铺到 400px 是拉伸，手机上能看出虚。
                 * 传的地址决定了"这次要下哪一档"，缓存内部会按需要的尺寸重新解码。
                 */
                val useLarge = GalaxyMath.prefersLargeThumb(thumbPxNow)
                val cameraNow = camera
                var started = 0
                for (index in layout.nodes.indices) {
                    if (started >= MAX_REQUESTS_PER_TICK) break
                    val node = layout.nodes[index]
                    val screen = GalaxyMath.worldToScreen(node.x, node.y, cameraNow, viewportWidth, viewportHeight)
                    if (screen.x < -thumbPxNow || screen.x > viewportWidth + thumbPxNow) continue
                    if (screen.y < -thumbPxNow || screen.y > viewportHeight + thumbPxNow) continue
                    val item = node.item
                    val url = if (useLarge) container.embedRepository.largeThumbUrl(item) else container.embedRepository.thumbUrl(item)
                    /* 只统计真正发起请求的那几次，别让"已经有了"把这一轮的额度占满 */
                    if (thumbs.bitmap(item.id, url, thumbPxNow.roundToInt(), scope) == null) started++
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
                    targets = layout.targets,
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
        val stars = remember { starField() }
        val thumbnailPath = remember { Path() }
        Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
            // ---- 深空舞台：底色渐变 + 三色雾 + 暗角（画刷按画布尺寸内部缓存）----
            backdrop.draw(this, size)

            /*
             * 选中脉冲的相位。0.11 rad/帧 ≈ 3.5 秒一个来回 —— 快一点点像心跳，
             * 慢一点点像呼吸；再快就会让人分心，这是"提示这一刻选中了谁"而不是动画表演。
             */
            val pulse = if (focusIndex >= 0 && !lowEnd) {
                sin(pulseTick * 0.11f) * 0.5f + 0.5f
            } else {
                0f
            }

            // ---- 尘埃：按深度染色、跟着镜头视差；降级档直接不画 ----
            if (!lowEnd) {
                val parallaxX = -camera.centerX * 26f
                val parallaxY = camera.centerY * 26f
                for (star in stars) {
                    val sx = (((star.x * size.width + parallaxX) % size.width) + size.width) % size.width
                    val sy = (((star.y * size.height + parallaxY) % size.height) + size.height) % size.height
                    drawCircle(
                        color = dustPalette[star.variant][star.depth].copy(alpha = star.alpha),
                        radius = star.radius,
                        center = Offset(sx, sy),
                    )
                }
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
                val nodeA = layout.nodes[edge.a]
                val nodeB = layout.nodes[edge.b]
                val a = GalaxyMath.worldToScreen(nodeA.x, nodeA.y, camera, size.width, size.height)
                val b = GalaxyMath.worldToScreen(nodeB.x, nodeB.y, camera, size.width, size.height)
                /*
                 * 同组同色：两端属于同一个角色/标签时用那一组的颜色，跨组则取两端的中间色。
                 * 颜色是算出来的（lerp 不分配对象），所以这里不需要为每条边建渐变画刷 ——
                 * 那会让一百多条边每帧产生一百多个垃圾对象。
                 */
                val color = galaxyEdgeColor(
                    colorA = nodeA.color,
                    colorB = nodeB.color,
                    sameGroup = nodeA.character.isNotEmpty() && nodeA.character == nodeB.character,
                )
                drawLine(
                    color = color.copy(alpha = alpha),
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
                    if (center.x < -80f || center.x > size.width + 80f || center.y < -80f || center.y > size.height + 80f) continue
                    var alpha = emphasis[index] * pointAlpha
                    if (focusIndex >= 0 && index != focusIndex) alpha *= 0.35f
                    if (alpha <= 0.01f) continue
                    val radius = pointRadius * (1f + node.degree.coerceAtMost(12) * 0.045f)
                    /*
                     * 一次 drawImage 代替以前的三层同心圆：纹理是预渲染的，颜色由 ColorFilter
                     * 上色（在 remember 里建好），加色混合让密集处的光自然叠亮，
                     * 而不是像普通 alpha 叠加那样糊成一片雾。
                     */
                    drawGalaxyGlow(
                        sprite = glowSprite,
                        centerX = center.x,
                        centerY = center.y,
                        radius = radius * 4.2f,
                        alpha = alpha * 0.5f,
                        colorFilter = glowFilters[index],
                    )
                    drawCircle(node.color.copy(alpha = alpha), radius = radius, center = center, blendMode = BlendMode.Plus)
                    // 中心一点白：最亮的星点要有"灯芯"，不然只是一个个彩色圆盘
                    drawCircle(
                        color = Color.White.copy(alpha = alpha * 0.36f),
                        radius = radius * 0.40f,
                        center = center,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            // ---- 选中脉冲：一圈向外扩散的同色光环 + 一枚常亮的芯 ----
            if (focusIndex >= 0 && pulse > 0f) {
                val focused = layout.nodes[focusIndex]
                val center = GalaxyMath.worldToScreen(focused.x, focused.y, camera, size.width, size.height)
                drawCircle(
                    color = focused.color.copy(alpha = (1f - pulse) * 0.55f),
                    radius = pointRadius * (2.4f + pulse * 2.6f),
                    center = center,
                    style = Stroke(width = 1.6f),
                    blendMode = BlendMode.Plus,
                )
                drawCircle(
                    color = focused.color.copy(alpha = 0.92f),
                    radius = pointRadius * 1.25f,
                    center = center,
                    blendMode = BlendMode.Plus,
                )
            }

            // ---- 缩略图 ----
            if (thumbAlpha > 0.05f) {
                var chips = 0
                for (index in layout.nodes.indices) {
                    val node = layout.nodes[index]
                    val center = GalaxyMath.worldToScreen(node.x, node.y, camera, size.width, size.height)
                    /* 每个格子的宽高按原图比例算：长边是 thumbPx，短边按比例缩 */
                    val box = GalaxyMath.thumbBoxPx(node.aspect, thumbPx)
                    if (center.x < -box.width || center.x > size.width + box.width) continue
                    if (center.y < -box.height || center.y > size.height + box.height) continue
                    val item = node.item
                    val url = if (GalaxyMath.prefersLargeThumb(thumbPx)) {
                        container.embedRepository.largeThumbUrl(item)
                    } else {
                        container.embedRepository.thumbUrl(item)
                    }
                    /* 不够清楚时这里会顺手排一张更大的；当前这张先照画，新的到位自然变清楚 */
                    val bitmap = thumbs.bitmap(item.id, url, thumbPx.roundToInt(), scope) ?: continue
                    var alpha = thumbAlpha * emphasis[index]
                    if (focusIndex >= 0 && index != focusIndex) alpha *= 0.42f
                    if (alpha <= 0.02f) continue

                    val left = center.x - box.width / 2f
                    val top = center.y - box.height / 2f
                    /* 圆角按短边算，长条图也不会变成胶囊形 */
                    val radius = min(GALAXY_THUMB_CORNER, min(box.width, box.height) / 2f)
                    thumbnailPath.rewind()
                    thumbnailPath.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = left,
                            top = top,
                            right = left + box.width,
                            bottom = top + box.height,
                            radiusX = radius,
                            radiusY = radius,
                        ),
                    )
                    clipPath(thumbnailPath) {
                        drawImage(
                            image = bitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                box.width.roundToInt().coerceAtLeast(1),
                                box.height.roundToInt().coerceAtLeast(1),
                            ),
                            alpha = alpha,
                        )
                    }
                    /*
                     * 玻璃边框三层，从外到内：
                     *   1. 外侧一圈更深的暗描边 —— 缩略图挨在一起时才分得开；
                     *   2. 分组色的发丝线 —— 把"这张属于哪个角色/标签"带出来；
                     *   3. 内侧 1px 白高光 —— 玻璃片的那道顶光。
                     */
                    val corner = CornerRadius(radius, radius)
                    drawRoundRect(
                        color = Color.Black.copy(alpha = alpha * 0.5f),
                        topLeft = Offset(left - 1.5f, top - 1.5f),
                        size = Size(box.width + 3f, box.height + 3f),
                        cornerRadius = corner,
                        style = Stroke(width = 2.4f),
                    )
                    drawRoundRect(
                        color = node.color.copy(alpha = alpha * 0.55f),
                        topLeft = Offset(left - 1f, top - 1f),
                        size = Size(box.width + 2f, box.height + 2f),
                        cornerRadius = corner,
                        style = Stroke(width = 1f),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = alpha * 0.20f),
                        topLeft = Offset(left + 0.6f, top + 0.6f),
                        size = Size((box.width - 1.2f).coerceAtLeast(1f), (box.height - 1.2f).coerceAtLeast(1f)),
                        cornerRadius = corner,
                        style = Stroke(width = 1f),
                    )

                    // ---- 标签芯片：图够大、且在关注范围内时才画，免得小图上一堆糊字 ----
                    val chipText = node.chip
                    if (chipText != null &&
                        box.width >= CHIP_MIN_PX &&
                        emphasis[index] > 0.5f &&
                        chips < MAX_CHIPS
                    ) {
                        chips++
                        // 文本在布局阶段就建好了，这里只测量与绘制，不产生任何新对象
                        val measured = textMeasurer.measure(text = chipText, style = chipStyle)
                        val chipWidth = measured.size.width + CHIP_PAD * 2f
                        val chipHeight = measured.size.height + CHIP_PAD
                        val chipLeft = center.x - chipWidth / 2f
                        val chipTop = top + box.height + 5f
                        val chipShape = CornerRadius(chipHeight / 2f, chipHeight / 2f)
                        drawRoundRect(
                            color = Color(0xE60A0D1C),
                            topLeft = Offset(chipLeft, chipTop),
                            size = Size(chipWidth, chipHeight),
                            cornerRadius = chipShape,
                        )
                        drawRoundRect(
                            color = node.color.copy(alpha = alpha * 0.65f),
                            topLeft = Offset(chipLeft, chipTop),
                            size = Size(chipWidth, chipHeight),
                            cornerRadius = chipShape,
                            style = Stroke(width = 1f),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(chipLeft + CHIP_PAD, chipTop + CHIP_PAD / 2f),
                            alpha = alpha.coerceIn(0f, 1f),
                        )
                    }
                }
            }

            // ---- 星云名称 ----
            /*
             * 缩略图一出来就把名字收掉（和网页端一致）：图已经能看出是什么了，
             * 名字继续压在上面只是挡住内容。
             */
            val labelAlpha = (1f - thumbAlpha * 1.6f).coerceIn(0f, 1f)
            if (labelAlpha > 0.02f && labels.isNotEmpty()) {
                /*
                 * **先量好尺寸，再剔除重叠**。
                 *
                 * 一开始按「字数 × 11」估算宽度，高密度屏上差了整整三倍：
                 * 11sp 在 3x 屏上是 33px，不是 11px。框算小了，标签既会互相压住，
                 * 也会伸出画布外被切掉（实测「小鸟游星野 · 1」右边少了个 1）。
                 * TextMeasurer 自带缓存，量二十来个标签不心疼。
                 */
                val measured = labels.map { label ->
                    label to textMeasurer.measure(
                        text = AnnotatedString(label.text),
                        style = TextStyle(fontSize = LABEL_FONT_SIZE_SP.sp, fontWeight = FontWeight.Medium, color = label.color.copy(alpha = labelAlpha)),
                    )
                }
                val widthByKey = measured.associate { (label, layout) -> label.key to layout.size.width.toFloat() }
                val heightPx = measured.firstOrNull()?.second?.size?.height ?: 0
                /*
                 * 标签名分级显示：缩小时只留图最多的几个（满屏名字会糊成一片、还盖住星云），
                 * 放大时一个个放出来。labels 已经按权重降序排好，取前 N 个即可。
                 */
                val budget = Galaxy3DMath.labelBudget(camera.zoom / max(0.001f, fit.zoom), labels.size)
                val visible = GalaxyMath.cullLabels(
                    labels = labels.take(budget),
                    camera = camera,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    widthOf = { label -> (widthByKey[label.key] ?: 0f) + LABEL_PADDING * 2f },
                    height = heightPx + LABEL_PADDING,
                )
                /* measured 是 (Label, TextLayoutResult) 的列表，按 key 建索引才知道该画哪一个 */
                val layoutByKey = measured.associate { (label, layout) -> label.key to layout }
                for (label in visible) {
                    val layout = layoutByKey[label.key] ?: continue
                    /* cullLabels 会把贴边的名称往画布内挪，偏移量挂在 label 上带回来 */
                    val screen = GalaxyMath.worldToScreen(label.x, label.y, camera, size.width, size.height)
                    val boxWidth = layout.size.width + LABEL_PADDING * 2f
                    val boxHeight = layout.size.height + LABEL_PADDING
                    val left = screen.x + label.offsetX - boxWidth / 2f
                    val top = screen.y + label.offsetY - boxHeight / 2f
                    drawRoundRect(
                        color = Color(0.03f, 0.05f, 0.10f, 0.62f * labelAlpha),
                        topLeft = Offset(left, top),
                        size = Size(boxWidth, boxHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxHeight / 2f, boxHeight / 2f),
                    )
                    drawRoundRect(
                        color = label.color.copy(alpha = 0.45f * labelAlpha),
                        topLeft = Offset(left, top),
                        size = Size(boxWidth, boxHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(boxHeight / 2f, boxHeight / 2f),
                        style = Stroke(width = 1f),
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(left + LABEL_PADDING, top + LABEL_PADDING / 2f),
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
                    title = when {
                        mode == EmbedGraphMode.TAG -> "还没有给图片打标签"
                        payload?.ready == false -> "服务端还没准备好相似图索引"
                        else -> "还没有建立视觉指纹"
                    },
                    description = if (mode == EmbedGraphMode.TAG) {
                        "标签模式靠标签连线，库里得先有标签。上传时可以用自动打标，也可以在图片库/标签页里手动补。"
                    } else {
                        "视觉指纹是「找相似图」的依据，每张图算一次（约 0.7 秒）。" +
                            "上传新图会自动算；库里的历史图片需要在网页端或服务端的设置里点一次「建立索引」。"
                    },
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

            /* 关系的依据：视觉指纹（长得像）/ 标签（被打了同一批标记）—— 分段控件 */
            PillSelector(
                options = EmbedGraphMode.entries,
                selected = mode,
                label = { it.label },
                onSelect = { candidate ->
                    if (candidate == mode) return@PillSelector
                    onModeChange(candidate)
                    graph = null
                    load(force = true, target = candidate)
                },
            )
            // 玻璃搜索框：比 Material 的 OutlinedTextField 更贴这套设计
            GlassTextField(
                value = query,
                onValueChange = { query = it; if (it.isNotBlank()) activeCharacter = null },
                placeholder = "搜图片名 / 角色 / 标签…",
                leadingIcon = Icons.Filled.Search,
                imeAction = ImeAction.Search,
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
                        // 与标签页同一个 TagChip：色点 + 名称 + 计数，选中态用组色描边
                        TagChip(
                            name = entry.first,
                            colorHex = characterHex(entry.first),
                            selected = activeCharacter == entry.first,
                            count = entry.second,
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

        // 选中卡片
        val selectedItem = if (selectedIndex >= 0) layout.nodes[selectedIndex].item else null
        if (selectedItem != null) {
            val selectedNode = layout.nodes[selectedIndex]
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                backdrop = true,
                borderWidth = 0.8.dp,
                glow = selectedNode.color,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 一枚圆角缩略图 + 玻璃边框：不用回头去星图里找"刚点的是哪张"
                        MediaThumb(
                            item = selectedItem,
                            size = 44.dp,
                            shape = RoundedCornerShape(FluxRadius.sm),
                            placeholderTint = selectedNode.color,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = selectedItem.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = onGlassColor(true, emphasis = true),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    if (selectedNode.character.isNotEmpty()) append("${selectedNode.character} · ")
                                    append("${selectedNode.degree} 张相似")
                                },
                                style = FluxType.monoSmall,
                                color = onGlassColor(true, emphasis = false),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(selectedNode.color.copy(alpha = 0.18f))
                                .border(0.8.dp, selectedNode.color.copy(alpha = 0.42f), RoundedCornerShape(50))
                                .clickable { onOpenImage(selectedItem.id) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = "打开",
                                style = MaterialTheme.typography.labelMedium,
                                color = selectedNode.color,
                            )
                        }
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
                }
            }
        } else {
            /*
             * 统计 HUD：一条浮在舞台上的玻璃带。
             * 只在**没有选中任何节点**时出现 —— 否则它会和上面的详情卡片抢同一块底部空间。
             */
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp),
                backdrop = true,
                shape = RoundedCornerShape(FluxRadius.md),
                borderWidth = 0.8.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "${layout.nodes.size} 张图 · ${layout.edges.size} 条相似关系",
                        style = FluxType.mono,
                        color = onGlassColor(true, emphasis = true),
                    )
                    Text(
                        text = buildString {
                            append(if (mode == EmbedGraphMode.TAG) "按标签连线" else "按视觉指纹连线")
                            append(" · 双指缩放 · 拖动平移 · 点一下看详情")
                            if (thumbAlpha < 0.5f) append("（再放大些就显示缩略图）")
                        },
                        style = FluxType.monoSmall,
                        color = onGlassColor(true, emphasis = false),
                    )
                }
            }
        }
    }
}

/* ------------------------------ 小工具 ------------------------------ */

/**
 * 角色 / 标签名 → `#rrggbb`。
 *
 * TagChip 收的是十六进制串（标签颜色本来就由服务端下发），而星系的组色是本地哈希出来的
 * Color，所以这里把 Color 转回十六进制，两边的芯片才能长得一模一样。
 * 只取 RGB，丢掉 alpha —— 芯片的透明度由它自己的选中态决定。
 */
private fun characterHex(name: String): String {
    val argb = GalaxyMath.characterColor(name).toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

/** 星尘的坐标是 0~1 的相对值，绘制时乘上画布尺寸 */
private data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    /** 取三色雾里的哪一档（0..11），由固定种子决定 */
    val variant: Int,
    /** 明暗档（0..3）：远景偏冷偏暗、近景偏亮 */
    val depth: Int,
)

/**
 * 背景星尘。固定种子生成，每次进页面都是同一片天空（不会一闪一闪地"重新洗牌"）。
 * 位置用 0~1 的相对值存，绘制时再乘画布尺寸，这样旋转屏幕也不会挤成一团。
 *
 * 颜色不在这里定：只存"取哪一档"，由调用方从 [galaxyDustPalette] 里按下标取 ——
 * 调色板跟着服务端 fluidColors 走，而这个列表是纯随机数，两者分开才能各自缓存。
 */
private fun starField(): List<Star> {
    val random = Random(0x5EED)
    return List(150) {
        Star(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.6f + random.nextFloat() * 1.5f,
            alpha = 0.10f + random.nextFloat() * 0.35f,
            variant = random.nextInt(12),
            depth = random.nextInt(4),
        )
    }
}

/** 缩略图的圆角（px）。按短边收敛，长条图也不会变成胶囊形。 */
private const val GALAXY_THUMB_CORNER = 10f

/** 缩略图短边到了这个宽度才挂标签芯片：再小就只剩糊字了 */
private const val CHIP_MIN_PX = 96f

/** 标签芯片的字号（sp）与内边距（px） */
private const val CHIP_FONT_SP = 10.5f
private const val CHIP_PAD = 5f

/** 一帧最多画多少个标签芯片：满屏芯片会盖住星云本身 */
private const val MAX_CHIPS = 14

/** 每个 200ms 周期最多发起多少张缩略图请求：太多会挤掉正在滑动的列表 */
private const val MAX_REQUESTS_PER_TICK = 12

/** 星云名称的字号（sp）。宽度一律实量，不要用「字数 × 字号」估 —— 那是 px 不是 sp */
private const val LABEL_FONT_SIZE_SP = 11f

/** 星云名称胶囊的内边距（px）：左右用它的两倍，上下用一倍 */
private const val LABEL_PADDING = 8f

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
                /* 接口没给尺寸（个别老数据/视频）时按正方形，至少不会画出个零面积的格子 */
                aspect = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f,
                chip = character.takeIf { it.isNotEmpty() }?.let { AnnotatedString(it) },
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
        targets = decorated.map { GalaxyMath.PickTarget(it.x, it.y, it.aspect) },
    )
}

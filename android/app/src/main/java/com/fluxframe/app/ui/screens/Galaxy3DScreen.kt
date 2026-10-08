package com.fluxframe.app.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.data.model.EmbedGraph
import com.fluxframe.app.data.model.EmbedGraphMode
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassIconButton
import com.fluxframe.app.ui.galaxy.Galaxy3DMath
import com.fluxframe.app.ui.galaxy.GalaxyMath
import com.fluxframe.app.ui.galaxy.GalaxyThumbCache
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** 三维缩略图的世界长边：透视会自己按近大远小缩放 */
private const val THUMB3D_WORLD = 0.085f
/** 屏幕上的长边上限：再大就把星系结构盖住了 */
private const val THUMB3D_MAX_PX = 420f
/** 超过这个长边就换 768 档 */
private const val THUMB3D_LARGE_AT = 210f
/** 推近动画时长（纳秒） */
private const val FOCUS3D_NANOS = 1_100_000_000L
/** 单帧最多画多少张缩略图 */
private const val MAX_THUMBS_3D = 140
/** 太远的点/边直接不画 */
private const val FAR_CULL_3D = 4.2f
/** 标签胶囊的内边距（px）与字号 */
private const val LABEL3D_PADDING = 8f
private const val LABEL3D_FONT_SP = 11f
/** 手势结束后多久恢复自转：太短会在手指刚离开时"抢方向盘" */
private const val SPIN_RESUME_NANOS = 1_200_000_000L

/**
 * 三维星系空间（安卓端）。
 *
 * 与网页端 `SimilarGalaxy3D.vue` + `engine3d.ts` 对应。安卓没有 WebGL，投影是在 Canvas 上手算的；
 * 公式全在 [Galaxy3DMath] 里，与 `camera3d.ts` 逐条对照，两端手感才会一致。
 *
 * 【每帧只重跑绘制阶段】相机放在 Compose 的 State 里、**只在 Canvas 的 draw lambda 里读**，
 * 所以 60fps 更新不会引发重组 —— 这是这个页面能跑顺的关键。
 *
 * 【点击判定另外算】不在 draw 阶段缓存投影（往 State 里写会造成无限重绘），
 * 而是在点击回调里用当前相机现算一遍。一百多个点，一次点击算一遍完全无所谓。
 */
@Composable
fun Galaxy3DScreen(
    onOpenImage: (String) -> Unit,
    onToast: (String?) -> Unit,
    mode: EmbedGraphMode,
    onModeChange: (EmbedGraphMode) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    var graph by remember { mutableStateOf<EmbedGraph?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

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
            container.embedRepository.graph(mode = target, space = "3d", force = force)
                .onSuccess { if (pendingMode == target) { graph = it; error = null } }
                .onFailure { if (pendingMode == target) error = it.message ?: "读取关系网失败" }
            if (pendingMode == target) loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val layout = remember(graph) { buildLayout3D(graph) }
    val thumbs = remember { GalaxyThumbCache(context) }
    DisposableEffect(Unit) { onDispose { thumbs.clear() } }

    var camera by remember { mutableStateOf(Galaxy3DMath.Camera(0.6f, 0.25f, 3f)) }
    var homeCamera by remember { mutableStateOf(Galaxy3DMath.Camera(0.6f, 0.25f, 3f)) }
    var homeDistance by remember { mutableFloatStateOf(3f) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var frameTick by remember { mutableLongStateOf(0L) }

    /* 陀螺仪偏移（平滑后的实际值与目标值分开存，避免传感器噪声让画面抖） */
    var gyroYaw by remember { mutableFloatStateOf(0f) }
    var gyroPitch by remember { mutableFloatStateOf(0f) }
    var gyroTargetYaw by remember { mutableFloatStateOf(0f) }
    var gyroTargetPitch by remember { mutableFloatStateOf(0f) }

    /* 推近动画。用普通对象持有：它不驱动 UI，驱动 UI 的是 camera */
    val focus = remember { FocusState() }
    var lastGestureAt by remember { mutableLongStateOf(0L) }
    /*
     * 推近结束后要打开的那张图。交给一个独立的 effect 去调 onOpenImage ——
     * 帧循环里直接调会把"打开大图"这种重活塞进渲染帧，而且那边读到的 layout 可能是旧的。
     */
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { id ->
            pendingOpen = null
            onOpenImage(id)
        }
    }

    LaunchedEffect(canvasSize, layout) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f || layout.nodes.isEmpty()) return@LaunchedEffect
        homeCamera = solveHome(canvasSize, layout)
        homeDistance = homeCamera.distance
        camera = homeCamera
        focusedIndex = -1
    }

    /* ------------------------------ 帧循环 ------------------------------ */

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val delta = if (last == 0L) 0f else min(0.05f, (now - last) / 1_000_000_000f)
                last = now

                val smooth = min(1f, delta * 6f)
                gyroYaw += (gyroTargetYaw - gyroYaw) * smooth
                gyroPitch += (gyroTargetPitch - gyroPitch) * smooth

                val running = focus.animation
                if (running != null) {
                    val t = (now - running.startedAt).toFloat() / FOCUS3D_NANOS
                    camera = if (t >= 1f) {
                        focus.animation = null
                        /* 镜头停住了再叫大图 —— 用户要的是"先推近，等它几乎停下再打开" */
                        running.openItemId?.let { pendingOpen = it }
                        running.to
                    } else {
                        Galaxy3DMath.lerpCamera(running.from, running.to, t)
                    }
                } else if (focusedIndex < 0 && now - lastGestureAt > SPIN_RESUME_NANOS) {
                    /* 默认缓慢自转。手势刚结束的一小段时间里不自转，否则像在跟手指抢方向盘；
                       推近定格之后也停（再转这张图就糊了）。 */
                    camera = Galaxy3DMath.autoSpin(camera, delta)
                }
                frameTick++
            }
        }
    }

    /* ------------------------------ 陀螺仪 ------------------------------ */

    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        var listener: SensorEventListener? = null
        if (manager != null && sensor != null) {
            val matrix = FloatArray(9)
            val angles = FloatArray(3)
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    SensorManager.getOrientation(matrix, angles)
                    /* angles = [方位角, 俯仰, 横滚]：横滚→左右视差，俯仰离竖直多远→前后视差 */
                    val parallax = Galaxy3DMath.gyroParallax(roll = angles[2], pitch = angles[1])
                    gyroTargetYaw = parallax.first
                    gyroTargetPitch = parallax.second
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { listener?.let { manager?.unregisterListener(it) } }
    }

    /* ------------------------------ 操作 ------------------------------ */

    fun focusOn(index: Int) {
        val node = layout.nodes.getOrNull(index) ?: return
        focusedIndex = index
        focus.animation = FocusAnimation(
            from = camera,
            to = Galaxy3DMath.cinematicTarget(camera, node.x, node.y, node.z),
            startedAt = System.nanoTime(),
            openItemId = node.item.id,
        )
    }

    fun resetView() {
        focusedIndex = -1
        focus.animation = FocusAnimation(from = camera, to = homeCamera, startedAt = System.nanoTime())
    }

    /** 点击时现算一遍投影找命中：一百多个点，一次点击算一遍完全无所谓 */
    fun pickAt(tapX: Float, tapY: Float): Int {
        if (canvasSize.width <= 0f || layout.nodes.isEmpty()) return -1
        val effective = camera.copy(yaw = camera.yaw + gyroYaw, pitch = camera.pitch + gyroPitch)
        val candidates = ArrayList<Galaxy3DMath.PickCandidate>(layout.nodes.size)
        for (node in layout.nodes) {
            val projected = Galaxy3DMath.projectPoint(node.x, node.y, node.z, effective, canvasSize.width, canvasSize.height)
            if (projected.depth <= 0.06f) continue
            val base = if (node.degree > 0) 0.011f + min(node.degree, 24) * 0.0007f else 0.011f
            candidates.add(Galaxy3DMath.PickCandidate(projected.x, projected.y, projected.depth, base * projected.scale))
        }
        return Galaxy3DMath.pick(candidates, tapX, tapY, minRadiusPx = 26f)
    }

    /* ------------------------------ 画面 ------------------------------ */

    Box(modifier = Modifier.fillMaxSize()) {
        val background = remember { Brush.verticalGradient(listOf(Color(0xFF060A15), Color(0xFF0A1020), Color(0xFF04060E))) }
        Box(modifier = Modifier.fillMaxSize().background(background))

        val stars = remember { starField3D() }
        val thumbnailPath = remember { Path() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                /* 手势分成两个 pointerInput：合并成一个的话 detectTransformGestures 永不返回，
                   后面的点击判定永远不会被执行（第一版就是这么写错的）。
                   二维星图页用的是同一种拆法，实测两个手势能共存。 */
                .pointerInput(layout) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        lastGestureAt = System.nanoTime()
                        if (pan.x != 0f || pan.y != 0f) {
                            focus.animation = null
                            camera = Galaxy3DMath.orbit(camera, pan.x, pan.y)
                        }
                        if (zoom != 1f) {
                            focus.animation = null
                            camera = Galaxy3DMath.dolly(camera, 1f / max(0.2f, zoom))
                        }
                    }
                }
                .pointerInput(layout, homeDistance) {
                    detectTapGestures { tap ->
                        val hit = pickAt(tap.x, tap.y)
                        if (hit >= 0) focusOn(hit) else {
                            focusedIndex = -1
                        }
                    }
                },
        ) {
            /* 读一下 frameTick 就与它建立了依赖：每帧刷新，相机的变化才会画出来 */
            @Suppress("UNUSED_EXPRESSION") frameTick

            val cameraNow = camera.copy(yaw = camera.yaw + gyroYaw, pitch = camera.pitch + gyroPitch)
            val viewWidth = size.width
            val viewHeight = size.height

            /* 背景星点：随视角轻微视差，给画面一点纵深参照 */
            for (star in stars) {
                var x = star.x * viewWidth + cameraNow.yaw * 30f * star.depth
                var y = star.y * viewHeight + cameraNow.pitch * 20f * star.depth
                x %= viewWidth
                y %= viewHeight
                if (x < 0f) x += viewWidth
                if (y < 0f) y += viewHeight
                drawCircle(Color.White.copy(alpha = star.alpha), star.radius, Offset(x, y))
            }

            if (layout.nodes.isEmpty()) return@Canvas

            /* ---- 投影（每帧一次，扁平数组避免 GC 抖动） ---- */
            val count = layout.nodes.size
            val screenX = FloatArray(count)
            val screenY = FloatArray(count)
            val depth = FloatArray(count)
            val scale = FloatArray(count)
            for (index in 0 until count) {
                val node = layout.nodes[index]
                val projected = Galaxy3DMath.projectPoint(node.x, node.y, node.z, cameraNow, viewWidth, viewHeight)
                screenX[index] = projected.x
                screenY[index] = projected.y
                depth[index] = projected.depth
                scale[index] = projected.scale
            }
            /* 远 → 近（画家算法） */
            val order = (0 until count).sortedByDescending { depth[it] }
            val focusIndex = focusedIndex

            /* ---- 边 ---- */
            for (edge in layout.edges) {
                val da = depth[edge.a]
                val db = depth[edge.b]
                if (da <= 0.06f || db <= 0.06f) continue
                if (da > FAR_CULL_3D && db > FAR_CULL_3D) continue
                var alpha = edge.score * 0.5f * Galaxy3DMath.depthAlpha(min(da, db), 0f, 3.2f, 0.05f)
                if (focusIndex >= 0) {
                    alpha = if (edge.a == focusIndex || edge.b == focusIndex) alpha * 1.5f else alpha * 0.18f
                }
                if (alpha <= 0.02f) continue
                drawLine(
                    color = Color(0.45f, 0.68f, 1f).copy(alpha = alpha.coerceAtMost(1f)),
                    start = Offset(screenX[edge.a], screenY[edge.a]),
                    end = Offset(screenX[edge.b], screenY[edge.b]),
                    strokeWidth = 1.1f,
                )
            }

            /* ---- 缩略图 + 星点 ---- */
            /* 拉近到全景距离的 86% 以内才开始出现缩略图：全景时一两百张糊在一起只是马赛克 */
            val closeness = ((homeDistance * 0.86f - cameraNow.distance) / (homeDistance * 0.5f)).coerceIn(0f, 1f)
            var drawn = 0
            for (index in order) {
                val node = layout.nodes[index]
                val d = depth[index]
                if (d <= 0.06f) continue
                var alpha = Galaxy3DMath.depthAlpha(d, 0.1f, 3.6f, 0.12f)
                if (focusIndex >= 0 && focusIndex != index) alpha *= 0.35f
                if (focusIndex == index) alpha = 1f
                if (alpha <= 0.02f) continue

                val base = if (node.degree > 0) 0.011f + min(node.degree, 24) * 0.0007f else 0.011f
                val radius = max(1.2f, base * scale[index])
                val center = Offset(screenX[index], screenY[index])
                /* 三层叠加做出星点的发光感（加色混合在 Compose 里没有，用递减透明度近似） */
                drawCircle(node.color.copy(alpha = alpha * 0.16f), radius * 3.4f, center)
                drawCircle(node.color.copy(alpha = alpha * 0.30f), radius * 1.9f, center)
                drawCircle(node.color.copy(alpha = alpha * 0.95f), radius, center)

                if (closeness <= 0.02f || drawn >= MAX_THUMBS_3D) continue
                val longPx = Galaxy3DMath.thumbLongPx(THUMB3D_WORLD, scale[index], THUMB3D_MAX_PX)
                if (longPx < 26f) continue
                if (screenX[index] < -longPx || screenX[index] > viewWidth + longPx) continue
                if (screenY[index] < -longPx || screenY[index] > viewHeight + longPx) continue

                val box = Galaxy3DMath.thumbBoxPx(node.aspect, longPx)
                val url = if (longPx > THUMB3D_LARGE_AT) {
                    container.embedRepository.largeThumbUrl(node.item)
                } else {
                    container.embedRepository.thumbUrl(node.item)
                }
                val bitmap = thumbs.bitmap(node.item.id, url, longPx.roundToInt(), scope) ?: continue
                drawn++
                var imageAlpha = closeness * Galaxy3DMath.depthAlpha(d, 0.1f, 3.4f, 0.25f)
                if (focusIndex >= 0) imageAlpha *= if (focusIndex == index) 1f else 0.22f
                if (imageAlpha <= 0.03f) continue

                val left = screenX[index] - box.width / 2f
                val top = screenY[index] - box.height / 2f
                val corner = min(10f, min(box.width, box.height) / 2f)
                thumbnailPath.rewind()
                thumbnailPath.addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = left + box.width,
                        bottom = top + box.height,
                        radiusX = corner,
                        radiusY = corner,
                    ),
                )
                clipPath(thumbnailPath) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(
                            box.width.roundToInt().coerceAtLeast(1),
                            box.height.roundToInt().coerceAtLeast(1),
                        ),
                        alpha = imageAlpha,
                    )
                }
                drawRoundRect(
                    color = node.color.copy(alpha = imageAlpha * 0.5f),
                    topLeft = Offset(left, top),
                    size = Size(box.width, box.height),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 1.1f),
                )
            }
            thumbs.trim()

            /* ---- 星云名称：按缩放分级显示 ---- */
            val budget = Galaxy3DMath.labelBudget(homeDistance / max(0.001f, cameraNow.distance), layout.labels.size)
            if (budget > 0) {
                val placed = ArrayList<Galaxy3DMath.ScreenLabel>(budget)
                for (label in layout.labels.take(budget)) {
                    val projected = Galaxy3DMath.projectPoint(label.x, label.y, label.z, cameraNow, viewWidth, viewHeight)
                    if (projected.depth <= 0.1f) continue
                    placed.add(
                        Galaxy3DMath.ScreenLabel(
                            key = label.key,
                            text = label.text,
                            x = projected.x,
                            y = projected.y,
                            weight = label.weight,
                            color = label.color,
                        ),
                    )
                }
                for (label in Galaxy3DMath.cullScreenLabels(
                    labels = placed,
                    viewportWidth = viewWidth,
                    viewportHeight = viewHeight,
                    /* 中文按字号估宽：十几个标签不值得真去测量排版 */
                    widthOf = { it.text.length * (LABEL3D_FONT_SP * 2.6f) + LABEL3D_PADDING * 2f },
                    height = LABEL3D_FONT_SP * 2.6f + LABEL3D_PADDING,
                )) {
                    val measured = textMeasurer.measure(
                        text = AnnotatedString(label.text),
                        style = TextStyle(fontSize = LABEL3D_FONT_SP.sp, fontWeight = FontWeight.Medium, color = label.color),
                    )
                    val boxWidth = measured.size.width + LABEL3D_PADDING * 2f
                    val boxHeight = measured.size.height + LABEL3D_PADDING
                    val left = label.x - boxWidth / 2f
                    val top = label.y - boxHeight / 2f
                    drawRoundRect(
                        color = Color(0.03f, 0.05f, 0.10f, 0.62f),
                        topLeft = Offset(left, top),
                        size = Size(boxWidth, boxHeight),
                        cornerRadius = CornerRadius(boxHeight / 2f, boxHeight / 2f),
                    )
                    drawRoundRect(
                        color = label.color.copy(alpha = 0.45f),
                        topLeft = Offset(left, top),
                        size = Size(boxWidth, boxHeight),
                        cornerRadius = CornerRadius(boxHeight / 2f, boxHeight / 2f),
                        style = Stroke(width = 1f),
                    )
                    drawText(textLayoutResult = measured, topLeft = Offset(left + LABEL3D_PADDING, top + LABEL3D_PADDING / 2f))
                }
            }
        }

        /* ------------------------------ 叠层 UI ------------------------------ */

        if (error != null) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(14.dp)) {
                ErrorBar(message = error!!, onRetry = { load(force = true) }, onDismiss = { error = null })
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EmbedGraphMode.entries.forEach { candidate ->
                ModeChip3D(
                    label = candidate.label,
                    active = mode == candidate,
                    onClick = {
                        if (mode == candidate) return@ModeChip3D
                        onModeChange(candidate)
                        graph = null
                        load(force = true, target = candidate)
                    },
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = if (focusedIndex >= 0) "点「回到全景」退回去" else "拖动旋转 · 双指缩放 · 点一张图推近看",
                style = MaterialTheme.typography.labelSmall,
                color = onGlassColor(dark, emphasis = false),
            )
            Text(
                text = if (loading) "正在载入三维布局…" else "${layout.nodes.size} 张图 · ${layout.labels.size} 个标签",
                style = MaterialTheme.typography.labelSmall,
                color = onGlassColor(dark, emphasis = false),
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = "回到全景",
            onClick = { resetView() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
        )

    }
}

/**
 * 算起始视角。画布比例会影响"摆得最开"的角度，所以竖屏和横屏会得到不同的角度 ——
 * 写死一个常数的话，坐标朝向或屏幕方向一变就不合适了（实测宽高比会从 1.95 掉到 1.43）。
 */
private fun solveHome(canvas: Size, layout: Layout3D): Galaxy3DMath.Camera {
    val aspect = if (canvas.height > 1f) canvas.width / canvas.height else 0.5f
    val points = layout.nodes.map { floatArrayOf(it.x, it.y, it.z) }
    val solved = Galaxy3DMath.solveInitialView(points, aspect, 0.82f)
    return Galaxy3DMath.Camera(
        yaw = solved.yaw,
        pitch = solved.pitch,
        distance = solved.distance,
        targetX = solved.targetX,
        targetY = solved.targetY,
        targetZ = solved.targetZ,
    )
}

/* ------------------------------ 内部状态 ------------------------------ */

private class FocusState {
    var animation: FocusAnimation? = null
}

private class FocusAnimation(
    val from: Galaxy3DMath.Camera,
    val to: Galaxy3DMath.Camera,
    val startedAt: Long,
    /**
     * 推近走完之后自动打开的那张图。回全景时是 null ——
     * 不区分的话每次点「回到全景」都会顺手弹一次大图。
     */
    val openItemId: String? = null,
)

private data class Label3D(
    val key: String,
    val text: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val weight: Int,
    val color: Color,
)

private data class Node3D(
    val item: ImageItem,
    val x: Float,
    val y: Float,
    val z: Float,
    val color: Color,
    val character: String,
    val degree: Int,
    val aspect: Float = 1f,
)

private data class Edge3DRef(val a: Int, val b: Int, val score: Float)

private data class Layout3D(
    val nodes: List<Node3D>,
    val edges: List<Edge3DRef>,
    val labels: List<Label3D>,
)

/**
 * 把接口数据摊平成画图要用的形状。
 *
 * 星云名称两种模式各有一套：
 *   · 标签模式：每个标签一个（**关系可重叠**，一张图会出现在多个标签里），位置取成员重心；
 *   · 视觉模式：用服务端给的分组，名字取组里最多的那个主标签。
 */
private fun buildLayout3D(graph: EmbedGraph?): Layout3D {
    if (graph == null) return Layout3D(emptyList(), emptyList(), emptyList())

    val nodes = ArrayList<Node3D>(graph.nodes.size)
    val indexById = HashMap<String, Int>(graph.nodes.size * 2)
    for (item in graph.nodes) {
        val position = graph.positions[item.id] ?: continue
        if (position.size < 3) continue
        val character = graph.characters[item.id].orEmpty()
        indexById[item.id] = nodes.size
        nodes.add(
            Node3D(
                item = item,
                x = position[0].toFloat(),
                y = position[1].toFloat(),
                z = position[2].toFloat(),
                color = GalaxyMath.characterColor(character),
                character = character,
                degree = 0,
                aspect = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f,
            ),
        )
    }

    val edges = ArrayList<Edge3DRef>(graph.edges.size)
    val degree = IntArray(nodes.size)
    for (edge in graph.edges) {
        val a = indexById[edge.a] ?: continue
        val b = indexById[edge.b] ?: continue
        edges.add(Edge3DRef(a, b, edge.score.toFloat()))
        degree[a]++
        degree[b]++
    }
    val decorated = nodes.mapIndexed { index, node -> node.copy(degree = degree[index]) }

    val labels = ArrayList<Label3D>()
    if (graph.mode == "tag" && graph.tagAnchors.isNotEmpty()) {
        for ((tag, _) in graph.tagAnchors) {
            var sumX = 0f
            var sumY = 0f
            var sumZ = 0f
            var count = 0
            for (node in decorated) {
                if (!node.item.tags.contains(tag)) continue
                sumX += node.x
                sumY += node.y
                sumZ += node.z
                count++
            }
            if (count < 2) continue
            labels.add(
                Label3D(
                    key = "tag:$tag",
                    text = "$tag · $count",
                    x = sumX / count,
                    y = sumY / count,
                    z = sumZ / count,
                    weight = count,
                    color = GalaxyMath.characterColor(tag),
                ),
            )
        }
    }
    for (group in graph.groups) {
        if (group.size < 3) continue
        val members = group.members.mapNotNull { indexById[it] }
        if (members.size < 3) continue
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        for (index in members) {
            sumX += decorated[index].x
            sumY += decorated[index].y
            sumZ += decorated[index].z
        }
        val character = group.members.firstNotNullOfOrNull { id ->
            graph.characters[id]?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        labels.add(
            Label3D(
                key = "group:${group.members.first()}",
                text = if (character.isNotEmpty()) "$character · ${group.size}" else "${group.size} 张",
                x = sumX / members.size,
                y = sumY / members.size,
                z = sumZ / members.size,
                weight = group.size,
                color = if (character.isNotEmpty()) GalaxyMath.characterColor(character) else Color(0.80f, 0.84f, 0.88f),
            ),
        )
    }
    labels.sortWith(compareByDescending<Label3D> { it.weight }.thenBy { it.text })
    /* 标签模式下标签已经画了一遍，视觉模式才需要分组名；两者都留着会重名叠在一起 */
    val trimmed = if (graph.mode == "tag" && labels.any { it.key.startsWith("tag:") }) {
        labels.filter { it.key.startsWith("tag:") }
    } else {
        labels
    }

    return Layout3D(decorated, edges, trimmed)
}

/* ------------------------------ 小部件 ------------------------------ */

@Composable
private fun ModeChip3D(label: String, active: Boolean, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onClick() },
        backdrop = false,
        borderWidth = if (active) 1.4.dp else 0.6.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else onGlassColor(LocalDarkTheme.current, emphasis = false),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private data class Star3D(val x: Float, val y: Float, val radius: Float, val alpha: Float, val depth: Float)

/** 背景星点。固定种子：每次进来星空一样，不会闪 */
private fun starField3D(count: Int = 190): List<Star3D> {
    val random = Random(20260801)
    return List(count) {
        Star3D(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.6f + random.nextFloat() * 1.5f,
            alpha = 0.14f + random.nextFloat() * 0.4f,
            depth = 0.3f + random.nextFloat(),
        )
    }
}

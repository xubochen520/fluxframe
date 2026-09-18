package com.fluxframe.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxframe.app.data.store.MediaKind
import com.fluxframe.app.data.store.UploadPhase
import com.fluxframe.app.ui.components.BottomNavItem
import com.fluxframe.app.ui.components.CompactIconButton
import com.fluxframe.app.ui.components.FluxBottomBar
import com.fluxframe.app.ui.components.FluxToast
import com.fluxframe.app.ui.components.FluxTopBar
import com.fluxframe.app.ui.components.FluidCapsule
import com.fluxframe.app.ui.components.TaskDock
import com.fluxframe.app.ui.screens.DeepseekScreen
import com.fluxframe.app.ui.screens.LibraryScreen
import com.fluxframe.app.ui.screens.LogsScreen
import com.fluxframe.app.ui.screens.OverviewScreen
import com.fluxframe.app.ui.screens.ParseScreen
import com.fluxframe.app.ui.screens.PersonDetailScreen
import com.fluxframe.app.ui.screens.SettingsScreen
import com.fluxframe.app.ui.screens.TagsScreen
import com.fluxframe.app.ui.screens.UploadReviewOverlay
import com.fluxframe.app.ui.screens.ViewerOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** 应用内的路由。用一个轻量返回栈而不是 Navigation Compose：
 *  页面数量少、参数简单，这样能让"全屏查看器"这类覆盖层与页面共存而不打架。 */
enum class AppRoute(val title: String) {
    OVERVIEW("总览"),
    LIBRARY("图片库"),
    VIDEOS("视频库"),
    PARSE("视频提取"),
    TAGS("智能标签"),
    TRASH("回收站"),
    LOGS("访问日志"),
    DEEPSEEK("DeepSeek 记账"),
    SETTINGS("系统设置"),
    PERSON("人物"),
}

/** 悬浮底栏（含外边距）大约占这么高，浮动层要避开它 */
private val BOTTOM_BAR_RESERVE = 88.dp

private val PRIMARY_ROUTES = listOf(
    AppRoute.OVERVIEW,
    AppRoute.LIBRARY,
    AppRoute.VIDEOS,
    AppRoute.TAGS,
    AppRoute.SETTINGS,
)

@Composable
fun AppShell(
    onHeaderHiddenChange: (Boolean) -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val session by container.sessionStore.state.collectAsStateWithLifecycle()
    val allMedia by container.mediaStore.liveImages.collectAsStateWithLifecycle()
    val images by container.mediaStore.images.collectAsStateWithLifecycle()
    val videos by container.mediaStore.videos.collectAsStateWithLifecycle()
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val trash by container.mediaStore.trashImages.collectAsStateWithLifecycle()
    val capsule by container.fluidCloud.capsule.collectAsStateWithLifecycle()
    val upload by container.taskStore.upload.collectAsStateWithLifecycle()
    val imports by container.taskStore.imports.collectAsStateWithLifecycle()
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val dark = uiPrefs.darkMode
    val stack = remember { mutableStateListOf(AppRoute.OVERVIEW) }
    val route = stack.last()
    val lifecycleOwner = LocalLifecycleOwner.current
    var personTagId by remember { mutableStateOf<String?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(
        initialPage = PRIMARY_ROUTES.indexOf(route).coerceAtLeast(0),
        pageCount = { PRIMARY_ROUTES.size },
    )
    // 底栏快速连点时，前一次 animateScrollToPage 会在半路被取消。
    // 此时 settledPage 可能短暂停在中间页，不能把它误当成用户的最终选择。
    var pendingPrimaryPage by remember { mutableStateOf<Int?>(null) }

    /* ------------------------- 顶栏随滚动自动收起 ------------------------- */
    val autoHideEnabled = uiPrefs.autoHideHeader
    val headerState = remember(autoHideEnabled) { HeaderAutoHide(enabled = autoHideEnabled) }
    val headerHidden = headerState.hidden
    val contentScrolled = headerState.scrolled

    val scrollConnection = remember(headerState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                headerState.onScroll(consumedY = consumed.y, availableY = available.y)
                return Offset.Zero
            }
        }
    }

    // 上报给 Activity：顶栏收起时连状态栏一起让位
    LaunchedEffect(headerHidden) { onHeaderHiddenChange(headerHidden) }
    val contentFullscreen = viewerIndex != null || upload?.phase == UploadPhase.REVIEW
    LaunchedEffect(contentFullscreen) { onFullscreenChange(contentFullscreen) }
    DisposableEffect(Unit) {
        onDispose {
            onHeaderHiddenChange(false)
            onFullscreenChange(false)
        }
    }

    // 每次首次进入主界面、以及从后台重新回到 App，都重新取图库与 DeepSeek 实时余额。
    // 图库走并发静默刷新，不把已有内容替换成加载页；余额走真实的官方接口刷新。
    fun refreshForegroundData() {
        scope.launch { container.mediaStore.silentRefreshAll() }
        container.deepseekStore.forceRefresh()
        if (session.user?.isAdmin == true) container.settingsStore.refresh()
    }

    LaunchedEffect(Unit) { refreshForegroundData() }
    DisposableEffect(lifecycleOwner) {
        var sawPause = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> sawPause = true
                Lifecycle.Event.ON_RESUME -> if (sawPause) {
                    sawPause = false
                    refreshForegroundData()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 内容区直接左右拖动时，以分页器最终停下的位置作为新的主页面。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                // 编程导航期间忽略中途 settledPage；只在用户直接滑动内容时反向同步路由。
                if (pendingPrimaryPage == null && PRIMARY_ROUTES.contains(stack.last())) {
                    val target = PRIMARY_ROUTES[page]
                    if (stack.last() != target) {
                        stack.clear()
                        stack.add(target)
                        headerState.reset()
                    }
                }
            }
    }

    // 点击底栏或从卡片入口跳到另一个主页面时，按目标所在方向平滑滑动。
    LaunchedEffect(route) {
        val page = PRIMARY_ROUTES.indexOf(route)
        if (page >= 0) {
            if (pagerState.settledPage == page && !pagerState.isScrollInProgress) {
                pendingPrimaryPage = null
            } else {
                pendingPrimaryPage = page
                try {
                    pagerState.animateScrollToPage(page)
                } finally {
                    // 只允许最新的目标收尾；被后一次点击取消的旧协程不得改回路由。
                    if (pendingPrimaryPage == page) {
                        withContext(NonCancellable) {
                            if (pagerState.settledPage != page) pagerState.scrollToPage(page)
                            pendingPrimaryPage = null
                        }
                    }
                }
            }
        }
    }

    fun push(target: AppRoute) {
        if (stack.last() != target) {
            PRIMARY_ROUTES.indexOf(target).takeIf { it >= 0 }?.let { pendingPrimaryPage = it }
            stack.add(target)
            headerState.reset()
        }
    }

    fun pop(): Boolean {
        if (stack.size > 1) {
            val next = stack[stack.lastIndex - 1]
            PRIMARY_ROUTES.indexOf(next).takeIf { it >= 0 }?.let { pendingPrimaryPage = it }
            stack.removeAt(stack.size - 1)
            headerState.reset()
            return true
        }
        return false
    }

    fun showToast(message: String?) {
        toast = message
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2400)
            toast = null
        }
    }

    // 系统返回：先关覆盖层，再退页面
    BackHandler(enabled = viewerIndex != null || upload?.phase == UploadPhase.REVIEW || route != AppRoute.OVERVIEW) {
        when {
            viewerIndex != null -> viewerIndex = null
            upload?.phase == UploadPhase.REVIEW -> container.taskStore.dismissUpload()
            route == AppRoute.PERSON -> {
                personTagId = null
                pop()
            }
            else -> pop()
        }
    }

    // 相册选择器：用系统 Photo Picker，不需要任何存储权限
    val visualMediaRequest = androidx.activity.result.PickVisualMediaRequest(
        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
    )
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.map { container.mediaRepository.describe(it) }.filter { it.isSupported }
            }
            if (sources.isEmpty()) {
                showToast("所选文件不是图片或视频，已跳过")
            } else {
                container.taskStore.startAnalyze(sources)
            }
        }
    }

    val routeContent: @Composable (AppRoute) -> Unit = { displayedRoute ->
        when (displayedRoute) {
            AppRoute.OVERVIEW -> OverviewScreen(
                onOpenLibrary = { push(AppRoute.LIBRARY) },
                onOpenTags = { push(AppRoute.TAGS) },
                onOpenDeepseek = { push(AppRoute.DEEPSEEK) },
                onOpenParse = { push(AppRoute.PARSE) },
                onOpenImage = { id ->
                    val index = allMedia.indexOfFirst { it.id == id }
                    if (index >= 0) viewerIndex = index
                },
                onUpload = { pickMedia.launch(visualMediaRequest) },
                onToast = { showToast(it) },
            )

            AppRoute.LIBRARY -> LibraryScreen(
                kind = MediaKind.IMAGE,
                onOpenImage = { index -> viewerIndex = index },
                onOpenTrash = { push(AppRoute.TRASH) },
                onToast = { showToast(it) },
            )

            AppRoute.VIDEOS -> LibraryScreen(
                kind = MediaKind.VIDEO,
                onOpenImage = { index -> viewerIndex = index },
                onOpenTrash = { push(AppRoute.TRASH) },
                onToast = { showToast(it) },
                onOpenParse = { push(AppRoute.PARSE) },
            )

            AppRoute.TRASH -> LibraryScreen(
                kind = MediaKind.TRASH,
                onOpenImage = { index -> viewerIndex = index },
                onOpenTrash = { pop() },
                onToast = { showToast(it) },
            )

            AppRoute.PARSE -> ParseScreen(onToast = { showToast(it) })

            AppRoute.TAGS -> TagsScreen(
                onOpenPerson = { tagId ->
                    personTagId = tagId
                    push(AppRoute.PERSON)
                },
                onOpenMediaLibrary = { kind ->
                    push(if (kind == MediaKind.VIDEO) AppRoute.VIDEOS else AppRoute.LIBRARY)
                },
                onToast = { showToast(it) },
            )

            AppRoute.PERSON -> PersonDetailScreen(
                tagId = personTagId.orEmpty(),
                onOpenMediaLibrary = { kind ->
                    push(if (kind == MediaKind.VIDEO) AppRoute.VIDEOS else AppRoute.LIBRARY)
                },
                onToast = { showToast(it) },
            )

            AppRoute.LOGS -> LogsScreen(onToast = { showToast(it) })
            AppRoute.DEEPSEEK -> DeepseekScreen(onToast = { showToast(it) })
            AppRoute.SETTINGS -> SettingsScreen(
                onOpenLogs = { push(AppRoute.LOGS) },
                onOpenDeepseek = { push(AppRoute.DEEPSEEK) },
                onLoggedOut = { stack.clear(); stack.add(AppRoute.OVERVIEW) },
                onToast = { showToast(it) },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
            AnimatedVisibility(
                visible = !headerHidden,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                FluxTopBar(
                    title = when (route) {
                        AppRoute.PERSON -> personTagId
                            ?.let { id -> tags.firstOrNull { it.id == id }?.name }
                            ?: "人物"
                        else -> route.title
                    },
                    modifier = Modifier.statusBarsPadding(),
                    scrolled = contentScrolled,
                    onBack = if (stack.size > 1) ({ pop() }) else null,
                    actions = {
                        if (route == AppRoute.LIBRARY || route == AppRoute.OVERVIEW) {
                            CompactIconButton(
                                icon = Icons.Filled.CloudUpload,
                                contentDescription = "上传",
                                onClick = { pickMedia.launch(visualMediaRequest) },
                            )
                        }
                        CompactIconButton(
                            icon = Icons.Filled.NoAdultContent,
                            contentDescription = if (session.user?.r18Mode == true) "关闭 R18" else "开启 R18",
                            tint = if (session.user?.r18Mode == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                null
                            },
                            onClick = { container.sessionStore.setR18Mode(!(session.user?.r18Mode ?: false)) },
                        )
                        CompactIconButton(
                            icon = if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "切换明暗",
                            onClick = { container.prefs.updateUi { it.copy(darkMode = !it.darkMode) } },
                        )
                    },
                )
            }

            FluidCapsule(
                state = capsule,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                onDismiss = { container.fluidCloud.dismiss() },
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (PRIMARY_ROUTES.contains(route)) {
                    HorizontalPager(
                        state = pagerState,
                        key = { PRIMARY_ROUTES[it].name },
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        routeContent(PRIMARY_ROUTES[page])
                    }
                } else {
                    routeContent(route)
                }
            }

            FluxBottomBar(
                items = listOf(
                    BottomNavItem("overview", "总览", Icons.Filled.Dashboard),
                    // 注意：这里故意不给徽标。以前把"媒体总数"当成红点挂在图片库上，
                    // 于是只要库里有东西就永远亮着一个小红点 —— 那不是"有新内容"的意思。
                    BottomNavItem("library", "图片库", Icons.Filled.Image),
                    BottomNavItem("videos", "视频库", Icons.Filled.Movie),
                    BottomNavItem("tags", "标签", Icons.Filled.Label),
                    BottomNavItem("settings", "设置", Icons.Filled.Settings),
                ),
                selectedKey = when (route) {
                    AppRoute.OVERVIEW -> "overview"
                    AppRoute.LIBRARY, AppRoute.TRASH, AppRoute.PERSON -> "library"
                    AppRoute.VIDEOS -> "videos"
                    AppRoute.PARSE, AppRoute.TAGS -> "tags"
                    AppRoute.LOGS, AppRoute.DEEPSEEK, AppRoute.SETTINGS -> "settings"
                },
                onSelect = { key ->
                    val target = when (key) {
                        "overview" -> AppRoute.OVERVIEW
                        "library" -> AppRoute.LIBRARY
                        "videos" -> AppRoute.VIDEOS
                        "tags" -> AppRoute.TAGS
                        else -> AppRoute.SETTINGS
                    }
                    pendingPrimaryPage = PRIMARY_ROUTES.indexOf(target)
                    stack.clear()
                    stack.add(target)
                    headerState.reset()
                },
                onSwipe = { direction ->
                    val current = PRIMARY_ROUTES.indexOf(route).takeIf { it >= 0 }
                        ?: pagerState.currentPage
                    val targetPage = (current + direction).coerceIn(0, PRIMARY_ROUTES.lastIndex)
                    if (targetPage != current) {
                        val target = PRIMARY_ROUTES[targetPage]
                        pendingPrimaryPage = targetPage
                        stack.clear()
                        stack.add(target)
                        headerState.reset()
                    }
                },
                hapticsEnabled = uiPrefs.bottomBarHapticsEnabled,
                modifier = Modifier.navigationBarsPadding(),
            )
        }

        // ---- 全屏查看器（覆盖整个界面）----
        // 翻页列表必须与当前页面渲染的列表完全一致，否则"看到的"和"滑到的"会对不上
        val viewerItems = when (route) {
            AppRoute.TRASH -> trash
            AppRoute.VIDEOS -> videos
            // 总览是图文混排的信息流，翻页也按全量走
            AppRoute.OVERVIEW -> allMedia
            else -> images
        }
        ViewerOverlay(
            index = viewerIndex,
            images = viewerItems,
            onClose = { viewerIndex = null },
            onIndexChange = { viewerIndex = it },
            onToast = { showToast(it) },
        )

        // ---- 上传确认（analyze 完成后弹出）----
        AnimatedVisibility(
            visible = upload?.phase == UploadPhase.REVIEW,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                UploadReviewOverlay(
                    onDismiss = { container.taskStore.dismissUpload() },
                    onToast = { showToast(it) },
                )
            }
        }

        // 底部浮层：任务坞在上、提示条在下，统一叠在底部导航栏之上，互不遮挡
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_RESERVE),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FluxToast(message = toast, modifier = Modifier.fillMaxWidth())
            TaskDock(
                upload = upload,
                imports = imports,
                onOpenLibrary = {
                    stack.clear()
                    stack.add(AppRoute.LIBRARY)
                },
                onCancelUpload = { container.taskStore.discardUpload() },
                onRetryUpload = { container.taskStore.retryUpload() },
                onDismissUpload = { container.taskStore.dismissUpload() },
                onCancelImport = { container.taskStore.cancelImport(it) },
                onRetryImport = { container.taskStore.retryImport(it) },
                onDismissImport = { container.taskStore.dismissImport(it) },
            )
        }
    }
}

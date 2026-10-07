package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.data.model.DashboardResponse
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.data.repo.ALL_TAGS
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 媒体库的筛选条件。
 *
 * 两个刻意的设计：
 *  - **标签可以多选**（[tags] 是集合），多个标签之间是「**同时满足**」——
 *    选「人物甲 + 海边」得到的是既有人物甲、又打了海边标签的项，这是收敛式筛选的直觉；
 *    界面上会写明"同时满足"，不会让人误以为是并集。
 *  - **标签筛选在本地做**（[search] 与 [sort] 才发给服务端）。
 *    原因：服务端的 `/api/images` 既没有分页、也没有多标签参数，
 *    默认（不选标签）时本来就一次返回全部；既然如此，多选就没有必要为每次点击
 *    都打一次网络请求 —— 本地过滤是瞬时的，而且天然支持任意多个标签。
 */
data class MediaFilters(
    val search: String = "",
    /** 已选标签名（多选，同时满足）。空集合 = 不限 */
    val tags: Set<String> = emptySet(),
    val sort: String = "views",
)

/** 某个标签在图片 / 视频里各有多少项 */
data class TagUsage(val images: Int = 0, val videos: Int = 0) {
    val total: Int get() = images + videos
}

/**
 * 媒体与标签的全局状态。
 *
 * 之所以做成应用级 store 而不是每屏一个 ViewModel：图片库、视频库、总览、标签页、
 * 回收站共享同一份数据，任一处的改动（改名、删除、打标签）需要立刻反映到其它页面，
 * 集中持有最省心，也避免重复请求。
 */
class MediaStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 全部未删除媒体（图片 + 视频）。搜索与排序已由服务端完成，标签筛选在本地 */
    private val _liveImages = MutableStateFlow<List<ImageItem>>(emptyList())
    val liveImages: StateFlow<List<ImageItem>> = _liveImages.asStateFlow()

    private val _trashImages = MutableStateFlow<List<ImageItem>>(emptyList())
    val trashImages: StateFlow<List<ImageItem>> = _trashImages.asStateFlow()

    private val _tags = MutableStateFlow<List<TagItem>>(emptyList())
    val tags: StateFlow<List<TagItem>> = _tags.asStateFlow()

    private val _dashboard = MutableStateFlow<DashboardResponse?>(null)
    val dashboard: StateFlow<DashboardResponse?> = _dashboard.asStateFlow()

    private val _filters = MutableStateFlow(MediaFilters())
    val filters: StateFlow<MediaFilters> = _filters.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 标签是否已在本次会话加载过（决定标签页是否显示骨架屏） */
    private val _tagsLoaded = MutableStateFlow(false)
    val tagsLoaded: StateFlow<Boolean> = _tagsLoaded.asStateFlow()

    /**
     * 上一次「媒体列表成功刷新」的时刻（毫秒）。
     *
     * 存在的理由：登录成功时 [SessionStore] 会 [refreshAll] 一轮，紧接着 AppShell 组合时
     * 又发起一轮 [silentRefreshAll] —— 两轮共 8 个并发请求打同一个服务端，首屏只会更慢。
     * 有了这个时间戳，调用方就能判断"刚拉过，别再来一遍"。
     */
    @Volatile
    var lastImagesLoadedAt: Long = 0L
        private set

    /** 后台预热缩略图的任务；列表一变就取消重来，避免为旧数据白干活 */
    private var warmJob: Job? = null

    /**
     * 前台正在看媒体网格时把预热暂停。
     *
     * Coil 2.x 没有请求优先级（`Priority` 是 1.x 的 API，2.7 里已经不存在），
     * 所以没法让后台预热"自动排队到后面"。既然如此就换个办法：用户真的在图库/视频库里
     * 滚动时，暂停预热，等离开这个页面再继续 —— 效果一样，而且行为可预测。
     */
    @Volatile
    var warmPaused: Boolean = false

    val personTags: List<TagItem> get() = _tags.value.filter { it.person }
    val normalTags: List<TagItem> get() = _tags.value.filter { !it.person }

    /* --------------------------- 派生：图片 / 视频 --------------------------- */
    /* 图片库与视频库是两套完全独立的列表；「带视频标签自动转移到视频库」正是这里：
       标签行只列出"在本类里真的有内容"的标签，并显示本类的数量。 */

    /** 图片库当前应展示的项（已应用标签筛选） */
    val images: StateFlow<List<ImageItem>> = combine(_liveImages, _filters) { list, filters ->
        MediaFiltering.apply(list, MediaKind.IMAGE, filters.tags)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 视频库当前应展示的项（已应用标签筛选） */
    val videos: StateFlow<List<ImageItem>> = combine(_liveImages, _filters) { list, filters ->
        MediaFiltering.apply(list, MediaKind.VIDEO, filters.tags)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 每个标签在图片 / 视频里的分布，用于两套标签行的数量与"该跳哪个库" */
    val tagUsage: StateFlow<Map<String, TagUsage>> = _liveImages
        .map { list -> MediaFiltering.usageOf(list) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun usageOf(tagName: String): TagUsage = tagUsage.value[tagName] ?: TagUsage()

    /** 该标签应该打开哪个库：有图开图片库，只有视频就开视频库 */
    fun libraryForTag(tagName: String): MediaKind = MediaFiltering.libraryFor(tagUsage.value[tagName])

    /* ------------------------------ 读取 ------------------------------ */

    fun refreshAll() {
        refreshImages()
        refreshTrash()
        refreshTags()
        refreshDashboard()
    }

    fun refreshImages() {
        scope.launch {
            _loading.value = true
            val filter = _filters.value
            // 标签不发给服务端：多选在本地做（见 MediaFilters 的注释）
            container.mediaRepository.images(
                search = filter.search,
                tag = null,
                sort = filter.sort,
                trash = false,
            )
                .onSuccess { items ->
                    _liveImages.value = items
                    _error.value = null
                    lastImagesLoadedAt = System.currentTimeMillis()
                    warmThumbnails()
                }
                .onFailure { error -> _error.value = error.message }
            _loading.value = false
        }
    }

    /**
     * 后台把前几张缩略图先抓进 Coil 的缓存。
     *
     * 用户反馈的「第一次点进图库会卡一小会」就出在这里：进图库那一刻，列表刚到、缩略图
     * 一张都还没下载，于是整屏同时开始加载。改成拿列表的时候就顺手在后台预热，
     * 等用户真的点进图库（通常还在总览页看数据），图已经在内存/磁盘缓存里，直接就出来了。
     *
     * 三条约束，缺一不可：
     *  - [warmPaused] 为真时让路：用户正在图库里滚动时，预热不参与抢带宽；
     *  - 一次只发一张、中间留间隔：预热是"顺便"，不能把带宽占满；
     *  - 列表变了就取消：别为已经翻过去的那一屏白干。
     */
    fun warmThumbnails(limit: Int = WARM_LIMIT) {
        val items = _liveImages.value.asSequence().filter { !it.isVideo }.take(limit).toList()
        if (items.isEmpty()) return
        warmJob?.cancel()
        warmJob = scope.launch(Dispatchers.IO) {
            val loader = Coil.imageLoader(container.context)
            for (item in items) {
                if (!isActive) return@launch
                // 前台在用就等一等，别去抢正在滚动那几屏的带宽
                while (warmPaused && isActive) delay(PAUSE_POLL_MS)
                val url = container.mediaRepository.gridUrl(item)
                if (url.isBlank()) continue
                val request = ImageRequest.Builder(container.context)
                    .data(url)
                    .size(WARM_PIXELS)
                    .build()
                runCatching { loader.execute(request) }
                delay(WARM_INTERVAL_MS)
            }
        }
    }

    fun refreshTrash() {
        scope.launch {
            container.mediaRepository.images(sort = "newest", trash = true)
                .onSuccess { _trashImages.value = it }
        }
    }

    fun refreshTags() {
        scope.launch {
            container.tagRepository.list()
                .onSuccess {
                    _tags.value = it
                    _tagsLoaded.value = true
                }
                .onFailure { error -> _error.value = error.message }
        }
    }

    fun refreshDashboard() {
        scope.launch {
            container.mediaRepository.dashboard()
                .onSuccess { _dashboard.value = it }
        }
    }

    /** 拉一次「只有数据没有加载态」的静默刷新，用于下拉刷新 */
    suspend fun silentRefreshAll() = coroutineScope {
        val imagesJob = async {
            container.mediaRepository.images(_filters.value.search, null, _filters.value.sort, false)
        }
        val tagsJob = async { container.tagRepository.list() }
        val dashboardJob = async { container.mediaRepository.dashboard() }
        val trashJob = async { container.mediaRepository.images(sort = "newest", trash = true) }

        imagesJob.await().onSuccess {
            _liveImages.value = it
            lastImagesLoadedAt = System.currentTimeMillis()
            warmThumbnails()
        }
        tagsJob.await().onSuccess {
            _tags.value = it
            _tagsLoaded.value = true
        }
        dashboardJob.await().onSuccess { _dashboard.value = it }
        trashJob.await().onSuccess { _trashImages.value = it }
    }

    /* ------------------------------ 筛选 ------------------------------ */

    fun setSearch(text: String) {
        if (_filters.value.search == text) return
        _filters.value = _filters.value.copy(search = text)
        refreshImages()
    }

    /** 多选开关：选中就加上，再点一次就取消。纯本地操作，不需要网络往返 */
    fun toggleTag(tagName: String) {
        if (tagName == ALL_TAGS) {
            clearTags()
            return
        }
        val current = _filters.value.tags
        val next = if (current.contains(tagName)) current - tagName else current + tagName
        if (next == current) return
        _filters.value = _filters.value.copy(tags = next)
    }

    /** 只选中这一个标签（从标签页点某个标签进来时用）；传 [ALL_TAGS] 表示清空 */
    fun setTag(tagName: String) {
        if (tagName == ALL_TAGS) {
            clearTags()
            return
        }
        if (_filters.value.tags == setOf(tagName)) return
        _filters.value = _filters.value.copy(tags = setOf(tagName))
    }

    fun clearTags() {
        if (_filters.value.tags.isEmpty()) return
        _filters.value = _filters.value.copy(tags = emptySet())
    }

    fun setSort(sort: String) {
        if (_filters.value.sort == sort) return
        _filters.value = _filters.value.copy(sort = sort)
        refreshImages()
    }

    fun resetFilters() {
        _filters.value = MediaFilters()
        refreshImages()
    }

    fun clearError() {
        _error.value = null
    }

    /* --------------------------- 本地即时更新 --------------------------- */
    /* 增删改先在本地生效，界面立刻响应；随后再静默对齐一次服务端 */

    fun applyRename(id: String, name: String) {
        _liveImages.value = _liveImages.value.map { if (it.id == id) it.copy(name = name) else it }
        _trashImages.value = _trashImages.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    /** 浏览成功后立即写回计数，不必等下一轮网络刷新。 */
    fun applyViewed(id: String, views: Int) {
        _liveImages.value = _liveImages.value.map { if (it.id == id) it.copy(views = views) else it }
        _trashImages.value = _trashImages.value.map { if (it.id == id) it.copy(views = views) else it }
    }

    fun moveToTrashLocally(id: String) {
        val item = _liveImages.value.firstOrNull { it.id == id } ?: return
        _liveImages.value = _liveImages.value.filterNot { it.id == id }
        _trashImages.value = listOf(item.copy(deletedAt = java.time.Instant.now().toString())) + _trashImages.value
    }

    fun restoreLocally(id: String) {
        val item = _trashImages.value.firstOrNull { it.id == id } ?: return
        _trashImages.value = _trashImages.value.filterNot { it.id == id }
        _liveImages.value = listOf(item.copy(deletedAt = null)) + _liveImages.value
    }

    fun removeLocally(id: String) {
        _liveImages.value = _liveImages.value.filterNot { it.id == id }
        _trashImages.value = _trashImages.value.filterNot { it.id == id }
    }

    /** 给某个媒体追加标签（本地即时反映） */
    fun applyTagAdded(imageId: String, tagName: String, tagId: String) {
        _liveImages.value = _liveImages.value.map { item ->
            if (item.id != imageId || item.tags.contains(tagName)) item
            else item.copy(tags = item.tags + tagName, tagIds = item.tagIds + tagId)
        }
    }

    fun applyTagRemoved(imageId: String, tagId: String) {
        _liveImages.value = _liveImages.value.map { item ->
            if (item.id != imageId) item
            else {
                val index = item.tagIds.indexOf(tagId)
                if (index < 0) item
                else item.copy(
                    tags = item.tags.filterIndexed { i, _ -> i != index },
                    tagIds = item.tagIds.filterIndexed { i, _ -> i != index },
                )
            }
        }
    }

    fun upsertTag(tag: TagItem) {
        val existing = _tags.value.any { it.id == tag.id }
        _tags.value = if (existing) {
            _tags.value.map { if (it.id == tag.id) tag else it }
        } else {
            (_tags.value + tag).sortedBy { it.name }
        }
    }

    /** 标签改名时同步媒体标签与当前筛选，避免保存后短暂显示旧名字或空列表。 */
    fun applyTagUpdated(previous: TagItem, updated: TagItem) {
        upsertTag(updated)
        if (previous.name == updated.name) return

        fun renameIn(items: List<ImageItem>) = items.map { item ->
            val index = item.tagIds.indexOf(updated.id)
            if (index < 0 || index >= item.tags.size) item
            else item.copy(tags = item.tags.mapIndexed { i, name -> if (i == index) updated.name else name })
        }

        _liveImages.value = renameIn(_liveImages.value)
        _trashImages.value = renameIn(_trashImages.value)
        if (_filters.value.tags.contains(previous.name)) {
            _filters.value = _filters.value.copy(
                tags = _filters.value.tags - previous.name + updated.name,
            )
        }
    }

    fun removeTagLocally(tagId: String) {
        val removed = _tags.value.firstOrNull { it.id == tagId }
        _tags.value = _tags.value.filterNot { it.id == tagId }
        fun stripFrom(items: List<ImageItem>) = items.map { item ->
            val index = item.tagIds.indexOf(tagId)
            if (index < 0) item
            else item.copy(
                tags = item.tags.filterIndexed { i, _ -> i != index },
                tagIds = item.tagIds.filterIndexed { i, _ -> i != index },
            )
        }
        _liveImages.value = stripFrom(_liveImages.value)
        _trashImages.value = stripFrom(_trashImages.value)
        // 被删掉的标签如果正被选中，要一并从筛选里去掉，否则会筛出空结果
        if (removed != null && _filters.value.tags.contains(removed.name)) {
            _filters.value = _filters.value.copy(tags = _filters.value.tags - removed.name)
        }
    }
}

/** 媒体库的种类：图片库 / 视频库 / 回收站 */
enum class MediaKind { IMAGE, VIDEO, TRASH }

/* 缩略图预热参数 */
/** 预热多少张：约两屏，够首屏"哗"地一下出来，又不会拖太久 */
private const val WARM_LIMIT = 48
/** 预热时按网格卡片的目标尺寸解码，和真正显示时用同一档，缓存才命中 */
private const val WARM_PIXELS = 320
/** 每张之间歇一下：预热是顺便做的事，不能把带宽占满 */
private const val WARM_INTERVAL_MS = 60L
/** 被前台暂停时多久回头看一眼（毫秒） */
private const val PAUSE_POLL_MS = 250L

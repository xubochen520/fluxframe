package com.fluxframe.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.data.repo.MediaRepository
import com.fluxframe.app.data.store.MediaFiltering
import com.fluxframe.app.data.store.MediaKind
import com.fluxframe.app.data.store.TagUsage
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.ErrorBar
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MediaCard
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.components.VideoPosterRequest
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 媒体库：图片库 / 视频库 / 回收站，三者共用一套实现。
 *
 * ### 标签区（v2.1.2 重做）
 * 上方是**两排**标签，而不是一排混在一起：
 *  - 上排：**人名**（人物标签）
 *  - 下排：**其他标签**
 *
 * 两排都支持**多选**（任意多个人名、任意多个其他标签），多个标签之间是
 * 「同时满足」；每排第一个「不限」用来一键清空该排。
 *
 * 标签行只列出**在本库里真的有内容**的标签（图片库只显示有图片的标签、
 * 视频库只显示有视频的标签），数量也按本库统计 —— 这样"只打了视频的标签"
 * 会自动出现在视频库里，不会在图片库里留一堆筛出空结果的标签。
 */
@Composable
fun LibraryScreen(
    kind: MediaKind,
    onOpenImage: (Int) -> Unit,
    onOpenTrash: () -> Unit,
    onToast: (String?) -> Unit,
    onOpenParse: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val dark = LocalDarkTheme.current
    val uiPrefs by container.prefs.ui.collectAsStateWithLifecycle()
    val filters by container.mediaStore.filters.collectAsStateWithLifecycle()
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val tagUsage by container.mediaStore.tagUsage.collectAsStateWithLifecycle()
    val loading by container.mediaStore.loading.collectAsStateWithLifecycle()
    val error by container.mediaStore.error.collectAsStateWithLifecycle()

    // 三条列表都来自 store：查看器翻页用的也是同一份，因此"看到的"与"滑到的"永远一致
    val images by container.mediaStore.images.collectAsStateWithLifecycle()
    val videos by container.mediaStore.videos.collectAsStateWithLifecycle()
    val trashImages by container.mediaStore.trashImages.collectAsStateWithLifecycle()
    val items: List<ImageItem> = when (kind) {
        MediaKind.IMAGE -> images
        MediaKind.VIDEO -> videos
        MediaKind.TRASH -> trashImages
    }

    var searchText by remember { mutableStateOf(filters.search) }
    var actionTarget by remember { mutableStateOf<ImageItem?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectedIds by remember(kind) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    val isTrash = kind == MediaKind.TRASH
    val selectedTags = filters.tags

    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    LaunchedEffect(items) {
        val available = items.asSequence().map { it.id }.toSet()
        selectedIds = selectedIds.intersect(available)
    }

    // 输入防抖，避免每敲一个字都打一次接口
    LaunchedEffect(searchText) {
        if (searchText != filters.search) {
            delay(280)
            container.mediaStore.setSearch(searchText)
        }
    }

    LaunchedEffect(kind) {
        if (isTrash) container.mediaStore.refreshTrash()
        // 故意**不**清空标签选择：从标签页点进来时会先 setTag 再跳转，
        // 在这里清掉就等于把用户的意图抹掉。标签行本身会显示选中状态，
        // 若某个标签在另一个库里没有内容，空状态会解释原因并给一键清空。
    }

    // 只有本库里真的有内容的标签才出现（"带视频标签自动转移到视频库"就体现在这里）
    val personTags: List<TagItem> = tags.filter { it.person && countIn(tagUsage, it.name, kind) > 0 }
    val otherTags: List<TagItem> = tags.filter { !it.person && countIn(tagUsage, it.name, kind) > 0 }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 搜索 / 排序 / 操作（上下间距压紧）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlassTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = when (kind) {
                            MediaKind.TRASH -> "在回收站中搜索"
                            MediaKind.VIDEO -> "搜索视频名称或标签"
                            MediaKind.IMAGE -> "搜索图片名称或标签"
                        },
                        leadingIcon = Icons.Filled.Search,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        onImeAction = { container.mediaStore.setSearch(searchText) },
                    )
                }
                if (!isTrash) {
                    RoundIconAction(
                        icon = Icons.Filled.Sort,
                        description = "排序",
                        onClick = { sortExpanded = true },
                    )
                }
                if (kind == MediaKind.VIDEO) {
                    RoundIconAction(
                        icon = Icons.Filled.Movie,
                        description = "从链接提取视频",
                        onClick = onOpenParse,
                    )
                }
                RoundIconAction(
                    icon = if (isTrash) Icons.Filled.Image else Icons.Filled.DeleteOutline,
                    description = if (isTrash) "返回" else "回收站",
                    onClick = onOpenTrash,
                )
                RoundIconAction(
                    icon = Icons.Filled.Refresh,
                    description = "刷新",
                    onClick = {
                        if (isTrash) container.mediaStore.refreshTrash() else container.mediaStore.refreshImages()
                    },
                )
            }

            if (!isTrash && selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.18f else 0.10f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "已选择 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = onGlassColor(dark, emphasis = true),
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconAction(
                        icon = Icons.Filled.SelectAll,
                        description = if (selectedIds.size == items.size) "取消全选" else "全选",
                        onClick = {
                            selectedIds = if (selectedIds.size == items.size) emptySet() else items.map { it.id }.toSet()
                        },
                    )
                    RoundIconAction(
                        icon = Icons.Filled.Download,
                        description = "下载所选",
                        onClick = {
                            val picked = items.filter { it.id in selectedIds }
                            scope.launch {
                                container.mediaDownloader.enqueueBatch(picked)
                                    .onSuccess { count ->
                                        onToast("已将 $count 个文件加入下载队列")
                                        selectedIds = emptySet()
                                    }
                                    .onFailure { onToast(it.message) }
                            }
                        },
                    )
                    if (selectedIds.size == 1) {
                        RoundIconAction(
                            icon = Icons.Filled.MoreVert,
                            description = "更多操作",
                            onClick = {
                                actionTarget = items.firstOrNull { it.id in selectedIds }
                                selectedIds = emptySet()
                            },
                        )
                    }
                    RoundIconAction(
                        icon = Icons.Filled.Close,
                        description = "退出多选",
                        onClick = { selectedIds = emptySet() },
                    )
                }
            }

            // ---- 两排标签：上排人名，下排其他标签；都可多选 ----
            if (!isTrash && (personTags.isNotEmpty() || otherTags.isNotEmpty())) {
                if (personTags.isNotEmpty()) {
                    TagFilterRow(
                        leadingLabel = "不限",
                        leadingSelected = personTags.none { selectedTags.contains(it.name) },
                        onLeadingClick = {
                            // 只清空"人物"这一排，其他排的选择保留
                            personTags.forEach { tag ->
                                if (selectedTags.contains(tag.name)) container.mediaStore.toggleTag(tag.name)
                            }
                        },
                        tags = personTags,
                        selectedTags = selectedTags,
                        countOf = { countIn(tagUsage, it.name, kind) },
                        onToggle = { container.mediaStore.toggleTag(it) },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (otherTags.isNotEmpty()) {
                    TagFilterRow(
                        leadingLabel = "不限",
                        leadingSelected = otherTags.none { selectedTags.contains(it.name) },
                        onLeadingClick = {
                            otherTags.forEach { tag ->
                                if (selectedTags.contains(tag.name)) container.mediaStore.toggleTag(tag.name)
                            }
                        },
                        tags = otherTags,
                        selectedTags = selectedTags,
                        countOf = { countIn(tagUsage, it.name, kind) },
                        onToggle = { container.mediaStore.toggleTag(it) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // 选择反馈：说清"同时满足"，并提供一键清空
                if (selectedTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "已选 ${selectedTags.size} 个标签 · 同时满足 · ${items.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = onGlassColor(dark, emphasis = false),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "清空",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { container.mediaStore.clearTags() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (error != null) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    ErrorBar(
                        message = error!!,
                        onRetry = { container.mediaStore.refreshImages() },
                        onDismiss = { container.mediaStore.clearError() },
                    )
                }
            }

            // ---- 网格 ----
            when {
                items.isEmpty() && loading -> LoadingBox(text = "正在加载媒体…")

                items.isEmpty() && selectedTags.isNotEmpty() -> EmptyState(
                    title = "没有同时满足的项",
                    description = "已选 ${selectedTags.joinToString("、")}。多个标签是「同时满足」，" +
                        "取消其中几个就能看到结果。",
                    icon = if (kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
                    actionLabel = "清空标签筛选",
                    onAction = { container.mediaStore.clearTags() },
                )

                items.isEmpty() -> EmptyState(
                    title = when (kind) {
                        MediaKind.TRASH -> "回收站是空的"
                        MediaKind.VIDEO -> "视频库是空的"
                        MediaKind.IMAGE -> "图片库是空的"
                    },
                    description = when (kind) {
                        MediaKind.TRASH -> "被删除的媒体会先放到这里，保留期内可随时恢复。"
                        MediaKind.VIDEO -> "上传视频，或在「视频提取」里粘贴链接把视频存进来。"
                        MediaKind.IMAGE -> "点击右上角的上传按钮，从相册选择图片。"
                    },
                    icon = when (kind) {
                        MediaKind.TRASH -> Icons.Filled.DeleteOutline
                        MediaKind.VIDEO -> Icons.Filled.Movie
                        MediaKind.IMAGE -> Icons.Filled.Image
                    },
                    actionLabel = if (kind == MediaKind.VIDEO) "去视频提取" else null,
                    onAction = if (kind == MediaKind.VIDEO) onOpenParse else null,
                )

                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(uiPrefs.gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        MediaCard(
                            name = item.name,
                            imageUrl = container.mediaRepository.gridUrl(item),
                            isVideo = item.isVideo,
                            aspectRatio = if (item.isVideo) 16f / 9f else item.aspectRatio,
                            views = if (isTrash) null else item.views,
                            r18 = item.r18,
                            tags = item.tags,
                            selected = item.id in selectedIds,
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    onOpenImage(index)
                                }
                            },
                            onLongClick = {
                                if (isTrash) {
                                    actionTarget = item
                                } else {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                }
                            },
                            poster = if (item.isVideo && uiPrefs.videoPosterEnabled) {
                                VideoPosterRequest(item)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        // 排序面板
        if (sortExpanded) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { sortExpanded = false }) {
                SortDialog(
                    current = filters.sort,
                    onPick = { key ->
                        sortExpanded = false
                        container.mediaStore.setSort(key)
                    },
                    onToggleColumns = {
                        val next = if (uiPrefs.gridColumns >= 5) 2 else uiPrefs.gridColumns + 1
                        container.prefs.updateUi { it.copy(gridColumns = next) }
                        onToast("每行 $next 张")
                    },
                    columns = uiPrefs.gridColumns,
                    onDismiss = { sortExpanded = false },
                )
            }
        }

        actionTarget?.let { item ->
            MediaActionDialog(
                item = item,
                isAdmin = container.sessionStore.isAdmin,
                onDismiss = { actionTarget = null },
                onToast = onToast,
            )
        }
    }
}

/** 某个标签在当前库里的数量（图片库看 images，视频库看 videos，回收站看总数） */
private fun countIn(
    usage: Map<String, TagUsage>,
    tagName: String,
    kind: MediaKind,
): Int = MediaFiltering.countIn(usage[tagName], kind)

/**
 * 一排可多选的标签。
 *
 * 第一个「不限」是这一排的"清空"按钮：当选中的标签都属于本排时它才高亮，
 * 语义上等价于"这一排不设限"。
 */
@Composable
private fun TagFilterRow(
    leadingLabel: String,
    leadingSelected: Boolean,
    onLeadingClick: () -> Unit,
    tags: List<TagItem>,
    selectedTags: Set<String>,
    countOf: (TagItem) -> Int,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 0.dp),
    ) {
        item {
            TagChip(
                name = leadingLabel,
                colorHex = "#6366F1",
                selected = leadingSelected,
                showDot = false,
                onClick = onLeadingClick,
            )
        }
        items(tags, key = { it.id }) { tag ->
            TagChip(
                name = tag.name,
                colorHex = tag.color,
                selected = selectedTags.contains(tag.name),
                count = countOf(tag),
                onClick = { onToggle(tag.name) },
            )
        }
    }
}


@Composable
private fun SortDialog(
    current: String,
    columns: Int,
    onPick: (String) -> Unit,
    onToggleColumns: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        com.fluxframe.app.ui.glass.GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "排序方式",
                    style = MaterialTheme.typography.titleSmall,
                    color = onGlassColor(dark, emphasis = true),
                )
                MediaRepository.SORT_OPTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.key) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (option.key == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                onGlassColor(dark, emphasis = true)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (option.key == current) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
                com.fluxframe.app.ui.components.HairLine()
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleColumns() }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = null,
                        tint = onGlassColor(dark, emphasis = false),
                        modifier = Modifier.size(17.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "每行 ${columns} 张",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onGlassColor(dark, emphasis = true),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "点击切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = onGlassColor(dark, emphasis = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    com.fluxframe.app.ui.glass.GlassSurface(
        modifier = Modifier.size(42.dp).clickable { onClick() },
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = onGlassColor(dark, emphasis = true),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** 供其它页面复用：把标签色转成圆点颜色时可统一走这里 */
@Composable
fun tagDotColor(colorHex: String): Color = com.fluxframe.app.ui.components.parseColor(colorHex)

/** 未使用的图标引用，避免被误删（保留搜索/关闭的语义） */
private val unusedIcons = listOf(Icons.Filled.Close)

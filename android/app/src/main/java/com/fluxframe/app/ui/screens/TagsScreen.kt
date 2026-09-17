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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.model.PersonDetail
import com.fluxframe.app.data.model.TagItem
import com.fluxframe.app.data.store.MediaKind
import com.fluxframe.app.ui.LocalAppContainer
import com.fluxframe.app.ui.components.CollapsibleHeader
import com.fluxframe.app.ui.components.CollapsibleSection
import com.fluxframe.app.ui.components.EmptyState
import com.fluxframe.app.ui.components.GlassTextField
import com.fluxframe.app.ui.components.LoadingBox
import com.fluxframe.app.ui.components.MediaThumb
import com.fluxframe.app.ui.components.MiniBadge
import com.fluxframe.app.ui.components.PrimaryActionButton
import com.fluxframe.app.ui.components.SectionTitle
import com.fluxframe.app.ui.components.SecondaryActionButton
import com.fluxframe.app.ui.components.StatTile
import com.fluxframe.app.ui.components.SwitchRow
import com.fluxframe.app.ui.components.TagChip
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.LocalDarkTheme
import com.fluxframe.app.ui.theme.onGlassColor
import kotlinx.coroutines.launch

/** 全部标签默认只渲染这么多个，避免标签多时一次性组合出整屏 */
private const val TAG_PREVIEW_COUNT = 24

/** 智能标签：人物组（可点进详情）+ 普通标签 */
@Composable
fun TagsScreen(
    onOpenPerson: (String) -> Unit,
    /** 点标签后跳到对应的库：有图片去图片库，只有视频就自动去视频库 */
    onOpenMediaLibrary: (MediaKind) -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val tags by container.mediaStore.tags.collectAsStateWithLifecycle()
    val media by container.mediaStore.liveImages.collectAsStateWithLifecycle()
    val loaded by container.mediaStore.tagsLoaded.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TagItem?>(null) }
    var confirmDelete by remember { mutableStateOf<TagItem?>(null) }
    var batchTarget by remember { mutableStateOf<TagItem?>(null) }
    // 折叠状态记在 saveable 里：转屏、进出页面都不会被重置
    var personExpanded by rememberSaveable { mutableStateOf(true) }
    var tagsExpanded by rememberSaveable { mutableStateOf(true) }
    var showAllTags by rememberSaveable { mutableStateOf(false) }

    val personTags = tags.filter { it.person }
    val normalTags = tags.filter { !it.person }
    val linkedCount = tags.sumOf { it.count }
    // 默认只渲染前 24 个标签，避免几百个标签时一次性组合出整屏
    val visibleTags = if (showAllTags) normalTags else normalTags.take(TAG_PREVIEW_COUNT)

    // 每个标签的"第一张符合它的媒体"，用来替换原来那个通用的人脸矢量图
    val previewByTag: Map<String, ImageItem> = remember(media) {
        val map = HashMap<String, ImageItem>()
        media.forEach { item ->
            item.tags.forEach { name -> if (!map.containsKey(name)) map[name] = item }
        }
        map
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "共 ${tags.size} 个标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = onGlassColor(dark, emphasis = false),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryActionButton(
                        text = if (personExpanded && tagsExpanded) "全部收起" else "全部展开",
                        onClick = {
                            val next = !(personExpanded && tagsExpanded)
                            personExpanded = next
                            tagsExpanded = next
                        },
                    )
                    SecondaryActionButton(
                        text = "新建标签",
                        icon = Icons.Filled.Add,
                        onClick = { showCreate = true },
                    )
                }
            }

            // 与网页端一致的三张统计卡：自定义标签 / 人物组人名 / 标签关联总数
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "自定义标签",
                        value = normalTags.size.toString(),
                        icon = Icons.Filled.Label,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "人物组人名",
                        value = personTags.size.toString(),
                        icon = Icons.Filled.Face,
                        accent = Color(0xFF22C55E),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "标签关联",
                        value = linkedCount.toString(),
                        icon = Icons.Filled.Label,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (!loaded && tags.isEmpty()) {
                item { LoadingBox(text = "正在加载标签…") }
            }

            // ---- 人物组（可折叠 + 横向滑动）----
            if (personTags.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = "人物",
                        count = personTags.size,
                        expanded = personExpanded,
                        onToggle = { personExpanded = !personExpanded },
                    ) {
                        // 横向滑动：人物再多也不必把页面拉得很长
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            items(personTags, key = { it.id }) { tag ->
                                GlassSurface(
                                    modifier = Modifier
                                        .width(104.dp)
                                        .clickable { onOpenPerson(tag.id) },
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        // 用该人物的第一张媒体当封面，替换原来的通用人脸矢量图；
                                        // 该人物还没有可见媒体时自动退回人脸占位
                                        MediaThumb(
                                            item = previewByTag[tag.name],
                                            size = 44.dp,
                                            shape = CircleShape,
                                            alpha = 0.92f,
                                            placeholderIcon = Icons.Filled.Face,
                                            placeholderTint = Color(0xFF22C55E),
                                        )
                                        Text(
                                            text = tag.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = onGlassColor(dark, emphasis = true),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 6.dp),
                                        )
                                        Text(
                                            text = "${tag.count} 张",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = onGlassColor(dark, emphasis = false),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 普通标签（可折叠；默认只渲染前若干个）----
            item {
                CollapsibleHeader(
                    title = "全部标签",
                    count = normalTags.size,
                    expanded = tagsExpanded,
                    onToggle = { tagsExpanded = !tagsExpanded },
                )
            }
            if (tagsExpanded && normalTags.isEmpty() && personTags.isEmpty() && loaded) {
                item {
                    EmptyState(
                        title = "还没有标签",
                        description = "上传后由 AI 自动打标，或在这里手动创建。",
                        icon = Icons.Filled.Label,
                    )
                }
            }
            if (tagsExpanded) {
                items(visibleTags, key = { it.id }) { tag ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                container.mediaStore.setTag(tag.name)
                                onOpenMediaLibrary(container.mediaStore.libraryForTag(tag.name))
                            },
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(com.fluxframe.app.ui.components.parseColor(tag.color)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onGlassColor(dark, emphasis = true),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // 顺带说明这个标签的内容落在哪个库（"带视频标签自动转移到视频库"）
                                    text = buildString {
                                        val usage = container.mediaStore.usageOf(tag.name)
                                        append("${tag.count} 个媒体")
                                        if (usage.videos > 0 && usage.images > 0) {
                                            append(" · 图 ${usage.images} / 视频 ${usage.videos}")
                                        } else if (usage.videos > 0) {
                                            append(" · 仅视频")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                            }
                            if (tag.r18) {
                                MiniBadge(text = "R18", color = Color(0xFFEF4444))
                            }
                            Text(
                                text = "批量",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .clickable { batchTarget = tag }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text(
                                text = "编辑",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .clickable { renameTarget = tag }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (normalTags.size > TAG_PREVIEW_COUNT) {
                    item {
                        SecondaryActionButton(
                            text = if (showAllTags) {
                                "收起，只看前 $TAG_PREVIEW_COUNT 个"
                            } else {
                                "展开全部（还有 ${normalTags.size - TAG_PREVIEW_COUNT} 个）"
                            },
                            onClick = { showAllTags = !showAllTags },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(6.dp)) }
        }
    }

    if (showCreate) {
        CreateTagDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, r18, person ->
                showCreate = false
                scope.launch {
                    container.tagRepository.create(name = name, r18 = r18, person = person)
                        .onSuccess {
                            container.mediaStore.upsertTag(it)
                            onToast("已创建「${it.name}」")
                        }
                        .onFailure { onToast(it.message) }
                }
            },
            onToast = onToast,
        )
    }

    renameTarget?.let { tag ->
        TextPromptRename(
            tag = tag,
            onDismiss = { renameTarget = null },
            onToast = onToast,
        )
    }

    confirmDelete?.let { tag ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDelete = null }) {
            com.fluxframe.app.ui.components.ConfirmDialog(
                title = "删除标签「${tag.name}」？",
                text = "只删除标签本身，不会删除图片。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    confirmDelete = null
                    scope.launch {
                        container.tagRepository.delete(tag.id)
                            .onSuccess {
                                container.mediaStore.removeTagLocally(tag.id)
                                container.mediaStore.refreshAll()
                                onToast("已删除")
                            }
                            .onFailure { onToast(it.message) }
                    }
                },
                onDismiss = { confirmDelete = null },
            )
        }
    }

    batchTarget?.let { tag ->
        BatchTagDialog(
            tag = tag,
            onDismiss = { batchTarget = null },
            onToast = onToast,
        )
    }

    // 进入标签页时保证数据新鲜
    LaunchedEffect(Unit) {
        if (!loaded) container.mediaStore.refreshTags()
    }
}

@Composable
private fun CreateTagDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, r18: Boolean, person: Boolean) -> Unit,
    onToast: (String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var r18 by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf(false) }
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
                    text = "新建标签",
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "标签名（1–40 字）",
                )
                SwitchRow(
                    title = "人物标签",
                    description = "放进「人物」分组，可用于人物索引",
                    checked = person,
                    onCheckedChange = { person = it },
                )
                SwitchRow(
                    title = "R-18 标签",
                    description = "标记为成人内容，未开启 R18 模式时对所有端隐藏",
                    checked = r18,
                    onCheckedChange = { r18 = it },
                    enabled = !person,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(
                            text = "创建",
                            enabled = name.isNotBlank(),
                            onClick = { onCreate(name.trim(), r18, person) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPromptRename(
    tag: TagItem,
    onDismiss: () -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf(tag.person) }
    var r18 by remember { mutableStateOf(tag.r18) }
    val dark = LocalDarkTheme.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            backdrop = true,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = onGlassColor(dark, emphasis = true),
                )
                SwitchRow(
                    title = "人物标签",
                    checked = person,
                    onCheckedChange = { person = it },
                )
                SwitchRow(
                    title = "R-18",
                    checked = r18,
                    enabled = !tag.r18 && !person,
                    onCheckedChange = { r18 = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryActionButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryActionButton(
                            text = "保存",
                            onClick = {
                                scope.launch {
                                    container.tagRepository.update(
                                        id = tag.id,
                                        person = if (person != tag.person) person else null,
                                        r18 = if (r18 != tag.r18) r18 else null,
                                    )
                                        .onSuccess {
                                            container.mediaStore.upsertTag(it)
                                            container.mediaStore.refreshAll()
                                            onToast("已更新")
                                        }
                                        .onFailure { onToast(it.message) }
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 人物详情：该人物的媒体数、共同出现的标签、最新预览 */
@Composable
fun PersonDetailScreen(
    tagId: String,
    onOpenMediaLibrary: (MediaKind) -> Unit,
    onToast: (String?) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val dark = LocalDarkTheme.current
    val media by container.mediaStore.liveImages.collectAsStateWithLifecycle()
    var detail by remember(tagId) { mutableStateOf<PersonDetail?>(null) }
    var loading by remember(tagId) { mutableStateOf(true) }
    var error by remember(tagId) { mutableStateOf<String?>(null) }
    var batchTarget by remember(tagId) { mutableStateOf<TagItem?>(null) }
    var relatedExpanded by rememberSaveable(tagId) { mutableStateOf(true) }

    LaunchedEffect(tagId) {
        loading = true
        container.tagRepository.personDetail(tagId)
            .onSuccess {
                detail = it
                error = null
            }
            .onFailure { error = it.message }
        loading = false
    }

    val current = detail

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) item { LoadingBox(text = "正在读取人物信息…") }
        if (error != null) {
            item {
                com.fluxframe.app.ui.components.ErrorBar(
                    message = error!!,
                    onRetry = {
                        scope.launch {
                            container.tagRepository.personDetail(tagId).onSuccess { detail = it }
                        }
                    },
                    onDismiss = { error = null },
                )
            }
        }

        current?.let { person ->
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 人物头像同样用真实媒体：优先取该人物最新的一张预览，
                            // 拿不到再退回全量列表里第一张符合的
                            MediaThumb(
                                item = person.latest.firstOrNull()?.let { preview ->
                                    media.firstOrNull { it.id == preview.id }
                                } ?: media.firstOrNull { it.tags.contains(person.name) },
                                size = 42.dp,
                                shape = CircleShape,
                                alpha = 0.92f,
                                placeholderIcon = Icons.Filled.Face,
                                placeholderTint = Color(0xFF22C55E),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = person.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = onGlassColor(dark, emphasis = true),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${person.imageCount} 个媒体 · ${person.related.size} 个关联标签",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = onGlassColor(dark, emphasis = false),
                                )
                            }
                        }
                        SecondaryActionButton(
                            text = if (container.mediaStore.usageOf(person.name).images > 0) {
                                "在图片库中查看该人物"
                            } else {
                                "在视频库中查看该人物"
                            },
                            onClick = {
                                container.mediaStore.setTag(person.name)
                                onOpenMediaLibrary(container.mediaStore.libraryForTag(person.name))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "批量加图",
                                    icon = Icons.Filled.Add,
                                    onClick = {
                                        batchTarget = TagItem(
                                            id = person.id,
                                            name = person.name,
                                            color = person.color,
                                            r18 = person.r18,
                                            person = person.person,
                                            count = person.count,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SecondaryActionButton(
                                    text = "移出人物组",
                                    onClick = {
                                        scope.launch {
                                            container.tagRepository.update(id = person.id, person = false)
                                                .onSuccess {
                                                    container.mediaStore.refreshAll()
                                                    onToast("已移出人物组")
                                                }
                                                .onFailure { onToast(it.message) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            if (person.latest.isNotEmpty()) {
                item { SectionTitle(text = "最新") }
                item {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false,
                    ) {
                        items(person.latest, key = { it.id }) { preview ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(container.mediaRepository.absoluteFor(preview.thumb))
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = preview.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            if (person.related.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = "共同出现的标签",
                        count = person.related.size,
                        expanded = relatedExpanded,
                        onToggle = { relatedExpanded = !relatedExpanded },
                    ) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            person.related.forEach { tag ->
                                TagChip(
                                    name = tag.name,
                                    colorHex = tag.color,
                                    count = tag.count,
                                    onClick = {
                                        container.mediaStore.setTag(tag.name)
                                        onOpenMediaLibrary(container.mediaStore.libraryForTag(tag.name))
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

    batchTarget?.let { tag ->
        BatchTagDialog(
            tag = tag,
            onDismiss = { batchTarget = null },
            onToast = onToast,
        )
    }
}

package com.fluxframe.app.data.store

import com.fluxframe.app.data.model.ImageItem

/**
 * 媒体筛选的**纯逻辑**（不依赖 Android 与网络），单独抽出来是为了能直接单元测试。
 *
 * 这里承载三条容易搞错、又直接影响用户观感的规则：
 *  1. 标签多选是「**同时满足**」（AND），不是并集；
 *  2. 图片库只出图片、视频库只出视频，回收站不分类；
 *  3. 标签的可用性按库统计 —— 只打在视频上的标签不会出现在图片库里，
 *     这就是「带视频标签自动转移到视频库」。
 */
object MediaFiltering {

    /** [selected] 为空表示不限；否则要求该项同时具备所有已选标签 */
    fun matches(item: ImageItem, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        return selected.all { item.tags.contains(it) }
    }

    /** 只看图片 */
    fun imagesOf(list: List<ImageItem>): List<ImageItem> = list.filter { it.isImage }

    /** 只看视频 */
    fun videosOf(list: List<ImageItem>): List<ImageItem> = list.filter { it.isVideo }

    /** 按库筛选（图片库 / 视频库 / 回收站）*/
    fun apply(list: List<ImageItem>, kind: MediaKind, selected: Set<String>): List<ImageItem> {
        val byKind = when (kind) {
            MediaKind.IMAGE -> imagesOf(list)
            MediaKind.VIDEO -> videosOf(list)
            MediaKind.TRASH -> list
        }
        if (kind == MediaKind.TRASH) return byKind
        return byKind.filter { matches(it, selected) }
    }

    /** 每个标签名在图片 / 视频里各有多少项 */
    fun usageOf(list: List<ImageItem>): Map<String, TagUsage> {
        val usage = HashMap<String, TagUsage>()
        list.forEach { item ->
            item.tags.forEach { name ->
                val current = usage[name] ?: TagUsage()
                usage[name] = if (item.isVideo) {
                    current.copy(videos = current.videos + 1)
                } else {
                    current.copy(images = current.images + 1)
                }
            }
        }
        return usage
    }

    /** 某个标签在某类库里有多少项（用于标签行的数量与"是否显示这一行"） */
    fun countIn(usage: TagUsage?, kind: MediaKind): Int {
        if (usage == null) return 0
        return when (kind) {
            MediaKind.VIDEO -> usage.videos
            MediaKind.IMAGE -> usage.images
            MediaKind.TRASH -> usage.total
        }
    }

    /**
     * 点一个标签时该跳哪个库：
     * 有图片就去图片库；只有视频（或视频更多）就去视频库。
     */
    fun libraryFor(usage: TagUsage?): MediaKind {
        if (usage == null) return MediaKind.IMAGE
        return when {
            usage.images > 0 -> MediaKind.IMAGE
            usage.videos > 0 -> MediaKind.VIDEO
            else -> MediaKind.IMAGE
        }
    }
}

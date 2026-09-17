package com.fluxframe.app

import com.fluxframe.app.data.model.ImageItem
import com.fluxframe.app.data.store.MediaFiltering
import com.fluxframe.app.data.store.MediaKind
import com.fluxframe.app.data.store.TagUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片库 / 视频库的筛选规则。
 *
 * 这一轮改动里最容易出错、也最影响观感的三条：
 *  1. 标签多选是「同时满足」（AND），不是并集；
 *  2. 图片库只出图片、视频库只出视频，回收站不分类；
 *  3. 只打在视频上的标签不能出现在图片库里（"带视频标签自动转移到视频库"）。
 *
 * 全是纯函数，不依赖 Android 与网络，所以任何一次构建都会真的跑。
 */
class MediaLibraryFilterTest {

    private fun image(id: String, tags: List<String>) = ImageItem(
        id = id,
        name = "图片-$id",
        mimeType = "image/jpeg",
        tags = tags,
    )

    private fun video(id: String, tags: List<String>) = ImageItem(
        id = id,
        name = "视频-$id",
        mimeType = "video/mp4",
        tags = tags,
    )

    private val library = listOf(
        image("i1", listOf("人物甲", "海边")),
        image("i2", listOf("人物甲", "夜景")),
        image("i3", listOf("人物乙", "海边")),
        image("i4", listOf("风景")),
        video("v1", listOf("人物甲", "旅行")),
        video("v2", listOf("旅行")),
        video("v3", listOf("只有视频才有")),
    )

    /* ------------------------------ 分类 ------------------------------ */

    @Test
    fun `图片库只出图片 视频库只出视频`() {
        val images = MediaFiltering.apply(library, MediaKind.IMAGE, emptySet())
        val videos = MediaFiltering.apply(library, MediaKind.VIDEO, emptySet())

        assertEquals(listOf("i1", "i2", "i3", "i4"), images.map { it.id })
        assertEquals(listOf("v1", "v2", "v3"), videos.map { it.id })
        assertTrue("图片库里不能混进视频", images.none { it.isVideo })
        assertTrue("视频库里不能混进图片", videos.none { it.isImage })
    }

    @Test
    fun `回收站不按类型拆分也不按标签过滤`() {
        val trash = MediaFiltering.apply(library, MediaKind.TRASH, setOf("人物甲"))
        assertEquals("回收站要原样展示（它自己已经是一份列表）", library.size, trash.size)
    }

    /* --------------------------- 多选语义 --------------------------- */

    @Test
    fun `多个标签是同时满足而不是并集`() {
        val images = MediaFiltering.apply(library, MediaKind.IMAGE, setOf("人物甲", "海边"))
        assertEquals("同时有「人物甲」和「海边」的只有 i1", listOf("i1"), images.map { it.id })

        // 并集的话会得到 i1 i2 i3，这里明确否掉
        assertFalse("不能退化成并集", images.size == 3)
    }

    @Test
    fun `选多个标签可能筛空 这是预期行为`() {
        val images = MediaFiltering.apply(library, MediaKind.IMAGE, setOf("人物乙", "夜景"))
        assertTrue(images.isEmpty())
    }

    @Test
    fun `不选标签等于不限`() {
        assertEquals(library.size, MediaFiltering.apply(library, MediaKind.TRASH, emptySet()).size)
        assertEquals(4, MediaFiltering.apply(library, MediaKind.IMAGE, emptySet()).size)
    }

    @Test
    fun `多选可以跨两类标签`() {
        // 人物甲（人物）+ 夜景（其他）→ 只有 i2
        val images = MediaFiltering.apply(library, MediaKind.IMAGE, setOf("人物甲", "夜景"))
        assertEquals(listOf("i2"), images.map { it.id })
    }

    /* ------------------------ 标签的库归属统计 ------------------------ */

    @Test
    fun `标签分布按图片与视频分开统计`() {
        val usage = MediaFiltering.usageOf(library)
        assertEquals(TagUsage(images = 2, videos = 1), usage["人物甲"])
        assertEquals(TagUsage(images = 2, videos = 0), usage["海边"])
        assertEquals(TagUsage(images = 0, videos = 2), usage["旅行"])
        assertEquals(TagUsage(images = 0, videos = 1), usage["只有视频才有"])
    }

    @Test
    fun `只打在视频上的标签不会出现在图片库里`() {
        val usage = MediaFiltering.usageOf(library)
        assertEquals(
            "「只有视频才有」在图片库里的数量必须是 0，于是标签行会把它藏起来",
            0,
            MediaFiltering.countIn(usage["只有视频才有"], MediaKind.IMAGE),
        )
        assertEquals(1, MediaFiltering.countIn(usage["只有视频才有"], MediaKind.VIDEO))
        // 「海边」只有图片，就不该出现在视频库里
        assertEquals(0, MediaFiltering.countIn(usage["海边"], MediaKind.VIDEO))
        assertEquals(2, MediaFiltering.countIn(usage["海边"], MediaKind.IMAGE))
        // 「人物甲」两边都有，两个库都要显示
        assertEquals(2, MediaFiltering.countIn(usage["人物甲"], MediaKind.IMAGE))
        assertEquals(1, MediaFiltering.countIn(usage["人物甲"], MediaKind.VIDEO))
    }

    @Test
    fun `没有内容的标签数量为零`() {
        assertEquals(0, MediaFiltering.countIn(null, MediaKind.IMAGE))
        assertEquals(0, MediaFiltering.countIn(null, MediaKind.VIDEO))
        assertEquals(0, MediaFiltering.countIn(null, MediaKind.TRASH))
    }

    /* -------------------- 点标签该跳哪个库 -------------------- */

    @Test
    fun `点标签跳向内容所在的那个库`() {
        val usage = MediaFiltering.usageOf(library)
        assertEquals(
            "有图片就去图片库",
            MediaKind.IMAGE,
            MediaFiltering.libraryFor(usage["海边"]),
        )
        assertEquals(
            "只有视频就自动去视频库",
            MediaKind.VIDEO,
            MediaFiltering.libraryFor(usage["只有视频才有"]),
        )
        assertEquals(
            "图文都有时优先图片库",
            MediaKind.IMAGE,
            MediaFiltering.libraryFor(usage["人物甲"]),
        )
        assertEquals("未知标签时退回图片库", MediaKind.IMAGE, MediaFiltering.libraryFor(null))
    }

    /* ---------------------------- 边界 ---------------------------- */

    @Test
    fun `一张媒体没有任何标签时不影响统计`() {
        val usage = MediaFiltering.usageOf(listOf(image("x", emptyList()), video("y", emptyList())))
        assertTrue("没有标签就不该产生任何统计项", usage.isEmpty())
        // 无标签的项在不限标签时必须照常出现（否则"没打标签的图片"会凭空消失）
        assertEquals(1, MediaFiltering.apply(listOf(image("x", emptyList())), MediaKind.IMAGE, emptySet()).size)
        assertEquals(1, MediaFiltering.apply(listOf(video("y", emptyList())), MediaKind.VIDEO, emptySet()).size)
    }
}

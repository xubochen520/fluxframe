package com.fluxframe.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.ui.components.BottomNavItem
import com.fluxframe.app.ui.components.FluxBottomBar
import com.fluxframe.app.ui.components.FluxTopBar
import com.fluxframe.app.ui.glass.GlassSurface
import com.fluxframe.app.ui.theme.FluxFrameTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 布局回归测试。
 *
 * 主要盯住两件事：
 *  1. **"登录后一片空白"**。成因：`GlassSurface` 的背景副本要整屏大小才能与外层背景
 *     对齐，而 Compose 的 `Box` 按最大子节点决定自身尺寸 —— 顶栏/底栏/弹窗被撑到整屏高，
 *     内容区被挤成 0。现在的实现改成"平移画布 + 按卡片区域绘制"，从根上没有了整屏子节点。
 *  2. **顶栏/底栏的高度预算**。顶栏固定 48dp、底栏是悬浮胶囊，二者都必须远小于整屏，
 *     否则又会挤掉内容区。
 *
 * 这些断言在修复前必然失败（顶栏高度 = 整屏、内容区高度 = 0），所以能真正防回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `带背景副本的玻璃卡片不会把自己撑到整屏高`() {
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.LIQUID_GLASS, darkTheme = true, animationsEnabled = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth().testTag("glass"),
                        backdrop = true,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text("卡片内容")
                    }
                }
            }
        }

        val height = composeRule.onNodeWithTag("glass").fetchSemanticsNode().size.height
        // 整屏 ≈ 891dp * 2(xhdpi) ≈ 1782px；卡片应只有内容高度
        assertTrue("玻璃卡片高度应贴合内容，实际 ${height}px（≈整屏说明背景副本参与了测量）", height in 1..400)
    }

    @Test
    fun `顶栏与底栏不会把内容区挤成零高度`() {
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.LIQUID_GLASS, darkTheme = true, animationsEnabled = false) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FluxTopBar(
                        title = "总览",
                        modifier = Modifier.testTag("topbar"),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("content")) {
                        Text("内容区可见", modifier = Modifier.testTag("contentText"))
                    }
                    FluxBottomBar(
                        items = listOf(BottomNavItem("overview", "总览", Icons.Filled.Dashboard)),
                        selectedKey = "overview",
                        onSelect = {},
                        modifier = Modifier.testTag("bottombar"),
                    )
                }
            }
        }

        val topBarHeight = composeRule.onNodeWithTag("topbar").fetchSemanticsNode().size.height
        val bottomBarHeight = composeRule.onNodeWithTag("bottombar").fetchSemanticsNode().size.height
        val contentHeight = composeRule.onNodeWithTag("content").fetchSemanticsNode().size.height

        assertTrue("顶栏高度应远小于整屏，实际 ${topBarHeight}px", topBarHeight in 1..400)
        assertTrue("底栏高度应远小于整屏，实际 ${bottomBarHeight}px", bottomBarHeight in 1..400)
        assertTrue("内容区必须有高度，实际 ${contentHeight}px", contentHeight > 300)

        // 最关键的一条：内容真的画出来了（"总览"在顶栏与底栏各有一处，所以按 tag 断言）
        composeRule.onNodeWithTag("contentText").assertIsDisplayed()
        composeRule.onNodeWithTag("topbar").assertIsDisplayed()
        composeRule.onNodeWithTag("bottombar").assertIsDisplayed()
    }

    @Test
    fun `三种主题下内容区都可见`() {
        // setContent 每个测试只能调一次，所以用状态切换主题而不是循环重建
        val currentStyle = mutableStateOf(AppThemeStyle.DEFAULT)
        composeRule.setContent {
            FluxFrameTheme(style = currentStyle.value, darkTheme = true, animationsEnabled = false) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FluxTopBar(title = currentStyle.value.label, modifier = Modifier.testTag("topbar"))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("body")) {
                        Text("内容")
                    }
                }
            }
        }

        AppThemeStyle.entries.forEach { style ->
            composeRule.runOnUiThread { currentStyle.value = style }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("body").assertIsDisplayed()
            val contentHeight = composeRule.onNodeWithTag("body").fetchSemanticsNode().size.height
            val topBarHeight = composeRule.onNodeWithTag("topbar").fetchSemanticsNode().size.height
            assertTrue("${style.label}：内容区高度应为正，实际 ${contentHeight}px", contentHeight > 300)
            assertTrue("${style.label}：顶栏高度应贴合内容，实际 ${topBarHeight}px", topBarHeight in 1..400)
        }
    }

    @Test
    fun `弹窗形态的玻璃卡片贴合内容而不是整屏`() {
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.ACRYLIC, darkTheme = true, animationsEnabled = false) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth().testTag("dialog"),
                        backdrop = true,
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("确认上传")
                            Text("共 3 个文件")
                        }
                    }
                }
            }
        }

        val height = composeRule.onNodeWithTag("dialog").fetchSemanticsNode().size.height
        assertTrue("弹窗高度应贴合内容，实际 ${height}px", height in 1..400)
    }

    /**
     * 顶栏"滚动态"不能改变高度。
     *
     * 旧版顶栏在滚动后要换成"带玻璃底板"的形态，如果那时高度变了，内容会整体跳动。
     * 这条断言锁死"两种状态下高度一致"。
     */
    @Test
    fun `顶栏在滚动态与静止态高度一致`() {
        val scrolled = mutableStateOf(false)
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.LIQUID_GLASS, darkTheme = true, animationsEnabled = false) {
                FluxTopBar(
                    title = "图片库",
                    scrolled = scrolled.value,
                    modifier = Modifier.testTag("topbar"),
                )
            }
        }

        val idleHeight = composeRule.onNodeWithTag("topbar").fetchSemanticsNode().size.height
        composeRule.runOnUiThread { scrolled.value = true }
        composeRule.waitForIdle()
        val scrolledHeight = composeRule.onNodeWithTag("topbar").fetchSemanticsNode().size.height

        assertTrue("顶栏高度应固定，静止=${idleHeight}px 滚动=${scrolledHeight}px", idleHeight == scrolledHeight)
        // 48dp @ xhdpi = 96px
        assertTrue("顶栏应为 48dp（约 96px），实际 ${idleHeight}px", idleHeight in 80..130)
    }

    /**
     * 悬浮底栏只在选中项上展开文字（SukiSU 风格）。
     *
     * 这条断言同时验证了"未选中项不渲染标签"这个视觉规则 —— 如果哪天改回
     * 五项全部"图标+文字"，这里会立刻失败。
     */
    @Test
    fun `悬浮底栏只给选中项展开文字`() {
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.LIQUID_GLASS, darkTheme = true, animationsEnabled = false) {
                FluxBottomBar(
                    items = listOf(
                        BottomNavItem("overview", "总览", Icons.Filled.Dashboard),
                        BottomNavItem("library", "图片库", Icons.Filled.Image),
                    ),
                    selectedKey = "library",
                    onSelect = {},
                )
            }
        }

        // 选中项的标签出现且只出现一次
        composeRule.onAllNodesWithText("图片库").assertCountEquals(1)
        // 未选中项不展开标签
        composeRule.onAllNodesWithText("总览").assertCountEquals(0)
    }
}

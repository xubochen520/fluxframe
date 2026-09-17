package com.fluxframe.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fluxframe.app.core.prefs.AppThemeStyle
import com.fluxframe.app.data.store.ImportTask
import com.fluxframe.app.data.store.UploadPhase
import com.fluxframe.app.data.store.UploadTask
import com.fluxframe.app.ui.components.TaskDock
import com.fluxframe.app.ui.theme.FluxFrameTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 新增 UI 的冒烟测试。
 *
 * 没有真机，所以至少用 Robolectric 把「能不能组合出来、文案在不在、按钮点得动吗」验一遍 ——
 * 这类问题（例如在非 RowScope 里用 weight、或 Dialog 里引用了未注入的 CompositionLocal）
 * 编译期发现不了，只能靠渲染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `任务坞渲染上传与提取两类卡片并能触发回调`() {
        var cancelledUpload = false
        var openedLibrary = false

        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.LIQUID_GLASS, darkTheme = true, animationsEnabled = false) {
                TaskDock(
                    upload = UploadTask(
                        phase = UploadPhase.ANALYZING,
                        totalFiles = 3,
                        progress = 0.42f,
                        message = "已上传 42%",
                    ),
                    imports = listOf(
                        ImportTask(
                            id = "job-1",
                            title = "示例视频",
                            kind = "video",
                            status = "done",
                            message = "已保存 1 个文件到图片库 ✓",
                        ),
                    ),
                    onOpenLibrary = { openedLibrary = true },
                    onCancelUpload = { cancelledUpload = true },
                    onRetryUpload = {},
                    onDismissUpload = {},
                    onCancelImport = {},
                    onRetryImport = {},
                    onDismissImport = {},
                )
            }
        }

        // 上传卡片
        composeRule.onNodeWithText("上传中 · 3 个文件").assertIsDisplayed()
        composeRule.onNodeWithText("已上传 42%").assertIsDisplayed()
        // 提取卡片（服务端返回的中文文案要能原样显示）
        composeRule.onNodeWithText("保存到图片库 · 示例视频").assertIsDisplayed()
        composeRule.onNodeWithText("已保存 1 个文件到图片库 ✓").assertIsDisplayed()
        // 完成态才有「去图片库查看」
        composeRule.onNodeWithText("去图片库查看").assertIsDisplayed()

        // 进行中的上传可以取消
        composeRule.onNodeWithText("取消").performClick()
        assertTrue("点「取消」应回调 onCancelUpload", cancelledUpload)
    }

    @Test
    fun `任务坞在空状态下不渲染任何内容`() {
        composeRule.setContent {
            FluxFrameTheme(style = AppThemeStyle.DEFAULT, darkTheme = true, animationsEnabled = false) {
                TaskDock(
                    upload = null,
                    imports = emptyList(),
                    onOpenLibrary = {},
                    onCancelUpload = {},
                    onRetryUpload = {},
                    onDismissUpload = {},
                    onCancelImport = {},
                    onRetryImport = {},
                    onDismissImport = {},
                )
            }
        }
        // 没有任何任务时，一张卡片都不该出现
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("去图片库查看").assertCountEquals(0)
    }

    /*
     * 关于「批量打标签弹窗」的测试缺口（如实记录，不假装覆盖了）：
     *
     * Dialog 里只要含 GlassTextField 或 LazyVerticalGrid，Robolectric 下 Compose
     * 就永远进不了 idle（AppNotIdleException），连把 mainClock.autoAdvance 关掉也没用，
     * 因为 setContent 自身会等 idle。做过对照实验：
     *   - Dialog + 纯文本            → 正常 idle
     *   - Dialog + 玻璃卡片(无/有背景副本) → 正常 idle
     *   - Dialog + 玻璃卡片 + 输入框   → 永不 idle
     *   - Dialog + 空 LazyVerticalGrid → 永不 idle
     * 同样这些部件放在 Dialog 外都能正常 idle，所以判定为测试环境限制而非应用缺陷。
     * 结论：BatchTagDialog 的正确性需要真机验证，本文件不覆盖它。
     */
}

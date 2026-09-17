package com.fluxframe.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「顶栏随滚动收起」的判定逻辑。
 *
 * 单独抽成一个类（而不是散在 `NestedScrollConnection` 的 lambda 里），
 * 是为了让这条规则**可以被单元测试直接验证** —— 顶栏收不收起是用户能直接看到的行为，
 * 靠肉眼看代码确认是不够的。
 *
 * 规则：
 *  - 向下看内容（手指上滑，`consumedY < 0`）累积到 [threshold] 像素后收起；
 *  - 往回滑（`consumedY > 0`）把累积量抵消掉，抵消到 0 就放出来；
 *  - 列表已经到顶、再往上拖（`availableY > 0`）时**立刻**放出来，不用等抵消。
 *
 * 最后一条很关键：否则用户滑回顶部后还得再滑一段顶栏才出现，手感会很怪。
 */
class HeaderAutoHide(
    private val enabled: Boolean,
    private val threshold: Float = DEFAULT_THRESHOLD,
) {

    /** 顶栏是否收起（收起时状态栏也一起让位） */
    var hidden by mutableStateOf(false)
        private set

    /** 内容是否已经滚动过（用于决定顶栏要不要浮出玻璃底板） */
    var scrolled by mutableStateOf(false)
        private set

    private var accumulator by mutableFloatStateOf(0f)

    /**
     * @param consumedY 子滚动容器这次实际消费掉的纵向位移（向下看内容为负）
     * @param availableY 未被消费、继续向上传递的位移（> 0 表示"已经到顶还在往上拖"）
     */
    fun onScroll(consumedY: Float, availableY: Float) {
        if (availableY > 0f) {
            reset()
            return
        }
        if (consumedY == 0f) return
        scrolled = true
        if (!enabled) return

        accumulator -= consumedY
        if (consumedY < 0f) {
            if (accumulator >= threshold) hidden = true
        } else if (accumulator <= 0f) {
            accumulator = 0f
            hidden = false
        }
    }

    /** 换页 / 切换底部导航 / 回到顶部时调用 */
    fun reset() {
        hidden = false
        scrolled = false
        accumulator = 0f
    }

    companion object {
        const val DEFAULT_THRESHOLD = 56f
    }
}

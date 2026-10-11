package com.fluxframe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxframe.app.data.model.EmbedGraphMode
import com.fluxframe.app.ui.components.PillSelector
import com.fluxframe.app.ui.glass.DarkStage

/** 星系视图的两种画法：二维总览 / 三维星系空间 */
enum class GalaxyView(val label: String) {
    /** 正交投影 + 屏幕空间布局。密度高，一眼看清有多少团、彼此多远 */
    FLAT("星系图"),

    /** 透视投影 + 轨道相机。看空间感、推近看单图 */
    SPACE("3D 星系"),
}

/**
 * 「相似图关系网」这一页。
 *
 * 只是个很薄的包装：真正的画面在 [GalaxyScreen]（二维）和 [Galaxy3DScreen]（三维）里。
 * 放在这里的是**两者共享的状态** ——
 *   · [mode]（关系的依据：视觉指纹 / 标签）
 *   · [view]（二维 / 三维）
 *
 * 为什么模式要提上来：二维和三维是同一份关系数据的两种画法，用户在三维里切到「标签」、
 * 再切回二维时，看到的应该还是标签 —— 各自持有一份状态的话切一次就被重置了。
 *
 * [view] 用 rememberSaveable：转屏或从后台回来不该把用户踢回二维。
 */
@Composable
fun SimilarRoute(
    onOpenImage: (String) -> Unit,
    onToast: (String?) -> Unit,
) {
    var mode by remember { mutableStateOf(EmbedGraphMode.VISUAL) }
    var view by rememberSaveable { mutableStateOf(GalaxyView.FLAT) }

    /*
     * 整页切到深色令牌：两个星系画法都是深空舞台，浅色主题下浮在上面的玻璃与文字
     * 必须按深色那一套走。切在这里而不是各自的页面里，切换器才不会在换视图时闪一下。
     */
    DarkStage {
        Box(modifier = Modifier.fillMaxSize()) {
            when (view) {
                GalaxyView.FLAT -> GalaxyScreen(
                    onOpenImage = onOpenImage,
                    onToast = onToast,
                    mode = mode,
                    onModeChange = { mode = it },
                )

                GalaxyView.SPACE -> Galaxy3DScreen(
                    onOpenImage = onOpenImage,
                    onToast = onToast,
                    mode = mode,
                    onModeChange = { mode = it },
                )
            }

            /*
             * 视图开关：分段控件，浮在右上角。
             * 两个画面各自都有自己的「关系依据」胶囊行（在左上角），分居两侧才不会打架。
             */
            PillSelector(
                options = GalaxyView.entries,
                selected = view,
                label = { it.label },
                onSelect = { candidate -> if (view != candidate) view = candidate },
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
            )
        }
    }
}

package com.fluxframe.app.fluidcloud

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Sync

/** 胶囊/流体云要展示的任务类型 */
enum class CapsuleKind(val label: String) {
    UPLOAD("上传"),
    DOWNLOAD("下载"),
    PARSE("视频提取"),
    AI("AI 引擎"),
    SYNC("同步"),
}

enum class CapsuleTone { INFO, SUCCESS, ERROR }

/**
 * 「流体云 / 实况通知」的统一数据模型。
 *
 * 它是整个应用里传输状态的**唯一真相**：OPPO 流体云、系统实况通知、
 * 应用内顶部胶囊三者都渲染同一份数据，因此三处显示永远一致。
 */
data class CapsuleState(
    val active: Boolean = false,
    val kind: CapsuleKind = CapsuleKind.SYNC,
    val title: String = "",
    val subtitle: String = "",
    /** 0..1；null 表示进度不确定（上游未给 content-length） */
    val progress: Float? = null,
    val tone: CapsuleTone = CapsuleTone.INFO,
) {
    val icon: ImageVector
        get() = when (kind) {
            CapsuleKind.UPLOAD -> Icons.Filled.CloudUpload
            CapsuleKind.DOWNLOAD -> Icons.Filled.Download
            CapsuleKind.PARSE -> Icons.Filled.Movie
            CapsuleKind.AI -> Icons.Filled.AutoAwesome
            CapsuleKind.SYNC -> Icons.Filled.Sync
        }

    val indeterminate: Boolean get() = progress == null

    val percentText: String
        get() = progress?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: ""
}

/**
 * 流体云能力探测结果，用于在设置页如实展示当前走的是哪条链路。
 *
 * 这里刻意区分「主链路」与「并行增强」：
 *  - [backend] 是**一定可用**的那条（标准实况通知）；
 *  - [oppoAvailable] 表示本机 ColorOS 的意图共享通道是否通（通了就并行再发一份）。
 * 两者可以同时为真，谁生效由系统决定，应用不做承诺。
 */
data class FluidCloudCapability(
    val backend: FluidCloudBackend,
    /** 面向用户的一句话说明 */
    val description: String,
    /** OPPO 意图共享通道是否可用 */
    val oppoBound: Boolean = false,
    /** 通知权限是否已授予（未授予时实况更新与通知都不会出现） */
    val notificationsAllowed: Boolean = true,
) {
    val isOppo: Boolean get() = backend == FluidCloudBackend.OPPO_FLUID_CLOUD

    /** 设置页右上角那个小徽标的文案 */
    val badge: String
        get() = when {
            !notificationsAllowed -> "待授权"
            oppoBound -> "流体云 + 实况窗"
            backend == FluidCloudBackend.SYSTEM_LIVE_UPDATE -> "实况更新"
            else -> "系统通知"
        }
}

enum class FluidCloudBackend {
    /** OPPO ColorOS 流体云（意图共享通道可用） */
    OPPO_FLUID_CLOUD,

    /** Android 16 Live Updates（progress-centric notification 已被系统接受） */
    SYSTEM_LIVE_UPDATE,

    /** 普通常驻进度通知 */
    NOTIFICATION,
}

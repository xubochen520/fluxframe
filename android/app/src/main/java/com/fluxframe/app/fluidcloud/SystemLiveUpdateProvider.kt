package com.fluxframe.app.fluidcloud

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 标准链路：系统通知 / Android 16 实况更新（Live Updates）。
 *
 * 这条链路**任何设备都能用**，不依赖厂商白名单，也不需要用户配置任何东西：
 *  - Android 16+：构造 progress-centric 通知并申请提升为实况更新，
 *    是否被提升由系统与用户的开关决定（见 [FluidCloudNotifier.supportsLiveUpdate]）；
 *  - Android 8~15：常驻进度通知。ColorOS / MIUI / HyperOS 会自行把它渲染成
 *    胶囊或实况窗形态 —— 这些是 ROM 行为，不是应用能决定的。
 */
class SystemLiveUpdateProvider(private val context: Context) : FluidCloudProvider {

    override val backend: FluidCloudBackend =
        if (FluidCloudNotifier.supportsLiveUpdate(context)) {
            FluidCloudBackend.SYSTEM_LIVE_UPDATE
        } else {
            FluidCloudBackend.NOTIFICATION
        }

    private val manager = NotificationManagerCompat.from(context)

    override fun isAvailable(): Boolean = FluidCloudNotifier.canPostNotifications(context)

    override fun publish(state: CapsuleState) {
        if (!isAvailable()) return
        runCatching {
            manager.notify(FluidCloudNotifier.NOTIFICATION_ID, FluidCloudNotifier.buildOngoing(context, state))
        }
    }

    override fun finish(state: CapsuleState) {
        if (!isAvailable()) return
        runCatching {
            manager.cancel(FluidCloudNotifier.NOTIFICATION_ID)
            manager.notify(FluidCloudNotifier.RESULT_NOTIFICATION_ID, FluidCloudNotifier.buildResult(context, state))
        }
    }

    override fun cancel() {
        runCatching {
            manager.cancel(FluidCloudNotifier.NOTIFICATION_ID)
            manager.cancel(FluidCloudNotifier.RESULT_NOTIFICATION_ID)
        }
    }
}

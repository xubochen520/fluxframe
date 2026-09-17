package com.fluxframe.app.fluidcloud

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 传输前台服务：让上传 / 视频提取在用户切到别的应用后仍能继续，
 * 并把进度以常驻通知（ColorOS 上会被提升为流体云 / 实况窗形态）呈现。
 *
 * 通知内容由 [FluidCloudBridge] 通过 `NotificationManagerCompat.notify`
 * 以同一个 id 更新，因此服务本身只需要负责「把 id 挂上前台」。
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "fluxframe 传输中" }
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val state = CapsuleState(active = true, title = title, subtitle = subtitle)
        runCatching {
            startForeground(FluidCloudNotifier.NOTIFICATION_ID, FluidCloudNotifier.buildOngoing(this, state))
        }
        // 被系统杀掉后不自动重启：任务本身由协程持有，重启一个空服务没有意义
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"

        fun start(context: Context, state: CapsuleState) {
            runCatching {
                val intent = Intent(context, TransferService::class.java).apply {
                    putExtra(EXTRA_TITLE, state.title)
                    putExtra(EXTRA_SUBTITLE, state.subtitle)
                }
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}

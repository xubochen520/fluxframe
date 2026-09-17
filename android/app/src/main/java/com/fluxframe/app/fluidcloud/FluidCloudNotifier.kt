package com.fluxframe.app.fluidcloud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fluxframe.app.R

/**
 * 进度通知的统一构造与渠道管理。
 *
 * ### 走的是「Google 实况更新（Live Updates）规范」
 * Android 16 把"用户发起、有明确起止的进度"正式收编为 **progress-centric notification**：
 * 用 `Notification.ProgressStyle` 描述进度，并申请被系统提升为实况更新（状态栏胶囊）。
 * ColorOS 16 的「流体云」就是把这套标准实况更新映射成自家的胶囊形态 ——
 * 所以**不需要 OPPO 的白名单 serviceId**，走标准规范即可。
 *
 * ### 三个必须同时满足的条件（缺一不可）
 * 1. **声明 `POST_PROMOTED_NOTIFICATIONS` 权限**。它在 Android 16 QPR1 引入，
 *    protectionLevel 为 `normal`（安装即授予）。不声明时
 *    `canPostPromotedNotifications()` 恒为 false，通知永远不会被提升 ——
 *    这是 v2.1.0「流体云无效」的直接原因。
 * 2. **在 extras 里写 `android.requestPromotedOngoing = true`**。
 *    这就是 androidx `NotificationCompat.Builder.setRequestPromotedOngoing(true)`
 *    的真实实现（反编译 androidx.core 1.17.0 确认：它只往 extras 塞一个 boolean，
 *    并没有调用平台 Builder 上的同名方法）。
 * 3. **通知渠道的重要性不能太低**。低重要性渠道会被系统直接排除在提升之外，
 *    因此这里给实况更新单独用一个高重要性渠道（与 InstallerX Revived 的做法一致）。
 *
 * ### 为什么这里还是反射
 * 本工程 `compileSdk = 35`（AGP 8.6.1 的上限），而能直接调用这些 API 的
 * `androidx.core:core:1.17.0` 要求 `compileSdk = 36` + AGP 8.9.1。
 * 因此 `Notification.ProgressStyle` 用反射构造（调用序列完全照抄 androidx 1.17.0 的
 * `ProgressStyle.apply()`），**任何一步失败都退回标准进度通知**，不会因此崩溃或少功能。
 */
object FluidCloudNotifier {

    /** 低版本用的常驻进度渠道 */
    const val CHANNEL_ONGOING = "fluxframe_transfer"

    /** 实况更新 / 流体云专用渠道（高重要性，否则系统不会提升） */
    const val CHANNEL_LIVE = "fluxframe_live"

    const val CHANNEL_RESULT = "fluxframe_result"
    const val NOTIFICATION_ID = 43110
    const val RESULT_NOTIFICATION_ID = 43111

    /** 平台 extras 的 key；与 androidx 的实现逐字一致 */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    private const val TAG = "FluxFrameLive"

    /** 最近一次实况更新构造失败的原因，设置页会把它显示出来 */
    @Volatile
    var lastLiveUpdateError: String? = null
        private set

    @Volatile
    var lastLiveUpdateOk: Boolean = false
        private set

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "传输进度",
                    // LOW：进度通知不该响铃震动，但要在状态栏/锁屏可见
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "上传、下载、视频提取的实时进度"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_LIVE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LIVE,
                    "实况通知（上传 / 提取进度）",
                    // 必须是较高重要性，否则系统不会把它提升为实况更新 / 流体云。
                    // 声音与震动单独关掉，避免每次上传都响一下。
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "在状态栏胶囊 / 流体云里显示进行中的上传与视频提取"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }

        if (manager.getNotificationChannel(CHANNEL_RESULT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT,
                    "传输结果",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "传输完成或失败的一次性提醒"
                    setShowBadge(true)
                },
            )
        }
    }

    /* ------------------------------ 能力探测 ------------------------------ */

    /** 系统是否允许把进行中的通知提升为实况更新（Android 16+ 才可能为 true） */
    fun supportsLiveUpdate(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching {
            val method = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            (method.invoke(manager) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    /** 权限是否在清单里声明（不判断是否被授予） */
    fun isPromotedPermissionDeclared(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.contains(PERMISSION_POST_PROMOTED_NOTIFICATIONS) == true
    }.getOrDefault(false)

    /**
     * 权限是否被授予。
     *
     * 注意：这个权限是 Android 16 QPR1 才引入的（AOSP 里标注
     * `featureFlag="android.app.ui_rich_ongoing"`）。在 Android 16.0 上它尚未定义，
     * 于是"声明了但永远授不到"—— 那**不是**问题，提升在 16.0 上本来就不需要它。
     * 因此判断要结合 [isPromotedPermissionDeclared] 一起看。
     */
    fun hasPromotedPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_POST_PROMOTED_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 面向设置页的诊断结论。
     * 流体云不出卡时，这里能直接指出卡在哪一步，而不是让人猜。
     */
    fun diagnose(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        val canPromote = supportsLiveUpdate(context)
        val declared = isPromotedPermissionDeclared(context)
        val granted = hasPromotedPermission(context)
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_LIVE)
        val importance = when (channel?.importance) {
            NotificationManager.IMPORTANCE_HIGH -> "高"
            NotificationManager.IMPORTANCE_DEFAULT -> "默认"
            NotificationManager.IMPORTANCE_LOW -> "低"
            NotificationManager.IMPORTANCE_MIN -> "最低"
            null -> "未创建"
            else -> "未知"
        }
        return buildString {
            append("Android ").append(sdk)
            append("（").append(Build.VERSION.RELEASE).append("）")
            append(" · 提升权限=").append(
                when {
                    granted -> "已授予"
                    declared -> "已声明未授予"
                    else -> "缺失"
                },
            )
            append(" · 系统接受提升=").append(if (canPromote) "是" else "否")
            append(" · 实况渠道重要性=").append(importance)
            when {
                sdk < 36 ->
                    append("。实况更新 / 流体云需要 Android 16 及以上，当前系统只能显示常驻进度通知。")

                !declared ->
                    append("。清单里缺少 POST_PROMOTED_NOTIFICATIONS，请更新到最新版本。")

                !canPromote ->
                    append("。系统暂未允许提升：可能是 Android 16.0（该权限尚未引入，不影响），" +
                        "也可能是「设置 → 通知 → 流体图库」里关掉了实况通知。")

                else -> append("。已按标准实况更新提交，ColorOS 16 会以流体云形态呈现。")
            }
            if (lastLiveUpdateOk) {
                append(" 最近一次下发：成功。")
            } else if (lastLiveUpdateError != null) {
                append(" 最近一次下发失败：").append(lastLiveUpdateError)
            }
        }
    }

    /* ------------------------------ 通知构造 ------------------------------ */

    /** 进行中的常驻进度通知（能提升为实况更新就提升） */
    fun buildOngoing(context: Context, state: CapsuleState): Notification {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 36) {
            buildLiveUpdate(context, state)?.let { return it }
        }
        return buildCompatOngoing(context, state)
    }

    /** 结束后的结果通知（自动消失） */
    fun buildResult(context: Context, state: CapsuleState): Notification {
        ensureChannels(context)
        val success = state.tone != CapsuleTone.ERROR
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { if (success) "传输完成" else "传输失败" })
            .setContentText(state.subtitle)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(8000)
            .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /** 低版本 / 提升不可用时的标准进度通知 */
    private fun buildCompatOngoing(context: Context, state: CapsuleState): Notification {
        val percent = state.progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { "fluxframe 传输中" })
            .setContentText(state.subtitle.ifBlank { "正在处理…" })
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${state.kind.label}${percent?.let { " $it%" }.orEmpty()} · fluxframe"),
            )
        if (percent != null) {
            builder.setProgress(100, percent, false)
            builder.setSubText("${state.kind.label} $percent%")
        } else {
            builder.setProgress(0, 0, true)
            builder.setSubText(state.kind.label)
        }
        return builder.build()
    }

    /* --------------------- Android 16 实况更新（反射） --------------------- */

    private fun buildLiveUpdate(context: Context, state: CapsuleState): Notification? {
        val percent = state.progress?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        val indeterminate = percent == null
        return runCatching {
            val builder = Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(state.title.ifBlank { "fluxframe 传输中" })
                .setContentText(state.subtitle.ifBlank { state.kind.label })
                .setSubText(state.kind.label)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(accentColor(state.tone))

            // ⭐ 提升申请：这是 androidx setRequestPromotedOngoing(true) 的真实实现
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

            // 胶囊上的极短文案（"上传 42%"）——优先用平台方法，退回同名 extra
            val shortText = shortCriticalText(state)
            val shortApplied = runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, shortText)
                true
            }.getOrDefault(false)
            if (!shortApplied) builder.extras.putString(EXTRA_SHORT_CRITICAL_TEXT, shortText)

            val style = buildProgressStyle(percent, indeterminate)
            if (style != null) {
                builder.setStyle(style)
            } else {
                // 拿不到 ProgressStyle 就退回普通进度条，通知依然可用
                builder.setProgress(if (indeterminate) 0 else 100, if (indeterminate) 0 else percent!!, indeterminate)
            }

            lastLiveUpdateOk = true
            lastLiveUpdateError = null
            builder.build()
        }.onFailure { error ->
            lastLiveUpdateOk = false
            lastLiveUpdateError = "${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, "实况更新构造失败，退回标准进度通知", error)
        }.getOrNull()
    }

    /**
     * 反射构造 `Notification.ProgressStyle`。
     * 调用序列与 androidx.core 1.17.0 的 `ProgressStyle.apply()` 完全一致。
     */
    private fun buildProgressStyle(percent: Int?, indeterminate: Boolean): Notification.Style? = runCatching {
        val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
        val style = styleClass.getDeclaredConstructor().newInstance()
        styleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
            .invoke(style, true)
        if (indeterminate) {
            styleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
                .invoke(style, true)
        } else {
            styleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                .invoke(style, percent ?: 0)
        }
        style as Notification.Style
    }.onFailure {
        lastLiveUpdateError = "ProgressStyle 反射失败：${it.javaClass.simpleName}: ${it.message}"
    }.getOrNull()

    /** 官方模板里这行字很短（2~4 个字），太长会被截断 */
    private fun shortCriticalText(state: CapsuleState): String {
        val percent = state.progress?.let { " ${(it.coerceIn(0f, 1f) * 100).toInt()}%" }.orEmpty()
        return when {
            !state.active && state.tone == CapsuleTone.ERROR -> "失败"
            !state.active -> "完成"
            else -> state.kind.label + percent
        }
    }

    private fun accentColor(tone: CapsuleTone): Int = when (tone) {
        CapsuleTone.SUCCESS -> 0xFF22C55E.toInt()
        CapsuleTone.ERROR -> 0xFFEF4444.toInt()
        CapsuleTone.INFO -> 0xFF6366F1.toInt()
    }

    /**
     * `POST_PROMOTED_NOTIFICATIONS` 在 compileSdk 35 的 android.jar 里还不存在，
     * 因此用字符串常量而不是 `Manifest.permission.XXX`。
     */
    private const val PERMISSION_POST_PROMOTED_NOTIFICATIONS =
        "android.permission.POST_PROMOTED_NOTIFICATIONS"

    /** 供 ViewModel / 设置页判断通知权限 */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

package com.fluxframe.app.fluidcloud

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「流体云 / 实况通知」总入口。
 *
 * ### 链路策略（不需要用户配置任何东西）
 *  - **主链路**：[SystemLiveUpdateProvider] —— 标准通知，并在 Android 16+ 上按
 *    Live Updates 规范申请提升为实况更新。它一定可用；
 *  - **并行增强**：[OppoFluidCloudProvider] —— ColorOS 的意图共享通道，
 *    探测到可用就**同时再发一份**，出卡与否交给系统，失败没有任何副作用。
 *
 * 之所以不做成"二选一"，是因为 OPPO 那条通道需要系统白名单，客户端无法确知结果；
 * 而"主链路 + 并行增强"能让任何机型都拿到一致的进度反馈，符合"别让我填东西"。
 *
 * 同时维护唯一的传输状态 [capsule]：应用内胶囊、通知、流体云三处同源。
 * 所有对外方法都是「即发即忘」且不会抛异常 —— 进度上报不该影响主流程。
 */
class FluidCloudBridge(
    private val context: Context,
) {

    private val systemProvider = SystemLiveUpdateProvider(context)
    private val oppoProvider = OppoFluidCloudProvider(context)

    /**
     * 能力快照。做成计算属性而不是一次性常量：
     * 通知权限可能在运行中被授予、OPPO 通道也可能随系统开关变化，
     * 每次读取都重新判定，设置页因此不需要"重启应用才生效"。
     */
    val capability: FluidCloudCapability
        get() = FluidCloudCapability(
            backend = if (oppoProvider.isAvailable()) {
                FluidCloudBackend.OPPO_FLUID_CLOUD
            } else {
                systemProvider.backend
            },
            oppoBound = oppoProvider.isAvailable(),
            notificationsAllowed = notificationsAllowed(),
            description = buildDescription(),
        )

    private fun notificationsAllowed(): Boolean = FluidCloudNotifier.canPostNotifications(context)

    /** 设置页展示的一行诊断：直接说清"卡在哪一步" */
    val diagnostic: String get() = FluidCloudNotifier.diagnose(context)

    private fun buildDescription(): String = when {
        !notificationsAllowed() ->
            "尚未授予通知权限，进度只会显示在应用内的顶部胶囊里。"

        oppoProvider.isAvailable() ->
            "已检测到 ColorOS 流体云通道，会同时下发意图共享与标准实况更新，哪一个生效由系统决定。"

        FluidCloudNotifier.supportsLiveUpdate(context) ->
            "系统已允许把进行中的传输提升为实况更新；ColorOS 16 会把它呈现为流体云胶囊。"

        Build.VERSION.SDK_INT >= 36 ->
            "当前系统暂未允许提升为实况更新，进度以常驻通知呈现。可在系统通知设置里检查。"

        else ->
            "当前系统低于 Android 16，进度以常驻通知呈现（ColorOS 上通常也会收进状态栏胶囊）。"
    }

    private val _capsule = MutableStateFlow<CapsuleState?>(null)

    /** 应用内顶部胶囊渲染的状态；null = 不显示 */
    val capsule: StateFlow<CapsuleState?> = _capsule.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hideJob: Job? = null

    /** 用户偏好：关掉后只保留应用内提示，不发通知/流体云 */
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                systemProvider.cancel()
                oppoProvider.cancel()
                TransferService.stop(context)
            }
        }

    /** 开始一个可展示进度的任务 */
    fun begin(
        kind: CapsuleKind,
        title: String,
        subtitle: String = "",
        withForegroundService: Boolean = true,
    ) {
        hideJob?.cancel()
        val state = CapsuleState(active = true, kind = kind, title = title, subtitle = subtitle)
        _capsule.value = state
        if (!enabled) return
        // 先把前台服务挂起来（系统只对"正在前台运行的服务"的通知做提升），
        // 再下发通知本身，顺序反了会丢掉提升资格。
        runCatching {
            if (withForegroundService) TransferService.start(context, state)
            publishAll(state)
        }
    }

    /** 更新进度；[progress] 为 null 表示不确定进度 */
    fun update(progress: Float?, subtitle: String? = null) {
        val current = _capsule.value ?: return
        val next = current.copy(
            progress = progress?.coerceIn(0f, 1f),
            subtitle = subtitle ?: current.subtitle,
        )
        _capsule.value = next
        if (!enabled) return
        runCatching { publishAll(next) }
    }

    fun updateTitle(title: String) {
        val current = _capsule.value ?: return
        val next = current.copy(title = title)
        _capsule.value = next
        if (enabled) runCatching { publishAll(next) }
    }

    /** 任务结束：展示结果，短暂停留后自动收起 */
    fun finish(message: String, tone: CapsuleTone = CapsuleTone.SUCCESS) {
        val current = _capsule.value
        val state = (current ?: CapsuleState(kind = CapsuleKind.SYNC, title = "fluxframe")).copy(
            active = false,
            tone = tone,
            subtitle = message,
        )
        _capsule.value = state
        if (enabled) {
            runCatching {
                systemProvider.finish(state)
                oppoProvider.finish(state)
                TransferService.stop(context)
            }
        }
        scheduleHide()
    }

    /** 用户取消 */
    fun cancel(message: String = "已取消") {
        val current = _capsule.value
        _capsule.value = current?.copy(active = false, tone = CapsuleTone.INFO, subtitle = message)
        if (enabled) {
            runCatching {
                systemProvider.cancel()
                oppoProvider.cancel()
                TransferService.stop(context)
            }
        }
        scheduleHide()
    }

    /** 立即清除应用内胶囊 */
    fun dismiss() {
        hideJob?.cancel()
        _capsule.value = null
    }

    private fun publishAll(state: CapsuleState) {
        systemProvider.publish(state)
        oppoProvider.publish(state)
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_DELAY_MS)
            _capsule.value = null
        }
    }

    private companion object {
        const val HIDE_DELAY_MS = 2600L
    }
}

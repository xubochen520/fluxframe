package com.fluxframe.app.fluidcloud

/**
 * 流体云 / 实况通知的统一出口。
 *
 * 之所以抽成接口：OPPO 流体云 SDK 需要企业开发者资质、应用审核与白名单才能拿到 aar，
 * 未集成时应用必须能正常编译运行并自动降级到系统实况通知。
 * 将来把官方 aar 放进 `app/libs/` 后，[OppoFluidCloudProvider] 会自动绑定，无需改动其它代码。
 */
interface FluidCloudProvider {

    val backend: FluidCloudBackend

    /** 当前环境是否可用（探测一次即可，UI 会展示探测结果） */
    fun isAvailable(): Boolean

    /** 创建/更新一条进行中的状态 */
    fun publish(state: CapsuleState)

    /** 结束并展示结果（成功或失败），随后自动消失 */
    fun finish(state: CapsuleState)

    /** 用户主动取消或任务被放弃 */
    fun cancel()
}

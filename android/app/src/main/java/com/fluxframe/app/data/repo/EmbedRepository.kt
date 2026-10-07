package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.data.model.EmbedBackfillRequest
import com.fluxframe.app.data.model.EmbedCalibration
import com.fluxframe.app.data.model.EmbedGraph
import com.fluxframe.app.data.model.EmbedGraphMode
import com.fluxframe.app.data.model.EmbedStatus
import com.fluxframe.app.data.model.EmbedThresholdRequest
import com.fluxframe.app.data.model.ImageItem
import kotlinx.coroutines.delay

/** 标签模式下默认要多少条边：一条边就是一个确凿的共同标签，不是噪声，可以多连一些 */
private const val TAG_EDGE_LIMIT = 1000

/**
 * 相似图 / 关系网（服务端 CCIP 视觉指纹）。
 *
 * 与标签体系完全无关：指纹是直接从像素提的 768 维向量，按余弦相似度找「看起来像同一角色」的图。
 * 实测本库同角色图片相似度中位 85%、不同角色 26%，所以默认阈值 0.35 落在两者之间的空带里。
 *
 * 视频没有指纹（服务端只对静态图片算），[similar] 会返回 [SimilarOutcome.Unsupported]。
 */
class EmbedRepository(private val container: AppContainer) {

    private val api get() = container.api

    /** 上一次成功取到的关系网，按模式各存一份（见 [graph] 的说明） */
    private val cachedGraphs = mutableMapOf<EmbedGraphMode, EmbedGraph>()

    /** 相似图请求的结果：正常、还在算、还是这类媒体不支持 */
    sealed interface SimilarOutcome {
        /** 拿到结果（可能是空列表 —— 库里确实没有够像的图） */
        data class Ok(val items: List<ImageItem>, val threshold: Double) : SimilarOutcome

        /** 指纹还没建好，服务端正在算 —— 稍后重试即可 */
        data object Analyzing : SimilarOutcome

        /** 服务端指纹模型未就绪，或该媒体类型（视频）没有指纹 */
        data class Unsupported(val reason: String) : SimilarOutcome
    }

    /**
     * 取某张图的相似图。
     *
     * 服务端对「还没建指纹的图」会当场算完再返回（约 0.7~3 秒），所以这里超时给得比普通接口宽；
     * 真遇到 [SimilarOutcome.Analyzing]（指纹排队靠后）时，调用方隔一秒左右重试一次就好。
     */
    suspend fun similar(imageId: String, limit: Int = 24): Result<SimilarOutcome> = apiCall {
        val response = api.similarImages(imageId, limit)
        when {
            !response.ready -> SimilarOutcome.Unsupported("服务端还没准备好相似图索引")
            response.indexed -> SimilarOutcome.Ok(
                items = response.items.filter { it.id.isNotBlank() },
                threshold = response.threshold,
            )
            response.analyzing -> SimilarOutcome.Analyzing
            else -> SimilarOutcome.Unsupported("这张图没有视觉指纹（视频不支持）")
        }
    }

    /**
     * 带重试的相似图查询：最多试 [attempts] 次，每次间隔 [intervalMs]。
     * 用于「刚上传完就点开看相似图」这种指纹还在算的场景，用户不必手动重试。
     */
    suspend fun similarWithRetry(
        imageId: String,
        limit: Int = 24,
        attempts: Int = 4,
        intervalMs: Long = 900L,
    ): Result<SimilarOutcome> {
        var last: Result<SimilarOutcome> = similar(imageId, limit)
        var index = 1
        while (index < attempts && last.getOrNull() is SimilarOutcome.Analyzing) {
            delay(intervalMs)
            last = similar(imageId, limit)
            index++
        }
        return last
    }

    /** 索引概况（已建多少张、阈值、后台是否还在算） */
    suspend fun status(): Result<EmbedStatus> = apiCall { api.embedStatus() }

    /**
     * 关系网：节点 + 相似边 + 相似分组 + 二维布局。
     *
     * [mode] 决定「关系」的依据（见 [EmbedGraphMode]）。两种模式各缓存一份：
     * 关系网一次约 100KB，而用户在「星系图 / 分组列表」之间来回切、或者点进大图再退回来
     * 都很频繁，每次都重新拉一遍既慢又白费流量。传 [force] = true 才强制重取
     * （下拉刷新、改了阈值之后、或者切换模式）。
     */
    suspend fun graph(
        edges: Int = 600,
        mode: EmbedGraphMode = EmbedGraphMode.VISUAL,
        force: Boolean = false,
    ): Result<EmbedGraph> {
        if (!force) cachedGraphs[mode]?.let { return Result.success(it) }
        /* 标签模式的边是"确凿的共同标签"而不是噪声，所以默认连得比视觉模式多 */
        val limit = if (mode == EmbedGraphMode.TAG) maxOf(edges, TAG_EDGE_LIMIT) else edges
        val result = apiCall { api.embedGraph(limit, mode.wire) }
        result.getOrNull()?.let { if (it.ready) cachedGraphs[mode] = it }
        return result
    }

    /** 阈值变了 / 新图入库后清掉缓存，下次进入重新取 */
    fun invalidateGraph() {
        cachedGraphs.clear()
    }

    /** 仅 ADMIN：把还没建指纹的图排进后台队列 */
    suspend fun backfill(force: Boolean = false): Result<Int> = apiCall {
        api.embedBackfill(EmbedBackfillRequest(force = force)).queued
    }

    /** 仅 ADMIN：调整相似度阈值（服务端会同时重算关系） */
    suspend fun setThreshold(threshold: Double): Result<Double> = apiCall {
        api.embedSetThreshold(EmbedThresholdRequest(threshold.coerceIn(0.05, 0.95))).threshold
    }

    /** 当前阈值的实测校准数据：同角色 / 异角色的分数分布 */
    suspend fun calibration(): Result<EmbedCalibration> = apiCall { api.embedCalibration() }

    /* ------------------------------ 地址拼接 ------------------------------ */

    /** 后端返回的是相对路径，拼成可访问的绝对地址（缩略图 / 原图都要用） */
    fun absolute(path: String): String = container.mediaRepository.absoluteFor(path)

    /** 网格缩略图地址（相似图列表、星系图小尺寸时用） */
    fun thumbUrl(item: ImageItem): String = absolute(item.thumb.ifBlank { item.url })

    /**
     * 星系图放大后用的高清缩略图（服务端的 768 变体）。
     * 320 档铺到 400px 就是拉伸，手机上能看出虚；没有大图时自动退回小图。
     */
    fun largeThumbUrl(item: ImageItem): String {
        val large = item.thumbLarge?.takeIf { it.isNotBlank() }
        return absolute(large ?: item.thumb.ifBlank { item.url })
    }

    /** 全屏查看原图地址 */
    fun previewUrl(item: ImageItem): String = absolute(item.url)
}

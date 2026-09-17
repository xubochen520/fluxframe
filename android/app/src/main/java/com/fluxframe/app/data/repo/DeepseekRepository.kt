package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.data.model.DeepseekConfigRequest
import com.fluxframe.app.data.model.DeepseekKeyCreateRequest
import com.fluxframe.app.data.model.DeepseekKeyCreateResponse
import com.fluxframe.app.data.model.DeepseekKeyUpdateRequest
import com.fluxframe.app.data.model.DeepseekMergeRequest
import com.fluxframe.app.data.model.DeepseekSummary

/**
 * DeepSeek 余额 / 用量记账。
 *
 * 注意响应体约定：
 * - `summary` / `refresh` 返回 [DeepseekSummary]（refresh 多一个 `refresh` 字段）；
 * - `POST keys` 返回 `{key, probe, summary}`；
 * - `PATCH/DELETE keys/:id`、`merge`、`config` **直接返回** [DeepseekSummary]，不再包一层。
 */
class DeepseekRepository(private val container: AppContainer) {

    private val api get() = container.api
    private val longApi get() = container.longApi

    suspend fun summary(): Result<DeepseekSummary> = apiCall { api.deepseekSummary() }

    /** 同步触发一次全量刷新（逐个 KEY 调官方余额接口），读超时用长客户端 */
    suspend fun refresh(): Result<DeepseekSummary> = apiCall { longApi.deepseekRefresh() }

    /** 即便 `probe.ok == false`，KEY 也会被创建（服务端先探测后无条件落库） */
    suspend fun addKey(
        name: String,
        apiKey: String,
        accountName: String = "",
        enabled: Boolean = true,
    ): Result<DeepseekKeyCreateResponse> = apiCall {
        longApi.deepseekAddKey(
            DeepseekKeyCreateRequest(
                name = name.trim(),
                apiKey = apiKey.trim(),
                accountName = accountName.trim(),
                enabled = enabled,
            ),
        )
    }

    suspend fun updateKey(
        id: String,
        name: String? = null,
        apiKey: String? = null,
        accountName: String? = null,
        platformKeyId: String? = null,
        enabled: Boolean? = null,
    ): Result<DeepseekSummary> = apiCall {
        longApi.deepseekUpdateKey(
            id,
            DeepseekKeyUpdateRequest(
                name = name?.trim(),
                apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() },
                accountName = accountName?.trim(),
                platformKeyId = platformKeyId?.trim(),
                enabled = enabled,
            ),
        )
    }

    /** 删除 KEY 会级联删掉它的记账流水，不可恢复 */
    suspend fun deleteKey(id: String): Result<DeepseekSummary> = apiCall { api.deepseekDeleteKey(id) }

    /** 把多个独立账户合并成一个（共用余额、只记一次账） */
    suspend fun merge(keyIds: List<String>, name: String? = null): Result<DeepseekSummary> =
        apiCall { longApi.deepseekMerge(DeepseekMergeRequest(keyIds, name?.trim()?.takeIf { it.isNotEmpty() })) }

    /** [refreshSeconds] 必须是 30..3600；[platformToken] 传空串 = 清除令牌 */
    suspend fun configure(
        enabled: Boolean? = null,
        refreshSeconds: Int? = null,
        platformToken: String? = null,
    ): Result<DeepseekSummary> = apiCall {
        longApi.deepseekConfig(DeepseekConfigRequest(enabled, refreshSeconds, platformToken))
    }
}

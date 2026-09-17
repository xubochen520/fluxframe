package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.ApiException
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.core.net.apiExceptionOf
import com.fluxframe.app.data.model.AiStatus
import com.fluxframe.app.data.model.AuditLogItem
import com.fluxframe.app.data.model.BiliQrCreateResponse
import com.fluxframe.app.data.model.BiliQrPollRequest
import com.fluxframe.app.data.model.BiliQrPollResponse
import com.fluxframe.app.data.model.EngineStatus
import com.fluxframe.app.data.model.SystemSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 系统设置 / 访问日志 / 引擎（ffmpeg、AI）/ B站扫码 —— 基本都需要 ADMIN */
class AdminRepository(private val container: AppContainer) {

    private val api get() = container.api
    private val longApi get() = container.longApi
    private val json get() = container.json

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun bodyOf(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): RequestBody =
        buildJsonObject(builder).toString().toRequestBody(jsonMediaType)

    private fun jsonBodyOf(json: String): RequestBody = json.toRequestBody(jsonMediaType)

    /* ------------------------------ 设置 ------------------------------ */

    suspend fun settings(): Result<SystemSettings> = apiCall { api.settings() }

    /**
     * 部分更新系统设置。
     *
     * 两个必须注意的点（来自服务端实现）：
     * 1. 数值字段必须发 **JSON number**；发成字符串能通过校验但运行时不读取。
     * 2. 响应体不含 deepseek / bili / ffmpeg 等计算字段，保存成功后应重新 [settings]。
     */
    suspend fun patchSettings(fields: Map<String, JsonElement>): Result<Unit> = apiCall {
        val response = api.patchSettings(JsonObject(fields).toString().toRequestBody(jsonMediaType))
        if (!response.isSuccessful) {
            throw apiExceptionOf(response.code(), response.errorBody()?.string())
        }
        Unit
    }

    /* ------------------------------ 访问日志 ------------------------------ */

    /** 服务端固定返回最近 500 条，无分页 */
    suspend fun auditLogs(): Result<List<AuditLogItem>> = apiCall { api.auditLogs().items }

    /* ------------------------------ ffmpeg ------------------------------ */

    suspend fun ffmpegStatus(): Result<EngineStatus> = apiCall { api.ffmpegStatus() }

    /** 火忘式：返回 {ok:true} 只代表请求已接受，真实结果要轮询 [ffmpegStatus] */
    suspend fun downloadFfmpeg(): Result<Unit> = apiCall {
        val result = api.downloadFfmpeg()
        if (!result.ok) throw ApiException(200, result.error ?: result.message ?: "下载请求被拒绝")
        Unit
    }

    /* ------------------------------ AI 引擎 ------------------------------ */

    /** 最坏耗时约 7.5 s（串行探测 5 个端口），用长超时客户端 */
    suspend fun aiStatus(): Result<AiStatus> = apiCall { longApi.aiStatus() }

    /** [variant] 只能是 `3b` / `7b`；镜像默认走服务端配置的 hf-mirror */
    suspend fun downloadAi(variant: String, mirror: String? = null): Result<Unit> = apiCall {
        val body = bodyOf {
            put("variant", variant)
            if (!mirror.isNullOrBlank()) put("mirror", mirror)
        }
        val result = longApi.downloadAi(body)
        if (!result.ok) throw ApiException(200, result.error ?: "下载请求被拒绝")
        Unit
    }

    /**
     * 启动 llama.cpp。**同步阻塞最长 150 秒**；文件不完整时返回 `200 {ok:false,error}`，
     * 启动失败/超时会返回 500，两种失败形态都要处理。
     */
    suspend fun startAi(): Result<Unit> = apiCall {
        val result = longApi.startAi(jsonBodyOf("{}"))
        if (!result.ok) throw ApiException(200, result.error ?: result.message ?: "AI 引擎启动失败")
        Unit
    }

    suspend fun stopAi(): Result<Unit> = apiCall {
        val result = api.stopAi()
        if (!result.ok) throw ApiException(200, result.error ?: "停止失败")
        Unit
    }

    /* ------------------------------ B站扫码 ------------------------------ */

    /** 成功时 `image` 是 PNG 的 data URL，需要自行 base64 解码成 Bitmap 显示 */
    suspend fun biliQrCreate(): Result<BiliQrCreateResponse> = apiCall { api.biliQrCreate() }

    suspend fun biliQrPoll(qrcodeKey: String): Result<BiliQrPollResponse> =
        apiCall { api.biliQrPoll(BiliQrPollRequest(qrcodeKey)) }
}

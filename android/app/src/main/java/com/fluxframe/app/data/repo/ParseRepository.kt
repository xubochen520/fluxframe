package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.ApiException
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.core.net.apiExceptionOf
import com.fluxframe.app.data.model.ParseData
import com.fluxframe.app.data.model.ParseImportRequest
import com.fluxframe.app.data.model.ParseImportStatus
import com.fluxframe.app.data.model.ParseResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 视频解析（PureParse）与「提取入库」。
 *
 * 关键约定：
 * - `/api/parse` **无需登录**，失败是 **HTTP 400 + `{ok:false,msg}`**（不是 `{message}`），
 *   所以接口层用 `Response<ParseResponse>` 接收，这里手动解错误体；
 * - 单次解析可能耗时十几秒 → 用长超时客户端；
 * - 入库后进度只能**轮询** `GET /api/parse/import/:id`（服务端没有 SSE），建议 800 ms。
 */
class ParseRepository(private val container: AppContainer) {

    private val api get() = container.api
    private val longApi get() = container.longApi
    private val json get() = container.json

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun absolute(path: String): String = container.prefs.server.value?.absolute(path) ?: path

    /**
     * 解析分享链接。传整段口令文本也行 —— 这里会先从中提取第一个 http(s) 链接，
     * 因为服务端只读 `body.url`（前端曾误发 `share_text`，会被静默忽略）。
     */
    suspend fun parse(rawInput: String): Result<ParseData> = apiCall {
        val link = extractLink(rawInput)
            ?: throw ApiException(400, "没有识别到链接，请粘贴完整的分享链接或口令")
        val body = buildJsonObject { put("url", link) }.toString().toRequestBody(jsonMediaType)
        val response = longApi.parse(body)
        if (response.isSuccessful) {
            val payload = response.body()
            val data = payload?.data
            if (payload?.ok == true && data != null) {
                data
            } else {
                throw ApiException(400, payload?.msg ?: "解析失败")
            }
        } else {
            val raw = response.errorBody()?.string()
            val payload = raw?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { json.decodeFromString(ParseResponse.serializer(), text) }.getOrNull()
            }
            if (payload?.msg != null) {
                throw ApiException(response.code(), payload.msg, raw)
            }
            throw apiExceptionOf(response.code(), raw)
        }
    }

    /** 交给服务端后台下载入库，返回任务 id */
    suspend fun startImport(request: ParseImportRequest): Result<String> =
        apiCall { longApi.parseImport(request).id }

    suspend fun importStatus(jobId: String): Result<ParseImportStatus> =
        apiCall { api.parseImportStatus(jobId) }

    /** 幂等；取消是异步生效的（随后再查进度可能得到 404） */
    suspend fun cancelImport(jobId: String): Result<Unit> = apiCall {
        api.cancelParseImport(jobId)
        Unit
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s，。、）)】"'<>]+""", RegexOption.IGNORE_CASE)

        /** 从整段分享口令里抠出链接 */
        fun extractLink(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                return trimmed.takeWhile { !it.isWhitespace() }
            }
            return URL_REGEX.find(trimmed)?.value
        }

        /** 服务端按 kind 做 content-type 校验并自动加「视频」标签 */
        fun videoImportOf(data: ParseData, name: String, useHighQuality: Boolean): ParseImportRequest? {
            val media = data.primaryMedia ?: return null
            return ParseImportRequest(
                url = media.src,
                ref = media.referer.ifBlank { data.referer }.takeIf { !it.isNullOrBlank() },
                name = name,
                kind = "video",
                platTag = data.platformLabel,
                // B站高清 DASH 双流：原样透传给服务端用 ffmpeg 合并
                high = if (useHighQuality) data.high else null,
            )
        }

        fun coverImportOf(data: ParseData, name: String): ParseImportRequest? {
            val cover = data.coverSrc ?: return null
            return ParseImportRequest(
                url = cover,
                ref = data.referer,
                name = name,
                kind = "cover",
            )
        }

        fun imageImportOf(data: ParseData, index: Int, name: String): ParseImportRequest? {
            val image = data.images.getOrNull(index) ?: return null
            return ParseImportRequest(
                url = image.src,
                ref = data.referer,
                name = if (data.images.size > 1) "$name-${index + 1}" else name,
                kind = "image",
                platTag = data.platformLabel,
            )
        }
    }
}

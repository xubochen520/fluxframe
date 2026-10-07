package com.fluxframe.app.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 统一的接口异常：把 HTTP 状态码与后端返回的 `{ "message": "..." }` 一起带出来，
 * 这样 UI 可以直接展示后端的中文提示（例如「单个文件不能超过 50 MB」）。
 */
class ApiException(
    val status: Int,
    override val message: String,
    val raw: String? = null,
) : Exception(message) {

    /** 未登录：需要跳回登录页 */
    val isUnauthorized: Boolean get() = status == 401

    /** 权限不足 */
    val isForbidden: Boolean get() = status == 403

    /** 连接层面的失败（服务器没开、WiFi 不通、IP 填错） */
    val isConnectivity: Boolean get() = status == 0
}

private val looseJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从错误响应体里抠出后端的 message 字段 */
internal fun parseErrorMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        looseJson.decodeFromString(ApiMessageSurrogate.serializer(), body).message
    } catch (_: Exception) {
        null
    }
}

@kotlinx.serialization.Serializable
private data class ApiMessageSurrogate(val message: String = "")

/** HTTP 状态码的兜底中文提示（后端没给 message 时使用） */
fun defaultMessageFor(code: Int): String = when (code) {
    400 -> "请求参数不正确"
    401 -> "登录已过期，请重新登录"
    403 -> "没有权限执行该操作"
    404 -> "资源不存在"
    409 -> "数据冲突，请刷新后重试"
    413 -> "文件太大，超过上传限制"
    416 -> "请求范围无效"
    500 -> "服务器内部错误"
    else -> "请求失败（HTTP $code）"
}

/**
 * 用「状态码 + 原始响应体」构造异常。
 * 适用于返回 `retrofit2.Response<T>` 的接口（这类接口 Retrofit 不会自动抛异常）。
 */
fun apiExceptionOf(code: Int, body: String?): ApiException =
    ApiException(code, parseErrorMessage(body) ?: defaultMessageFor(code), body)

/**
 * 把任意异常归一成带中文提示的 [ApiException]。
 * 注意：协程取消必须原样抛出，否则会破坏结构化并发。
 */
fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is HttpException -> {
        val body = try {
            response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        ApiException(code(), parseErrorMessage(body) ?: defaultMessageFor(code()), body)
    }

    is SocketTimeoutException -> ApiException(0, "连接超时：服务器没有响应，请检查地址与端口是否正确")
    is ConnectException -> ApiException(0, "无法连接服务器：请确认服务已启动，且手机能访问该地址")
    is UnknownHostException -> ApiException(0, "找不到该地址：请检查 IP 或域名是否正确")
    is IOException -> ApiException(0, "网络异常：${message ?: "连接被中断"}")
    else -> ApiException(-1, message ?: "发生未知错误")
}

/** 包裹一次接口调用，把异常变成 [Result.failure] */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Throwable) {
    Result.failure(error.toApiException())
}

/** 从 [Result] 中取错误提示 */
fun Result<*>.errorText(): String = exceptionOrNull()?.let { it.toApiException().message } ?: "操作失败"

package com.fluxframe.app.data.repo

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.apiCall
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.data.model.CurrentUser
import com.fluxframe.app.data.model.LoginRequest
import com.fluxframe.app.data.model.PasswordChangeRequest
import com.fluxframe.app.data.model.R18ModeRequest

/** 登录 / 当前用户 / 会话 */
class AuthRepository(private val container: AppContainer) {

    private val api get() = container.api

    suspend fun login(username: String, password: String): Result<CurrentUser> =
        apiCall { api.login(LoginRequest(username.trim(), password)).user }
            .onSuccess { container.prefs.lastUsername = it.username }

    suspend fun me(): Result<CurrentUser> = apiCall { api.me() }

    /**
     * 注册新账户。服务端不返回会话，调用方需要紧接着 [login]。
     * 校验规则与服务端一致：用户名 3–30 字符、密码至少 8 位。
     */
    suspend fun register(username: String, password: String): Result<CurrentUser> =
        apiCall { api.register(LoginRequest(username.trim(), password)) }

    suspend fun logout(): Result<Unit> = apiCall {
        runCatching { api.logout() }
        container.cookieJar.clear()
        container.prefs.clearAccount()
        Unit
    }

    /** 切换 R18 可见性（服务端会据此过滤 R-18 标签与图片） */
    suspend fun setR18Mode(enabled: Boolean): Result<Boolean> =
        apiCall { api.setR18Mode(R18ModeRequest(enabled)).r18Mode }

    suspend fun changePassword(current: String, next: String): Result<Unit> = apiCall {
        api.changePassword(PasswordChangeRequest(current, next))
        Unit
    }

    /**
     * 取原始会话 token，供系统 DownloadManager 使用
     * （它不共享 OkHttp 的 CookieJar，需要手动带上 Cookie 头）。
     */
    suspend fun downloadToken(): Result<String> = apiCall { api.downloadSession().token }

    /** 当前是否还持有有效会话 Cookie（用于冷启动时判断是否需要重新登录） */
    fun hasSessionCookie(): Boolean =
        container.prefs.server.value?.let { container.cookieJar.hasSession(it.host) } ?: false

    /** 健康检查：确认地址填对了、服务活着 */
    suspend fun ping(): Result<Boolean> = try {
        Result.success(api.health().ok)
    } catch (error: Throwable) {
        Result.failure(error.toApiException())
    }
}

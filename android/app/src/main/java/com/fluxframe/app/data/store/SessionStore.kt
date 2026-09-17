package com.fluxframe.app.data.store

import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.core.net.toApiException
import com.fluxframe.app.data.model.CurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 应用启动阶段：决定根界面显示「配服务器 / 登录 / 主界面」 */
enum class SessionPhase {
    /** 冷启动自检中 */
    BOOTING,

    /** 还没配置服务器地址 */
    NEED_SERVER,

    /** 有服务器但没登录（或会话失效） */
    NEED_LOGIN,

    /** 已登录 */
    READY,
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.BOOTING,
    val user: CurrentUser? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 会话状态：当前用户、登录态、R18 可见性。
 *
 * 冷启动时若本地还有会话 Cookie，会先向 `/api/me` 探一次，
 * 有效就直接进主界面（免登录），失效则退回登录页。
 */
class SessionStore(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val r18Mode: Boolean get() = _state.value.user?.r18Mode == true
    val isAdmin: Boolean get() = _state.value.user?.isAdmin == true

    init {
        // 任何请求收到 401 都会把界面踢回登录页
        scope.launch {
            container.sessionExpired.collect {
                if (_state.value.phase == SessionPhase.READY) {
                    container.cookieJar.clear()
                    _state.value = SessionState(
                        phase = SessionPhase.NEED_LOGIN,
                        error = "登录已过期，请重新登录",
                    )
                }
            }
        }
    }

    fun bootstrap() {
        scope.launch {
            val endpoint = container.prefs.server.value
            if (endpoint == null) {
                _state.value = SessionState(phase = SessionPhase.NEED_SERVER)
                return@launch
            }
            if (!container.cookieJar.hasSession(endpoint.host)) {
                _state.value = SessionState(phase = SessionPhase.NEED_LOGIN)
                return@launch
            }
            _state.value = SessionState(phase = SessionPhase.BOOTING)
            container.authRepository.me()
                .onSuccess { user ->
                    _state.value = SessionState(phase = SessionPhase.READY, user = user)
                }
                .onFailure { error ->
                    val apiError = error.toApiException()
                    _state.value = SessionState(
                        phase = SessionPhase.NEED_LOGIN,
                        // 会话失效是正常流程（重新登录即可），不当作错误弹给用户
                        error = if (apiError.isUnauthorized) null else apiError.message,
                    )
                }
        }
    }

    /** 配置好服务器地址后调用 */
    fun onServerConfigured() {
        container.cookieJar.clear()
        _state.value = SessionState(phase = SessionPhase.NEED_LOGIN)
    }

    /** 用户主动要求更换服务器（登录页的「切换服务器」） */
    fun requestServerChange() {
        container.cookieJar.clear()
        _state.value = SessionState(phase = SessionPhase.NEED_SERVER)
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _state.value = _state.value.copy(error = "请输入用户名和密码")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            container.authRepository.login(username, password)
                .onSuccess { user ->
                    _state.value = SessionState(phase = SessionPhase.READY, user = user)
                    // 登录成功后立刻拉一次全局数据，主界面不用等
                    container.mediaStore.refreshAll()
                    // /api/settings 需要 ADMIN，普通用户拉只会拿到 403，没必要打这一枪
                    if (user.isAdmin) container.settingsStore.refresh()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(loading = false, error = error.message)
                }
        }
    }

    /**
     * 注册并直接登录。
     *
     * 服务端的 `/api/auth/register` 只创建账户、**不下发会话**，
     * 所以这里注册成功后立刻用同一组凭据登录一次，用户不用再手输一遍。
     */
    fun register(username: String, password: String) {
        val name = username.trim()
        when {
            name.length < 3 -> {
                _state.value = _state.value.copy(error = "用户名至少需要 3 个字符")
                return
            }
            password.length < 8 -> {
                _state.value = _state.value.copy(error = "密码至少需要 8 个字符")
                return
            }
        }
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            container.authRepository.register(name, password)
                .onSuccess {
                    container.authRepository.login(name, password)
                        .onSuccess { user ->
                            _state.value = SessionState(phase = SessionPhase.READY, user = user)
                            container.mediaStore.refreshAll()
                            if (user.isAdmin) container.settingsStore.refresh()
                        }
                        .onFailure { error ->
                            // 账户已创建，只是自动登录没成功——提示去登录，不误导成注册失败
                            _state.value = SessionState(
                                phase = SessionPhase.NEED_LOGIN,
                                error = "账户已创建，请用新账号登录（自动登录失败：${error.message}）",
                            )
                        }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(loading = false, error = error.message)
                }
        }
    }

    fun logout() {        scope.launch {
            container.logout()
            _state.value = SessionState(phase = SessionPhase.NEED_LOGIN)
        }
    }

    /** 切换 R18 可见性：服务端会据此过滤 R-18 标签与图片 */
    fun setR18Mode(enabled: Boolean) {
        scope.launch {
            container.authRepository.setR18Mode(enabled)
                .onSuccess { result ->
                    val user = _state.value.user
                    if (user != null) _state.value = _state.value.copy(user = user.copy(r18Mode = result))
                    // 可见内容变了，整体重拉
                    container.mediaStore.refreshAll()
                }
                .onFailure { error -> _state.value = _state.value.copy(error = error.message) }
        }
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    fun updateUser(user: CurrentUser) {
        _state.value = _state.value.copy(user = user)
    }
}

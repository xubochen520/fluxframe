package com.fluxframe.app.core.di

import android.content.Context
import com.fluxframe.app.BuildConfig
import com.fluxframe.app.core.net.HostSelectionInterceptor
import com.fluxframe.app.core.net.PersistentCookieJar
import com.fluxframe.app.core.net.ServerDiscovery
import com.fluxframe.app.core.prefs.AppPreferences
import com.fluxframe.app.data.remote.FluxFrameApi
import com.fluxframe.app.data.repo.AdminRepository
import com.fluxframe.app.data.repo.AuthRepository
import com.fluxframe.app.data.repo.DeepseekRepository
import com.fluxframe.app.data.repo.MediaRepository
import com.fluxframe.app.data.repo.ParseRepository
import com.fluxframe.app.data.repo.TagRepository
import com.fluxframe.app.data.store.DeepseekStore
import com.fluxframe.app.data.store.LogsStore
import com.fluxframe.app.data.store.MediaStore
import com.fluxframe.app.data.store.SessionStore
import com.fluxframe.app.data.store.SettingsStore
import com.fluxframe.app.data.store.TaskStore
import com.fluxframe.app.fluidcloud.FluidCloudBridge
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 手写依赖容器（不用 Hilt/Koin：本项目依赖很少，手写更直观也更快）。
 * 由 [com.fluxframe.app.FluxFrameApp] 创建，通过 `LocalAppContainer` 注入 Compose 树。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 供需要 ContentResolver / 系统服务的仓库使用 */
    val context: Context get() = appContext

    val prefs = AppPreferences(appContext)
    val cookieJar = PersistentCookieJar(appContext)
    val discovery = ServerDiscovery()
    val fluidCloud = FluidCloudBridge(appContext)

    /** 会话失效事件：任何请求收到 401 都会往这里发一次，App 据此回登录页 */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val serverProvider: () -> com.fluxframe.app.core.net.ServerEndpoint? = { prefs.server.value }

    private val sessionGuard = Interceptor { chain ->
        val response: Response = chain.proceed(chain.request())
        if (response.code == 401 && !chain.request().url.encodedPath.contains("auth/login")) {
            _sessionExpired.tryEmit(Unit)
        }
        response
    }

    /** 常规接口：60 s 读超时（覆盖 /api/ai/status 最坏 7.5 s、/api/parse 十几秒） */
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(HostSelectionInterceptor(serverProvider))
        .addInterceptor(sessionGuard)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 长耗时接口：上传大视频、`/api/ai/start`（同步阻塞最长 150 s）、
     * 服务端下载 ffmpeg / AI 引擎。写超时设为 0 = 不限时。
     */
    val longOkHttp: OkHttpClient = okHttp.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()

    private val longRetrofit: Retrofit = retrofit.newBuilder()
        .client(longOkHttp)
        .build()

    val api: FluxFrameApi = retrofit.create(FluxFrameApi::class.java)
    val longApi: FluxFrameApi = longRetrofit.create(FluxFrameApi::class.java)

    /* ------------------------------- 仓库层 ------------------------------- */

    val authRepository = AuthRepository(this)
    val mediaRepository = MediaRepository(this)
    val tagRepository = TagRepository(this)
    val adminRepository = AdminRepository(this)
    val deepseekRepository = DeepseekRepository(this)
    val parseRepository = ParseRepository(this)
    val mediaDownloader = com.fluxframe.app.core.download.MediaDownloader(this)

    /* ------------------------------- 状态层 ------------------------------- */
    /* 用 lazy 打断「store 之间互相引用」的初始化循环：
       SessionStore 的 init 会订阅容器事件，事件回调里又要用到 MediaStore。 */

    val sessionStore by lazy { SessionStore(this) }
    val mediaStore by lazy { MediaStore(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val logsStore by lazy { LogsStore(this) }
    val deepseekStore by lazy { DeepseekStore(this) }
    val taskStore by lazy { TaskStore(this) }

    /** 应用级协程作用域（长驻任务：解析轮询、后台刷新） */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    val appVersionName: String get() = BuildConfig.VERSION_NAME
    val appVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** 退出登录：清 Cookie + 清账号记忆 + 清图片与视频封面缓存（保留服务器地址与界面偏好） */
    suspend fun logout() {
        runCatching { api.logout() }
        cookieJar.clear()
        prefs.clearAccount()
        runCatching {
            coil.Coil.imageLoader(appContext).memoryCache?.clear()
            coil.Coil.imageLoader(appContext).diskCache?.clear()
        }
        runCatching { com.fluxframe.app.core.media.VideoPosterLoader.clear(appContext) }
    }

    private companion object {
        /**
         * 占位基址：真实 host:port 由 [HostSelectionInterceptor] 在请求前改写。
         * 用 `.invalid` 顶级域，避免任何情况下真的被解析走。
         */
        const val PLACEHOLDER_BASE_URL = "http://fluxframe.invalid/"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

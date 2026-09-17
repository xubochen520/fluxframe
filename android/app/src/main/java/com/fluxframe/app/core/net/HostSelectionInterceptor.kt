package com.fluxframe.app.core.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把 Retrofit 的占位基址（`http://fluxframe.local/`）改写成当前配置的服务器。
 *
 * 这样「切换服务器」只需要改一个 StateFlow，不必重建 Retrofit / OkHttp，
 * 也不会让已经发出的请求半路换 host。
 */
class HostSelectionInterceptor(
    private val provider: () -> ServerEndpoint?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val endpoint = provider()
        val request = chain.request()
        if (endpoint == null) return chain.proceed(request)

        val rewritten = request.url.newBuilder()
            .scheme("http")
            .host(endpoint.host)
            .port(endpoint.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

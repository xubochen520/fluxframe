package com.fluxframe.app.core.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 会话 Cookie 的持久化容器。
 *
 * 后端用 `fluxframe_session`（httpOnly、path=/、30 天有效）维持登录态，
 * 因此客户端必须像浏览器一样保存并在后续请求里回传。
 * OkHttp 的 CookieJar 回调是同步的，这里用 SharedPreferences 做落地，
 * 保证杀掉进程后重新打开仍然免登录。
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = linkedMapOf<String, MutableList<Cookie>>()

    init {
        restore()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val list = store.getOrPut(host) { mutableListOf() }
        for (cookie in cookies) {
            list.removeAll { it.name == cookie.name && it.path == cookie.path }
            // maxAge 为 0 或负数表示服务端要求删除该 Cookie
            if (cookie.expiresAt > System.currentTimeMillis()) {
                list.add(cookie)
            }
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = store[url.host] ?: return emptyList()
        val alive = list.filter { it.expiresAt > now && url.pathSegments.joinToString("/").startsWith(it.path.trimStart('/').trimEnd('/')) }
        if (alive.size != list.size) {
            store[url.host] = alive.toMutableList()
            persist()
        }
        return alive
    }

    /** 退出登录 / 切换服务器时清空 */
    @Synchronized
    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
    }

    /** 直接取原始 Cookie 头，交给系统下载器使用（它不共享 OkHttp 的 CookieJar） */
    @Synchronized
    fun rawCookieHeader(host: String): String? {
        val now = System.currentTimeMillis()
        val list = store[host]?.filter { it.expiresAt > now } ?: return null
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun hasSession(host: String): Boolean = !rawCookieHeader(host).isNullOrBlank()

    private fun persist() {
        val editor = prefs.edit()
        editor.clear()
        var index = 0
        for ((host, cookies) in store) {
            for (cookie in cookies) {
                val encoded = encode(cookie) ?: continue
                editor.putString("c$index", "$host\u0001$encoded")
                index++
            }
        }
        editor.apply()
    }

    private fun restore() {
        for ((key, value) in prefs.all) {
            if (!key.startsWith("c") || value !is String) continue
            val split = value.split('\u0001', limit = 2)
            if (split.size != 2) continue
            val host = split[0]
            val cookie = decode(host, split[1]) ?: continue
            if (cookie.expiresAt <= System.currentTimeMillis()) continue
            store.getOrPut(host) { mutableListOf() }.add(cookie)
        }
    }

    private fun encode(cookie: Cookie): String? = runCatching {
        listOf(
            cookie.name,
            cookie.value,
            cookie.expiresAt.toString(),
            cookie.domain,
            cookie.path,
            cookie.secure.toString(),
            cookie.httpOnly.toString(),
            cookie.hostOnly.toString(),
        ).joinToString("\u0002")
    }.getOrNull()

    private fun decode(host: String, raw: String): Cookie? = runCatching {
        val parts = raw.split('\u0002')
        if (parts.size < 8) return null
        val builder = Cookie.Builder()
            .name(parts[0])
            .value(parts[1])
            .expiresAt(parts[2].toLong())
            .path(parts[4])
        if (parts[7].toBoolean()) builder.hostOnlyDomain(host) else builder.domain(parts[3])
        if (parts[5].toBoolean()) builder.secure()
        if (parts[6].toBoolean()) builder.httpOnly()
        builder.build()
    }.getOrNull()

    private companion object {
        const val PREFS = "fluxframe_session_cookies"
    }
}

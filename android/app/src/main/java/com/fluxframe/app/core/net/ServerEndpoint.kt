package com.fluxframe.app.core.net

/**
 * 后端服务器地址。
 *
 * 用户可能输入的形式很多（App 的「手动输入」提示也是这么写的）：
 *   `192.168.1.100`、`192.168.1.100:4311`、`http://192.168.1.100:4311/`、` http://192.168.1.100 `
 * 统一由 [parse] 归一化。省略端口时按 [DEFAULT_PORT]（4311）处理 ——
 * 与 Android 客户端的自动扫描端口保持一致。
 */
data class ServerEndpoint(val host: String, val port: Int = DEFAULT_PORT) {

    /** Retrofit / OkHttp 用的基址，必须以 `/` 结尾 */
    val baseUrl: String get() = "http://$host:$port/"

    /** `192.168.1.100:4311` */
    val authority: String get() = "$host:$port"

    /** 拼出绝对地址，用于 Coil / ExoPlayer / DownloadManager 直接请求 */
    fun absolute(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    companion object {
        const val DEFAULT_PORT = 4311

        /** 与 App 自动扫描保持一致的两个端口 */
        val AUTO_SCAN_PORTS = listOf(4311, 5173)

        fun parse(raw: String?): ServerEndpoint? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            text = text.removePrefix("http://").removePrefix("https://")
            text = text.substringBefore('/')
            text = text.substringBefore('?')
            if (text.isEmpty()) return null

            val host: String
            val port: Int
            val colon = text.lastIndexOf(':')
            if (colon >= 0) {
                host = text.substring(0, colon).trim().trim('[', ']')
                val portText = text.substring(colon + 1).trim()
                port = portText.toIntOrNull() ?: return null
            } else {
                host = text
                port = DEFAULT_PORT
            }
            if (host.isEmpty()) return null
            if (port !in 1..65535) return null
            return ServerEndpoint(host, port)
        }
    }
}

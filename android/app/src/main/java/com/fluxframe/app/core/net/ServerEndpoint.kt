package com.fluxframe.app.core.net

/**
 * 后端服务器地址。
 *
 * 用户可能输入的形式很多（App 的「手动输入」提示也是这么写的）：
 *   `192.168.1.100`、`192.168.1.100:4311`、`http://192.168.1.100:4311/`、
 *   `fluxframe.example.com`、`https://fluxframe.example.com`
 * 统一由 [parse] 归一化。
 *
 * **省略端口时的默认值取决于写的是 IP 还是域名**（这条是踩过坑的）：
 *   - IPv4 / IPv6 字面量 → [DEFAULT_PORT]（4311），与局域网直连、自动扫描一致；
 *   - 域名 → 80 / 443，因为域名基本都指向反向代理或内网穿透，公网入口只开标准 Web 端口。
 *
 * 实测：`fluxframe.example.com` 经 frp 只暴露了 80（4311、443 在外网均不可达）。
 * 老版本对任何输入都补 `:4311`，于是域名被拼成 `http://<域名>:4311/`，必然连不上。
 *
 * 端口是猜的时候用 [candidates] 逐个试探，别赌单一默认值。
 */
data class ServerEndpoint(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** true 表示 https://，同时决定 [baseUrl] 的协议 */
    val secure: Boolean = false,
) {

    /** Retrofit / OkHttp 用的基址，必须以 `/` 结尾 */
    val baseUrl: String get() = "${if (secure) "https" else "http"}://$host:$port/"

    /** `192.168.1.100:4311`，给界面显示用 */
    val authority: String get() = "$host:$port"

    /**
     * 存盘用。**必须带 scheme**：只存 [authority] 的话 `https://` 会在重启后
     * 静默退化成 http，用户看到的症状是「昨天还能连，今天连不上」。
     */
    val storageKey: String get() = "${if (secure) "https" else "http"}://$host:$port"

    /** 拼出绝对地址，用于 Coil / ExoPlayer / DownloadManager 直接请求 */
    fun absolute(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    companion object {
        /** 局域网直连与自动扫描用的默认端口 */
        const val DEFAULT_PORT = 4311
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443

        /** 与 App 自动扫描保持一致的两个端口 */
        val AUTO_SCAN_PORTS = listOf(4311, 5173)

        /** IPv4 字面量：四段数字。用来和域名区分，决定省略端口时的默认值 */
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private data class Parsed(
            val endpoint: ServerEndpoint,
            val scheme: String?,
            val explicitPort: Boolean,
            val isIp: Boolean,
        )

        fun parse(raw: String?): ServerEndpoint? = parseDetail(raw)?.endpoint

        /**
         * 按优先级列出该输入可能对应的所有端点，供连接时逐个探测。
         *
         * 用户写了端口就只有他自己那一个；否则从最可能的开始试：
         *   - `https://` → 443，退一步 4311（有人把 https 反代挂在 4311）
         *   - `http://`  → 80，退一步 4311
         *   - IP         → 4311，退一步 80
         *   - 域名        → 80、443，退一步 4311（DDNS + 端口转发的自建场景）
         */
        fun candidates(raw: String?): List<ServerEndpoint> {
            val parsed = parseDetail(raw) ?: return emptyList()
            if (parsed.explicitPort) return listOf(parsed.endpoint)
            val ports = when {
                parsed.scheme == "https" -> listOf(HTTPS_PORT, DEFAULT_PORT)
                parsed.scheme == "http" -> listOf(HTTP_PORT, DEFAULT_PORT)
                parsed.isIp -> listOf(DEFAULT_PORT, HTTP_PORT)
                else -> listOf(HTTP_PORT, HTTPS_PORT, DEFAULT_PORT)
            }
            return ports.distinct().map { parsed.endpoint.copy(port = it) }
        }

        private fun parseDetail(raw: String?): Parsed? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            val scheme = when {
                text.startsWith("https://", ignoreCase = true) -> "https"
                text.startsWith("http://", ignoreCase = true) -> "http"
                else -> null
            }
            if (scheme != null) text = text.substring(scheme.length + 3)

            text = text.substringBefore('/')
            text = text.substringBefore('?')
            text = text.substringBefore('#')
            if (text.isEmpty()) return null

            val colon = text.lastIndexOf(':')
            val head = if (colon >= 0) text.substring(0, colon) else ""
            val tail = if (colon >= 0) text.substring(colon + 1) else ""
            // `::1`、`2001:db8::1` 这类裸 IPv6：最后一个冒号前面还是冒号（或为空），
            // 所以冒号后面那截不是端口，整段都是主机名。
            val bareIpv6 = colon >= 0 && (head.isEmpty() || head.endsWith(":"))
            val explicitPort = colon >= 0 && !bareIpv6 && tail.isNotEmpty() && tail.all { it.isDigit() }
            // 形如 `host:abc` —— 冒号后不是端口也不算 IPv6，直接判非法
            if (colon >= 0 && !bareIpv6 && !explicitPort) return null

            val host = (if (explicitPort) head else text).trim().trim('[', ']')
            if (host.isEmpty()) return null

            val port = if (explicitPort) {
                tail.toIntOrNull() ?: return null
            } else {
                when {
                    scheme == "https" -> HTTPS_PORT
                    scheme == "http" -> HTTP_PORT
                    isIpLiteral(host) -> DEFAULT_PORT
                    else -> HTTP_PORT
                }
            }
            if (port !in 1..65535) return null

            return Parsed(
                endpoint = ServerEndpoint(host, port, secure = scheme == "https"),
                scheme = scheme,
                explicitPort = explicitPort,
                isIp = isIpLiteral(host),
            )
        }

        private fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')
    }
}

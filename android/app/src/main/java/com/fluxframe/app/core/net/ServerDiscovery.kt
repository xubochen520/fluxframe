package com.fluxframe.app.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 局域网服务器自动发现。
 *
 * 与旧的 Capacitor 壳行为保持一致：手机与服务器在同一 /24 网段时，
 * 扫描同网段 1–254 的 **4311**、**5173** 两个端口，
 * 用 `GET /api/health` 判定是否为本服务。
 *
 * 用 [NetworkInterface] 枚举本机 IPv4 而不是 WifiManager.getConnectionInfo()：
 * 后者在 Android 13+ 需要 NEARBY_WIFI_DEVICES 且在部分 ROM 上返回 null，
 * 而网卡枚举不需要任何权限，也不受「随机 MAC」影响。
 */
class ServerDiscovery {

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .callTimeout(1600, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 本机所有非回环 IPv4 */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith("127.") || it.startsWith("169.254.") }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * 由本机 IP 推出待扫描的 /24 前缀，私有网段优先。
     * 例如本机 192.168.31.99 → `["192.168.31"]`。
     */
    fun subnetPrefixes(): List<String> {
        val prefixes = localIpv4().mapNotNull { ip ->
            val parts = ip.split('.')
            if (parts.size != 4) null else parts.take(3).joinToString(".")
        }.distinct()
        return prefixes.sortedByDescending { prefix ->
            when {
                prefix.startsWith("192.168.") -> 3
                prefix.startsWith("10.") -> 2
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn("$prefix.") -> 2
                else -> 0
            }
        }
    }

    /** 探测单个地址是否为 fluxframe 服务 */
    suspend fun probe(endpoint: ServerEndpoint): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${endpoint.baseUrl}api/health").get().build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                body.contains("fluxframe", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    /**
     * 扫描并逐台抛出可用服务器（边扫边报，UI 上出现一台就能点一台）。
     * 500 多个候选地址按 48 并发探测，最坏约 10 秒跑完。
     */
    fun scan(
        prefixes: List<String> = subnetPrefixes(),
        ports: List<Int> = ServerEndpoint.AUTO_SCAN_PORTS,
    ): Flow<ServerEndpoint> = channelFlow {
        if (prefixes.isEmpty()) return@channelFlow
        val semaphore = Semaphore(48)
        for (prefix in prefixes) {
            for (host in 1..254) {
                for (port in ports) {
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val endpoint = ServerEndpoint("$prefix.$host", port)
                            if (probe(endpoint)) trySend(endpoint)
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

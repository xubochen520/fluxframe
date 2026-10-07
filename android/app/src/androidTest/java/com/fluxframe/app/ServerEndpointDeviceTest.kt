package com.fluxframe.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxframe.app.core.net.ServerDiscovery
import com.fluxframe.app.core.net.ServerEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端（instrumentation）验证：**经公网域名**能不能真的连上服务器。
 *
 * 为什么单独放在 androidTest 而不是纯 JVM 单测里：
 * `ServerEndpoint` 的解析逻辑在 `PureLogicTest` 里已经覆盖，但「域名 → 候选端口 →
 * 真实探测」这条链路必须要有网络和真实 DNS 才算数。放在这里跑，验证的是设备
 * 实际会走的代码路径，而不是我手写的 curl。
 *
 * 背景（2026-10-07）：`fluxframe.example.com` 经 frp 内网穿透只暴露了 80，
 * 4311 / 443 从公网都不可达。老版本对任何输入都补 `:4311`，所以填域名必然失败。
 *
 * 注意：本用例依赖公网可达，断网时会失败——这是刻意的，静默跳过就失去意义了。
 */
@RunWith(AndroidJUnit4::class)
class ServerEndpointDeviceTest {

    private val domain = "fluxframe.example.com"

    @Test
    fun domainPrefersStandardWebPort() {
        val candidates = ServerEndpoint.candidates(domain)
        assertEquals("域名应优先试 80 / 443，再退到 4311", listOf(80, 443, 4311), candidates.map { it.port })
        assertEquals("http://$domain:80/", candidates.first().baseUrl)
    }

    @Test
    fun probeSucceedsThroughPublicDomain() = runBlocking {
        val endpoint = ServerEndpoint.candidates(domain).first()
        val ok = ServerDiscovery().probe(endpoint)
        assertTrue("设备无法经 $domain 访问服务器（试的是 $endpoint）", ok)
    }
}

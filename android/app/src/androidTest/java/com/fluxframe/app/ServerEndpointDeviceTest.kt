package com.fluxframe.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxframe.app.core.net.ServerDiscovery
import com.fluxframe.app.core.net.ServerEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端（instrumentation）验证：**经公网域名**能不能真的连上服务器。
 *
 * 为什么放在 androidTest 而不是纯 JVM 单测：`ServerEndpoint` 的解析逻辑在
 * `PureLogicTest` 里已经覆盖，但「域名 → 候选端口 → 真实探测」这条链路必须要有
 * 网络和真实 DNS 才算数。放在这里跑的是设备实际会走的代码路径，而不是手写的 curl。
 *
 * 要验证的坑：域名通常指向反向代理 / 内网穿透，公网只开 80 / 443，而局域网直连
 * 在 4311。老版本对任何输入都补 `:4311`，于是填域名必然连不上。
 *
 * 域名通过 instrumentation 参数传入，不写死在仓库里：
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.domain=your.host.example
 * 不传参数则跳过（`Assume`），因此在没有真实部署的环境里跑全量测试不会误报失败。
 */
@RunWith(AndroidJUnit4::class)
class ServerEndpointDeviceTest {

    private val domain: String? = InstrumentationRegistry.getArguments()
        .getString("domain")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    @Test
    fun domainPrefersStandardWebPort() {
        assumeTrue("未提供 -Pandroid.testInstrumentationRunnerArguments.domain，跳过", domain != null)
        val candidates = ServerEndpoint.candidates(domain!!)
        assertEquals("域名应优先试 80 / 443，再退到 4311", listOf(80, 443, 4311), candidates.map { it.port })
        assertEquals("http://$domain:80/", candidates.first().baseUrl)
    }

    @Test
    fun probeSucceedsThroughPublicDomain() = runBlocking {
        assumeTrue("未提供 -Pandroid.testInstrumentationRunnerArguments.domain，跳过", domain != null)
        val endpoint = ServerEndpoint.candidates(domain!!).first()
        val ok = ServerDiscovery().probe(endpoint)
        assertTrue("设备无法经 $domain 访问服务器（试的是 $endpoint）", ok)
    }
}

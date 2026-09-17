package com.fluxframe.app

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.fluxframe.app.core.media.VideoPosterLoader
import com.fluxframe.app.fluidcloud.CapsuleKind
import com.fluxframe.app.fluidcloud.CapsuleState
import com.fluxframe.app.fluidcloud.FluidCloudNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「流体云 / 实况更新」为什么不出卡，这里用测试把每个必要条件钉死。
 *
 * 背景：v2.1.0 的实况更新链路在真机上完全无效。根因是**清单里少了
 * `POST_PROMOTED_NOTIFICATIONS`** —— 它是 Android 16 QPR1 引入的普通权限
 * （protectionLevel 为 `normal|appop`，安装即授予），不声明时
 * `NotificationManager.canPostPromotedNotifications()` 恒为 false，
 * 通知永远不会被提升为实况更新，ColorOS 上也就没有流体云。
 *
 * 这类问题编译不会报错、运行时也不抛异常，只能靠"把必要条件写成断言"来防回归。
 * 参考实现：开源项目 InstallerX Revived 的清单里同样声明了它，并且给实况更新
 * 单独用了高重要性渠道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveUpdateContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun requestedPermissions(): Array<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions ?: emptyArray()
    }

    @Test
    fun `清单必须声明 POST_PROMOTED_NOTIFICATIONS`() {
        assertTrue(
            "缺少 android.permission.POST_PROMOTED_NOTIFICATIONS —— " +
                "没有它 canPostPromotedNotifications() 恒为 false，流体云永远不会出现",
            requestedPermissions().contains("android.permission.POST_PROMOTED_NOTIFICATIONS"),
        )
    }

    @Test
    fun `清单必须声明通知权限与前台服务权限`() {
        val permissions = requestedPermissions().toList()
        assertTrue("缺少 POST_NOTIFICATIONS", permissions.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(
            "缺少 FOREGROUND_SERVICE_DATA_SYNC",
            permissions.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
        )
    }

    @Test
    fun `实况更新渠道必须存在且重要性足够高`() {
        FluidCloudNotifier.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager)

        val live = manager.getNotificationChannel(FluidCloudNotifier.CHANNEL_LIVE)
        assertNotNull("必须为实况更新单独建渠道，否则会和普通进度通知的重要性互相牵制", live)
        // 低重要性渠道会被系统排除在提升之外，所以这里必须至少是"默认"
        assertTrue(
            "实况渠道重要性过低（实际 ${live!!.importance}），系统不会提升",
            live.importance >= NotificationManager.IMPORTANCE_DEFAULT,
        )
        // 但也不能因此响铃震动
        assertEquals("实况渠道不应有声音", null, live.sound)
        assertFalse("实况渠道不应震动", live.shouldVibrate())

        // 普通进度渠道保留给低版本，必须是低重要性（安静）
        val ongoing = manager.getNotificationChannel(FluidCloudNotifier.CHANNEL_ONGOING)
        assertNotNull(ongoing)
        assertTrue(ongoing!!.importance <= NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun `诊断不会把已声明的提升权限误报成缺失`() {
        // Android 16.0 上该权限尚未定义（QPR1 才引入），"声明了但授不到"是正常的；
        // 诊断必须区分"缺失"和"已声明未授予"，否则会把人引向错误的方向。
        assertTrue(
            "诊断应报告权限已声明",
            FluidCloudNotifier.isPromotedPermissionDeclared(context),
        )
        val diagnosis = FluidCloudNotifier.diagnose(context)
        assertFalse("已声明却报缺失，会误导排查方向：$diagnosis", diagnosis.contains("提升权限=缺失"))
    }

    @Test
    fun `低于 Android 16 时不谎称支持实况更新`() {
        // Robolectric 跑在 API 34 上
        assertTrue(Build.VERSION.SDK_INT < 36)
        assertFalse(
            "ProgressStyle 是 Android 16 的能力，低版本必须如实报告不支持",
            FluidCloudNotifier.supportsLiveUpdate(context),
        )
        val diagnosis = FluidCloudNotifier.diagnose(context)
        assertTrue("诊断里应带上 Android 版本，实际=$diagnosis", diagnosis.contains("Android 34"))
        assertTrue(
            "低版本诊断里必须说清原因，而不是含糊其辞，实际=$diagnosis",
            diagnosis.contains("Android 16"),
        )
    }

    @Test
    fun `低版本下仍然能构造出可用的进度通知`() {
        val state = CapsuleState(
            active = true,
            kind = CapsuleKind.UPLOAD,
            title = "上传中",
            subtitle = "已上传 42%",
            progress = 0.42f,
        )
        val notification = FluidCloudNotifier.buildOngoing(context, state)
        assertNotNull(notification)
        assertEquals(100, notification.extras.getInt("android.progressMax", -1))
        assertEquals(42, notification.extras.getInt("android.progress", -1))
    }

    @Test
    fun `视频封面加载器在没有请求时保持干净状态`() {
        assertNotNull(VideoPosterLoader)
        // 未命中过的键必须是 null，而不是抛异常
        assertEquals(null, VideoPosterLoader.peek("never-requested-key"))
        VideoPosterLoader.clear(context)
        assertEquals(0, VideoPosterLoader.successCount)
        assertEquals(0, VideoPosterLoader.failureCount)
        assertEquals(null, VideoPosterLoader.lastError)
        assertEquals(500L, VideoPosterLoader.FRAME_AT_MILLIS)
    }
}

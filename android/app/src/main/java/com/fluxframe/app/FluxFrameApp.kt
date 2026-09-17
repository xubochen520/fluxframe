package com.fluxframe.app

import android.app.ActivityManager
import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.fetch.MediaDataSourceFetcher
import coil.memory.MemoryCache
import com.fluxframe.app.core.di.AppContainer
import com.fluxframe.app.fluidcloud.FluidCloudNotifier

/**
 * 应用入口：构建依赖容器、配置 Coil 图片管线、创建通知渠道。
 *
 * Coil 复用 [AppContainer.okHttp]（也就是带着会话 Cookie 的客户端），
 * 这样 `<img>` 类的图片请求与接口请求共用登录态，无需手动塞 Cookie。
 *
 * 这里注册的两个组件是 Coil 侧的**补充能力**（接受 `MediaDataSource` 作为请求数据、
 * 从视频里解一帧）。网格里的视频封面**不依赖它们** —— 那条链路走
 * `core/media/VideoPosterLoader`，因为 Coil 的解码失败在界面上是不可观测的，
 * 真机上表现为"永远只有渐变占位"（见该文件注释）。
 *
 * 缓存上限是**显式**设定的：Coil 默认按 `Runtime.maxMemory()` 的百分比算内存缓存，
 * 而清单里开着 `largeHeap` 时这个基数会被放大到 512MB 级 ——
 * 那正是"占用很高"的一个直接来源。现在按系统给的常规堆大小算，并设硬上限。
 */
class FluxFrameApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        FluidCloudNotifier.ensureChannels(this)

        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(container.okHttp)
            .components {
                add(VideoFrameDecoder.Factory())
                add(MediaDataSourceFetcher.Factory())
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(imageMemoryCacheBytes())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
        Coil.setImageLoader(imageLoader)

        // 全局未捕获异常不该是白屏：交给系统默认处理，但留下可诊断信息
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("FluxFrame", "未捕获异常 @ ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 位图内存缓存：按系统常规堆大小的 18% 计，并卡在 24–96 MB 之间。
     * 3 列网格下的 320px 缩略图一张约 0.3 MB，这个额度足够放下十几屏。
     *
     * 注意 Coil 的 `maxSizeBytes` 只接受 Int —— 上限因此也刻意压在 96MB。
     */
    private fun imageMemoryCacheBytes(): Int {
        val memoryClass = runCatching {
            (getSystemService(ActivityManager::class.java))?.memoryClass ?: 192
        }.getOrDefault(192)
        val target = memoryClass * 1024 * 1024 * 18 / 100
        return target.coerceIn(MIN_MEMORY_CACHE_BYTES, MAX_MEMORY_CACHE_BYTES)
    }

    private companion object {
        const val MIN_MEMORY_CACHE_BYTES = 24 * 1024 * 1024
        const val MAX_MEMORY_CACHE_BYTES = 96 * 1024 * 1024
        /** 磁盘缓存 192MB：够放下一个图库的缩略图与视频封面 */
        const val DISK_CACHE_BYTES = 192L * 1024 * 1024
    }
}

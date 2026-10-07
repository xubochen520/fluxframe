package com.fluxframe.app.ui.galaxy

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 星系图缩略图的加载与缓存。
 *
 * 【为什么不用 AsyncImage 一个节点一个组件】屏幕上同时可见一两百个节点，每个都铺一个
 * composable + AsyncImage，光是测量与布局就足够掉帧，拖动时更明显。这里把位图取进内存，
 * 由 Canvas 一帧画完 —— 和网页端用 WebGL 纹理是同一个思路。
 *
 * 【全部在主线程上记账】[request] 由组合/副作用调用，[get] 由绘制调用，回调也切回主线程，
 * 所以 inFlight / lastUsed 都不需要加锁，少一类并发 bug。
 */
class GalaxyThumbCache(private val context: Context) {

    private val appContext = context.applicationContext

    /** 已经解码好的位图。用 StateMap：新到一张会自动触发重绘 */
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()

    /** 正在加载的 key，避免同一张图排队好几次 */
    private val inFlight = mutableSetOf<String>()

    /** 访问顺序的 LRU：迭代顺序就是"最久没用过的在前" */
    private val lastUsed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {}

    fun get(key: String): ImageBitmap? {
        val bitmap = bitmaps[key] ?: return null
        lastUsed[key] = System.currentTimeMillis()
        return bitmap
    }

    fun has(key: String): Boolean = bitmaps.containsKey(key)

    /**
     * 请求一张缩略图。[sizePx] 给当前屏幕上实际要画的大小 ——
     * 按需解码是这套东西能跑顺的关键：一千多像素的原图直接铺上去，光是解码就卡住了。
     */
    fun request(key: String, url: String, sizePx: Int, scope: CoroutineScope) {
        if (url.isBlank()) return
        if (bitmaps.containsKey(key) || inFlight.contains(key)) return
        inFlight.add(key)

        scope.launch(Dispatchers.IO) {
            val request = ImageRequest.Builder(appContext)
                .data(url)
                .size(sizePx.coerceIn(48, 512))
                // 硬件位图不能可靠地画进软件层 Canvas，这里明确要一张普通位图
                .allowHardware(false)
                .build()
            val bitmap = runCatching { Coil.imageLoader(appContext).execute(request) }
                .getOrNull()
                ?.let { it as? SuccessResult }
                ?.drawable
                ?.let { runCatching { it.toBitmap() }.getOrNull() }

            withContext(Dispatchers.Main) {
                inFlight.remove(key)
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmaps[key] = bitmap.asImageBitmap()
                    lastUsed[key] = System.currentTimeMillis()
                }
            }
        }
    }

    /** 把最久没用过的位图丢掉，别让长浏览把内存撑满 */
    fun trim() {
        if (bitmaps.size <= MAX_BITMAPS) return
        var toRemove = bitmaps.size - MAX_BITMAPS
        val iterator = lastUsed.entries.iterator()
        while (iterator.hasNext() && toRemove > 0) {
            val key = iterator.next().key
            iterator.remove()
            if (bitmaps.remove(key) != null) toRemove--
        }
    }

    /** 退出星系图时清空（这些位图只服务于这一个页面） */
    fun clear() {
        bitmaps.clear()
        lastUsed.clear()
        inFlight.clear()
    }

    private companion object {
        /**
         * 同时留在内存里的缩略图张数。
         * 一张 210px 的位图约 0.18MB，256 张约 45MB —— 和 Coil 自身的内存缓存量级相当，
         * 不会把常规堆挤爆。
         */
        const val MAX_BITMAPS = 256
    }
}

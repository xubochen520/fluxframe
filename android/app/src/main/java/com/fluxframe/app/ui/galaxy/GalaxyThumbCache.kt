package com.fluxframe.app.ui.galaxy

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 星系图缩略图的加载与缓存。
 *
 * 【为什么不用 AsyncImage 一个节点一个组件】屏幕上同时可见一两百个节点，每个都铺一个
 * composable + AsyncImage，光是测量与布局就足够掉帧，拖动时更明显。这里把位图取进内存，
 * 由 Canvas 一帧画完 —— 和网页端用 WebGL 纹理是同一个思路。
 *
 * 【关键：位图会随着放大重新取更清楚的】
 * 第一版只在「缓存里没有」时才去加载，于是**谁先加载就永远用谁的分辨率**：
 * 缩略图在屏幕边长 30px 时就开始淡入，那时请求到的是 30px 的小图；等用户放大到
 * 单个 400px，还是那张 30px 的位图被拉大十几倍 —— 表现就是「画质压缩过了，糊得看不清」。
 * 现在每次取图都会比对「现有位图的解码尺寸」和「当前要画多大」，明显不够就重新解一张更大的；
 * 旧的那张继续显示，新的到位再替换，所以不会闪白，只会越看越清楚。
 *
 * 【为什么用 Scale.FIT】格子是**按原比例**的矩形（见 GalaxyMath.thumbBoxPx），
 * 所以这里要的是"整张图缩进目标框"，不能裁、也不能拉。
 *
 * 【解码尺寸按长边算】`.size(targetPx)` 配 FIT，解出来的位图长边就是 targetPx，
 * 短边按原比例 —— 正好等于它在屏幕上要占的尺寸，一点不浪费。
 * 第一版用 FILL 解成正方形，再画进正方格子，等于把 2:3 的竖图横着拉宽 1.5 倍，又变形又发虚。
 *
 * 【全部在主线程上记账】[bitmap] 由绘制调用，回调也切回主线程，所以这些 map 不需要加锁。
 */
class GalaxyThumbCache(private val context: Context) {

    private val appContext = context.applicationContext

    /** 已经解码好的位图。用 StateMap：新到一张会自动触发重绘 */
    private val bitmaps = mutableStateMapOf<String, ImageBitmap>()

    /** 每张已就绪位图的解码边长（px），用来判断"这张够不够清楚" */
    private val loadedPx = mutableMapOf<String, Int>()

    /** 正在加载的目标边长，避免同一张图重复排队，也避免用更小的尺寸覆盖更大的 */
    private val pendingPx = mutableMapOf<String, Int>()

    /** 访问顺序的 LRU：迭代顺序就是"最久没用过的在前" */
    private val lastUsed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {}

    /**
     * 取一张够清楚的缩略图。返回当前能画的那张（可能还不够清楚，也可能还没有），
     * 同时按需在后台补一张更大的。
     *
     * [neededPx] 传当前屏幕上实际要画的边长；[url] 传该尺寸下最合适的那个变体地址
     * （放大到一定程度要换成 768 档，320 档在手机上撑不住）。
     */
    fun bitmap(key: String, url: String, neededPx: Int, scope: CoroutineScope): ImageBitmap? {
        val ready = bitmaps[key]
        val havePx = loadedPx[key] ?: 0
        val pending = pendingPx[key] ?: 0
        val want = neededPx.coerceIn(MIN_DECODE_PX, MAX_DECODE_PX)
        /* 判断标准本身是纯函数（GalaxyMath.needsSharperBitmap），有单元测试盯着 */
        val best = max(havePx, pending)
        if (GalaxyMath.needsSharperBitmap(want, best)) load(key, url, max(want, best), scope)
        return ready
    }

    private fun load(key: String, url: String, targetPx: Int, scope: CoroutineScope) {
        if (url.isBlank()) return
        pendingPx[key] = targetPx

        scope.launch(Dispatchers.IO) {
            val request = ImageRequest.Builder(appContext)
                .data(url)
                .size(targetPx)
                // 整张图缩进目标框：不裁不拉，长边正好等于 targetPx
                .scale(Scale.FIT)
                // 硬件位图不能可靠地画进软件层 Canvas，这里明确要一张普通位图
                .allowHardware(false)
                .build()
            val bitmap = runCatching { Coil.imageLoader(appContext).execute(request) }
                .getOrNull()
                ?.let { it as? SuccessResult }
                ?.drawable
                ?.let { runCatching { it.toBitmap() }.getOrNull() }

            withContext(Dispatchers.Main) {
                if (pendingPx[key] == targetPx) pendingPx.remove(key)
                /*
                 * 可能同时有两张在路上（先请求了 60px，放大后又请求了 200px）。
                 * 只让"不比现有的小"的那张落地，否则先发的大图会被后到的小图覆盖，
                 * 又糊回去。
                 */
                if (bitmap != null && !bitmap.isRecycled && targetPx >= (loadedPx[key] ?: 0)) {
                    bitmaps[key] = bitmap.asImageBitmap()
                    loadedPx[key] = targetPx
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
            if (bitmaps.remove(key) != null) {
                loadedPx.remove(key)
                toRemove--
            }
        }
    }

    /** 退出星系图时清空（这些位图只服务于这一个页面） */
    fun clear() {
        bitmaps.clear()
        loadedPx.clear()
        pendingPx.clear()
        lastUsed.clear()
    }

    private companion object {
        /**
         * 同时留在内存里的缩略图张数。
         * 一张 400px 的位图约 0.64MB，256 张约 160MB —— 太多。所以上限按张数收到 128，
         * 约 82MB，仍和 Coil 自身的内存缓存量级相当。
         */
        const val MAX_BITMAPS = 128

        /** 解一张不小于这个尺寸的，太小了没有意义 */
        const val MIN_DECODE_PX = 48

        /** 解码上限：768 档的实际用途也就到这儿，再大手机上肉眼看不出差别 */
        const val MAX_DECODE_PX = 512
    }
}

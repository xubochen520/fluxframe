package com.fluxframe.app

import androidx.compose.ui.geometry.Offset
import com.fluxframe.app.ui.galaxy.Galaxy3DMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三维相机与投影。这些值和网页端 `camera3d.ts` 是同一套，改一边就得改另一边 ——
 * 所以这里断言的是**具体数值**而不只是"大于零"，网页端改了公式这边会立刻红。
 */
class Galaxy3DMathTest {

    private val viewportWidth = 1000f
    private val viewportHeight = 800f

    private fun camera(
        yaw: Float = 0f,
        pitch: Float = 0f,
        distance: Float = 2.5f,
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
    ) = Galaxy3DMath.Camera(yaw, pitch, distance, x, y, z)

    /* ------------------------------ 投影 ------------------------------ */

    @Test
    fun `origin projects to the centre of the viewport`() {
        val point = Galaxy3DMath.projectPoint(0f, 0f, 0f, camera(), viewportWidth, viewportHeight)
        assertEquals(500f, point.x, 0.01f)
        assertEquals(400f, point.y, 0.01f)
        assertEquals(2.5f, point.depth, 0.001f)
    }

    @Test
    fun `world plus y points up on screen`() {
        /* 屏幕 +y 朝下，所以世界 +y 应该在画布上方（更小的 y） */
        val above = Galaxy3DMath.projectPoint(0f, 0.5f, 0f, camera(), viewportWidth, viewportHeight)
        val below = Galaxy3DMath.projectPoint(0f, -0.5f, 0f, camera(), viewportWidth, viewportHeight)
        assertTrue("世界 +y 要画在屏幕上方：${above.y} vs ${below.y}", above.y < below.y)
        assertTrue(above.y < 400f)
    }

    @Test
    fun `nearer points look bigger`() {
        /* 约定是「相机在 -z 一侧朝 +z 看」，所以 -z 的点更近（见 projectPoint 的说明） */
        val near = Galaxy3DMath.projectPoint(0f, 0f, -0.8f, camera(), viewportWidth, viewportHeight)
        val far = Galaxy3DMath.projectPoint(0f, 0f, 0.8f, camera(), viewportWidth, viewportHeight)
        assertTrue("近点的 depth 更小：${near.depth} vs ${far.depth}", near.depth < far.depth)
        assertTrue("近点的透视缩放更大", near.scale > far.scale)
        assertEquals("近点应当正好落在目标距离减去偏移上", 2.5f - 0.8f, near.depth, 0.001f)
    }

    @Test
    fun `projection scale matches focal over depth times half the short side`() {
        /* 这条是给网页端对照用的：公式一旦走样，两端取景就不一样了 */
        val point = Galaxy3DMath.projectPoint(0f, 0f, 0f, camera(distance = 2f), viewportWidth, viewportHeight)
        val expected = Galaxy3DMath.FOCAL / 2f * minOf(viewportWidth, viewportHeight) * 0.5f
        assertEquals(expected, point.scale, 0.01f)
    }

    @Test
    fun `points behind the camera are culled not divided by zero`() {
        /* depth 保留真实值（负的）交给调用方剔除，缩放则用安全深度算，所以不会出现 NaN/Infinity */
        val behind = Galaxy3DMath.projectPoint(0f, 0f, -10f, camera(), viewportWidth, viewportHeight)
        assertTrue("背后的点 depth 应当是负的，好让调用方一眼剔掉：${behind.depth}", behind.depth < 0f)
        assertTrue("缩放必须仍然是有限数", behind.scale.isFinite())
        assertEquals(
            "安全深度固定 0.05，所以缩放可算",
            Galaxy3DMath.FOCAL / 0.05f * minOf(viewportWidth, viewportHeight) * 0.5f,
            behind.scale,
            0.1f,
        )
    }

    @Test
    fun `yaw rotates around the centre`() {
        val quarter = (Math.PI / 2).toFloat()
        val before = Galaxy3DMath.projectPoint(1f, 0f, 0f, camera(), viewportWidth, viewportHeight)
        val after = Galaxy3DMath.projectPoint(1f, 0f, 0f, camera(yaw = quarter), viewportWidth, viewportHeight)
        assertTrue("转之前 +x 的点在中心右侧", before.x > 500f)
        assertEquals("绕 y 轴转 90° 后落到画面中心", 500f, after.x, 1f)
        /* 转 90° 把它转到 -z 一侧，按本文件的约定那就是**更近** */
        assertEquals(2.5f - 1f, after.depth, 0.01f)
    }

    /* ------------------------------ 取景 ------------------------------ */

    @Test
    fun `fit distance accounts for focal`() {
        /* 漏掉 focal 的话距离会偏小 1.65 倍，表现是星系上下被切掉一截 */
        val distance = Galaxy3DMath.fitDistance(radius = 1f, fill = 0.8f, focal = 1.6f)
        assertEquals(2f, distance, 0.01f)
    }

    @Test
    fun `fit radius uses the 95th percentile so one outlier cannot shrink the galaxy`() {
        val radii = List(99) { 1f } + listOf(50f)
        assertEquals("最后那个离群点不该参与取景", 1f, Galaxy3DMath.fitRadius(radii), 0.001f)
    }

    /* ------------------------------ 相机操作 ------------------------------ */

    @Test
    fun `dragging right rotates the galaxy to the right`() {
        val moved = Galaxy3DMath.orbit(camera(), dxPixels = 100f, dyPixels = 0f)
        assertTrue("手指往右拖，yaw 应该减小（内容跟手）", moved.yaw < 0f)
        assertEquals(-100f * Galaxy3DMath.ORBIT_RADIANS_PER_PX, moved.yaw, 0.0001f)
    }

    /*
     * 下面两条是「内容跟手」的**落点**验证 —— 光看 yaw/pitch 的符号是看不出对错的，
     * 得把点真的投到屏幕上，看它往哪边跑。这两条就是冲着"上下滑动反了"那个 bug 来的。
     */

    @Test
    fun `dragging down brings the near side down with the finger`() {
        val cam = camera(distance = 2.5f)
        /* 相机在 -z 一侧，所以 (0,0,-1) 是离相机最近的点 —— 拖它最能看出跟不跟手 */
        val before = Galaxy3DMath.projectPoint(0f, 0f, -1f, cam, viewportWidth, viewportHeight)
        val after = Galaxy3DMath.projectPoint(
            0f, 0f, -1f, Galaxy3DMath.orbit(cam, 0f, 120f), viewportWidth, viewportHeight,
        )
        assertTrue("往下拖之后近侧应当更靠下：${before.y} → ${after.y}", after.y > before.y)
    }

    @Test
    fun `dragging up brings the near side up with the finger`() {
        val cam = camera(distance = 2.5f)
        val before = Galaxy3DMath.projectPoint(0f, 0f, -1f, cam, viewportWidth, viewportHeight)
        val after = Galaxy3DMath.projectPoint(
            0f, 0f, -1f, Galaxy3DMath.orbit(cam, 0f, -120f), viewportWidth, viewportHeight,
        )
        assertTrue("往上拖之后近侧应当更靠上：${before.y} → ${after.y}", after.y < before.y)
    }

    @Test
    fun `dragging right brings the near side right with the finger`() {
        val cam = camera(distance = 2.5f)
        val before = Galaxy3DMath.projectPoint(0f, 0f, -1f, cam, viewportWidth, viewportHeight)
        val after = Galaxy3DMath.projectPoint(
            0f, 0f, -1f, Galaxy3DMath.orbit(cam, 120f, 0f), viewportWidth, viewportHeight,
        )
        assertTrue("往右拖之后近侧应当更靠右：${before.x} → ${after.x}", after.x > before.x)
    }

    @Test
    fun `pitch is clamped so the camera never flips over`() {
        /* dy 为正 = 手指往下拖。往下拖到死只到 -PITCH_LIMIT，往上拖到死只到 +PITCH_LIMIT */
        assertEquals("往下拖到底", -Galaxy3DMath.PITCH_LIMIT, Galaxy3DMath.orbit(camera(), 0f, 10000f).pitch, 0.0001f)
        assertEquals("往上拖到底", Galaxy3DMath.PITCH_LIMIT, Galaxy3DMath.orbit(camera(), 0f, -10000f).pitch, 0.0001f)
    }

    @Test
    fun `dolly is multiplicative and clamped`() {
        val closer = Galaxy3DMath.dolly(camera(distance = 2f), 0.5f)
        assertEquals(1f, closer.distance, 0.0001f)
        val tooClose = Galaxy3DMath.dolly(camera(distance = 2f), 0.0001f)
        assertEquals(Galaxy3DMath.MIN_DISTANCE, tooClose.distance, 0.0001f)
        val tooFar = Galaxy3DMath.dolly(camera(distance = 2f), 1000f)
        assertEquals(Galaxy3DMath.MAX_DISTANCE, tooFar.distance, 0.0001f)
    }

    @Test
    fun `auto spin advances yaw at the documented rate`() {
        val spun = Galaxy3DMath.autoSpin(camera(), 1f)
        assertEquals(Galaxy3DMath.AUTO_SPIN_RADIANS_PER_SEC, spun.yaw, 0.0001f)
    }

    /* ------------------------------ 推近 ------------------------------ */

    @Test
    fun `cinematic target moves the eye onto the node and squares up the view`() {
        val from = camera(yaw = 0.7f, pitch = 0.5f, distance = 3f)
        val target = Galaxy3DMath.cinematicTarget(from, 0.3f, -0.2f, 0.1f)
        assertEquals("目标点要移到那张图上", 0.3f, target.targetX, 0.0001f)
        assertEquals(-0.2f, target.targetY, 0.0001f)
        assertEquals(0.1f, target.targetZ, 0.0001f)
        assertEquals("俯仰归零，最后正对着看", 0f, target.pitch, 0.0001f)
        assertEquals(Galaxy3DMath.FOCUS_DISTANCE, target.distance, 0.0001f)
    }

    @Test
    fun `cinematic target takes the nearest full turn instead of unwinding`() {
        /* 已经转了 350°：应该再转 10° 回正，而不是倒着转 350° */
        val almostFull = camera(yaw = (2 * Math.PI).toFloat() - 0.17f)
        val target = Galaxy3DMath.cinematicTarget(almostFull, 0f, 0f, 0f)
        val delta = kotlin.math.abs(target.yaw - almostFull.yaw)
        assertTrue("转过的角度应当很小（约 0.17 弧度），实际 $delta", delta < 0.4f)
    }

    @Test
    fun `lerp eases at both ends`() {
        val from = camera(yaw = 0f, distance = 3f)
        val to = camera(yaw = 1f, distance = 1f)
        assertEquals("起点", 0f, Galaxy3DMath.lerpCamera(from, to, 0f).yaw, 0.0001f)
        assertEquals("终点", 1f, Galaxy3DMath.lerpCamera(from, to, 1f).yaw, 0.0001f)
        assertEquals("正中", 0.5f, Galaxy3DMath.lerpCamera(from, to, 0.5f).yaw, 0.0001f)
        /* 两头慢：1/4 处的进度要小于 1/4 */
        assertTrue(Galaxy3DMath.lerpCamera(from, to, 0.25f).yaw < 0.25f)
    }

    /* ------------------------------ 命中 ------------------------------ */

    @Test
    fun `pick prefers the nearer node when two overlap`() {
        val candidates = listOf(
            Galaxy3DMath.PickCandidate(500f, 400f, 2.0f, 20f),
            Galaxy3DMath.PickCandidate(505f, 400f, 1.0f, 20f),
        )
        assertEquals("重叠时给离相机最近的那个", 1, Galaxy3DMath.pick(candidates, 502f, 400f))
    }

    @Test
    fun `pick gives far small nodes a finger sized hit area`() {
        val candidates = listOf(Galaxy3DMath.PickCandidate(500f, 400f, 1.5f, 2f))
        assertEquals("远处的点半径只有 2px，但手指点得中", 0, Galaxy3DMath.pick(candidates, 512f, 400f, minRadiusPx = 24f))
        assertEquals("超出最小半径就该落空", -1, Galaxy3DMath.pick(candidates, 540f, 400f, minRadiusPx = 24f))
    }

    @Test
    fun `pick ignores nodes behind the camera`() {
        val candidates = listOf(Galaxy3DMath.PickCandidate(500f, 400f, -1f, 50f))
        assertEquals(-1, Galaxy3DMath.pick(candidates, 500f, 400f))
    }

    /* ------------------------------ 深度 / 陀螺仪 / 标签 ------------------------------ */

    @Test
    fun `depth alpha fades far nodes instead of popping them`() {
        assertEquals(1f, Galaxy3DMath.depthAlpha(0.5f, 1f, 4f), 0.0001f)
        assertEquals(0.2f, Galaxy3DMath.depthAlpha(5f, 1f, 4f, 0.2f), 0.0001f)
        val middle = Galaxy3DMath.depthAlpha(2.5f, 1f, 4f, 0.2f)
        assertTrue("中间是渐变的，不是台阶", middle > 0.2f && middle < 1f)
    }

    @Test
    fun `gyro parallax is centred, clamped and tiny`() {
        val (flatYaw, flatPitch) = Galaxy3DMath.gyroParallax(roll = 0f, pitch = (Math.PI / 2).toFloat())
        assertEquals("平放正对时不加偏移", 0f, flatYaw, 0.0001f)
        assertEquals(0f, flatPitch, 0.0001f)

        val (tiltedYaw, _) = Galaxy3DMath.gyroParallax(roll = 10f, pitch = 0f)
        assertEquals("歪到底也只到上限", Galaxy3DMath.GYRO_LIMIT, tiltedYaw, 0.0001f)
        assertTrue("视差必须很小，否则和拖动打架", Galaxy3DMath.GYRO_LIMIT <= 0.2f)

        /* 从竖直（pitch≈±90°）到平放（pitch=0）正好是一整段行程，符号朝负的一侧 */
        val (_, tiltForward) = Galaxy3DMath.gyroParallax(roll = 0f, pitch = 0f)
        assertEquals("倾斜量应当顶到上限", Galaxy3DMath.GYRO_LIMIT, kotlin.math.abs(tiltForward), 0.0001f)
    }

    @Test
    fun `label budget grows with zoom and never exceeds the cap`() {
        assertEquals("全景只留图最多的几个", 6, Galaxy3DMath.labelBudget(1f, total = 23))
        assertTrue("放大后要放出来更多", Galaxy3DMath.labelBudget(4f, 23) > 6)
        assertEquals("总数封顶", 23, Galaxy3DMath.labelBudget(50f, total = 23))
        assertEquals("没有标签时是 0", 0, Galaxy3DMath.labelBudget(3f, total = 0))
        assertEquals("总数少于基数时不超过总数", 3, Galaxy3DMath.labelBudget(1f, total = 3))
        assertTrue("必须单调不减", Galaxy3DMath.labelBudget(2f, 23) >= Galaxy3DMath.labelBudget(1f, 23))
    }

    @Test
    fun `thumbnails keep their aspect ratio in 3d too`() {
        val portrait = Galaxy3DMath.thumbBoxPx(0.67f, 400f)
        assertEquals(400f, portrait.height, 0.01f)
        assertTrue(portrait.width < portrait.height)
    }

    @Test
    fun `projected screen positions line up with the 2d helper for a flat cloud`() {
        /* 把三维相机摆成俯视 z=0 平面，屏幕位置应当和二维的 worldToScreen 一致 ——
           两端共用同一套屏幕约定的证据。 */
        val flat = Galaxy3DMath.Camera(yaw = 0f, pitch = 0f, distance = 0f, targetX = 0f, targetY = 0f, targetZ = 0f)
        val point = Galaxy3DMath.projectPoint(0.1f, 0.2f, 0f, flat, viewportWidth, viewportHeight, focal = 2f)
        /* focal=2、depth=0.05 会被安全钳到，所以直接比对"以中心为原点"的方向 */
        assertTrue("+x 在右", point.x > viewportWidth / 2f)
        assertTrue("+y 在上", point.y < viewportHeight / 2f)
        assertTrue("坐标必须是有限数", point.x.isFinite() && point.y.isFinite() && point.scale.isFinite())
    }

    /* --------------------- 起始视角：解出来而不是写死 --------------------- */

    /** 一团拉长的、还偏心的点云 —— 正是降维结果的典型形状 */
    private fun cloud(): List<FloatArray> = List(80) { index ->
        val t = index / 79f
        floatArrayOf(
            (t - 0.5f) * 1.9f + 0.25f,
            kotlin.math.sin(t * 6.2f) * 0.35f - 0.15f,
            (t - 0.5f) * 0.5f + 0.1f,
        )
    }

    @Test
    fun `solved initial view puts the whole cloud on screen`() {
        val points = cloud()
        for (aspect in listOf(2f, 1f, 0.5f)) {
            val width = 1000f * aspect
            val height = 1000f
            val view = Galaxy3DMath.solveInitialView(points, viewportAspect = aspect, fill = 0.82f)
            val camera = Galaxy3DMath.Camera(view.yaw, view.pitch, view.distance, view.targetX, view.targetY, view.targetZ)
            var offScreen = 0
            for (point in points) {
                val projected = Galaxy3DMath.projectPoint(point[0], point[1], point[2], camera, width, height)
                if (projected.depth <= 0.06f) offScreen++
                if (projected.x < 0f || projected.x > width) offScreen++
                if (projected.y < 0f || projected.y > height) offScreen++
            }
            assertEquals("画布比例 $aspect 时不该有点跑到画面外", 0, offScreen)
        }
    }

    @Test
    fun `solved initial view centres on the bounding box not the origin`() {
        /* 这团云明显偏在 +x 上：目标点要是还用原点，画面就会偏到一边 */
        val view = Galaxy3DMath.solveInitialView(cloud(), viewportAspect = 1f)
        assertTrue("目标点该落在包围盒中心附近，实际 ${view.targetX}", view.targetX > 0.15f)
    }

    @Test
    fun `solved initial view differs between a wide and a tall canvas`() {
        /* 横屏和竖屏"摆得最开"的角度不一样：这正是不能写死一个常数的原因。
           （不去比谁退得更远 —— 竖屏有可能挑到一个"本身就细长"的角度，反而离得更近，
           那正是解算的意义所在。） */
        val points = cloud()
        val wide = Galaxy3DMath.solveInitialView(points, viewportAspect = 2f)
        val tall = Galaxy3DMath.solveInitialView(points, viewportAspect = 0.5f)
        val sameAngle = kotlin.math.abs(wide.yaw - tall.yaw) < 0.01f && kotlin.math.abs(wide.pitch - tall.pitch) < 0.01f
        assertTrue("画布比例变了，起始角度也该跟着变", !sameAngle)
        assertTrue("距离必须是有限的正数", wide.distance.isFinite() && wide.distance > 0f && tall.distance > 0f)
    }
}

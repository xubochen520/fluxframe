/**
 * 3D 星系空间的相机与投影 —— **纯函数，不碰 GL**。
 *
 * 单独抽出来有两个理由：
 *   1. 可以脱离 WebGL 跑单元测试（镜头手感、命中判定、推近插值都是可以断言的）；
 *   2. 安卓端要照搬同一套公式 —— 那边没有 WebGL，是 Canvas 上手动投影，
 *      参数必须一模一样，否则两端的旋转速度和取景会不一致。
 */

export interface Camera3D {
  /** 绕 y 轴的环绕角（弧度）。默认自转就是不断加这个值 */
  yaw: number
  /** 俯仰角（弧度），被夹在 ±PITCH_LIMIT 内，防止翻过头 */
  pitch: number
  /** 相机到目标点的距离。越小越近 */
  distance: number
  /** 视线看向的世界坐标点（拖动旋转就是绕它转） */
  targetX: number
  targetY: number
  targetZ: number
}

export interface Projected {
  /** 屏幕坐标（设备像素），(0,0) 在左上 */
  x: number
  y: number
  /** 相机空间深度：越小越近，<=0 表示在相机背后 */
  depth: number
  /** 透视缩放系数：屏幕尺寸 = 世界尺寸 × scale */
  scale: number
}

/** 俯仰上限（约 77°）：再往上相机就翻到顶了，画面会突然镜像 */
export const PITCH_LIMIT = 1.35
/** 焦距。和 distance 是同一个量纲，比值决定透视强弱 */
export const FOCAL = 1.65
/** 最近的相机距离：再近就穿到云里面了 */
export const MIN_DISTANCE = 0.35
/** 最远：整个星系缩成一个小点也没意义 */
export const MAX_DISTANCE = 9

function clamp(value: number, low: number, high: number) {
  return value < low ? low : value > high ? high : value
}

export function clampPitch(pitch: number) {
  return clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT)
}

export function clampDistance(distance: number) {
  return clamp(distance, MIN_DISTANCE, MAX_DISTANCE)
}

/**
 * 世界坐标 → 屏幕坐标。
 *
 * 顺序是「先绕 y 转（左右环绕），再绕 x 转（上下俯仰），最后平移出去做透视除法」——
 * 和安卓端、以及 `_galaxy/render3.py` 里那张验证图完全一致。
 */
export function projectPoint(
  x: number,
  y: number,
  z: number,
  camera: Camera3D,
  viewportWidth: number,
  viewportHeight: number,
  focal = FOCAL,
): Projected {
  const dx = x - camera.targetX
  const dy = y - camera.targetY
  const dz = z - camera.targetZ

  const cosYaw = Math.cos(camera.yaw)
  const sinYaw = Math.sin(camera.yaw)
  const x1 = dx * cosYaw + dz * sinYaw
  const z1 = -dx * sinYaw + dz * cosYaw

  const cosPitch = Math.cos(camera.pitch)
  const sinPitch = Math.sin(camera.pitch)
  const y1 = dy * cosPitch - z1 * sinPitch
  const z2 = dy * sinPitch + z1 * cosPitch

  const depth = z2 + camera.distance
  /* 背后或贴脸的点给一个极小正数：除法不会炸，画出来也在屏幕外，由调用方按 depth 剔掉 */
  const safeDepth = depth < 0.05 ? 0.05 : depth
  const scale = (focal / safeDepth) * Math.min(viewportWidth, viewportHeight) * 0.5

  return {
    x: viewportWidth * 0.5 + x1 * scale,
    /* 世界 +y 朝上，屏幕 +y 朝下 */
    y: viewportHeight * 0.5 - y1 * scale,
    depth,
    scale,
  }
}

/**
 * 取景：把半径 [radius] 的球正好塞进视口，再乘 [fill] 留点边距。
 *
 * **必须带上 focal**：投影里 屏幕尺寸 = 世界尺寸 × focal / distance × min(w,h)/2，
 * 所以要让「半径碰到视口半高的 fill 倍」，距离得是 `radius × focal / fill`。
 * 早先漏了 focal 这一项，算出来的距离偏小 1.65 倍 —— 表现是星系上下被切掉一截。
 *
 * 与视口尺寸无关（w/h 在推导里约掉了），所以窗口怎么变取景都一样。
 */
export function fitDistance(radius: number, fill = 0.78, focal = FOCAL): number {
  const safeRadius = radius > 0.0001 ? radius : 1
  return clampDistance((safeRadius * focal) / Math.max(0.2, fill))
}

/**
 * 拖动 → 环绕。
 *
 * 手感按「拖多少像素转多少度」定，并且**与视口大小无关**（用像素而不是比例），
 * 这样同一个手势在手机和桌面上转过的角度一样。
 * 横向是反向的：手指往右拖，星系应该跟着往右转（内容跟手），所以 yaw 减小。
 */
export const ORBIT_RADIANS_PER_PX = 0.0075

export function orbit(camera: Camera3D, dxPixels: number, dyPixels: number): Camera3D {
  return {
    ...camera,
    yaw: camera.yaw - dxPixels * ORBIT_RADIANS_PER_PX,
    pitch: clampPitch(camera.pitch + dyPixels * ORBIT_RADIANS_PER_PX),
  }
}

/** 缩放：factor < 1 拉近。距离是乘性的，所以滚轮每一格的"感觉"在任何距离下都一样 */
export function dolly(camera: Camera3D, factor: number): Camera3D {
  return { ...camera, distance: clampDistance(camera.distance * factor) }
}

/** 缓慢自转的角速度（弧度/秒）：约 3.4°/s，转一圈约 105 秒 */
export const AUTO_SPIN_RADIANS_PER_SEC = 0.06

export function autoSpin(camera: Camera3D, deltaSeconds: number): Camera3D {
  return { ...camera, yaw: camera.yaw + AUTO_SPIN_RADIANS_PER_SEC * deltaSeconds }
}

/* ------------------------------ 推近动画 ------------------------------ */

export function easeInOutCubic(t: number) {
  const value = clamp(t, 0, 1)
  return value < 0.5 ? 4 * value * value * value : 1 - Math.pow(-2 * value + 2, 3) / 2
}

/**
 * 电影镜头：把相机推到某个节点前面。
 *
 * 「电影感」来自三点，不只是拉近：
 *   1. 目标点从当前看向的位置**平滑移**到那张图上，而不是瞬移；
 *   2. 俯仰角归零、环绕角就近取整到 0 —— 最后画面是正对着的，像定妆照；
 *   3. 用 easeInOutCubic（两头慢中间快），起步和刹车都有分量。
 *
 * [yaw] 要「就近归零」：如果当前已经转了 350°，应该再转 10° 回到正前，
 * 而不是倒着转 350°（那样会绕一大圈，观感很晕）。
 */
export function cinematicTarget(from: Camera3D, nodeX: number, nodeY: number, nodeZ: number, closeDistance: number): Camera3D {
  const turns = Math.round(from.yaw / (Math.PI * 2))
  const nearestZero = turns * Math.PI * 2
  return {
    yaw: nearestZero,
    pitch: 0,
    distance: clampDistance(closeDistance),
    targetX: nodeX,
    targetY: nodeY,
    targetZ: nodeZ,
  }
}

export function lerpCamera(from: Camera3D, to: Camera3D, t: number): Camera3D {
  const k = easeInOutCubic(t)
  return {
    yaw: from.yaw + (to.yaw - from.yaw) * k,
    pitch: from.pitch + (to.pitch - from.pitch) * k,
    distance: from.distance + (to.distance - from.distance) * k,
    targetX: from.targetX + (to.targetX - from.targetX) * k,
    targetY: from.targetY + (to.targetY - from.targetY) * k,
    targetZ: from.targetZ + (to.targetZ - from.targetZ) * k,
  }
}

/* ------------------------------ 命中判定 ------------------------------ */

export interface PickCandidate {
  /** 已经投影到屏幕上的位置（设备像素） */
  screenX: number
  screenY: number
  depth: number
  /** 这个节点在屏幕上的半径（设备像素） */
  radiusPx: number
}

/**
 * 找点击命中的节点。
 *
 * 两个要点：
 *   · **优先近的**：3D 里前后会重叠，点到重叠区域应该给离相机最近的那个，
 *     否则会出现"点在前面这张上却选中了背后那张"。
 *   · 命中半径取「节点半径」与 [minRadiusPx] 的较大者：远处的点再小，
 *     也要保证手指点得中（手机上尤其重要）。
 */
export function pick3(
  candidates: PickCandidate[],
  tapX: number,
  tapY: number,
  minRadiusPx = 22,
): number {
  let best = -1
  let bestDepth = Infinity
  for (let i = 0; i < candidates.length; i++) {
    const candidate = candidates[i]
    if (candidate.depth <= 0.06) continue
    const dx = candidate.screenX - tapX
    const dy = candidate.screenY - tapY
    const radius = Math.max(candidate.radiusPx, minRadiusPx)
    if (dx * dx + dy * dy > radius * radius) continue
    if (candidate.depth < bestDepth) {
      bestDepth = candidate.depth
      best = i
    }
  }
  return best
}

/**
 * 深度 → 不透明度与缩放。
 *
 * 远处的点淡出而不是直接消失：直接消失会在旋转时"闪"；
 * 淡出看起来像没入星尘，也让近处的层次更清楚。
 */
export function depthAlpha(depth: number, near: number, far: number, minAlpha = 0.18): number {
  if (depth <= near) return 1
  if (depth >= far) return minAlpha
  const t = (depth - near) / (far - near)
  return 1 - t * (1 - minAlpha)
}

/* ------------------------------ 标签名分级 ------------------------------ */

/**
 * 这个缩放级别下最多显示几个标签名。
 *
 * 缩小时满屏名字会糊成一片、还盖住星云本身，所以只留图最多的那几个；
 * 放大时再一个个放出来 —— 用户要的就是这个"逐步显现"的过程。
 *
 * [zoomRatio] = 全景距离 / 当前距离：1 是全景，越大越近。
 * 用 0.75 次方而不是线性：线性的话前几格缩放就把标签放完了，
 * 后面再放大反而没有新东西出现。
 */
export function labelBudget(zoomRatio: number, total: number, base = 6, cap = 30): number {
  const ratio = Number.isFinite(zoomRatio) && zoomRatio > 0 ? zoomRatio : 1
  const limit = Math.min(cap, Math.max(0, total))
  const wanted = Math.round(base * Math.pow(ratio, 0.75))
  return Math.max(Math.min(base, limit), Math.min(wanted, limit))
}

/**
 * 标签名排序：图多的优先。
 * [weightOf] 返回这个标签下有多少张图；并列时按名字排，保证每次顺序一致。
 */
export function rankLabels<T>(labels: T[], weightOf: (label: T) => number, nameOf: (label: T) => string): T[] {
  return [...labels].sort((a, b) => weightOf(b) - weightOf(a) || nameOf(a).localeCompare(nameOf(b)))
}

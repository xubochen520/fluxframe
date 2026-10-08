/**
 * 3D 星系空间的渲染器（WebGL）。
 *
 * 和二维那套（engine.ts）是**两个独立的模块**，不共用代码：二维那套是正交投影 + 屏幕空间
 * 布局，三维这套是透视投影 + 轨道相机，把两者硬凑成一个会到处都是 if。
 * 共用的只有思路：点光晕用加色混合、缩略图按需加载并按深度淡出、命中判定走屏幕空间。
 *
 * 【投影公式】顶点着色器里手写，和 camera3d.ts 的 projectPoint 逐字对应 ——
 * 那边是可以在 Node 里跑单测的纯函数，这边是 GPU 上的同一套算术。改一处必须改另一处。
 *
 * 【为什么不用矩阵】正交/透视矩阵当然更"标准"，但这里的相机只有 yaw/pitch/distance/target
 * 四个自由度，手写反而更短更直观，也省掉一次 mat4 乘法的误差。
 */
import {
  AUTO_SPIN_RADIANS_PER_SEC,
  FOCAL,
  autoSpin,
  cinematicTarget,
  depthAlpha,
  dolly,
  fitDistance,
  solveInitialView,
  lerpCamera,
  orbit,
  pick3,
  projectPoint,
  type Camera3D,
  type PickCandidate,
} from './camera3d'

export interface Node3D {
  id: string
  name: string
  thumb: string
  thumbLarge: string
  x: number
  y: number
  z: number
  /** 线性空间的 0~1 RGB */
  color: [number, number, number]
  character: string
  degree: number
  /** 原图长宽比（宽/高） */
  aspect: number
}

export interface Edge3D { a: number; b: number; score: number }

export interface Engine3DCallbacks {
  onHover?: (index: number | null, clientX: number, clientY: number) => void
  onSelect?: (index: number | null) => void
  onOpen?: (index: number) => void
  onView?: (state: { distance: number; yaw: number; focused: boolean }) => void
  onFocusChange?: (index: number | null) => void
  /** 推近动画走完（镜头停住了）。用来接着打开大图 —— 见 SimilarGalaxy3D 的用法 */
  onFocusSettled?: (index: number) => void
}

/* ------------------------------ 着色器 ------------------------------ */

/*
 * 顶点着色器共用的投影段。**精度声明必须在顶点和片元里写成一样**，
 * 否则链接阶段会报 "Precisions of uniform uResolution differ"（在二维引擎上踩过一次）。
 */
const PROJECT_GLSL = `
uniform vec2 uResolution;
uniform vec3 uTarget;
uniform float uYaw;
uniform float uPitch;
uniform float uDistance;
uniform float uFocal;
uniform float uViewMin;

/** 世界坐标 → 屏幕像素（与 camera3d.ts 的 projectPoint 逐字对应） */
vec2 projectToScreen(vec3 world, out float depth, out float scale) {
  vec3 rel = world - uTarget;
  float cy = cos(uYaw), sy = sin(uYaw);
  vec3 p1 = vec3(rel.x * cy + rel.z * sy, rel.y, -rel.x * sy + rel.z * cy);
  float cp = cos(uPitch), sp = sin(uPitch);
  vec3 p2 = vec3(p1.x, p1.y * cp - p1.z * sp, p1.y * sp + p1.z * cp);
  depth = p2.z + uDistance;
  float safeDepth = max(depth, 0.05);
  scale = uFocal / safeDepth * uViewMin * 0.5;
  return vec2(uResolution.x * 0.5 + p2.x * scale, uResolution.y * 0.5 - p2.y * scale);
}

vec2 screenToNdc(vec2 screen) {
  return vec2(screen.x / uResolution.x * 2.0 - 1.0, 1.0 - screen.y / uResolution.y * 2.0);
}
`

const POINT_VS = `
precision highp float;
attribute vec3 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;   // x: 基础半径(世界), y: 不透明度
${PROJECT_GLSL}
varying vec3 vColor;
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aPos, depth, scale);
  float radiusPx = max(aMeta.x * scale, 0.7);
  // gl_PointSize 有上限（一般 64~255），太大的点会被截断，所以光晕靠片元里画
  gl_PointSize = clamp(radiusPx * 2.4, 1.0, 64.0);
  vColor = aColor;
  vAlpha = aMeta.y * (depth < 0.06 ? 0.0 : 1.0);
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

const POINT_FS = `
precision highp float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  vec2 offset = gl_PointCoord * 2.0 - 1.0;
  float r = length(offset);
  if (r > 1.0) discard;
  // 亮核 + 外晕，加色混合下会自己叠出星云感
  float core = smoothstep(1.0, 0.15, r);
  float halo = pow(max(0.0, 1.0 - r), 2.4);
  gl_FragColor = vec4(vColor * (core * 1.1 + halo * 0.5), (core * 0.85 + halo * 0.5) * vAlpha);
}
`

const EDGE_VS = `
precision highp float;
attribute vec3 aPos;
attribute vec2 aMeta;   // x: 不透明度, y: 未用
${PROJECT_GLSL}
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aPos, depth, scale);
  vAlpha = aMeta.x * (depth < 0.06 ? 0.0 : 1.0);
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

const EDGE_FS = `
precision highp float;
varying float vAlpha;
uniform vec3 uLineColor;
void main() {
  gl_FragColor = vec4(uLineColor, vAlpha);
}
`

const THUMB_VS = `
precision highp float;
attribute vec3 aCenter;
attribute vec2 aCorner;   // -1..1，两个轴
attribute vec2 aUv;
attribute vec4 aTint;     // rgb + alpha
${PROJECT_GLSL}
/* 尺寸做成 uniform 而不是逐顶点属性：一个四边形的四个角必然一样大，
   放进 stride 只会让每帧多搬 4 份重复数据 */
uniform vec2 uSizePx;
varying vec2 vUv;
varying vec4 vTint;
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aCenter, depth, scale);
  // 广告牌：在**屏幕空间**偏移四个角，所以永远正对相机，近大远小由 scale 带出来
  vec2 corner = screen + aCorner * uSizePx * 0.5;
  vUv = aUv;
  vTint = aTint;
  vAlpha = depth < 0.06 ? 0.0 : 1.0;
  gl_Position = vec4(screenToNdc(corner), 0.0, 1.0);
}
`

const THUMB_FS = `
precision highp float;
varying vec2 vUv;
varying vec4 vTint;
varying float vAlpha;
uniform sampler2D uTexture;
uniform vec2 uFeather;
void main() {
  vec4 texel = texture2D(uTexture, vUv);
  // 圆角：到边缘的距离小于羽化宽度就渐隐，避免锯齿
  vec2 edge = min(vUv, 1.0 - vUv);
  float corner = smoothstep(0.0, uFeather.x, min(edge.x, edge.y));
  gl_FragColor = vec4(texel.rgb * vTint.rgb, texel.a * vTint.a * corner * vAlpha);
}
`

/* ------------------------------ 常量 ------------------------------ */

/** 缩略图在屏幕上的长边上限（设备像素）：再大就会把星系结构盖住 */
const THUMB_MAX_PX = 420
/** 超过这个屏幕长边就换 768 档 */
const THUMB_LARGE_AT = 210
/** 单帧最多画多少张缩略图 */
const MAX_THUMBS_PER_FRAME = 160
/** 同时挂多少张纹理 */
const MAX_TEXTURES = 300
/** 一次并发加载几张 */
const LOAD_CONCURRENCY = 6
/** 点击/悬停的最小命中半径（设备像素） */
const PICK_MIN_RADIUS = 24
/** 拖动多少像素算「拖动」而不是「点击」 */
const CLICK_SLOP = 5
/** 推近动画时长 */
const FOCUS_DURATION_MS = 1100
/** 推近后相机停在这个距离 */
const FOCUS_DISTANCE = 0.62
/** 不做深度剔除的阈值：太远的点直接不画 */
const FAR_CULL = 4.2

/** 投影到屏幕上的一个节点：命中判定要的字段 + 透视缩放（缩略图尺寸要用） */
type ProjectedNode = PickCandidate & { scale: number }

interface TextureEntry {
  texture: WebGLTexture | null
  image: HTMLImageElement | null
  state: 'idle' | 'loading' | 'ready' | 'failed'
  lastUsed: number
  aspect: number
  level: number
}

function compile(gl: WebGLRenderingContext, type: number, source: string) {
  const shader = gl.createShader(type)
  if (!shader) return null
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    console.error('着色器编译失败', gl.getShaderInfoLog(shader), source)
    gl.deleteShader(shader)
    return null
  }
  return shader
}

function link(gl: WebGLRenderingContext, vsSource: string, fsSource: string) {
  const vs = compile(gl, gl.VERTEX_SHADER, vsSource)
  const fs = compile(gl, gl.FRAGMENT_SHADER, fsSource)
  if (!vs || !fs) return null
  const program = gl.createProgram()
  if (!program) return null
  gl.attachShader(program, vs)
  gl.attachShader(program, fs)
  gl.linkProgram(program)
  gl.deleteShader(vs)
  gl.deleteShader(fs)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    console.error('着色器链接失败', gl.getProgramInfoLog(program))
    return null
  }
  return program
}

export class Galaxy3DEngine {
  private gl: WebGLRenderingContext
  private canvas: HTMLCanvasElement
  private nodes: Node3D[]
  private edges: Edge3D[]
  private callbacks: Engine3DCallbacks

  private camera: Camera3D
  private homeCamera: Camera3D
  private focused: number | null = null
  private focusFrom: Camera3D | null = null
  private focusTo: Camera3D | null = null
  private focusStartedAt = 0
  private hoverIndex: number | null = null
  private selectedIndex: number | null = null

  /** 陀螺仪偏移（弧度），叠加在 yaw/pitch 上。做成平滑量，避免传感器噪声让画面抖 */
  private gyroYaw = 0
  private gyroPitch = 0
  private gyroTargetYaw = 0
  private gyroTargetPitch = 0
  private gyroEnabled = false

  private width = 0
  private height = 0
  private dpr = 1
  private frame = 0
  private lastFrameAt = 0
  private viewTick = 0
  private dragging = false
  private dragMoved = 0
  private lastPointerX = 0
  private lastPointerY = 0
  private detach: Array<() => void> = []
  private contextLost = false

  private pointProgram: WebGLProgram | null = null
  private edgeProgram: WebGLProgram | null = null
  private thumbProgram: WebGLProgram | null = null
  private pointBuffer: WebGLBuffer | null = null
  private edgeBuffer: WebGLBuffer | null = null
  private thumbBuffer: WebGLBuffer | null = null

  private pointData: Float32Array
  private edgeData: Float32Array
  private thumbData = new Float32Array(4 * 12)

  private textures = new Map<string, TextureEntry>()
  private queue: Array<{ index: number; level: number; priority: number }> = []
  private loading = 0
  /** 这一帧算好的投影结果，悬停/点击判定直接复用，不重复算 */
  private projected: ProjectedNode[] = []

  constructor(canvas: HTMLCanvasElement, nodes: Node3D[], edges: Edge3D[], callbacks: Engine3DCallbacks = {}) {
    this.canvas = canvas
    this.nodes = nodes
    this.edges = edges
    this.callbacks = callbacks

    const attributes: WebGLContextAttributes = { alpha: true, antialias: true, premultipliedAlpha: false, depth: false }
    const gl = (canvas.getContext('webgl', attributes) || canvas.getContext('experimental-webgl', attributes)) as WebGLRenderingContext | null
    if (!gl) throw new Error('WebGL 不可用')
    this.gl = gl

    this.pointProgram = link(gl, POINT_VS, POINT_FS)
    this.edgeProgram = link(gl, EDGE_VS, EDGE_FS)
    this.thumbProgram = link(gl, THUMB_VS, THUMB_FS)
    this.pointBuffer = gl.createBuffer()
    this.edgeBuffer = gl.createBuffer()
    this.thumbBuffer = gl.createBuffer()

    this.pointData = new Float32Array(nodes.length * 8)
    /* 每条边两个端点：位置(3) + 元数据(2) */
    this.edgeData = new Float32Array(edges.length * 2 * 5)

    /* 起始视角按当前画布形状算出来：写死角度的话，坐标朝向或窗口比例一变就不合适了 */
    this.homeCamera = this.solveHome()
    this.camera = { ...this.homeCamera }

    this.attachEvents()
    this.resize()
  }

  /**
   * 算起始视角。画布比例会影响"摆得最开"的角度，所以窗口尺寸变了要重算 ——
   * 但只在**用户没有自己转过**的时候才动镜头，否则会把人家转好的视角顶掉。
   */
  private solveHome() {
    const rect = this.canvas.getBoundingClientRect()
    const aspect = rect.height > 1 ? rect.width / rect.height : 16 / 9
    const solved = solveInitialView(this.nodes, aspect, 0.82)
    return {
      yaw: solved.yaw,
      pitch: solved.pitch,
      distance: solved.distance,
      targetX: solved.targetX,
      targetY: solved.targetY,
      targetZ: solved.targetZ,
    }
  }

  /* ------------------------------ 事件 ------------------------------ */

  private attachEvents() {
    const canvas = this.canvas

    const onPointerDown = (event: PointerEvent) => {
      this.dragging = true
      this.dragMoved = 0
      this.lastPointerX = event.clientX
      this.lastPointerY = event.clientY
      canvas.setPointerCapture?.(event.pointerId)
    }
    const onPointerMove = (event: PointerEvent) => {
      if (this.dragging) {
        const dx = event.clientX - this.lastPointerX
        const dy = event.clientY - this.lastPointerY
        this.lastPointerX = event.clientX
        this.lastPointerY = event.clientY
        this.dragMoved += Math.abs(dx) + Math.abs(dy)
        /*
         * 拖动时**放弃电影镜头的目标点**：用户手动转了，就不该再被动画拉回去。
         * 但保留 focused 状态（选中框还在），只是相机归用户控制。
         */
        this.focusFrom = null
        this.focusTo = null
        this.camera = orbit(this.camera, dx, dy)
        return
      }
      const rect = canvas.getBoundingClientRect()
      const hit = this.pickAt((event.clientX - rect.left) * this.dpr, (event.clientY - rect.top) * this.dpr)
      if (hit !== this.hoverIndex) {
        this.hoverIndex = hit
        this.callbacks.onHover?.(hit, event.clientX, event.clientY)
      } else if (hit !== null) {
        this.callbacks.onHover?.(hit, event.clientX, event.clientY)
      }
    }
    const onPointerUp = (event: PointerEvent) => {
      if (!this.dragging) return
      this.dragging = false
      canvas.releasePointerCapture?.(event.pointerId)
      /* 拖过就不算点击：转动星系时手指总会有几像素位移 */
      if (this.dragMoved > CLICK_SLOP * this.dpr) return
      const rect = canvas.getBoundingClientRect()
      const hit = this.pickAt((event.clientX - rect.left) * this.dpr, (event.clientY - rect.top) * this.dpr)
      if (hit === null) {
        this.selectedIndex = null
        this.focused = null
        this.callbacks.onSelect?.(null)
        return
      }
      this.selectedIndex = hit
      this.callbacks.onSelect?.(hit)
      this.focusOn(hit)
    }
    const onDoubleClick = (event: MouseEvent) => {
      const rect = canvas.getBoundingClientRect()
      const hit = this.pickAt((event.clientX - rect.left) * this.dpr, (event.clientY - rect.top) * this.dpr)
      if (hit !== null) this.callbacks.onOpen?.(hit)
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      this.focusFrom = null
      this.focusTo = null
      this.camera = dolly(this.camera, event.deltaY > 0 ? 1.12 : 1 / 1.12)
    }

    let pinchStart = 0
    const onTouchStart = (event: TouchEvent) => {
      if (event.touches.length === 2) {
        pinchStart = Math.hypot(
          event.touches[0].clientX - event.touches[1].clientX,
          event.touches[0].clientY - event.touches[1].clientY,
        )
      }
    }
    const onTouchMove = (event: TouchEvent) => {
      if (event.touches.length !== 2 || !pinchStart) return
      event.preventDefault()
      const distance = Math.hypot(
        event.touches[0].clientX - event.touches[1].clientX,
        event.touches[0].clientY - event.touches[1].clientY,
      )
      if (distance > 0) {
        this.camera = dolly(this.camera, pinchStart / distance)
        pinchStart = distance
      }
    }

    canvas.addEventListener('pointerdown', onPointerDown)
    canvas.addEventListener('pointermove', onPointerMove)
    canvas.addEventListener('pointerup', onPointerUp)
    canvas.addEventListener('pointercancel', onPointerUp)
    canvas.addEventListener('dblclick', onDoubleClick)
    canvas.addEventListener('wheel', onWheel, { passive: false })
    canvas.addEventListener('touchstart', onTouchStart, { passive: true })
    canvas.addEventListener('touchmove', onTouchMove, { passive: false })
    this.detach.push(() => {
      canvas.removeEventListener('pointerdown', onPointerDown)
      canvas.removeEventListener('pointermove', onPointerMove)
      canvas.removeEventListener('pointerup', onPointerUp)
      canvas.removeEventListener('pointercancel', onPointerUp)
      canvas.removeEventListener('dblclick', onDoubleClick)
      canvas.removeEventListener('wheel', onWheel)
      canvas.removeEventListener('touchstart', onTouchStart)
      canvas.removeEventListener('touchmove', onTouchMove)
    })
  }

  /* ------------------------------ 相机 ------------------------------ */

  private effectiveCamera(): Camera3D {
    return {
      ...this.camera,
      yaw: this.camera.yaw + this.gyroYaw,
      pitch: this.camera.pitch + this.gyroPitch,
    }
  }

  /** 电影镜头推近到某个节点 */
  focusOn(index: number) {
    const node = this.nodes[index]
    if (!node) return
    this.focused = index
    this.focusFrom = { ...this.effectiveCamera() }
    this.focusTo = cinematicTarget(this.focusFrom, node.x, node.y, node.z, FOCUS_DISTANCE)
    this.focusStartedAt = performance.now()
    this.callbacks.onFocusChange?.(index)
  }

  /** 退回全景 */
  resetView() {
    this.focused = null
    this.selectedIndex = null
    this.focusFrom = { ...this.effectiveCamera() }
    this.focusTo = { ...this.homeCamera }
    this.focusStartedAt = performance.now()
    this.callbacks.onFocusChange?.(null)
    this.callbacks.onSelect?.(null)
  }

  /**
   * 陀螺仪 / 设备方向 → 视差偏移。
   *
   * 「光栅画」那种随视角晃动的立体感就是这么来的：**只加一点点**（±0.12 弧度，约 7°），
   * 加多了会跟用户自己的拖动打架，也容易晕。
   */
  setOrientation(beta: number | null, gamma: number | null) {
    if (beta === null || gamma === null) return
    this.gyroEnabled = true
    const limit = 0.12
    this.gyroTargetYaw = Math.max(-limit, Math.min(limit, (gamma / 45) * limit))
    this.gyroTargetPitch = Math.max(-limit, Math.min(limit, ((beta - 45) / 45) * limit))
  }

  get isFocused() {
    return this.focused !== null
  }

  get focusedIndex() {
    return this.focused
  }

  /* ------------------------------ 尺寸 / 循环 ------------------------------ */

  resize() {
    const rect = this.canvas.getBoundingClientRect()
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const width = Math.max(1, Math.round(rect.width * dpr))
    const height = Math.max(1, Math.round(rect.height * dpr))
    if (width === this.width && height === this.height && dpr === this.dpr) return
    this.width = width
    this.height = height
    this.dpr = dpr
    this.canvas.width = width
    this.canvas.height = height
  }

  start() {
    if (this.frame) return
    this.lastFrameAt = performance.now()
    const loop = () => {
      this.frame = requestAnimationFrame(loop)
      const now = performance.now()
      const delta = Math.min(0.05, (now - this.lastFrameAt) / 1000)
      this.lastFrameAt = now
      this.step(delta, now)
      this.render()
    }
    this.frame = requestAnimationFrame(loop)
  }

  private step(delta: number, now: number) {
    /* 陀螺仪偏移平滑跟随，避免传感器噪声让画面抖 */
    this.gyroYaw += (this.gyroTargetYaw - this.gyroYaw) * Math.min(1, delta * 6)
    this.gyroPitch += (this.gyroTargetPitch - this.gyroPitch) * Math.min(1, delta * 6)

    if (this.focusFrom && this.focusTo) {
      const t = (now - this.focusStartedAt) / FOCUS_DURATION_MS
      if (t >= 1) {
        this.camera = { ...this.focusTo }
        this.focusFrom = null
        this.focusTo = null
        /*
         * 只有"推近到某张图"才通知。回全景也是一段动画，但它会把 focused 置空 ——
         * 不区分的话每次点「回到全景」都会顺手弹一次大图。
         */
        if (this.focused !== null) this.callbacks.onFocusSettled?.(this.focused)
      } else {
        this.camera = lerpCamera(this.focusFrom, this.focusTo, t)
      }
      this.reportView()
      return
    }

    /*
     * 默认缓慢自转。三种情况停：
     *   · 用户正在拖动（跟手优先）
     *   · 已经推近到某张图（定格看图，再转就糊了）
     *   · 相机还在飞向目标（交给插值）
     */
    if (!this.dragging && this.focused === null) {
      this.camera = autoSpin(this.camera, delta)
    }
    this.reportView()
  }

  /** 相机状态上报（每 6 帧一次就够）：标签分级显示要用它算「放大了多少倍」 */
  private reportView() {
    if (++this.viewTick % 6 !== 0) return
    this.callbacks.onView?.({ distance: this.camera.distance, yaw: this.camera.yaw, focused: this.focused !== null })
  }

  private render() {
    const gl = this.gl
    if (this.contextLost || !this.width || !this.height) return
    const camera = this.effectiveCamera()
    gl.viewport(0, 0, this.width, this.height)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.enable(gl.BLEND)

    this.projected = this.nodes.map((node) => {
      const point = projectPoint(node.x, node.y, node.z, camera, this.width, this.height)
      return {
        screenX: point.x,
        screenY: point.y,
        depth: point.depth,
        scale: point.scale,
        radiusPx: (node.degree > 0 ? 0.011 + Math.min(node.degree, 24) * 0.0007 : 0.011) * point.scale,
      }
    })

    this.drawEdges(camera)
    this.drawThumbnails(camera)
    this.drawPoints(camera)
  }

  private applyCameraUniforms(program: WebGLProgram, camera: Camera3D) {
    const gl = this.gl
    gl.uniform2f(gl.getUniformLocation(program, 'uResolution'), this.width, this.height)
    gl.uniform3f(gl.getUniformLocation(program, 'uTarget'), camera.targetX, camera.targetY, camera.targetZ)
    gl.uniform1f(gl.getUniformLocation(program, 'uYaw'), camera.yaw)
    gl.uniform1f(gl.getUniformLocation(program, 'uPitch'), camera.pitch)
    gl.uniform1f(gl.getUniformLocation(program, 'uDistance'), camera.distance)
    gl.uniform1f(gl.getUniformLocation(program, 'uFocal'), FOCAL)
    gl.uniform1f(gl.getUniformLocation(program, 'uViewMin'), Math.min(this.width, this.height))
  }

  private attach(program: WebGLProgram, entries: Array<{ name: string; size: number; offset: number }>, stride: number) {
    const gl = this.gl
    for (const entry of entries) {
      const location = gl.getAttribLocation(program, entry.name)
      if (location < 0) continue
      gl.enableVertexAttribArray(location)
      gl.vertexAttribPointer(location, entry.size, gl.FLOAT, false, stride, entry.offset)
    }
  }

  private resetAttributes() {
    const gl = this.gl
    const max = gl.getParameter(gl.MAX_VERTEX_ATTRIBS) as number
    for (let i = 0; i < max; i++) gl.disableVertexAttribArray(i)
  }

  private drawEdges(camera: Camera3D) {
    const gl = this.gl
    const program = this.edgeProgram
    if (!program || !this.edges.length || !this.edgeBuffer) return

    const focus = this.focused ?? this.hoverIndex ?? this.selectedIndex
    let write = 0
    for (const edge of this.edges) {
      const a = this.projected[edge.a]
      const b = this.projected[edge.b]
      if (!a || !b || a.depth <= 0.06 || b.depth <= 0.06) continue
      if (a.depth > FAR_CULL && b.depth > FAR_CULL) continue
      let alpha = edge.score * 0.55 * depthAlpha(Math.min(a.depth, b.depth), 0, 3.2, 0.05)
      if (focus !== null) alpha = (edge.a === focus || edge.b === focus) ? alpha * 1.5 : alpha * 0.18
      const nodeA = this.nodes[edge.a]
      const nodeB = this.nodes[edge.b]
      this.edgeData[write++] = nodeA.x
      this.edgeData[write++] = nodeA.y
      this.edgeData[write++] = nodeA.z
      this.edgeData[write++] = alpha
      this.edgeData[write++] = 0
      this.edgeData[write++] = nodeB.x
      this.edgeData[write++] = nodeB.y
      this.edgeData[write++] = nodeB.z
      this.edgeData[write++] = alpha
      this.edgeData[write++] = 0
    }
    if (!write) return

    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.uniform3f(gl.getUniformLocation(program, 'uLineColor'), 0.45, 0.68, 1.0)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.edgeData.subarray(0, write), gl.DYNAMIC_DRAW)
    this.attach(program, [
      { name: 'aPos', size: 3, offset: 0 },
      { name: 'aMeta', size: 2, offset: 3 * 4 },
    ], 5 * 4)
    gl.drawArrays(gl.LINES, 0, write / 5)
  }

  private drawPoints(camera: Camera3D) {
    const gl = this.gl
    const program = this.pointProgram
    if (!program || !this.pointBuffer) return

    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i]
      const point = this.projected[i]
      const base = i * 8
      this.pointData[base] = node.x
      this.pointData[base + 1] = node.y
      this.pointData[base + 2] = node.z
      this.pointData[base + 3] = node.color[0]
      this.pointData[base + 4] = node.color[1]
      this.pointData[base + 5] = node.color[2]
      this.pointData[base + 6] = node.degree > 0 ? 0.011 + Math.min(node.degree, 24) * 0.0007 : 0.011
      let alpha = depthAlpha(point.depth, 0.1, 3.6, 0.12)
      const focus = this.focused ?? this.hoverIndex ?? this.selectedIndex
      if (focus !== null && focus !== i) alpha *= 0.35
      if (this.focused === i) alpha = 1
      this.pointData[base + 7] = alpha
    }

    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.pointData, gl.DYNAMIC_DRAW)
    this.attach(program, [
      { name: 'aPos', size: 3, offset: 0 },
      { name: 'aColor', size: 3, offset: 3 * 4 },
      { name: 'aMeta', size: 2, offset: 6 * 4 },
    ], 8 * 4)
    gl.drawArrays(gl.POINTS, 0, this.nodes.length)
  }

  private drawThumbnails(camera: Camera3D) {
    const gl = this.gl
    const program = this.thumbProgram
    if (!program || !this.thumbBuffer) return

    /*
     * 缩略图什么时候出现：相机拉近到一定程度才显示。
     * 全景时一两百张图糊在一起只会变成马赛克，还不如干净的星点。
     */
    const closeness = Math.max(0, Math.min(1, (this.homeCamera.distance * 0.86 - camera.distance) / (this.homeCamera.distance * 0.5)))
    if (closeness <= 0.02) return

    /* 远→近排序，画家算法；同时挑出屏幕内的，按离屏心距离截断 */
    const order = this.projected
      .map((point, index) => ({ index, point }))
      .filter(({ point }) => point.depth > 0.06 && point.depth < FAR_CULL
        && point.screenX > -THUMB_MAX_PX && point.screenX < this.width + THUMB_MAX_PX
        && point.screenY > -THUMB_MAX_PX && point.screenY < this.height + THUMB_MAX_PX)
      .sort((a, b) => b.point.depth - a.point.depth)

    let drawn = 0
    gl.useProgram(program)
    this.resetElements(program, camera)
    gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.thumbBuffer)

    for (const entry of order) {
      if (drawn >= MAX_THUMBS_PER_FRAME) break
      const node = this.nodes[entry.index]
      const point = entry.point
      /* 屏幕长边：随透视缩放，并且离得越近越大；封顶免得一张图糊满屏 */
      const longPx = Math.min(0.085 * point.scale, THUMB_MAX_PX)
      if (longPx < 26) continue
      const level = longPx > THUMB_LARGE_AT ? 1 : 0
      const texture = this.textureFor(entry.index, level, 1e7 - point.depth)
      if (!texture) continue

      const aspect = node.aspect > 0.05 ? node.aspect : 1
      const widthPx = aspect >= 1 ? longPx : longPx * aspect
      const heightPx = aspect >= 1 ? longPx / aspect : longPx
      let alpha = closeness * depthAlpha(point.depth, 0.1, 3.4, 0.25)
      const focus = this.focused ?? this.selectedIndex
      if (focus !== null && focus !== entry.index) alpha *= this.focused !== null ? 0.22 : 0.5
      if (alpha <= 0.03) continue

      const corners = [-1, -1, 1, -1, -1, 1, 1, 1]
      for (let corner = 0; corner < 4; corner++) {
        const base = corner * 12
        this.thumbData[base] = node.x
        this.thumbData[base + 1] = node.y
        this.thumbData[base + 2] = node.z
        this.thumbData[base + 3] = corners[corner * 2]
        this.thumbData[base + 4] = corners[corner * 2 + 1]
        this.thumbData[base + 5] = corners[corner * 2] < 0 ? 0 : 1
        /* 上传时做了 UNPACK_FLIP_Y_WEBGL，v=0 是图片顶部 */
        this.thumbData[base + 6] = corners[corner * 2 + 1] < 0 ? 0 : 1
        this.thumbData[base + 7] = node.color[0] * 0.35 + 0.65
        this.thumbData[base + 8] = node.color[1] * 0.35 + 0.65
        this.thumbData[base + 9] = node.color[2] * 0.35 + 0.65
        this.thumbData[base + 10] = alpha
        this.thumbData[base + 11] = 0
      }
      /* 尺寸是 uniform，四个角一样大，所以每张图只设一次 */
      gl.bufferData(gl.ARRAY_BUFFER, this.thumbData, gl.DYNAMIC_DRAW)
      gl.uniform2f(gl.getUniformLocation(program, 'uSizePx'), widthPx, heightPx)
      gl.uniform1i(gl.getUniformLocation(program, 'uTexture'), 0)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, texture)
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      drawn++
    }
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE)
    if (drawn > 0) this.evictTextures()
  }

  private resetElements(program: WebGLProgram, camera: Camera3D) {
    const gl = this.gl
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    /* 圆角羽化：一个设备像素对应多少 UV */
    gl.uniform2f(gl.getUniformLocation(program, 'uFeather'), 2 / Math.max(8, 160), 2 / Math.max(8, 160))
    this.attach(program, [
      { name: 'aCenter', size: 3, offset: 0 },
      { name: 'aCorner', size: 2, offset: 3 * 4 },
      { name: 'aUv', size: 2, offset: 5 * 4 },
      { name: 'aTint', size: 4, offset: 7 * 4 },
    ], 12 * 4)
  }

  /* ------------------------------ 纹理 ------------------------------ */

  private textureKey(index: number, level: number) {
    return `${index}:${level}`
  }

  private textureFor(index: number, level: number, priority: number): WebGLTexture | null {
    const key = this.textureKey(index, level)
    let entry = this.textures.get(key)
    if (!entry) {
      entry = { texture: null, image: null, state: 'idle', lastUsed: performance.now(), aspect: 1, level }
      this.textures.set(key, entry)
    }
    entry.lastUsed = performance.now()
    if (entry.state === 'ready') return entry.texture
    if (entry.state === 'idle') {
      entry.state = 'loading'
      this.queue.push({ index, level, priority })
      this.queue.sort((a, b) => b.priority - a.priority)
      this.pump()
    }
    /* 高分图还没到就先用小图顶上 */
    if (level === 1) {
      const fallback = this.textures.get(this.textureKey(index, 0))
      if (fallback?.state === 'ready') return fallback.texture
    }
    return null
  }

  private pump() {
    while (this.loading < LOAD_CONCURRENCY && this.queue.length) {
      const job = this.queue.shift()!
      const node = this.nodes[job.index]
      const url = job.level === 1 ? (node.thumbLarge || node.thumb) : node.thumb
      const key = this.textureKey(job.index, job.level)
      const entry = this.textures.get(key)
      if (!entry || !url) continue
      this.loading++
      const image = new Image()
      image.crossOrigin = 'anonymous'
      entry.image = image
      image.onload = () => {
        entry.state = 'ready'
        entry.aspect = image.naturalWidth && image.naturalHeight ? image.naturalWidth / image.naturalHeight : 1
        if (entry.texture) this.gl.deleteTexture(entry.texture)
        entry.texture = this.upload(image)
        this.loading--
        this.pump()
      }
      image.onerror = () => {
        entry.state = 'failed'
        this.loading--
        this.pump()
      }
      image.src = url
    }
  }

  private upload(image: HTMLImageElement): WebGLTexture | null {
    const gl = this.gl
    const texture = gl.createTexture()
    if (!texture) return null
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, 1)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    return texture
  }

  private evictTextures() {
    if (this.textures.size <= MAX_TEXTURES) return
    const ready = [...this.textures.entries()]
      .filter(([, entry]) => entry.state === 'ready')
      .sort((a, b) => a[1].lastUsed - b[1].lastUsed)
    let remove = this.textures.size - MAX_TEXTURES
    for (const [key, entry] of ready) {
      if (remove-- <= 0) break
      if (entry.texture) this.gl.deleteTexture(entry.texture)
      this.textures.delete(key)
    }
  }

  /* ------------------------------ 命中 ------------------------------ */

  private pickAt(deviceX: number, deviceY: number): number | null {
    if (!this.projected.length) return null
    const index = pick3(this.projected, deviceX, deviceY, PICK_MIN_RADIUS * this.dpr)
    return index < 0 ? null : index
  }

  /** 画布上的 CSS 坐标 → 某个节点的屏幕位置（给 DOM 标签用） */
  screenPositionOf(index: number) {
    const point = this.projected[index]
    if (!point) return null
    return { x: point.screenX / this.dpr, y: point.screenY / this.dpr, depth: point.depth }
  }

  /**
   * 任意世界坐标 → 画布内的 CSS 像素位置（给星云名称用）。
   * 用**当前生效的相机**（含陀螺仪偏移），否则标签会和画面错开一点点。
   */
  projectWorld(x: number, y: number, z: number) {
    const point = projectPoint(x, y, z, this.effectiveCamera(), this.width, this.height)
    return { x: point.x / this.dpr, y: point.y / this.dpr, depth: point.depth }
  }

  /** 全景时的相机距离：用来算「放大了多少倍」，标签分级显示要它 */
  get home() {
    return this.homeCamera.distance
  }

  destroy() {
    this.contextLost = true
    if (this.frame) cancelAnimationFrame(this.frame)
    this.frame = 0
    for (const off of this.detach) off()
    this.detach = []
    const gl = this.gl
    for (const entry of this.textures.values()) if (entry.texture) gl.deleteTexture(entry.texture)
    this.textures.clear()
    this.queue = []
    for (const buffer of [this.pointBuffer, this.edgeBuffer, this.thumbBuffer]) if (buffer) gl.deleteBuffer(buffer)
    for (const program of [this.pointProgram, this.edgeProgram, this.thumbProgram]) if (program) gl.deleteProgram(program)
    try { gl.getExtension('WEBGL_lose_context')?.loseContext() } catch { /* 有些实现没这个扩展 */ }
  }
}

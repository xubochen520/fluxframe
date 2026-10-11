/**
 * 相似图「星系图」的 WebGL 渲染引擎。
 *
 * 参考 PixPlot 的分工：点的位置由服务端算好（见 server/src/layout.ts），
 * 浏览器这边只做一件事——把成百上千个点画得又好看又跟手。
 *
 * 【为什么必须用 WebGL】DOM / SVG 路线在这个场景是死路：每个点一个元素，几百个就开始掉帧，
 * 缩放平移时浏览器要重排重绘整棵树。WebGL 里每张图就是一个顶点，一次 drawArrays 全画完；
 * 缩略图更省——只有真的放大到看得见时才去加载纹理，和 PixPlot 的做法一致。
 *
 * 【五层画面】从后往前：
 *   1. 星云底：一次性生成的噪声纹理，按「远处世界坐标」采样，跟着镜头慢慢挪（远景视差）
 *   2. 星尘：几百个暗淡小点，视差 0.35，制造景深
 *   3. 相似边：相似度越高越亮的连线，加色混合
 *   4. 图片点：加色混合的柔光点，颜色来自主角色
 *   5. 缩略图：放大到一定程度后每个点淡入成一张圆角缩略图，点本身同时淡出
 *
 * 【四个踩过的坑，都在代码里标了】
 *   · 加色混合要自己预乘 alpha 并用 (ONE, ONE)，用 (SRC_ALPHA, ONE) 会把 alpha 再乘一遍，
 *     整片糊成白色。
 *   · 视差不是「把坐标乘个系数」：要让某层看起来更远，得让它的世界坐标按 parallax 缩放，
 *     也就是采样时用 uCenter / parallax。直接乘坐标是无效的（推导见 backdropUniforms）。
 *   · 圆角抗锯齿不能用 fwidth：WebGL1 下需要 OES_standard_derivatives 扩展，缺了会编译失败。
 *     改成用「一个屏幕像素对应多少片内坐标」的 uniform，哪都能跑。
 *   · 缩略图这一层要从加色混合切回普通 alpha 混合，否则图片叠在一起会互相烧白。
 */
import {
  ACCENT_GLSL,
  CARD_FRAG,
  DUST_FRAG,
  HALO_FRAG,
  RING_FRAG,
  STAGE_GLSL,
  createGlowSprite,
  createHazeCanvas,
  createLabelAtlas,
  createStageStyle,
  isLowEndDevice,
  readStageStyle,
  sortIndicesByKey,
  stageBase,
  uploadCanvasTexture,
  watchStageStyle,
  type LabelAtlas,
} from './render-sprites'

export interface GalaxyNode {
  /** 图片 id */
  id: string
  name: string
  /** 低分辨率缩略图（320） */
  thumb: string
  /** 放大后换用的高分图（768） */
  thumbLarge: string
  x: number
  y: number
  /** 线性空间的 0~1 RGB */
  color: [number, number, number]
  character: string
  /** 相似图数量，决定点画多大 */
  degree: number
  /**
   * 原图长宽比（宽/高）。缩略图按它定形状 —— 不裁成正方形，也不拉伸。
   * 二次元插画大半是 2:3 的竖图，居中裁成正方形会切掉三分之一的构图。
   */
  aspect: number
}

/** 索引指向 nodes 数组下标 */
export interface GalaxyEdge { a: number; b: number; score: number }

export interface GalaxyCallbacks {
  onHover?: (index: number | null, clientX: number, clientY: number) => void
  onSelect?: (index: number | null) => void
  onOpen?: (index: number) => void
  onView?: (zoom: number, thumbAlpha: number) => void
  /** 每帧绘制完回调一次：DOM 层的星云标签靠它跟着镜头走 */
  onCamera?: () => void
}

/** 点光晕的世界半径；屏幕半径 = 这个值 × zoom */
const POINT_RADIUS = 0.012
/** 缩略图的**长边**世界尺寸；屏幕长边 = 这个值 × zoom */
export const THUMB_SIZE = 0.042
/** 缩略图开始淡入 / 完全显示的屏幕边长（设备像素） */
const THUMB_FADE_FROM = 30
const THUMB_FADE_TO = 62
/** 缩略图超过这个屏幕边长就换高分图（/variant/768） */
const THUMB_LARGE_AT = 120
/**
 * 缩略图的半宽/半高：**长边**固定为 [longExtent]，短边按原比例。
 *
 * 不做成正方形。库里 128 张图的实际比例在 0.47~2.02 之间、大半是 2:3 的竖图，
 * 居中裁成正方形要切掉三分之一的构图，拉伸就更难看。保持原比例，竖图就是竖着的格子。
 *
 * 屏幕坐标和世界坐标都能用：传进来的 [longExtent] 是哪个空间的，出来的就是哪个空间的。
 */
export function thumbHalfExtent(aspect: number, longExtent: number) {
  const ratio = Number.isFinite(aspect) && aspect > 0.05 ? aspect : 1
  return ratio >= 1
    ? { halfW: longExtent / 2, halfH: longExtent / (2 * ratio) }
    : { halfW: (longExtent * ratio) / 2, halfH: longExtent / 2 }
}

/**
 * 缩略图的屏幕边长上限（设备像素）。
 * 不设上限会出事：继续放大时一张缩略图能铺满半个画布（实测 68× 时边长 800px），
 * 图反而看不清了。所以超过上限就让世界尺寸随 zoom 反比缩小——表现为「图不再变大，
 * 但彼此越拉越开」，跟地图上标签恒定字号是一个道理。
 *
 * 这里封的是**长边**。
 */
const THUMB_MAX_PX = 210
/** 单帧最多画多少张缩略图：每张一次 draw call，不控数量会在中景掉帧 */
const MAX_THUMBS_PER_FRAME = 180
/** 纹理缓存上限，超了把最久没用的交还驱动 */
const MAX_TEXTURES = 360
/** 一次并发加载多少张缩略图 */
const LOAD_CONCURRENCY = 6
/** 命中判定的屏幕半径（CSS 像素） */
const PICK_RADIUS = 18
/** 拖动超过这个距离就算平移，不算点击 */
const CLICK_SLOP = 4
/** 相对「刚好装下」最多能放大多少倍 */
const MAX_ZOOM_FACTOR = 60

const DUST_COUNT = 900
/** 星尘铺开的范围（节点世界单位）。每颗的视差按深度在 0.22~0.5 之间 */
const DUST_SPREAD = 3.4
/** 星云的视差系数：越小看起来越远 */
const NEBULA_PARALLAX = 0.1
/** 节点多于这个数时只给最「中心」的这么多个点画光晕（与缩略图的单帧预算同量级） */
const HALO_BUDGET = MAX_THUMBS_PER_FRAME
/** 光晕直径 / 点直径 */
const HALO_SCALE = 3.4
const RING_SCALE = 3.2
const PULSE_PERIOD_MS = 1700
/** 只为脉冲重画时限到约 30fps */
const PULSE_FRAME_MS = 32
/** 卡片外圈留给投影 / 选中外发光的像素（CSS 像素） */
const CARD_MARGIN = 6
const CARD_SELECTED_MARGIN = 18

const POINT_LAYOUT = [
  { name: 'aPos', size: 2, offset: 0 },
  { name: 'aColor', size: 3, offset: 2 * 4 },
  { name: 'aMeta', size: 2, offset: 5 * 4 },
]
const EDGE_LAYOUT = [
  { name: 'aPos', size: 2, offset: 0 },
  { name: 'aColor', size: 3, offset: 2 * 4 },
]
const DUST_LAYOUT = [
  { name: 'aPos', size: 2, offset: 0 },
  { name: 'aMeta', size: 3, offset: 2 * 4 },
]
const THUMB_LAYOUT = [
  { name: 'aPos', size: 2, offset: 0 },
  { name: 'aLocal', size: 2, offset: 2 * 4 },
  { name: 'aTint', size: 4, offset: 4 * 4 },
]
const CORNERS = [-1, -1, 1, -1, -1, 1, 1, 1]

type GL = WebGLRenderingContext | WebGL2RenderingContext

interface TextureEntry {
  texture: WebGLTexture | null
  image: HTMLImageElement | null
  state: 'loading' | 'ready' | 'error'
  lastUsed: number
  aspect: number
}

function compile(gl: GL, type: number, source: string) {
  const shader = gl.createShader(type)!
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader)
    gl.deleteShader(shader)
    throw new Error(`着色器编译失败：${log}`)
  }
  return shader
}

function link(gl: GL, vertexSource: string, fragmentSource: string) {
  const program = gl.createProgram()!
  const vertex = compile(gl, gl.VERTEX_SHADER, vertexSource)
  const fragment = compile(gl, gl.FRAGMENT_SHADER, fragmentSource)
  gl.attachShader(program, vertex)
  gl.attachShader(program, fragment)
  gl.linkProgram(program)
  gl.deleteShader(vertex)
  gl.deleteShader(fragment)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program)
    gl.deleteProgram(program)
    throw new Error(`着色器链接失败：${log}`)
  }
  return program
}

/* ---------------- 着色器 ---------------- */

/** 所有图元共用的相机变换：世界坐标 → 裁剪空间 */
const CAMERA_VERT = `
precision highp float;
uniform vec2 uResolution;
uniform vec2 uCenter;
uniform float uZoom;
vec4 project(vec2 world) {
  vec2 device = (world - uCenter) * uZoom + uResolution * 0.5;
  return vec4(device / uResolution * 2.0 - 1.0, 0.0, 1.0);
}
`

const POINT_VERT = `${CAMERA_VERT}
attribute vec2 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;   // x: 世界半径, y: 透明度
varying vec3 vColor;
varying float vAlpha;
void main() {
  gl_Position = project(aPos);
  /* 驱动对 gl_PointSize 的上限各不相同（有的只到 63），一律 clamp 到 240 设备像素 */
  gl_PointSize = clamp(aMeta.x * uZoom * 2.0, 1.0, 240.0);
  vColor = aColor;
  vAlpha = aMeta.y;
}
`

const POINT_FRAG = `
precision mediump float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  vec2 uv = gl_PointCoord * 2.0 - 1.0;
  float r = length(uv);
  if (r > 1.0) discard;
  /* 中心一个亮核 + 外面一圈柔和光晕：加色混合下自然形成「星点」 */
  float core = smoothstep(0.22, 0.0, r);
  float halo = pow(max(0.0, 1.0 - r), 2.2) * 0.26;
  float alpha = (core * 0.85 + halo) * vAlpha;
  gl_FragColor = vec4(vColor * alpha * (0.75 + core * 0.9), alpha);
}
`

const EDGE_VERT = `${CAMERA_VERT}
attribute vec2 aPos;
attribute vec3 aColor;
attribute float aAlpha;
varying vec3 vColor;
varying float vAlpha;
void main() {
  gl_Position = project(aPos);
  vColor = aColor;
  vAlpha = aAlpha;
}
`

const EDGE_FRAG = `
precision mediump float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  gl_FragColor = vec4(vColor * vAlpha, vAlpha);
}
`

const THUMB_VERT = `${CAMERA_VERT}
attribute vec2 aPos;
attribute vec2 aLocal;   // -1..1 的片内坐标，可超出 ±1（外圈画投影与选中光）
attribute vec4 aTint;    // rgb + 透明度
varying vec2 vLocal;
varying vec4 vTint;
void main() {
  gl_Position = project(aPos);
  vLocal = aLocal;
  vTint = aTint;
}
`

const THUMB_FRAG = CARD_FRAG

/** 星尘：每颗有自己的深度，深度决定视差、大小、亮度和偏白的程度 */
const DUST_VERT = `${CAMERA_VERT}
${ACCENT_GLSL}
uniform float uPixel;
attribute vec2 aPos;    // -1~1，乘 DUST_SPREAD 是世界坐标
attribute vec3 aMeta;   // x: 深度 0远~1近, y: 取色位置, z: 随机
varying vec3 vColor;
varying float vAlpha;
void main() {
  float parallax = mix(0.22, 0.5, aMeta.x);
  vec2 device = (aPos * ${DUST_SPREAD.toFixed(2)} - uCenter * parallax) * uZoom + uResolution * 0.5;
  gl_Position = vec4(device / uResolution * 2.0 - 1.0, 0.0, 1.0);
  gl_PointSize = (0.9 + aMeta.x * 1.6 + aMeta.z * 0.6) * uPixel;
  vColor = mix(accentAt(aMeta.y), vec3(0.9, 0.93, 1.0), aMeta.x * 0.4);
  vAlpha = (0.1 + aMeta.x * 0.42) * (0.6 + aMeta.z * 0.4);
}
`

const HALO_VERT = `${CAMERA_VERT}
uniform float uScale;
uniform float uMaxSize;
attribute vec2 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;
varying vec3 vColor;
varying float vAlpha;
void main() {
  gl_Position = project(aPos);
  gl_PointSize = clamp(aMeta.x * uZoom * 2.0 * uScale, 2.0, uMaxSize);
  vColor = aColor;
  vAlpha = aMeta.y;
}
`

const RING_VERT = `${CAMERA_VERT}
uniform float uScale;
uniform float uMaxSize;
uniform float uLine;
attribute vec2 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;
varying vec3 vColor;
varying float vPixel;
void main() {
  gl_Position = project(aPos);
  float size = clamp(aMeta.x * uZoom * 2.0 * uScale, 30.0 * uLine, uMaxSize);
  gl_PointSize = size;
  vColor = aColor;
  vPixel = 2.0 * uLine / size;
}
`

/**
 * 背景层要单独写一套：它没有世界坐标可变换，只在片元里按当前镜头反推「每个像素属于哪一层」。
 *
 * 【为什么顶点着色器里不声明 uResolution】ANGLE 对同一 uniform 在顶点/片元两个阶段
 * 的精度极敏感：顶点默认 highp、片元默认 mediump，同名 uniform 精度不一致会直接链接失败
 * （报 "Precisions of uniform uResolution differ between VERTEX and FRAGMENT shaders"）。
 * 顶点阶段干脆不算，把 -1~1 的裁剪坐标原样传给片元，让 uResolution 只存在于片元着色器。
 */
const BACKDROP_VERT = `
attribute vec2 aPos;
varying vec2 vNdc;
void main() {
  vNdc = aPos;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
`

/**
 * 视差推导：希望星云上某个特征点 p 在屏幕上的位置是
 *     screen = (p - center) * zoom * parallax + resolution/2
 * 反过来解出 p，再拿 p 去采样纹理，就得到「更远的那一层」。
 *
 * 两个容易写错的地方（第一版两个都踩了，整屏只剩一个「十字」）：
 *   1. 不能直接把世界坐标乘 parallax——那样算出来的屏幕位移和普通物体一模一样，没有景深。
 *   2. 解出来的 p 是「这一层的坐标」，比节点层的世界坐标大 1/parallax 倍。采样前必须乘回
 *      parallax 才是节点层的世界尺度，否则 uv 会跑到纹理外面被 CLAMP 成一片纯色。
 */
const BACKDROP_FRAG = `
precision highp float;
uniform vec2 uResolution;
uniform vec2 uCenter;
uniform float uZoom;
${STAGE_GLSL}
varying vec2 vNdc;
vec2 layerUv(float parallax, float worldPerTile, vec2 offset) {
  vec2 device = (vNdc * 0.5 + 0.5) * uResolution;
  vec2 offsetFromCentre = device - uResolution * 0.5;
  vec2 feature = offsetFromCentre / (uZoom * parallax) + uCenter;
  return feature * parallax / worldPerTile + offset;
}
void main() {
  /* 远层：一块纹理铺 5 个世界单位，视差 0.1，几乎不跟着镜头动 */
  vec4 far = texture2D(uTexture, layerUv(${NEBULA_PARALLAX}, 5.0, vec2(0.5)));
  /* 近层：铺 2.4 个世界单位，视差 0.26，只在画面中间露一点 */
  vec4 near = texture2D(uTexture, layerUv(0.26, 2.4, vec2(0.13, 0.62)));
  gl_FragColor = vec4(stage(vNdc, far, near), 1.0);
}
`

/* ---------------- 引擎 ---------------- */

export class GalaxyEngine {
  private canvas: HTMLCanvasElement
  private gl: GL
  private nodes: GalaxyNode[]
  private edges: GalaxyEdge[]
  private callbacks: GalaxyCallbacks

  private pointProgram: WebGLProgram
  private edgeProgram: WebGLProgram
  private thumbProgram: WebGLProgram
  private backdropProgram: WebGLProgram
  private dustProgram: WebGLProgram
  private haloProgram: WebGLProgram
  private ringProgram: WebGLProgram

  private pointBuffer: WebGLBuffer
  private edgeBuffer: WebGLBuffer
  private edgeAlphaBuffer: WebGLBuffer
  private thumbBuffer: WebGLBuffer
  private dustBuffer: WebGLBuffer
  private quadBuffer: WebGLBuffer
  private haloIndexBuffer: WebGLBuffer
  private backdropTexture: WebGLTexture
  private thumbTexture: WebGLTexture
  private spriteTexture: WebGLTexture
  private atlasTexture: WebGLTexture

  private pointData: Float32Array
  private edgeData: Float32Array
  private edgeAlpha: Float32Array
  private thumbData = new Float32Array(4 * 8)

  private style = createStageStyle()
  private lowEnd = isLowEndDevice()
  private maxPointSize = 64
  /** 画光晕的点数；小于节点数时按索引缓冲只画相似关系最强的那些 */
  private haloCount = 0
  private atlas: LabelAtlas | null = null
  private nodeLabels: Array<Float32Array | null> = []
  /** 纹理缓存的 key 预先拼好，帧内不再拼字符串 */
  private textureKeys: string[] = []
  /** 缩略图绘制顺序（离屏心由近到远），跨帧复用、原地排序 */
  private thumbOrder: Int32Array
  private thumbDistance: Float32Array
  private now = 0
  private lastPulseAt = 0
  private selectedThumbAlpha = 0
  private uniformCache = new Map<WebGLProgram, Map<string, WebGLUniformLocation | null>>()
  private attributeCache = new Map<WebGLProgram, Map<string, number>>()

  /** 每个节点的高亮权重 0~1（搜索、图例筛选都靠它压暗无关的点） */
  private emphasis: Float32Array
  private hoverIndex: number | null = null
  private selectedIndex: number | null = null

  private zoom = 1
  private minZoom = 1
  private maxZoom = 1
  private fitZoom = 1
  private centerX = 0
  private centerY = 0
  private targetZoom = 1
  private targetCenterX = 0
  private targetCenterY = 0
  private animating = false

  private dpr = 1
  private width = 1
  private height = 1
  private dirty = true
  private contextLost = false
  private frame = 0
  private thumbAlpha = 0

  private textures = new Map<string, TextureEntry>()
  private queue: Array<{ key: string; url: string; priority: number }> = []
  private loading = 0

  private pointers = new Map<number, { x: number; y: number }>()
  private dragStart: { x: number; y: number; centerX: number; centerY: number; moved: boolean } | null = null
  /** 双指捏合：只记上一次的两指距离，每帧用增量比值缩放（锚点取两指中点） */
  private pinchStart: { distance: number } | null = null

  private detach: Array<() => void> = []

  constructor(canvas: HTMLCanvasElement, nodes: GalaxyNode[], edges: GalaxyEdge[], callbacks: GalaxyCallbacks = {}) {
    this.canvas = canvas
    this.nodes = nodes
    this.edges = edges
    this.callbacks = callbacks

    const attributes: WebGLContextAttributes = {
      alpha: true,
      antialias: false,
      depth: false,
      stencil: false,
      premultipliedAlpha: true,
      powerPreference: 'high-performance',
    }
    const gl = (canvas.getContext('webgl2', attributes) || canvas.getContext('webgl', attributes)) as GL | null
    if (!gl) throw new Error('这台设备的浏览器不支持 WebGL，无法显示星系图')
    this.gl = gl

    this.pointProgram = link(gl, POINT_VERT, POINT_FRAG)
    this.edgeProgram = link(gl, EDGE_VERT, EDGE_FRAG)
    this.thumbProgram = link(gl, THUMB_VERT, THUMB_FRAG)
    this.backdropProgram = link(gl, BACKDROP_VERT, BACKDROP_FRAG)
    this.dustProgram = link(gl, DUST_VERT, DUST_FRAG)
    this.haloProgram = link(gl, HALO_VERT, HALO_FRAG)
    this.ringProgram = link(gl, RING_VERT, RING_FRAG)

    this.pointBuffer = gl.createBuffer()!
    this.edgeBuffer = gl.createBuffer()!
    this.edgeAlphaBuffer = gl.createBuffer()!
    this.thumbBuffer = gl.createBuffer()!
    this.dustBuffer = gl.createBuffer()!
    this.quadBuffer = gl.createBuffer()!
    this.haloIndexBuffer = gl.createBuffer()!
    this.backdropTexture = gl.createTexture()!
    this.thumbTexture = gl.createTexture()!
    this.spriteTexture = gl.createTexture()!
    this.atlasTexture = gl.createTexture()!
    const pointRange = gl.getParameter(gl.ALIASED_POINT_SIZE_RANGE) as Float32Array | null
    this.maxPointSize = Math.min(256, pointRange ? pointRange[1] : 64)

    this.emphasis = new Float32Array(nodes.length).fill(1)
    this.pointData = new Float32Array(nodes.length * 7)
    this.edgeData = new Float32Array(edges.length * 12)
    this.edgeAlpha = new Float32Array(edges.length * 2)
    this.thumbOrder = new Int32Array(nodes.length)
    this.thumbDistance = new Float32Array(nodes.length)
    for (let i = 0; i < nodes.length; i++) {
      this.thumbOrder[i] = i
      this.textureKeys.push(`${i}:0`, `${i}:1`)
    }
    readStageStyle(canvas, this.style)

    this.uploadStatic()
    this.uploadTexturesAndBackdrop()
    this.bindEvents()
    this.resize()
    this.resetView(true)
    this.detach.push(watchStageStyle(() => {
      if (readStageStyle(this.canvas, this.style)) this.dirty = true
    }))
    if (document.fonts && document.fonts.status !== 'loaded') {
      document.fonts.ready.then(() => {
        if (this.contextLost) return
        this.buildAtlas()
        this.dirty = true
      })
    }
    this.frame = requestAnimationFrame(this.loop)
  }

  /* ---------- 静态数据 ---------- */

  private uploadStatic() {
    const gl = this.gl
    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i]
      const base = i * 7
      this.pointData[base] = node.x
      this.pointData[base + 1] = node.y
      this.pointData[base + 2] = node.color[0]
      this.pointData[base + 3] = node.color[1]
      this.pointData[base + 4] = node.color[2]
      /* 相似图越多画得越大：一眼看出哪几张是「中心」 */
      this.pointData[base + 5] = POINT_RADIUS * (1 + Math.min(node.degree, 12) * 0.045)
      this.pointData[base + 6] = 1
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.pointData, gl.DYNAMIC_DRAW)

    for (let i = 0; i < this.edges.length; i++) {
      const edge = this.edges[i]
      const a = this.nodes[edge.a]
      const b = this.nodes[edge.b]
      const base = i * 12
      this.edgeData.set([a.x, a.y, a.color[0], a.color[1], a.color[2], 0], base)
      this.edgeData.set([b.x, b.y, b.color[0], b.color[1], b.color[2], 0], base + 6)
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.edgeData, gl.STATIC_DRAW)

    /* 缩略图每个节点一次，共用一块小缓冲反复写 */
    gl.bindBuffer(gl.ARRAY_BUFFER, this.thumbBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.thumbData.byteLength, gl.DYNAMIC_DRAW)

    const strength = new Float32Array(this.nodes.length)
    for (const edge of this.edges) {
      strength[edge.a] += edge.score
      strength[edge.b] += edge.score
    }
    this.haloCount = this.nodes.length
    if (this.nodes.length > HALO_BUDGET) {
      const top = Array.from(this.nodes.keys())
        .sort((a, b) => strength[b] - strength[a] || a - b)
        .slice(0, HALO_BUDGET)
        .filter((index) => index < 65536)
      this.haloCount = top.length
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.haloIndexBuffer)
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(top), gl.STATIC_DRAW)
    }

    this.updateEdgeAlpha()
  }

  private buildDust() {
    const data = new Float32Array(DUST_COUNT * 5)
    let seed = 0x51f3a7
    const random = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0
      return seed / 4294967296
    }
    for (let i = 0; i < DUST_COUNT; i++) {
      const base = i * 5
      data[base] = random() * 2 - 1
      data[base + 1] = random() * 2 - 1
      /* 远处的多、近处的少 */
      data[base + 2] = Math.pow(random(), 1.7)
      data[base + 3] = random()
      data[base + 4] = random()
    }
    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dustBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
  }

  private buildAtlas() {
    const atlas = createLabelAtlas(this.nodes.map((node) => node.character), this.dpr)
    this.atlas = atlas
    for (let i = 0; i < this.nodes.length; i++) this.nodeLabels[i] = atlas.rects.get(this.nodes[i].character) ?? null
    this.gl.activeTexture(this.gl.TEXTURE0)
    uploadCanvasTexture(this.gl, this.atlasTexture, atlas.canvas, false, false)
  }

  private uploadTexturesAndBackdrop() {
    const gl = this.gl
    gl.activeTexture(gl.TEXTURE0)
    uploadCanvasTexture(gl, this.backdropTexture, createHazeCanvas(), true, false)
    uploadCanvasTexture(gl, this.spriteTexture, createGlowSprite(), false, true)
    if (this.atlas) uploadCanvasTexture(gl, this.atlasTexture, this.atlas.canvas, false, false)

    gl.bindTexture(gl.TEXTURE_2D, this.thumbTexture)
    /* 1×1 的白色占位：万一某帧没绑纹理就采样，也不会得到「不完整纹理」的全黑结果 */
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]))
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)

    gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW)

    this.buildDust()
  }

  /* ---------- 事件 ---------- */

  private bindEvents() {
    const canvas = this.canvas
    const on = <K extends keyof HTMLElementEventMap>(type: K, handler: (event: HTMLElementEventMap[K]) => void, options?: AddEventListenerOptions) => {
      canvas.addEventListener(type, handler as EventListener, options)
      this.detach.push(() => canvas.removeEventListener(type, handler as EventListener, options))
    }

    on('wheel', (event) => {
      event.preventDefault()
      const rect = canvas.getBoundingClientRect()
      const factor = Math.exp(-event.deltaY * (event.deltaMode === 1 ? 0.05 : 0.0016))
      this.zoomAt(factor, event.clientX - rect.left, event.clientY - rect.top)
    }, { passive: false })

    on('pointerdown', (event) => {
      canvas.setPointerCapture(event.pointerId)
      this.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
      if (this.pointers.size === 1) {
        this.dragStart = { x: event.clientX, y: event.clientY, centerX: this.centerX, centerY: this.centerY, moved: false }
        this.animating = false
      } else if (this.pointers.size === 2) {
        const [a, b] = [...this.pointers.values()]
        this.pinchStart = { distance: Math.hypot(a.x - b.x, a.y - b.y) }
        this.dragStart = null
      }
    })

    on('pointermove', (event) => {
      if (this.pointers.has(event.pointerId)) this.pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })

      if (this.pointers.size === 2 && this.pinchStart) {
        const [a, b] = [...this.pointers.values()]
        const distance = Math.hypot(a.x - b.x, a.y - b.y)
        /* 逐帧按「本次距离 / 上次距离」增量缩放，锚点取两指中点：
           这样双指捏合是围着手指缩放，而不是围着画布中心缩放。
           pinchStart.distance 每帧更新成当前值，增量比值才不会累乘出错。 */
        if (this.pinchStart.distance > 12 && distance > 12) {
          const rect = canvas.getBoundingClientRect()
          this.zoomAt(distance / this.pinchStart.distance, (a.x + b.x) / 2 - rect.left, (a.y + b.y) / 2 - rect.top)
        }
        this.pinchStart = { distance }
        return
      }

      if (this.dragStart && this.pointers.size === 1) {
        const dx = event.clientX - this.dragStart.x
        const dy = event.clientY - this.dragStart.y
        if (Math.abs(dx) > CLICK_SLOP || Math.abs(dy) > CLICK_SLOP) this.dragStart.moved = true
        if (this.dragStart.moved) {
          /* 「抓住内容拖」：鼠标往哪边拖，星图就往哪边跟。
             注意两轴的符号是**相反**的：屏幕 x 与世界 x 同向，屏幕 y 与世界 y 反向
             （WebGL 世界 +y 朝上、鼠标 +y 朝下）。第一版把 y 也写成了减号，
             结果就是上下拖动的方向和手相反。 */
          this.centerX = this.dragStart.centerX - (dx * this.dpr) / this.zoom
          this.centerY = this.dragStart.centerY + (dy * this.dpr) / this.zoom
          this.targetCenterX = this.centerX
          this.targetCenterY = this.centerY
          this.dirty = true
        }
        return
      }

      const rect = canvas.getBoundingClientRect()
      const found = this.pick(event.clientX - rect.left, event.clientY - rect.top)
      if (found !== this.hoverIndex) {
        this.hoverIndex = found
        this.updateEdgeAlpha()
        this.dirty = true
        canvas.style.cursor = found === null ? 'grab' : 'pointer'
        this.callbacks.onHover?.(found, event.clientX, event.clientY)
      } else if (found !== null) {
        this.callbacks.onHover?.(found, event.clientX, event.clientY)
      }
    })

    const endPointer = (event: PointerEvent) => {
      const wasDragging = this.dragStart
      this.pointers.delete(event.pointerId)
      if (this.pointers.size < 2) this.pinchStart = null
      if (this.pointers.size === 0 && wasDragging) {
        this.dragStart = null
        if (!wasDragging.moved) {
          const rect = canvas.getBoundingClientRect()
          this.select(this.pick(event.clientX - rect.left, event.clientY - rect.top))
        }
      }
    }
    on('pointerup', endPointer)
    on('pointercancel', endPointer)
    on('pointerleave', () => {
      if (this.pointers.size === 0 && this.hoverIndex !== null) {
        this.hoverIndex = null
        this.updateEdgeAlpha()
        this.dirty = true
        this.callbacks.onHover?.(null, 0, 0)
      }
    })

    on('dblclick', (event) => {
      const rect = canvas.getBoundingClientRect()
      const found = this.pick(event.clientX - rect.left, event.clientY - rect.top)
      if (found !== null) {
        this.select(found)
        this.callbacks.onOpen?.(found)
      } else {
        this.zoomAt(1.8, event.clientX - rect.left, event.clientY - rect.top)
      }
    })

    const onLost = (event: Event) => {
      event.preventDefault()
      this.contextLost = true
    }
    const onRestored = () => {
      this.contextLost = false
      for (const entry of this.textures.values()) entry.texture = null
      this.textures.clear()
      this.queue = []
      this.uploadStatic()
      this.uploadTexturesAndBackdrop()
      this.dirty = true
    }
    canvas.addEventListener('webglcontextlost', onLost)
    canvas.addEventListener('webglcontextrestored', onRestored)
    this.detach.push(() => canvas.removeEventListener('webglcontextlost', onLost))
    this.detach.push(() => canvas.removeEventListener('webglcontextrestored', onRestored))
  }

  /* ---------- 相机 ---------- */

  /**
   * CSS 坐标（相对画布、y 朝下）→ 着色器里的 device 坐标（y 朝上）。
   *
   * 这个换算必须只此一处。两套 y 方向（WebGL 世界 +y 朝上、DOM/CSS +y 朝下）混用是
   * 这套代码最容易翻车的地方，而且错得很隐蔽：画面看着完全正常，只有「鼠标瞄不准」
   * 和「上下拖动反向」两个症状——因为命中判定、缩放锚点这条链路整体镜像了。
   * 之前 worldToScreen 修对了，pick/zoomAt 却还按老约定算，就踩了这个坑。
   */
  private cssToDevice(cssX: number, cssY: number) {
    return { x: cssX * this.dpr, y: this.height - cssY * this.dpr }
  }

  private clampZoom(value: number) {
    return Math.max(this.minZoom, Math.min(this.maxZoom, value))
  }

  /** 以画布上的某点（CSS 坐标）为锚点缩放：锚点底下的世界坐标保持不动 */
  private zoomAt(factor: number, cssX: number, cssY: number) {
    const device = this.cssToDevice(cssX, cssY)
    const worldX = (device.x - this.width * 0.5) / this.zoom + this.centerX
    const worldY = (device.y - this.height * 0.5) / this.zoom + this.centerY
    const next = this.clampZoom(this.zoom * factor)
    if (next === this.zoom) return
    this.zoom = next
    this.centerX = worldX - (device.x - this.width * 0.5) / next
    this.centerY = worldY - (device.y - this.height * 0.5) / next
    this.targetZoom = next
    this.targetCenterX = this.centerX
    this.targetCenterY = this.centerY
    this.animating = false
    this.dirty = true
    this.callbacks.onView?.(this.zoom, this.thumbAlpha)
  }

  zoomBy(factor: number) {
    this.zoomAt(factor, this.canvas.clientWidth / 2, this.canvas.clientHeight / 2)
  }

  /**
   * 「刚好装下整张图」的镜头。按节点实际包围盒算，而不是假设布局正好铺满 [-1,1]——
   * 归一化只保证最长边到 ±1，短边可能只有 ±0.75，照方框算会白白空掉两条边。
   */
  private frameLayout() {
    let minX = -1
    let maxX = 1
    let minY = -1
    let maxY = 1
    if (this.nodes.length) {
      minX = Infinity
      maxX = -Infinity
      minY = Infinity
      maxY = -Infinity
      for (const node of this.nodes) {
        if (node.x < minX) minX = node.x
        if (node.x > maxX) maxX = node.x
        if (node.y < minY) minY = node.y
        if (node.y > maxY) maxY = node.y
      }
    }
    const spanX = Math.max(0.25, maxX - minX)
    const spanY = Math.max(0.25, maxY - minY)
    return {
      zoom: Math.min(this.width / spanX, this.height / spanY) * 0.86,
      cx: (minX + maxX) / 2,
      cy: (minY + maxY) / 2,
    }
  }

  resetView(immediate = false) {
    const frame = this.frameLayout()
    this.fitZoom = frame.zoom
    this.minZoom = frame.zoom * 0.4
    this.maxZoom = frame.zoom * MAX_ZOOM_FACTOR
    this.targetZoom = frame.zoom
    this.targetCenterX = frame.cx
    this.targetCenterY = frame.cy
    if (immediate) {
      this.zoom = frame.zoom
      this.centerX = frame.cx
      this.centerY = frame.cy
      this.animating = false
    } else {
      this.animating = true
    }
    this.dirty = true
  }

  flyTo(worldX: number, worldY: number, zoom?: number) {
    this.targetCenterX = worldX
    this.targetCenterY = worldY
    if (zoom !== undefined) this.targetZoom = this.clampZoom(zoom)
    this.animating = true
    this.dirty = true
  }

  /** 飞到某个节点：放大到「缩略图刚好清楚」的倍数 */
  flyToNode(index: number, zoomFactor = 3.4) {
    const node = this.nodes[index]
    if (!node) return
    this.flyTo(node.x, node.y, Math.min(this.maxZoom, this.fitZoom * zoomFactor))
  }

  /** 缩略图刚好清楚所需的缩放倍数，给「放大到能看见」按钮用 */
  get thumbZoom() {
    return this.clampZoom((THUMB_FADE_TO / THUMB_SIZE))
  }

  get zoomLevel() { return this.zoom }
  get fitZoomLevel() { return this.fitZoom }

  /**
   * 世界坐标 → 屏幕坐标（CSS 像素），DOM 覆盖层（星云名称、气泡定位）用。
   * y 必须翻一次：WebGL 里世界 +y 朝上（NDC +1 在画布顶部），DOM 里 +y 朝下。
   * 漏掉这个负号，所有 DOM 标签都会以水平中线为轴上下镜像——实测「纳西妲 · 37」
   * 就跑到画布下方去了，而它那团星云其实在上方。
   */
  worldToScreen(worldX: number, worldY: number) {
    return {
      x: ((worldX - this.centerX) * this.zoom + this.width * 0.5) / this.dpr,
      y: (this.height * 0.5 - (worldY - this.centerY) * this.zoom) / this.dpr,
    }
  }

  nodeScreenPosition(index: number) {
    const node = this.nodes[index]
    if (!node) return null
    return this.worldToScreen(node.x, node.y)
  }

  /** 画布的 CSS 尺寸（worldToScreen 用的就是这套坐标） */
  get viewportSize() {
    return { width: this.width / this.dpr, height: this.height / this.dpr }
  }

  /* ---------- 命中 / 选中 / 高亮 ---------- */

  private pick(cssX: number, cssY: number): number | null {
    const device = this.cssToDevice(cssX, cssY)

    /*
     * 缩略图看得见的时候（中近景），**整张图都是点击区**。
     * 格子在屏幕上有 200~400px 见方，还按中心点 18px 判定的话，
     * 点在图上任何位置都会落空 —— 用户反馈的"不好交互"就是这个。
     * 矩形有重叠时取离点击处最近的那个中心。
     */
    if (this.thumbAlpha > 0.5) {
      const longPx = Math.min(THUMB_SIZE * this.zoom, THUMB_MAX_PX)
      let best: number | null = null
      let bestDistance = Infinity
      for (let i = 0; i < this.nodes.length; i++) {
        const node = this.nodes[i]
        const dx = (node.x - this.centerX) * this.zoom + this.width * 0.5 - device.x
        const dy = (node.y - this.centerY) * this.zoom + this.height * 0.5 - device.y
        const { halfW, halfH } = thumbHalfExtent(node.aspect, longPx)
        if (Math.abs(dx) > halfW || Math.abs(dy) > halfH) continue
        const distance = dx * dx + dy * dy
        if (distance < bestDistance) {
          bestDistance = distance
          best = i
        }
      }
      /* 命中了就用矩形判定；没命中再走下面的半径兜底（点可能与图错开一点） */
      if (best !== null) return best
    }

    const limit = PICK_RADIUS * this.dpr
    let best: number | null = null
    let bestDistance = limit * limit
    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i]
      const dx = (node.x - this.centerX) * this.zoom + this.width * 0.5 - device.x
      const dy = (node.y - this.centerY) * this.zoom + this.height * 0.5 - device.y
      const distance = dx * dx + dy * dy
      if (distance < bestDistance) {
        bestDistance = distance
        best = i
      }
    }
    return best
  }

  private select(index: number | null) {
    this.selectedIndex = index === this.selectedIndex ? null : index
    this.updateEdgeAlpha()
    this.dirty = true
    this.callbacks.onSelect?.(this.selectedIndex)
  }

  /** 外部设置选中项（不做「再点一次取消」的切换，那是鼠标点击才有的语义） */
  selectNode(index: number | null) {
    this.selectedIndex = index
    this.updateEdgeAlpha()
    this.dirty = true
    this.callbacks.onSelect?.(index)
  }

  clearSelection() { this.selectNode(null) }

  /** 外部（搜索框、图例）驱动的整体明暗：权重 0 的点会被压到几乎看不见 */
  setEmphasis(weights: Float32Array | null) {
    if (!weights) this.emphasis.fill(1)
    else for (let i = 0; i < this.emphasis.length; i++) this.emphasis[i] = i < weights.length ? weights[i] : 1
    this.updateEdgeAlpha()
    this.dirty = true
  }

  /**
   * 把「悬停 + 选中 + 搜索高亮」合成每条边的透明度。
   * 有关注点时只有连着它的边亮起来，其余压到很低——几百条边里也能一眼看出这张跟谁像。
   */
  private updateEdgeAlpha() {
    const focus = this.hoverIndex ?? this.selectedIndex
    for (let i = 0; i < this.edges.length; i++) {
      const edge = this.edges[i]
      const strength = Math.max(0, Math.min(1, (edge.score - 0.25) / 0.6))
      let alpha = 0.022 + Math.pow(strength, 1.6) * 0.17
      if (focus !== null) {
        const touches = edge.a === focus || edge.b === focus
        alpha = touches ? 0.32 + strength * 0.58 : alpha * 0.16
      }
      alpha *= 0.12 + Math.min(this.emphasis[edge.a], this.emphasis[edge.b]) * 0.88
      this.edgeAlpha[i * 2] = alpha
      this.edgeAlpha[i * 2 + 1] = alpha
    }
    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeAlphaBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.edgeAlpha, gl.DYNAMIC_DRAW)
  }

  /* ---------- 缩略图纹理 ---------- */

  private textureKey(index: number, level: number) { return this.textureKeys[index * 2 + level] }

  /** 按需排队加载；同一条纹理不会重复请求 */
  private ensureTexture(index: number, level: number, priority: number) {
    const key = this.textureKey(index, level)
    if (this.textures.has(key)) return
    const url = level === 0 ? this.nodes[index].thumb : this.nodes[index].thumbLarge
    if (!url) return
    this.textures.set(key, { texture: null, image: null, state: 'loading', lastUsed: performance.now(), aspect: 1 })
    this.queue.push({ key, url, priority })
    this.pumpQueue()
  }

  private pumpQueue() {
    while (this.loading < LOAD_CONCURRENCY && this.queue.length) {
      /* 优先加载画面中心附近的：先排队的不一定最该先看到 */
      this.queue.sort((a, b) => b.priority - a.priority)
      const job = this.queue.shift()!
      const entry = this.textures.get(job.key)
      if (!entry || entry.state !== 'loading') continue
      this.loading++
      const image = new Image()
      image.decoding = 'async'
      image.onload = () => {
        this.loading--
        entry.image = image
        entry.state = 'ready'
        entry.aspect = image.naturalWidth && image.naturalHeight ? image.naturalWidth / image.naturalHeight : 1
        entry.lastUsed = performance.now()
        this.dirty = true
        this.pumpQueue()
      }
      image.onerror = () => {
        this.loading--
        entry.state = 'error'
        this.pumpQueue()
      }
      entry.image = image
      image.src = job.url
    }
  }

  private upload(entry: TextureEntry) {
    const gl = this.gl
    if (!entry.image) return null
    if (!entry.texture) entry.texture = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, entry.texture)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, 1)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, entry.image)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    entry.lastUsed = performance.now()
    return entry.texture
  }

  private evictTextures() {
    if (this.textures.size <= MAX_TEXTURES) return
    const now = performance.now()
    const idle: Array<{ key: string; lastUsed: number }> = []
    for (const [key, entry] of this.textures) {
      if (entry.state === 'loading' && now - entry.lastUsed < 4000) continue
      idle.push({ key, lastUsed: entry.lastUsed })
    }
    idle.sort((a, b) => a.lastUsed - b.lastUsed)
    const remove = this.textures.size - MAX_TEXTURES
    for (let i = 0; i < remove && i < idle.length; i++) {
      const entry = this.textures.get(idle[i].key)
      if (entry?.texture) this.gl.deleteTexture(entry.texture)
      this.textures.delete(idle[i].key)
    }
  }

  /* ---------- 尺寸 ---------- */

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const width = Math.max(1, Math.round(this.canvas.clientWidth * dpr))
    const height = Math.max(1, Math.round(this.canvas.clientHeight * dpr))
    if (width === this.width && height === this.height && dpr === this.dpr) return
    const first = this.width <= 1
    const rebuildAtlas = dpr !== this.dpr || !this.atlas
    this.dpr = dpr
    this.width = width
    this.height = height
    this.canvas.width = width
    this.canvas.height = height
    if (rebuildAtlas) this.buildAtlas()
    const frame = this.frameLayout()
    this.fitZoom = frame.zoom
    this.minZoom = frame.zoom * 0.4
    this.maxZoom = frame.zoom * MAX_ZOOM_FACTOR
    if (first) {
      this.zoom = frame.zoom
      this.centerX = frame.cx
      this.centerY = frame.cy
      this.targetZoom = frame.zoom
      this.targetCenterX = frame.cx
      this.targetCenterY = frame.cy
    } else {
      /* 尺寸变了但不重置用户的视角：中心是世界坐标不用动，缩放钳一下就好 */
      this.zoom = this.clampZoom(this.zoom)
      this.targetZoom = this.clampZoom(this.targetZoom)
    }
    this.dirty = true
  }

  /* ---------- 渲染 ---------- */

  private loop = (now: number) => {
    this.frame = requestAnimationFrame(this.loop)
    if (this.contextLost) return
    /* 相机缓动：所有镜头变化都走这一条路，手感才一致 */
    if (this.animating) {
      const ease = 0.17
      this.zoom += (this.targetZoom - this.zoom) * ease
      this.centerX += (this.targetCenterX - this.centerX) * ease
      this.centerY += (this.targetCenterY - this.centerY) * ease
      if (Math.abs(this.targetZoom - this.zoom) < this.fitZoom * 0.0015 &&
          Math.abs(this.targetCenterX - this.centerX) < 0.0015 &&
          Math.abs(this.targetCenterY - this.centerY) < 0.0015) {
        this.zoom = this.targetZoom
        this.centerX = this.targetCenterX
        this.centerY = this.targetCenterY
        this.animating = false
      }
      this.dirty = true
    }
    /* 选中脉冲只重画画面、不回调 onCamera：镜头没动，DOM 标签不用跟着算 */
    const pulse = !this.dirty && this.selectedIndex !== null && this.pulsing() && now - this.lastPulseAt >= PULSE_FRAME_MS
    if (!this.dirty && !pulse) return
    const notify = this.dirty
    this.dirty = false
    this.now = now
    this.lastPulseAt = now
    this.draw(notify)
  }

  private pulsing() {
    return !this.lowEnd && !this.style.reducedMotion
  }

  /** 选中卡片外发光的强度 0~1 */
  private selectionPulse() {
    if (!this.pulsing()) return 0.8
    return 0.62 + 0.38 * Math.sin((this.now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS * Math.PI * 2)
  }

  private attribute(program: WebGLProgram, name: string) {
    let table = this.attributeCache.get(program)
    if (!table) {
      table = new Map()
      this.attributeCache.set(program, table)
    }
    let location = table.get(name)
    if (location === undefined) {
      location = this.gl.getAttribLocation(program, name)
      table.set(name, location)
    }
    return location
  }

  private uniform(program: WebGLProgram, name: string) {
    let table = this.uniformCache.get(program)
    if (!table) {
      table = new Map()
      this.uniformCache.set(program, table)
    }
    let location = table.get(name)
    if (location === undefined) {
      location = this.gl.getUniformLocation(program, name)
      table.set(name, location)
    }
    return location
  }

  private setCamera(program: WebGLProgram) {
    const gl = this.gl
    gl.uniform2f(this.uniform(program, 'uResolution'), this.width, this.height)
    gl.uniform2f(this.uniform(program, 'uCenter'), this.centerX, this.centerY)
    gl.uniform1f(this.uniform(program, 'uZoom'), this.zoom)
  }

  /** 每趟绘制前把顶点属性全关掉：几个 program 的 attribute 位置会重叠，不清理会互相串 */
  private resetAttributes() {
    for (let i = 0; i < 8; i++) this.gl.disableVertexAttribArray(i)
  }

  private attach(program: WebGLProgram, entries: ReadonlyArray<{ name: string; size: number; offset: number }>, stride: number) {
    const gl = this.gl
    for (const entry of entries) {
      const location = this.attribute(program, entry.name)
      if (location < 0) continue
      gl.enableVertexAttribArray(location)
      gl.vertexAttribPointer(location, entry.size, gl.FLOAT, false, stride, entry.offset)
    }
  }

  private draw(notify = true) {
    const gl = this.gl
    gl.viewport(0, 0, this.width, this.height)
    gl.disable(gl.DEPTH_TEST)
    gl.enable(gl.BLEND)
    this.resetAttributes()

    this.drawBackdrop()
    if (!this.lowEnd) this.drawDust()
    this.drawEdges()
    const thumbAlpha = this.drawPoints()
    this.drawThumbnails(thumbAlpha)
    this.drawRing()
    this.resetAttributes()

    if (!notify) return
    if (thumbAlpha !== this.thumbAlpha) {
      this.thumbAlpha = thumbAlpha
      this.callbacks.onView?.(this.zoom, thumbAlpha)
    }
    this.callbacks.onCamera?.()
  }

  private drawBackdrop() {
    const gl = this.gl
    const program = this.backdropProgram
    gl.useProgram(program)
    this.resetAttributes()
    gl.disable(gl.BLEND)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.backdropTexture)
    gl.uniform1i(this.uniform(program, 'uTexture'), 0)
    gl.uniform2f(this.uniform(program, 'uResolution'), this.width, this.height)
    gl.uniform2f(this.uniform(program, 'uCenter'), this.centerX, this.centerY)
    gl.uniform1f(this.uniform(program, 'uZoom'), this.zoom)
    gl.uniform3fv(this.uniform(program, 'uAccent'), this.style.accents)
    gl.uniform3fv(this.uniform(program, 'uBase'), stageBase(this.style))
    gl.uniform1f(this.uniform(program, 'uHaze'), this.style.light ? 0.4 : 0.32)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuffer)
    const location = this.attribute(program, 'aPos')
    gl.enableVertexAttribArray(location)
    gl.vertexAttribPointer(location, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLES, 0, 6)
    gl.enable(gl.BLEND)
    /* 加色混合：着色器里已经预乘过 alpha，这里用 (ONE, ONE)。
       用 (SRC_ALPHA, ONE) 会把 alpha 再乘一遍，整片糊成白色。 */
    gl.blendFunc(gl.ONE, gl.ONE)
  }

  private drawDust() {
    const gl = this.gl
    const program = this.dustProgram
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.uniform3fv(this.uniform(program, 'uAccent'), this.style.accents)
    gl.uniform1f(this.uniform(program, 'uPixel'), this.dpr)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dustBuffer)
    this.attach(program, DUST_LAYOUT, 5 * 4)
    gl.drawArrays(gl.POINTS, 0, DUST_COUNT)
  }

  private drawEdges() {
    const gl = this.gl
    const program = this.edgeProgram
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer)
    this.attach(program, EDGE_LAYOUT, 6 * 4)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeAlphaBuffer)
    const alpha = this.attribute(program, 'aAlpha')
    gl.enableVertexAttribArray(alpha)
    gl.vertexAttribPointer(alpha, 1, gl.FLOAT, false, 0, 0)
    if (this.edges.length) gl.drawArrays(gl.LINES, 0, this.edges.length * 2)
  }

  /** 画点（先光晕、后亮核），并返回当前该显示的缩略图透明度（0~1） */
  private drawPoints() {
    const gl = this.gl
    const screenSize = this.zoom * THUMB_SIZE
    const thumbAlpha = Math.max(0, Math.min(1, (screenSize - THUMB_FADE_FROM) / (THUMB_FADE_TO - THUMB_FADE_FROM)))
    const focus = this.hoverIndex ?? this.selectedIndex

    /* 点的透明度每帧算：缩略图淡入时点同时淡出，避免糊在一起 */
    for (let i = 0; i < this.nodes.length; i++) {
      let alpha = this.emphasis[i]
      if (focus !== null && i !== focus) alpha *= 0.35
      this.pointData[i * 7 + 6] = alpha * (1 - thumbAlpha * 0.72)
    }
    if (!this.nodes.length) return thumbAlpha
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, this.pointData)
    this.drawHalos()

    const program = this.pointProgram
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    this.attach(program, POINT_LAYOUT, 7 * 4)
    gl.drawArrays(gl.POINTS, 0, this.nodes.length)
    return thumbAlpha
  }

  /** 光晕：同一份点数据，换成预渲染的光晕纹理画大一圈；节点太多时只画关系最强的一批 */
  private drawHalos() {
    const gl = this.gl
    const program = this.haloProgram
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.activeTexture(gl.TEXTURE1)
    gl.bindTexture(gl.TEXTURE_2D, this.spriteTexture)
    gl.activeTexture(gl.TEXTURE0)
    gl.uniform1i(this.uniform(program, 'uSprite'), 1)
    gl.uniform1f(this.uniform(program, 'uScale'), HALO_SCALE)
    gl.uniform1f(this.uniform(program, 'uMaxSize'), Math.min(this.maxPointSize, 220))
    gl.uniform1f(this.uniform(program, 'uGain'), 0.55)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    this.attach(program, POINT_LAYOUT, 7 * 4)
    if (this.haloCount < this.nodes.length) {
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.haloIndexBuffer)
      gl.drawElements(gl.POINTS, this.haloCount, gl.UNSIGNED_SHORT, 0)
    } else {
      gl.drawArrays(gl.POINTS, 0, this.nodes.length)
    }
  }

  private drawThumbnails(thumbAlpha: number) {
    this.selectedThumbAlpha = 0
    if (thumbAlpha <= 0.02 || !this.nodes.length) return
    const gl = this.gl
    /* 缩略图在屏幕上多大：随缩放变大，但封顶（见 THUMB_MAX_PX 的说明）。thumbPx 是**长边** */
    const thumbPx = Math.min(THUMB_SIZE * this.zoom, THUMB_MAX_PX)
    const longWorld = thumbPx / this.zoom

    /* 只画屏幕里的；按「离屏幕中心近」排序后截断——每张缩略图一次 draw call，
       不控数量会在中景（几百张同时可见）掉帧。顺序数组跨帧复用，镜头连续移动时几乎不用挪。 */
    const order = this.thumbOrder
    const distance = this.thumbDistance
    const margin = thumbPx
    let visible = 0
    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i]
      const dx = (node.x - this.centerX) * this.zoom
      const dy = (node.y - this.centerY) * this.zoom
      const inside = Math.abs(dx) <= this.width * 0.5 + margin && Math.abs(dy) <= this.height * 0.5 + margin
      distance[i] = inside ? dx * dx + dy * dy : Infinity
      if (inside) visible++
    }
    if (!visible) return
    sortIndicesByKey(order, distance, true)

    const focus = this.hoverIndex ?? this.selectedIndex
    const program = this.thumbProgram
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.activeTexture(gl.TEXTURE2)
    gl.bindTexture(gl.TEXTURE_2D, this.atlasTexture)
    gl.activeTexture(gl.TEXTURE0)
    gl.uniform1i(this.uniform(program, 'uTexture'), 0)
    gl.uniform1i(this.uniform(program, 'uAtlas'), 2)
    gl.uniform1f(this.uniform(program, 'uStroke'), this.dpr)
    const uHalfPx = this.uniform(program, 'uHalfPx')
    const uRadius = this.uniform(program, 'uRadius')
    const uSelect = this.uniform(program, 'uSelect')
    const uChip = this.uniform(program, 'uChip')
    const uChipUv = this.uniform(program, 'uChipUv')
    gl.bindBuffer(gl.ARRAY_BUFFER, this.thumbBuffer)
    this.attach(program, THUMB_LAYOUT, 8 * 4)

    /* 缩略图要挡住后面的点：预乘 alpha 的普通叠加；外圈的选中光不占 alpha，等于加色 */
    gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA)

    const select = this.selectionPulse()
    const chipHeight = this.atlas ? this.atlas.chipHeight : 0
    const chipFade = Math.max(0, Math.min(1, (thumbPx / this.dpr - 84) / 20))
    const level = thumbPx > THUMB_LARGE_AT ? 1 : 0
    /* uniform 跨绘制保留，值没变就不重复上传 */
    let lastHalfW = -1
    let lastHalfH = -1
    let lastRadius = -1
    let lastSelect = -1
    let lastLabel: Float32Array | null | undefined
    let lastTextW = -1
    let drawn = 0
    for (let n = 0; n < visible && drawn < MAX_THUMBS_PER_FRAME; n++) {
      const index = order[n]
      const node = this.nodes[index]
      this.ensureTexture(index, level, 1e7 - distance[index])
      let entry = this.textures.get(this.textureKey(index, level))
      /* 高分图还没到就先用小图顶上，不要空着 */
      if (level === 1 && (!entry || entry.state !== 'ready')) {
        entry = this.textures.get(this.textureKey(index, 0))
      }
      if (!entry || entry.state !== 'ready') continue
      const texture = entry.texture ?? this.upload(entry)
      if (!texture) continue

      let alpha = thumbAlpha * this.emphasis[index]
      if (focus !== null && index !== focus) alpha *= 0.42
      if (alpha <= 0.02) continue

      /* 格子按原图比例（长边恒定），UV 就是整张图 —— 不裁也不拉（同 thumbHalfExtent） */
      const ratio = Number.isFinite(node.aspect) && node.aspect > 0.05 ? node.aspect : 1
      const halfW = ratio >= 1 ? longWorld / 2 : (longWorld * ratio) / 2
      const halfH = ratio >= 1 ? longWorld / (2 * ratio) : longWorld / 2
      const halfWPx = halfW * this.zoom
      const halfHPx = halfH * this.zoom
      const selected = index === this.selectedIndex
      const marginPx = (selected ? CARD_SELECTED_MARGIN : CARD_MARGIN) * this.dpr
      const growX = 1 + marginPx / halfWPx
      const growY = 1 + marginPx / halfHPx
      for (let corner = 0; corner < 4; corner++) {
        const base = corner * 8
        const localX = CORNERS[corner * 2] * growX
        const localY = CORNERS[corner * 2 + 1] * growY
        this.thumbData[base] = node.x + localX * halfW
        this.thumbData[base + 1] = node.y + localY * halfH
        this.thumbData[base + 2] = localX
        this.thumbData[base + 3] = localY
        this.thumbData[base + 4] = node.color[0]
        this.thumbData[base + 5] = node.color[1]
        this.thumbData[base + 6] = node.color[2]
        this.thumbData[base + 7] = alpha
      }
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, this.thumbData)
      if (halfWPx !== lastHalfW || halfHPx !== lastHalfH) {
        gl.uniform2f(uHalfPx, halfWPx, halfHPx)
        lastHalfW = halfWPx
        lastHalfH = halfHPx
      }
      const radius = Math.min(Math.min(halfWPx, halfHPx) * 0.16, 12 * this.dpr)
      if (radius !== lastRadius) {
        gl.uniform1f(uRadius, radius)
        lastRadius = radius
      }
      const selectValue = selected ? select : 0
      if (selectValue !== lastSelect) {
        gl.uniform1f(uSelect, selectValue)
        lastSelect = selectValue
      }
      const label = chipFade > 0 ? this.nodeLabels[index] ?? null : null
      const textW = label && label[4] + chipHeight * 2.2 <= halfWPx * 2 ? label[4] : 0
      if (label !== lastLabel || textW !== lastTextW) {
        if (label) {
          gl.uniform4f(uChip, chipFade, chipHeight, textW, label[5])
          gl.uniform4f(uChipUv, label[0], label[1], label[2], label[3])
        } else {
          gl.uniform4f(uChip, 0, 0, 0, 0)
        }
        lastLabel = label
        lastTextW = textW
      }
      gl.bindTexture(gl.TEXTURE_2D, texture)
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      if (selected) this.selectedThumbAlpha = alpha
      drawn++
    }
    gl.blendFunc(gl.ONE, gl.ONE)
    if (drawn > 0) this.evictTextures()
  }

  /** 选中光环：缩略图淡入后由卡片外发光接替 */
  private drawRing() {
    const index = this.selectedIndex
    const ringAlpha = 1 - this.selectedThumbAlpha
    if (index === null || index >= this.nodes.length || ringAlpha <= 0.02) return
    const gl = this.gl
    const program = this.ringProgram
    const pulse = this.pulsing()
    gl.useProgram(program)
    this.resetAttributes()
    this.setCamera(program)
    gl.uniform1f(this.uniform(program, 'uScale'), RING_SCALE)
    gl.uniform1f(this.uniform(program, 'uMaxSize'), this.maxPointSize)
    gl.uniform1f(this.uniform(program, 'uLine'), this.dpr * 0.8)
    gl.uniform1f(this.uniform(program, 'uPhase'), pulse ? (this.now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS : 0)
    gl.uniform1f(this.uniform(program, 'uPing'), pulse ? 1 : 0)
    gl.uniform1f(this.uniform(program, 'uAlpha'), ringAlpha)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    this.attach(program, POINT_LAYOUT, 7 * 4)
    gl.drawArrays(gl.POINTS, index, 1)
  }

  /* ---------- 生命周期 ---------- */

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
    for (const buffer of [this.pointBuffer, this.edgeBuffer, this.edgeAlphaBuffer, this.thumbBuffer, this.dustBuffer, this.quadBuffer, this.haloIndexBuffer]) gl.deleteBuffer(buffer)
    gl.deleteTexture(this.backdropTexture)
    gl.deleteTexture(this.thumbTexture)
    gl.deleteTexture(this.spriteTexture)
    gl.deleteTexture(this.atlasTexture)
    for (const program of [this.pointProgram, this.edgeProgram, this.thumbProgram, this.backdropProgram, this.dustProgram, this.haloProgram, this.ringProgram]) gl.deleteProgram(program)
    /* 浏览器同时能持有的 WebGL 上下文有限（一般 16 个），进出视图几次就可能用光。
       主动丢上下文是唯一可靠的释放方式：canvas 从 DOM 移除后 GC 回收得很慢。 */
    try { gl.getExtension('WEBGL_lose_context')?.loseContext() } catch { /* 有些实现没这个扩展，忽略 */ }
  }
}

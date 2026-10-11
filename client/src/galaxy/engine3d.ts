/**
 * 3D 星系空间的渲染器（WebGL）。
 *
 * 和二维那套（engine.ts）是**两个独立的模块**，不共用代码：二维那套是正交投影 + 屏幕空间
 * 布局，三维这套是透视投影 + 轨道相机，把两者硬凑成一个会到处都是 if。
 * 共用的只有思路（以及 render-sprites.ts 里的素材与片元着色器）：点光晕用预渲染纹理做加色混合、
 * 缩略图按需加载并按深度淡出、命中判定走屏幕空间。
 *
 * 【投影公式】顶点着色器里手写，和 camera3d.ts 的 projectPoint 逐字对应 ——
 * 那边是可以在 Node 里跑单测的纯函数，这边是 GPU 上的同一套算术。改一处必须改另一处。
 *
 * 【为什么不用矩阵】正交/透视矩阵当然更"标准"，但这里的相机只有 yaw/pitch/distance/target
 * 四个自由度，手写反而更短更直观，也省掉一次 mat4 乘法的误差。
 *
 * 【五层画面】从后往前，和二维那套一一对应（见 engine.ts 顶部）：
 *   1. 深空舞台：全屏片元里按「视线方向」算，跟着相机转，星雾锁在世界空间里
 *   2. 星尘：每颗有自己的深度，深度决定渲染视差、大小、亮度和偏色
 *   3. 相似边：相似度越高越亮，颜色在两端点的角色色之间按相似度混合
 *   4. 图片点：预渲染光晕纹理 + 加色混合的亮核
 *   5. 缩略图：圆角卡片 + 玻璃描边 + 标签芯片；选中时外发光接替脉冲光环
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
  // gl_PointSize 有上限（一般 64~255），太大的点会被截断，所以大范围的光晕交给 HALO_VS
  gl_PointSize = clamp(radiusPx * 2.0, 1.0, 240.0);
  vColor = aColor;
  vAlpha = aMeta.y * (depth < 0.06 ? 0.0 : 1.0);
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

const POINT_FS = `
precision mediump float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  vec2 offset = gl_PointCoord * 2.0 - 1.0;
  float r = length(offset);
  if (r > 1.0) discard;
  // 亮核 + 很柔的外晕（外圈由预渲染光晕纹理补），加色混合下自然形成星点
  float core = smoothstep(0.22, 0.0, r);
  float halo = pow(max(0.0, 1.0 - r), 2.2) * 0.26;
  float alpha = (core * 0.85 + halo) * vAlpha;
  gl_FragColor = vec4(vColor * alpha * (0.75 + core * 0.9), alpha);
}
`

/** 光晕：和二维那套同一套算术，只是世界半径要先过一遍透视缩放 */
const HALO_VS = `
precision highp float;
uniform float uScale;
uniform float uMaxSize;
attribute vec3 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;
${PROJECT_GLSL}
varying vec3 vColor;
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aPos, depth, scale);
  gl_PointSize = clamp(max(aMeta.x * scale, 0.7) * 2.0 * uScale, 2.0, uMaxSize);
  vColor = aColor;
  vAlpha = aMeta.y * (depth < 0.06 ? 0.0 : 1.0);
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

/** 选中光环：世界半径同样要过透视缩放；vPixel 告诉片元一个线宽相当于多少片内坐标 */
const RING_VS = `
precision highp float;
uniform float uScale;
uniform float uMaxSize;
uniform float uLine;
attribute vec3 aPos;
attribute vec3 aColor;
attribute vec2 aMeta;
${PROJECT_GLSL}
varying vec3 vColor;
varying float vPixel;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aPos, depth, scale);
  float size = clamp(max(aMeta.x * scale, 0.7) * 2.0 * uScale, 30.0 * uLine, uMaxSize);
  gl_PointSize = size;
  vColor = aColor;
  vPixel = 2.0 * uLine / size;
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

const EDGE_VS = `
precision highp float;
attribute vec3 aPos;
attribute vec3 aColor;
attribute float aAlpha;
${PROJECT_GLSL}
varying vec3 vColor;
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aPos, depth, scale);
  vColor = aColor;
  vAlpha = aAlpha * (depth < 0.06 ? 0.0 : 1.0);
  gl_Position = vec4(screenToNdc(screen), 0.0, 1.0);
}
`

/* 与二维那套逐字相同：预乘 alpha 输出，配 (ONE, ONE) 加色混合 —— 用 (SRC_ALPHA, ONE) 会把
   alpha 再乘一遍，整片糊成白色（engine.ts 里踩过的坑） */
const EDGE_FS = `
precision mediump float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  gl_FragColor = vec4(vColor * vAlpha, vAlpha);
}
`

/**
 * 星尘。二维那套是正交投影，视差可以直接把 uCenter 除以 parallax；三维这套相机是**绕着
 * target 转**的，只改 center 不会产生视差（相机没动，画面就不动）。等价写法是把这一层的
 * 「相对向量」按 parallax 缩放：rel = (world - target + (target - eye)·parallax) * parallax，
 * 展开就是 (world - eye·parallax - target·(1-parallax)) * parallax。
 * parallax 越小 → 相机对它的位移越小、缩放越小 → 看起来越远。
 *
 * 【为什么不用 uniform 数组传 eye】WebGL1 下 uniform3fv 传 vec3 数组是合法的，但这里只需要
 * 一个逐层的标量，写成三个 uniform 标量最省事，也避免驱动对数组 uniform 的兼容差异。
 */
const DUST_SPREAD = 3.4

const DUST_VS = `
precision highp float;
${ACCENT_GLSL}
${PROJECT_GLSL}
uniform float uEyeX;
uniform float uEyeY;
uniform float uEyeZ;
uniform float uSpread;
uniform float uBasePx;
uniform float uSizeRef;   // 基准距离下的 scale，用来把星点大小归一化
attribute vec3 aPos;    // -1~1 的立方体，乘 uSpread 是世界坐标
attribute vec3 aMeta;   // x: 视差 0远~1近, y: 取色位置, z: 随机
varying vec3 vColor;
varying float vAlpha;
void main() {
  float parallax = mix(0.22, 0.5, aMeta.x);
  vec3 eye = vec3(uEyeX, uEyeY, uEyeZ);
  vec3 rel = (aPos * uSpread - eye * parallax - uTarget * (1.0 - parallax)) * parallax;
  float cy = cos(uYaw), sy = sin(uYaw);
  vec3 p1 = vec3(rel.x * cy + rel.z * sy, rel.y, -rel.x * sy + rel.z * cy);
  float cp = cos(uPitch), sp = sin(uPitch);
  vec3 p2 = vec3(p1.x, p1.y * cp - p1.z * sp, p1.y * sp + p1.z * cp);
  float depth = p2.z + uDistance * parallax;
  float scale = uFocal / max(depth, 0.05) * uViewMin * 0.5;
  gl_Position = vec4(screenToNdc(vec2(uResolution.x * 0.5 + p2.x * scale, uResolution.y * 0.5 - p2.y * scale)), 0.0, 1.0);
  /* 星点大小只做温和的远近变化：直接用透视 scale 会大得离谱（scale 是「长度→屏幕像素」
     的量纲，uBasePx 只是几个像素的基准），所以归一化成 0.5~1 的系数再乘基准。 */
  float sizeFalloff = mix(0.5, 1.0, clamp((scale - 0.6 * uSizeRef) / (1.8 * uSizeRef), 0.0, 1.0));
  gl_PointSize = (0.9 + aMeta.x * 1.6 + aMeta.z * 0.6) * uBasePx * sizeFalloff;
  vColor = mix(accentAt(aMeta.y), vec3(0.9, 0.93, 1.0), aMeta.x * 0.4);
  /* 远的暗、近的亮；再按深度压一道，钻到相机背后的不画 */
  vAlpha = (0.06 + aMeta.x * 0.3) * (0.6 + aMeta.z * 0.4) * (depth < 0.06 ? 0.0 : 1.0);
}
`

/** 缩略图卡片：顶点只传中心 + 片内角点，UV 由角点推出来（和二维那套的 CARD_FRAG 对齐） */
const THUMB_VS = `
precision highp float;
attribute vec3 aCenter;
attribute vec2 aLocal;    // -1..1 的片内坐标，可超出 ±1（外圈画投影与选中光）
attribute vec4 aTint;     // rgb + alpha
${PROJECT_GLSL}
/* 尺寸做成 uniform 而不是逐顶点属性：一个四边形的四个角必然一样大，
   放进 stride 只会让每帧多搬 4 份重复数据 */
uniform vec2 uSizePx;
varying vec2 vLocal;
varying vec4 vTint;
varying float vAlpha;
void main() {
  float depth;
  float scale;
  vec2 screen = projectToScreen(aCenter, depth, scale);
  // 广告牌：在**屏幕空间**偏移四个角，所以永远正对相机，近大远小由 scale 带出来
  vec2 corner = screen + aLocal * uSizePx * 0.5;
  vLocal = aLocal;
  vTint = aTint;
  vAlpha = depth < 0.06 ? 0.0 : 1.0;
  gl_Position = vec4(screenToNdc(corner), 0.0, 1.0);
}
`

const THUMB_FS = CARD_FRAG

/* 全屏舞台：只传 -1~1 的裁剪坐标，uResolution 只出现在片元里 ——
   ANGLE 对同名 uniform 在顶点/片元两个阶段的精度差异极其敏感（二维那套踩过） */
const BACKDROP_VS = `
attribute vec2 aPos;
varying vec2 vNdc;
void main() {
  vNdc = aPos;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
`

/**
 * 深空舞台。三维这套相机是绕着 target 转的，所以星雾得**跟着视线方向走**，否则一转视角
 * 就会发现雾贴在屏幕上不动，立刻穿帮。做法是把每个像素反推成一条视线方向（把 yaw/pitch
 * 的旋转补回去），拿它当 uv 去采样：远处的纹理几乎不随视角变（当背景），近处的纹理随视角
 * 明显平移（当身边的雾），一转视角就有强视差。
 *
 * 上下渐变与暗角仍然按屏幕坐标算 —— 这层是「天幕」，本来就该挂在屏幕上。
 */
const BACKDROP_FS = `
precision highp float;
uniform vec2 uResolution;
uniform float uYaw;
uniform float uPitch;
${STAGE_GLSL}
varying vec2 vNdc;
/** 片元 → 世界方向的近似射线（忽略透视长度，只取方向）：把相机的旋转补回去 */
vec3 viewRay(vec2 ndc, float yaw, float pitch) {
  vec3 cam = vec3(ndc, -1.0);
  float cy = cos(yaw), sy = sin(yaw);
  float cp = cos(pitch), sp = sin(pitch);
  vec3 yawed = vec3(cam.x * cy - cam.z * sy, cam.y, cam.x * sy + cam.z * cy);
  return vec3(yawed.x, yawed.y * cp - yawed.z * sp, yawed.y * sp + yawed.z * cp);
}
void main() {
  vec3 ray = normalize(viewRay(vNdc, uYaw, uPitch));
  /* 远层：几乎不随视角动，当深空背景（x 用经度，横着转只挪一点点） */
  vec4 far = texture2D(uTexture, vec2(atan(ray.x, -ray.z) * 0.18, ray.y * 0.5) + vec2(0.5));
  /* 近层：随视角平移得多，转视角时有明显视差 */
  vec4 near = texture2D(uTexture, vec2(atan(ray.x, -ray.z) * 0.55, ray.y * 1.1 + 0.2) + vec2(0.13, 0.55));
  gl_FragColor = vec4(stage(vNdc, far, near), 1.0);
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

/** 星尘数量（与二维那套一致；低端机减半） */
const DUST_COUNT = 900
/** 节点多于这个数时只给最「中心」的这么多个点画光晕（与缩略图的单帧预算同量级） */
const HALO_BUDGET = MAX_THUMBS_PER_FRAME
/** 光晕直径 / 点直径 */
const HALO_SCALE = 3.4
/** 选中光环直径 / 点直径（缩略图还没出来时用它） */
const RING_SCALE = 2.6
const PULSE_PERIOD_MS = 1700
/** 只为脉冲重画时限到约 30fps */
const PULSE_FRAME_MS = 32
/** 卡片外圈留给投影 / 选中外发光的像素（CSS 像素） */
const CARD_MARGIN = 6
const CARD_SELECTED_MARGIN = 18
/** 芯片（标签）从多大开始显示：屏幕长边（CSS 像素） */
const CHIP_FADE_FROM = 84
const CHIP_FADE_TO = 104

/** 顶点属性布局：只描述一次，attach 时复用 */
const POINT_LAYOUT = [
  { name: 'aPos', size: 3, offset: 0 },
  { name: 'aColor', size: 3, offset: 3 * 4 },
  { name: 'aMeta', size: 2, offset: 6 * 4 },
]
const EDGE_LAYOUT = [
  { name: 'aPos', size: 3, offset: 0 },
  { name: 'aColor', size: 3, offset: 3 * 4 },
]
const DUST_LAYOUT = [
  { name: 'aPos', size: 3, offset: 0 },
  { name: 'aMeta', size: 3, offset: 3 * 4 },
]
const THUMB_LAYOUT = [
  { name: 'aCenter', size: 3, offset: 0 },
  { name: 'aLocal', size: 2, offset: 3 * 4 },
  { name: 'aTint', size: 4, offset: 5 * 4 },
]
const CORNERS = [-1, -1, 1, -1, -1, 1, 1, 1]

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
  private backdropProgram: WebGLProgram | null = null
  private dustProgram: WebGLProgram | null = null
  private haloProgram: WebGLProgram | null = null
  private ringProgram: WebGLProgram | null = null

  private pointBuffer: WebGLBuffer | null = null
  private edgeBuffer: WebGLBuffer | null = null
  private thumbBuffer: WebGLBuffer | null = null
  private dustBuffer: WebGLBuffer | null = null
  private quadBuffer: WebGLBuffer | null = null
  private haloIndexBuffer: WebGLBuffer | null = null
  private backdropTexture: WebGLTexture | null = null
  private thumbTexture: WebGLTexture | null = null
  private spriteTexture: WebGLTexture | null = null
  private atlasTexture: WebGLTexture | null = null

  private pointData: Float32Array
  private edgeData: Float32Array
  private thumbData = new Float32Array(4 * 8)
  private dustData: Float32Array
  /** 实际画多少颗星尘：低端机减半 */
  private dustCount = DUST_COUNT

  /* ---- 与二维那套对齐的舞台状态：配色、降级、光晕预算、标签图集 ---- */
  private style = createStageStyle()
  private lowEnd = isLowEndDevice()
  private maxPointSize = 64
  /** 画光晕的点数；小于节点数时按索引缓冲只画相似关系最强的那些 */
  private haloCount = 0
  private atlas: LabelAtlas | null = null
  private nodeLabels: Array<Float32Array | null> = []
  /** 纹理缓存的 key 预先拼好，帧内不再拼字符串（二维那套的做法，这里沿用） */
  private textureKeys: string[] = []
  private now = 0
  /** 上一次推进脉冲相位的时刻：把它限到约 30fps，省掉一半无谓的重算 */
  private lastPulseAt = 0
  /** 选中脉冲的相位 0~1（非脉冲帧沿用上一次，光环不会每帧乱跳） */
  private pulsePhase = 0
  private selectedThumbAlpha = 0
  /** 缩略图绘制顺序（离屏心由近到远），跨帧复用、原地排序 */
  private thumbOrder: Int32Array
  private thumbDistance: Float32Array
  private uniformCache = new Map<WebGLProgram, Map<string, WebGLUniformLocation | null>>()
  private attributeCache = new Map<WebGLProgram, Map<string, number>>()

  private textures = new Map<string, TextureEntry>()
  private queue: Array<{ index: number; level: number; priority: number }> = []
  private loading = 0
  /** 这一帧算好的投影结果，悬停/点击判定直接复用，不重复算 */
  private projected: ProjectedNode[] = []
  /**
   * 一帧的绘制统计。纯只读诊断量，不参与任何画面决策 —— 面板上「一张缩略图都不出来」
   * 这类问题，光看画面分不清是「没进循环」「纹理没加载」还是「前景被裁掉」，
   * 有这几个数就能一眼定位。挂实例上方便控制台里直接看。
   */
  readonly stats = {
    frames: 0,
    thumbCandidates: 0,
    thumbDrawn: 0,
    textureRequested: 0,
    textureReady: 0,
    textureFailed: 0,
    lastCloseness: 0,
    lastLongPx: 0,
  }
  /** 每类「跳过」各发生多少次：排查缩略图不显示时最有用 */
  private readonly skipStats: Record<string, number> = { small: 0, noTexture: 0, lowAlpha: 0 }

  constructor(canvas: HTMLCanvasElement, nodes: Node3D[], edges: Edge3D[], callbacks: Engine3DCallbacks = {}) {
    this.canvas = canvas
    this.nodes = nodes
    this.edges = edges
    this.callbacks = callbacks

    /*
     * premultipliedAlpha 打开：片元统一输出预乘 alpha（加色混合的层直接输出 (c*a, a)），
     * 与二维那套（engine.ts）保持同一套混合算术。舞台底色是不透明的，画布整体 alpha=1，
     * 所以对页面合成没有影响。
     */
    const attributes: WebGLContextAttributes = { alpha: true, antialias: true, premultipliedAlpha: true, depth: false }
    const gl = (canvas.getContext('webgl', attributes) || canvas.getContext('experimental-webgl', attributes)) as WebGLRenderingContext | null
    if (!gl) throw new Error('WebGL 不可用')
    this.gl = gl

    this.pointProgram = link(gl, POINT_VS, POINT_FS)
    this.edgeProgram = link(gl, EDGE_VS, EDGE_FS)
    this.thumbProgram = link(gl, THUMB_VS, THUMB_FS)
    this.backdropProgram = link(gl, BACKDROP_VS, BACKDROP_FS)
    this.dustProgram = link(gl, DUST_VS, DUST_FRAG)
    this.haloProgram = link(gl, HALO_VS, HALO_FRAG)
    this.ringProgram = link(gl, RING_VS, RING_FRAG)
    this.pointBuffer = gl.createBuffer()
    this.edgeBuffer = gl.createBuffer()
    this.thumbBuffer = gl.createBuffer()
    this.dustBuffer = gl.createBuffer()
    this.quadBuffer = gl.createBuffer()
    this.haloIndexBuffer = gl.createBuffer()
    this.backdropTexture = gl.createTexture()
    this.thumbTexture = gl.createTexture()
    this.spriteTexture = gl.createTexture()
    this.atlasTexture = gl.createTexture()

    const pointRange = gl.getParameter(gl.ALIASED_POINT_SIZE_RANGE) as Float32Array | null
    this.maxPointSize = Math.min(256, pointRange ? pointRange[1] : 64)

    this.pointData = new Float32Array(nodes.length * 8)
    /* 每条边两个端点：位置(3) + 颜色(3) */
    this.edgeData = new Float32Array(edges.length * 2 * 6)
    this.dustCount = this.lowEnd ? DUST_COUNT >> 1 : DUST_COUNT
    this.dustData = new Float32Array(this.dustCount * 6)
    this.thumbOrder = new Int32Array(nodes.length)
    this.thumbDistance = new Float32Array(nodes.length)
    for (let i = 0; i < nodes.length; i++) {
      this.thumbOrder[i] = i
      this.textureKeys.push(`${i}:0`, `${i}:1`)
    }
    /* 光晕预算：节点太多时只给相似关系最强的那一批画光晕（与二维那套同一套语义） */
    const strength = new Float32Array(nodes.length)
    for (const edge of edges) {
      strength[edge.a] += edge.score
      strength[edge.b] += edge.score
    }
    this.haloCount = nodes.length
    if (nodes.length > HALO_BUDGET) {
      const top = Array.from(nodes.keys())
        .sort((a, b) => strength[b] - strength[a] || a - b)
        .slice(0, HALO_BUDGET)
        .filter((index) => index < 65536)
      this.haloCount = top.length
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.haloIndexBuffer)
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(top), gl.STATIC_DRAW)
    }

    /* 起始视角按当前画布形状算出来：写死角度的话，坐标朝向或窗口比例一变就不合适了 */
    this.homeCamera = this.solveHome()
    this.camera = { ...this.homeCamera }
    readStageStyle(canvas, this.style)

    this.attachEvents()
    this.resize()
    this.buildDust()
    this.buildAtlas()
    this.uploadTextures()
    this.detach.push(watchStageStyle(() => { readStageStyle(this.canvas, this.style) }))
    if (document.fonts && document.fonts.status !== 'loaded') {
      document.fonts.ready.then(() => {
        if (this.contextLost) return
        this.buildAtlas()
      })
    }
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
    /* 标签图集是按设备像素 1:1 画的，像素比一变就得重画，否则字会糊 */
    const rebuildAtlas = dpr !== this.dpr
    this.width = width
    this.height = height
    this.dpr = dpr
    this.canvas.width = width
    this.canvas.height = height
    if (rebuildAtlas) this.buildAtlas()
  }

  start() {
    if (this.frame) return
    this.lastFrameAt = performance.now()
    const loop = () => {
      this.frame = requestAnimationFrame(loop)
      const now = performance.now()
      const delta = Math.min(0.05, (now - this.lastFrameAt) / 1000)
      this.lastFrameAt = now
      this.now = now
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

  /** 选中脉冲要不要重画：约 30fps（不降低动效偏好、非低端机、真的选中了东西） */
  private wantsPulseFrame(now: number) {
    if (this.focused === null && this.selectedIndex === null) return false
    if (this.lowEnd || this.style.reducedMotion) return false
    return now - this.lastPulseAt >= PULSE_FRAME_MS
  }

  private render() {
    const gl = this.gl
    if (this.contextLost || !this.width || !this.height) return
    const camera = this.effectiveCamera()
    gl.viewport(0, 0, this.width, this.height)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.enable(gl.BLEND)

    /*
     * 投影结果原地更新：这条数组每帧都被命中判定、连线、缩略图、光晕读到，
     * 复用同一批对象就不必每帧新建（相机自转时这是最热的一条路径）。
     * 投影数学完全没动，还是 camera3d 的 projectPoint。
     */
    const projected = this.projected
    if (projected.length !== this.nodes.length) {
      projected.length = 0
      for (let i = 0; i < this.nodes.length; i++) {
        projected.push({ screenX: 0, screenY: 0, depth: 0, scale: 0, radiusPx: 0 })
      }
    }
    for (let i = 0; i < this.nodes.length; i++) {
      const node = this.nodes[i]
      const point = projectPoint(node.x, node.y, node.z, camera, this.width, this.height)
      const slot = projected[i]
      slot.screenX = point.x
      slot.screenY = point.y
      slot.depth = point.depth
      slot.scale = point.scale
      slot.radiusPx = (node.degree > 0 ? 0.011 + Math.min(node.degree, 24) * 0.0007 : 0.011) * point.scale
    }

    /* 从后往前：舞台 → 星尘 → 连线 → 缩略图 → 光晕/点 → 选中光环 */
    this.drawStage(camera)
    if (!this.lowEnd) this.drawDust(camera)
    this.drawEdges(camera)
    this.drawThumbnails(camera)
    this.drawPoints(camera)
    this.drawRing(camera)
    this.stats.frames++
  }

  /* ------------------------------ uniform / attribute ------------------------------ */

  private applyCameraUniforms(program: WebGLProgram, camera: Camera3D) {
    const gl = this.gl
    gl.uniform2f(this.uniform(program, 'uResolution'), this.width, this.height)
    gl.uniform3f(this.uniform(program, 'uTarget'), camera.targetX, camera.targetY, camera.targetZ)
    gl.uniform1f(this.uniform(program, 'uYaw'), camera.yaw)
    gl.uniform1f(this.uniform(program, 'uPitch'), camera.pitch)
    gl.uniform1f(this.uniform(program, 'uDistance'), camera.distance)
    gl.uniform1f(this.uniform(program, 'uFocal'), FOCAL)
    gl.uniform1f(this.uniform(program, 'uViewMin'), Math.min(this.width, this.height))
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

  private resetAttributes() {
    const gl = this.gl
    const max = gl.getParameter(gl.MAX_VERTEX_ATTRIBS) as number
    for (let i = 0; i < max; i++) gl.disableVertexAttribArray(i)
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

  /** 选中脉冲的强度 0~1（卡片外发光与光环共用）；相位只按约 30fps 推进，非脉冲帧沿用上一次 */
  private selectionPulse() {
    if (this.lowEnd || this.style.reducedMotion) return 0.8
    if (this.now - this.lastPulseAt >= PULSE_FRAME_MS) {
      this.lastPulseAt = this.now
      this.pulsePhase = (this.now % PULSE_PERIOD_MS) / PULSE_PERIOD_MS
    }
    return 0.62 + 0.38 * Math.sin(this.pulsePhase * Math.PI * 2)
  }

  /* ------------------------------ 绘制 ------------------------------ */

  /**
   * 深空舞台。相机是绕着 target 转的，星雾按视线方向采样（见 BACKDROP_FS），
   * 所以转视角时远景几乎不动、近处的雾横向掠过，和二维那套的视差观感一致。
   */
  private drawStage(camera: Camera3D) {
    const gl = this.gl
    const program = this.backdropProgram
    if (!program || !this.quadBuffer || !this.backdropTexture) return
    gl.useProgram(program)
    this.resetAttributes()
    /* 舞台铺满整屏且不透明，不需要混合 */
    gl.disable(gl.BLEND)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.backdropTexture)
    gl.uniform1i(this.uniform(program, 'uTexture'), 0)
    gl.uniform2f(this.uniform(program, 'uResolution'), this.width, this.height)
    gl.uniform1f(this.uniform(program, 'uYaw'), camera.yaw)
    gl.uniform1f(this.uniform(program, 'uPitch'), camera.pitch)
    gl.uniform3fv(this.uniform(program, 'uAccent'), this.style.accents)
    gl.uniform3fv(this.uniform(program, 'uBase'), stageBase(this.style))
    gl.uniform1f(this.uniform(program, 'uHaze'), this.style.light ? 0.4 : 0.32)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuffer)
    const location = this.attribute(program, 'aPos')
    gl.enableVertexAttribArray(location)
    gl.vertexAttribPointer(location, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLES, 0, 6)
    gl.enable(gl.BLEND)
    /* 加色混合：片元里已经预乘过 alpha，这里用 (ONE, ONE) */
    gl.blendFunc(gl.ONE, gl.ONE)
  }

  /** 星尘：每颗按自己的深度取视差、大小、亮度和偏色（视差推导见 DUST_VS） */
  private drawDust(camera: Camera3D) {
    const gl = this.gl
    const program = this.dustProgram
    if (!program || !this.dustBuffer || !this.dustCount) return
    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.uniform3fv(this.uniform(program, 'uAccent'), this.style.accents)
    gl.uniform1f(this.uniform(program, 'uEyeX'), camera.targetX + this.eyeOffsetX(camera))
    gl.uniform1f(this.uniform(program, 'uEyeY'), camera.targetY + this.eyeOffsetY(camera))
    gl.uniform1f(this.uniform(program, 'uEyeZ'), camera.targetZ + this.eyeOffsetZ(camera))
    gl.uniform1f(this.uniform(program, 'uSpread'), DUST_SPREAD)
    /* 星点按屏幕比例定大小，换分辨率观感才一致 */
    gl.uniform1f(this.uniform(program, 'uBasePx'), this.dpr * (this.width / (this.dpr * 1600)))
    gl.uniform1f(this.uniform(program, 'uSizeRef'), this.sizeReference())
    gl.blendFunc(gl.ONE, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dustBuffer)
    this.attach(program, DUST_LAYOUT, 6 * 4)
    gl.drawArrays(gl.POINTS, 0, this.dustCount)
  }

  /**
   * 相机位置（世界坐标）= target + R(yaw,pitch) · (0, 0, distance)。
   * 也就是 PROJECT_GLSL 里那套旋转的逆变换：forward = (-sinY·cosP, sinP, -cosY·cosP)，
   * 相机就在 target 的 -forward 方向上。星尘的视差要按层缩放相机位置，所以这里必须和
   * 顶点着色器里的旋转保持一致，写错了两层会错位。
   */
  private eyeOffsetX(camera: Camera3D) {
    return Math.sin(camera.yaw) * Math.cos(camera.pitch) * camera.distance
  }
  private eyeOffsetY(camera: Camera3D) {
    return -Math.sin(camera.pitch) * camera.distance
  }
  private eyeOffsetZ(camera: Camera3D) {
    return Math.cos(camera.yaw) * Math.cos(camera.pitch) * camera.distance
  }

  /**
   * 基准距离（全景）下的透视 scale：星点大小用它归一化，远近才有可比性。
   * 注意 uViewMin 用的是**当前画布**，不是 homeCamera 那会儿的尺寸，窗口改过也自洽。
   */
  private sizeReference() {
    return FOCAL / Math.max(this.homeCamera.distance, 0.05) * Math.min(this.width, this.height) * 0.5
  }

  /**
   * 相似边。和二维那套同一套语义：
   *   · 透明度随相似度渐变（低分几乎看不见，高分明亮）
   *   · 颜色在两端点的角色色之间按相似度混合 —— 同一组的连线自然同色
   *   · 有焦点（推近/悬停/选中）时只留连着它的边，其余压到很低
   * 每帧重建数据：节点在透视下会前后换位，缓存排序反而更贵。
   */
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
      const strength = Math.max(0, Math.min(1, (edge.score - 0.25) / 0.6))
      /* 相似度越高越亮：低分几乎看不见，高分明显（和二维那套同一条曲线） */
      let alpha = 0.06 + Math.pow(strength, 1.2) * 0.42
      if (focus !== null) alpha = (edge.a === focus || edge.b === focus) ? 0.32 + strength * 0.6 : alpha * 0.16
      alpha *= depthAlpha(Math.min(a.depth, b.depth), 0, 3.2, 0.06)
      if (alpha <= 0.002) continue
      const nodeA = this.nodes[edge.a]
      const nodeB = this.nodes[edge.b]
      const mix = strength
      this.edgeData[write++] = nodeA.x
      this.edgeData[write++] = nodeA.y
      this.edgeData[write++] = nodeA.z
      this.edgeData[write++] = nodeA.color[0] + (nodeB.color[0] - nodeA.color[0]) * mix
      this.edgeData[write++] = nodeA.color[1] + (nodeB.color[1] - nodeA.color[1]) * mix
      this.edgeData[write++] = nodeA.color[2] + (nodeB.color[2] - nodeA.color[2]) * mix
      this.edgeData[write++] = nodeB.x
      this.edgeData[write++] = nodeB.y
      this.edgeData[write++] = nodeB.z
      this.edgeData[write++] = nodeA.color[0] + (nodeB.color[0] - nodeA.color[0]) * mix
      this.edgeData[write++] = nodeA.color[1] + (nodeB.color[1] - nodeA.color[1]) * mix
      this.edgeData[write++] = nodeA.color[2] + (nodeB.color[2] - nodeA.color[2]) * mix
    }
    if (!write) return

    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.blendFunc(gl.ONE, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.edgeBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.edgeData.subarray(0, write), gl.DYNAMIC_DRAW)
    this.attach(program, EDGE_LAYOUT, 6 * 4)
    gl.drawArrays(gl.LINES, 0, write / 6)
  }

  /** 图片点：预渲染光晕（大一圈、加色）打底，再叠一个亮核 */
  private drawPoints(camera: Camera3D) {
    const gl = this.gl
    const program = this.pointProgram
    if (!program || !this.pointBuffer) return

    const focus = this.focused ?? this.hoverIndex ?? this.selectedIndex
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
      if (focus !== null && focus !== i) alpha *= 0.35
      if (this.focused === i) alpha = 1
      this.pointData[base + 7] = alpha
    }

    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, this.pointData, gl.DYNAMIC_DRAW)

    /* 光晕先画：节点太多时只画相似关系最强的一批 */
    if (this.haloProgram) {
      const halo = this.haloProgram
      gl.useProgram(halo)
      this.resetAttributes()
      this.applyCameraUniforms(halo, camera)
      gl.activeTexture(gl.TEXTURE1)
      gl.bindTexture(gl.TEXTURE_2D, this.spriteTexture)
      gl.activeTexture(gl.TEXTURE0)
      gl.uniform1i(this.uniform(halo, 'uSprite'), 1)
      gl.uniform1f(this.uniform(halo, 'uScale'), HALO_SCALE)
      gl.uniform1f(this.uniform(halo, 'uMaxSize'), Math.min(this.maxPointSize, 220))
      gl.uniform1f(this.uniform(halo, 'uGain'), 0.55)
      gl.blendFunc(gl.ONE, gl.ONE)
      gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
      this.attach(halo, POINT_LAYOUT, 8 * 4)
      if (this.haloCount < this.nodes.length && this.haloIndexBuffer) {
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.haloIndexBuffer)
        gl.drawElements(gl.POINTS, this.haloCount, gl.UNSIGNED_SHORT, 0)
      } else {
        gl.drawArrays(gl.POINTS, 0, this.nodes.length)
      }
    }

    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.blendFunc(gl.ONE, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    this.attach(program, POINT_LAYOUT, 8 * 4)
    gl.drawArrays(gl.POINTS, 0, this.nodes.length)
  }

  private drawThumbnails(camera: Camera3D) {
    this.selectedThumbAlpha = 0
    const gl = this.gl
    const program = this.thumbProgram
    if (!program || !this.thumbBuffer || !this.nodes.length) return

    /*
     * 缩略图什么时候出现：相机拉近到一定程度才显示。
     * 全景时一两百张图糊在一起只会变成马赛克，还不如干净的星点。
     */
    const closeness = Math.max(0, Math.min(1, (this.homeCamera.distance * 0.86 - camera.distance) / (this.homeCamera.distance * 0.5)))
    this.stats.lastCloseness = closeness
    if (closeness <= 0.02) return

    /* 远→近排序，画家算法；同时挑出屏幕内的，按离屏心距离截断。
       顺序数组跨帧复用、原地插入排序，帧内不分配数组（与二维那套一致）。 */
    const order = this.thumbOrder
    const distance = this.thumbDistance
    let visible = 0
    for (let i = 0; i < this.nodes.length; i++) {
      const point = this.projected[i]
      const inside = point.depth > 0.06 && point.depth < FAR_CULL
        && point.screenX > -THUMB_MAX_PX && point.screenX < this.width + THUMB_MAX_PX
        && point.screenY > -THUMB_MAX_PX && point.screenY < this.height + THUMB_MAX_PX
      distance[i] = inside ? point.depth : Infinity
      if (inside) visible++
    }
    if (!visible) return
    sortIndicesByKey(order, distance, false)

    const focus = this.focused ?? this.selectedIndex
    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.activeTexture(gl.TEXTURE2)
    gl.bindTexture(gl.TEXTURE_2D, this.atlasTexture)
    gl.activeTexture(gl.TEXTURE0)
    gl.uniform1i(this.uniform(program, 'uTexture'), 0)
    gl.uniform1i(this.uniform(program, 'uAtlas'), 2)
    gl.uniform1f(this.uniform(program, 'uStroke'), this.dpr)
    const uSizePx = this.uniform(program, 'uSizePx')
    const uHalfPx = this.uniform(program, 'uHalfPx')
    const uRadius = this.uniform(program, 'uRadius')
    const uSelect = this.uniform(program, 'uSelect')
    const uChip = this.uniform(program, 'uChip')
    const uChipUv = this.uniform(program, 'uChipUv')
    gl.bindBuffer(gl.ARRAY_BUFFER, this.thumbBuffer)
    this.attach(program, THUMB_LAYOUT, 8 * 4)

    /* 卡片用预乘 alpha 的普通叠加（和二维那套一致）；外圈的选中光不占 alpha，等于加色 */
    gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA)

    const select = this.selectionPulse()
    const chipHeight = this.atlas ? this.atlas.chipHeight : 0
    const chipVisible = this.atlas ? 1 : 0
    const level = THUMB_MAX_PX > THUMB_LARGE_AT ? 1 : 0
    /* uniform 跨绘制保留，值没变就不重复上传 */
    let lastSizeW = -1
    let lastSizeH = -1
    let lastHalfW = -1
    let lastHalfH = -1
    let lastRadius = -1
    let lastSelect = -1
    let lastLabel: Float32Array | null | undefined
    let lastTextW = -1
    let lastChipFade = -1
    let drawn = 0
    for (let n = 0; n < this.nodes.length && drawn < MAX_THUMBS_PER_FRAME; n++) {
      const index = order[n]
      const point = this.projected[index]
      if (!Number.isFinite(distance[index]) || !point) continue
      const node = this.nodes[index]
      /* 屏幕长边：随透视缩放，并且离得越近越大；封顶免得一张图糊满屏 */
      const longPx = Math.min(0.085 * point.scale, THUMB_MAX_PX)
      if (longPx < 26) {
        this.skipStats.small++
        continue
      }
      const assetLevel = longPx > THUMB_LARGE_AT ? 1 : 0
      const texture = this.textureFor(index, assetLevel, 1e7 - point.depth)
      this.stats.thumbCandidates++
      this.stats.lastLongPx = longPx
      if (!texture) {
        this.skipStats.noTexture++
        continue
      }

      const aspect = node.aspect > 0.05 ? node.aspect : 1
      const widthPx = aspect >= 1 ? longPx : longPx * aspect
      const heightPx = aspect >= 1 ? longPx / aspect : longPx
      let alpha = closeness * depthAlpha(point.depth, 0.1, 3.4, 0.25)
      if (focus !== null && focus !== index) alpha *= this.focused !== null ? 0.22 : 0.5
      if (alpha <= 0.03) {
        this.skipStats.lowAlpha++
        continue
      }

      /* 卡片比图大一圈：外圈留着画投影和选中外发光（同二维那套的 CARD_MARGIN） */
      const selected = index === this.selectedIndex
      const marginPx = (selected ? CARD_SELECTED_MARGIN : CARD_MARGIN) * this.dpr
      const sizePx = Math.max(1, widthPx + marginPx * 2)
      const sizePy = Math.max(1, heightPx + marginPx * 2)
      const growX = sizePx / Math.max(widthPx, 1e-4)
      const growY = sizePy / Math.max(heightPx, 1e-4)
      for (let corner = 0; corner < 4; corner++) {
        const base = corner * 8
        this.thumbData[base] = node.x
        this.thumbData[base + 1] = node.y
        this.thumbData[base + 2] = node.z
        this.thumbData[base + 3] = CORNERS[corner * 2] * growX
        this.thumbData[base + 4] = CORNERS[corner * 2 + 1] * growY
        this.thumbData[base + 5] = node.color[0]
        this.thumbData[base + 6] = node.color[1]
        this.thumbData[base + 7] = node.color[2]
      }
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, this.thumbData)
      if (sizePx !== lastSizeW || sizePy !== lastSizeH) {
        gl.uniform2f(uSizePx, sizePx, sizePy)
        lastSizeW = sizePx
        lastSizeH = sizePy
      }
      /* 片内坐标要换算成设备像素，圆角距离场才能按物理像素算 */
      const halfW = sizePx * 0.5
      const halfH = sizePy * 0.5
      if (halfW !== lastHalfW || halfH !== lastHalfH) {
        gl.uniform2f(uHalfPx, halfW, halfH)
        lastHalfW = halfW
        lastHalfH = halfH
      }
      const radius = Math.min(Math.min(halfW, halfH) * 0.16, 12 * this.dpr)
      if (radius !== lastRadius) {
        gl.uniform1f(uRadius, radius)
        lastRadius = radius
      }
      const selectValue = selected ? select : 0
      if (selectValue !== lastSelect) {
        gl.uniform1f(uSelect, selectValue)
        lastSelect = selectValue
      }
      const label = this.nodeLabels[index] ?? null
      /* 芯片在卡片太窄时缩掉 / 不显示，免得溢出格子 */
      const longCss = longPx / this.dpr
      const chipFade = label && chipHeight
        ? Math.max(0, Math.min(1, (longCss - CHIP_FADE_FROM) / (CHIP_FADE_TO - CHIP_FADE_FROM)))
        : 0
      const textW = label && label[4] + chipHeight * 2.2 <= halfW * 2 ? label[4] : 0
      if (label !== lastLabel || textW !== lastTextW || chipFade !== lastChipFade) {
        if (label && chipFade > 0) {
          gl.uniform4f(uChip, chipFade * chipVisible, chipHeight, textW, label[5])
          gl.uniform4f(uChipUv, label[0], label[1], label[2], label[3])
        } else {
          gl.uniform4f(uChip, 0, 0, 0, 0)
        }
        lastLabel = label
        lastTextW = textW
        lastChipFade = chipFade
      }
      gl.bindTexture(gl.TEXTURE_2D, texture)
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      if (selected) this.selectedThumbAlpha = alpha
      drawn++
      this.stats.thumbDrawn++
    }
    gl.blendFunc(gl.ONE, gl.ONE)
    if (drawn > 0) this.evictTextures()
  }

  /** 选中光环：缩略图还没出来（全景/远景）时用它，卡片外发光出来后自动让位 */
  private drawRing(camera: Camera3D) {
    const index = this.selectedIndex
    const ringAlpha = 1 - this.selectedThumbAlpha
    if (index === null || index >= this.nodes.length || ringAlpha <= 0.02) return
    const gl = this.gl
    const program = this.ringProgram
    if (!program || !this.pointBuffer) return
    const pulse = !this.lowEnd && !this.style.reducedMotion
    this.selectionPulse()
    gl.useProgram(program)
    this.resetAttributes()
    this.applyCameraUniforms(program, camera)
    gl.uniform1f(this.uniform(program, 'uScale'), RING_SCALE)
    gl.uniform1f(this.uniform(program, 'uMaxSize'), this.maxPointSize)
    gl.uniform1f(this.uniform(program, 'uLine'), this.dpr * 0.8)
    gl.uniform1f(this.uniform(program, 'uPhase'), pulse ? this.pulsePhase : 0)
    gl.uniform1f(this.uniform(program, 'uPing'), pulse ? 1 : 0)
    gl.uniform1f(this.uniform(program, 'uAlpha'), ringAlpha)
    gl.blendFunc(gl.ONE, gl.ONE)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.pointBuffer)
    this.attach(program, POINT_LAYOUT, 8 * 4)
    gl.drawArrays(gl.POINTS, index, 1)
  }

  /* ------------------------------ 纹理 / 图集 ------------------------------ */

  /** 星尘：固定的立方体分布 + 逐颗的深度、取色位置、随机数（种子固定，进页面每次一样） */
  private buildDust() {
    const data = this.dustData
    let seed = 0x51f3a7
    const random = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0
      return seed / 4294967296
    }
    for (let i = 0; i < this.dustCount; i++) {
      const base = i * 6
      data[base] = random() * 2 - 1
      data[base + 1] = random() * 2 - 1
      data[base + 2] = random() * 2 - 1
      /* 远处的多、近处的少 */
      data[base + 3] = Math.pow(random(), 1.7)
      data[base + 4] = random()
      data[base + 5] = random()
    }
    const gl = this.gl
    if (!this.dustBuffer) return
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dustBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
  }

  /** 标签图集：按设备像素 1:1 画，只在构造、像素比变化、字体就绪时重建 */
  private buildAtlas() {
    const gl = this.gl
    const atlas = createLabelAtlas(this.nodes.map((node) => node.name), this.dpr)
    this.atlas = atlas
    for (let i = 0; i < this.nodes.length; i++) this.nodeLabels[i] = atlas.rects.get(this.nodes[i].name) ?? null
    if (!this.atlasTexture) return
    gl.activeTexture(gl.TEXTURE0)
    uploadCanvasTexture(gl, this.atlasTexture, atlas.canvas, false, false)
  }

  /** 舞台纹理与光晕纹理：只在初始化（以及上下文恢复）时生成一次 */
  private uploadTextures() {
    const gl = this.gl
    gl.activeTexture(gl.TEXTURE0)
    if (this.backdropTexture) uploadCanvasTexture(gl, this.backdropTexture, createHazeCanvas(), true, false)
    if (this.spriteTexture) uploadCanvasTexture(gl, this.spriteTexture, createGlowSprite(), false, true)
    if (this.atlas && this.atlasTexture) uploadCanvasTexture(gl, this.atlasTexture, this.atlas.canvas, false, false)

    if (this.thumbTexture) {
      gl.bindTexture(gl.TEXTURE_2D, this.thumbTexture)
      /* 1×1 的白色占位：万一某帧没绑纹理就采样，也不会得到「不完整纹理」的全黑结果 */
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]))
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    }

    if (this.quadBuffer) {
      gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuffer)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW)
    }
  }

  private textureKey(index: number, level: number) {
    return this.textureKeys[index * 2 + level] ?? `${index}:${level}`
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
      this.stats.textureRequested++
      const image = new Image()
      image.crossOrigin = 'anonymous'
      entry.image = image
      image.onload = () => {
        entry.state = 'ready'
        entry.aspect = image.naturalWidth && image.naturalHeight ? image.naturalWidth / image.naturalHeight : 1
        if (entry.texture) this.gl.deleteTexture(entry.texture)
        entry.texture = this.upload(image)
        this.stats.textureReady++
        this.loading--
        this.pump()
      }
      image.onerror = () => {
        entry.state = 'failed'
        this.stats.textureFailed++
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
    for (const buffer of [this.pointBuffer, this.edgeBuffer, this.thumbBuffer, this.dustBuffer, this.quadBuffer, this.haloIndexBuffer]) {
      if (buffer) gl.deleteBuffer(buffer)
    }
    for (const texture of [this.backdropTexture, this.thumbTexture, this.spriteTexture, this.atlasTexture]) {
      if (texture) gl.deleteTexture(texture)
    }
    for (const program of [this.pointProgram, this.edgeProgram, this.thumbProgram, this.backdropProgram, this.dustProgram, this.haloProgram, this.ringProgram]) {
      if (program) gl.deleteProgram(program)
    }
    try { gl.getExtension('WEBGL_lose_context')?.loseContext() } catch { /* 有些实现没这个扩展 */ }
  }
}

/**
 * 星系两套引擎（engine.ts / engine3d.ts）共用的绘制素材：舞台配色、预渲染纹理、共用片元着色器。
 * 纹理都只在构造（或主题、像素比变化）时生成一次，帧内只复用。
 */
import { THEMES } from '../themes'

export interface StageStyle {
  /** 三个强调色（与背景流体同源），sRGB 0~1，依次 rgb rgb rgb */
  accents: Float32Array
  light: boolean
  reducedMotion: boolean
}

/* 主题预设：页面没有暴露 --accent-1~3 时按 data-theme-name 兜底。
   值从 themes.ts 派生 —— 主题目录是全站唯一真源，新增主题星系自动跟进 */
const THEME_PRESETS: Record<string, string[]> = Object.fromEntries(
  THEMES.map((theme) => [theme.name.toLowerCase(), [...theme.accents]]),
)

/** 舞台底色（上、下）。浅色主题也保持深空，只略微提亮 */
const STAGE_DARK = new Float32Array([0.043, 0.055, 0.118, 0.02, 0.024, 0.051])
const STAGE_LIGHT = new Float32Array([0.075, 0.09, 0.2, 0.035, 0.043, 0.098])

export function stageBase(style: StageStyle) {
  return style.light ? STAGE_LIGHT : STAGE_DARK
}

export function createStageStyle(): StageStyle {
  return { accents: new Float32Array(9), light: false, reducedMotion: false }
}

/** 与 FluidCanvas 相同的低端机判断 */
export function isLowEndDevice() {
  const nav = navigator as Navigator & { deviceMemory?: number }
  return (nav.hardwareConcurrency || 8) <= 4 || (nav.deviceMemory || 8) <= 4
}

let colorProbe: CanvasRenderingContext2D | null = null
const scratch = new Float32Array(9)

/** 任意 CSS 颜色 → 0~1 RGB；借 2D canvas 规范化，hex / rgb() / hsl() / 颜色名都能吃 */
function parseColor(value: string, out: Float32Array, offset: number) {
  const text = value.trim()
  if (!text) return false
  colorProbe ??= document.createElement('canvas').getContext('2d')
  if (!colorProbe) return false
  colorProbe.fillStyle = '#010203'
  colorProbe.fillStyle = text
  const normalized = String(colorProbe.fillStyle)
  let r = 0
  let g = 0
  let b = 0
  if (normalized.startsWith('#')) {
    if (normalized === '#010203' && text.toLowerCase() !== '#010203') return false
    const n = Number.parseInt(normalized.slice(1, 7), 16)
    r = (n >> 16) & 255
    g = (n >> 8) & 255
    b = n & 255
  } else {
    const parts = normalized.match(/[\d.]+/g)
    if (!parts || parts.length < 3) return false
    r = Number(parts[0])
    g = Number(parts[1])
    b = Number(parts[2])
  }
  out[offset] = r / 255
  out[offset + 1] = g / 255
  out[offset + 2] = b / 255
  return true
}

/** 读当前舞台配色；有变化返回 true */
export function readStageStyle(element: Element, style: StageStyle) {
  const root = document.documentElement
  const computed = getComputedStyle(element)
  let ok = true
  for (let i = 0; i < 3 && ok; i++) ok = parseColor(computed.getPropertyValue(`--accent-${i + 1}`), scratch, i * 3)
  if (!ok) {
    const preset = THEME_PRESETS[(root.dataset.themeName || '').toLowerCase()] || THEME_PRESETS.aurora
    for (let i = 0; i < 3; i++) parseColor(preset[i], scratch, i * 3)
  }
  const light = root.dataset.theme === 'light'
  const reducedMotion = root.dataset.motion === 'reduced' || window.matchMedia('(prefers-reduced-motion: reduce)').matches
  let changed = light !== style.light || reducedMotion !== style.reducedMotion
  for (let i = 0; i < 9; i++) if (Math.abs(scratch[i] - style.accents[i]) > 1e-4) changed = true
  if (changed) {
    style.accents.set(scratch)
    style.light = light
    style.reducedMotion = reducedMotion
  }
  return changed
}

/** 主题、配色、动效偏好变化时回调（合并成一次，不逐帧读样式） */
export function watchStageStyle(onChange: () => void) {
  let timer = 0
  const schedule = () => {
    if (!timer) timer = window.setTimeout(() => { timer = 0; onChange() }, 120)
  }
  const observer = new MutationObserver(schedule)
  const attributeFilter = ['style', 'class', 'data-theme', 'data-theme-name', 'data-motion']
  observer.observe(document.documentElement, { attributes: true, attributeFilter })
  if (document.body) observer.observe(document.body, { attributes: true, attributeFilter })
  const media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener?.('change', schedule)
  return () => {
    observer.disconnect()
    media.removeEventListener?.('change', schedule)
    if (timer) window.clearTimeout(timer)
  }
}

function seeded(seed: number) {
  return () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296
}

/**
 * 星云纹理：R、G 两个通道各存一层云的浓度，B 存星点；着色器按三个强调色染色，换主题不必重画。
 * 每团云在 3×3 个位置各画一次，纹理按 REPEAT 平铺时没有接缝。
 */
export function createHazeCanvas() {
  const size = 512
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, size, size)
  ctx.globalCompositeOperation = 'lighter'
  const random = seeded(0x2f6e2b1)
  const blob = (x: number, y: number, radius: number, rgb: string, alpha: number) => {
    for (let ox = -size; ox <= size; ox += size) {
      for (let oy = -size; oy <= size; oy += size) {
        const cx = x + ox
        const cy = y + oy
        if (cx + radius < 0 || cx - radius > size || cy + radius < 0 || cy - radius > size) continue
        const gradient = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius)
        gradient.addColorStop(0, `rgba(${rgb},${alpha})`)
        gradient.addColorStop(0.5, `rgba(${rgb},${alpha * 0.4})`)
        gradient.addColorStop(1, `rgba(${rgb},0)`)
        ctx.fillStyle = gradient
        ctx.fillRect(cx - radius, cy - radius, radius * 2, radius * 2)
      }
    }
  }
  for (let i = 0; i < 16; i++) blob(random() * size, random() * size, size * (0.12 + random() * 0.24), '255,0,0', 0.24 + random() * 0.22)
  for (let i = 0; i < 16; i++) blob(random() * size, random() * size, size * (0.1 + random() * 0.22), '0,255,0', 0.24 + random() * 0.22)
  for (let i = 0; i < 1100; i++) {
    const big = random() > 0.94
    ctx.fillStyle = `rgba(0,0,255,${big ? 0.65 + random() * 0.35 : 0.16 + random() * 0.46})`
    ctx.beginPath()
    ctx.arc(random() * size, random() * size, big ? 1.15 : 0.6, 0, Math.PI * 2)
    ctx.fill()
  }
  return canvas
}

/** 节点光晕：灰度存在 R 通道，亮核 + 大范围柔光两段 */
export function createGlowSprite() {
  const size = 128
  const half = size / 2
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, size, size)
  const gradient = ctx.createRadialGradient(half, half, 0, half, half, half)
  const stops: Array<[number, number]> = [[0, 1], [0.07, 0.8], [0.18, 0.44], [0.34, 0.2], [0.55, 0.075], [0.8, 0.018], [1, 0]]
  for (const [at, value] of stops) {
    const v = Math.round(value * 255)
    gradient.addColorStop(at, `rgb(${v},${v},${v})`)
  }
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, size, size)
  return canvas
}

export interface LabelAtlas {
  canvas: HTMLCanvasElement
  /** 标签 → [u0, v0, u1, v1, 文字宽(px), 行高(px)] */
  rects: Map<string, Float32Array>
  /** 标签芯片高度（设备像素） */
  chipHeight: number
}

/** 标签芯片的文字图集：白字黑底，采样 R 通道当覆盖率。按设备像素 1:1 绘制，字是清楚的 */
export function createLabelAtlas(labels: Iterable<string>, pixelRatio: number): LabelAtlas {
  const fontSize = Math.round(11 * pixelRatio)
  const cell = Math.ceil(fontSize * 1.55)
  const maxText = Math.round(112 * pixelRatio)
  const width = 1024
  const unique = [...new Set(labels)].filter(Boolean).slice(0, 400)
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')!
  const font = `600 ${fontSize}px "DM Sans", system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`
  ctx.font = font
  const placed: Array<{ text: string; x: number; y: number; w: number }> = []
  let x = 0
  let y = 0
  for (const label of unique) {
    let text = label
    let w = Math.ceil(ctx.measureText(text).width)
    while (w > maxText && text.length > 1) {
      text = text.slice(0, -1)
      w = Math.ceil(ctx.measureText(`${text}…`).width)
    }
    if (text !== label) text = `${text}…`
    if (x + w + 4 > width) {
      x = 0
      y += cell
    }
    placed.push({ text, x: x + 2, y, w })
    x += w + 6
  }
  canvas.width = width
  canvas.height = Math.max(1, y + cell)
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.font = font
  ctx.fillStyle = '#fff'
  ctx.textBaseline = 'middle'
  const rects = new Map<string, Float32Array>()
  placed.forEach((item, index) => {
    ctx.fillText(item.text, item.x, item.y + cell / 2)
    rects.set(unique[index], new Float32Array([
      item.x / width, item.y / canvas.height, (item.x + item.w) / width, (item.y + cell) / canvas.height, item.w, cell,
    ]))
  })
  return { canvas, rects, chipHeight: cell + Math.round(4 * pixelRatio) }
}

/** 把一块 2D canvas 交给 WebGL；纹理对象由调用方持有并复用 */
export function uploadCanvasTexture(gl: WebGLRenderingContext | WebGL2RenderingContext, texture: WebGLTexture, source: HTMLCanvasElement, repeat: boolean, mipmap: boolean) {
  gl.bindTexture(gl.TEXTURE_2D, texture)
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, 0)
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
  const wrap = repeat ? gl.REPEAT : gl.CLAMP_TO_EDGE
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mipmap ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
  if (mipmap) gl.generateMipmap(gl.TEXTURE_2D)
}

/**
 * 按 key 原地插入排序。帧与帧之间顺序几乎不变，插入排序接近 O(n)，而且不分配内存。
 * ascending=false 时降序。
 */
export function sortIndicesByKey(order: Int32Array, key: Float32Array, ascending: boolean) {
  for (let i = 1; i < order.length; i++) {
    const item = order[i]
    const value = key[item]
    let j = i - 1
    while (j >= 0 && (ascending ? key[order[j]] > value : key[order[j]] < value)) {
      order[j + 1] = order[j]
      j--
    }
    order[j + 1] = item
  }
}

/* ------------------------------ 共用着色器片段 ------------------------------ */

/** 深空底：上下渐变 + 暗角 + 三色雾 + 星点。far / near 是两层视差采样的星云纹理 */
export const STAGE_GLSL = `
uniform sampler2D uTexture;
uniform vec3 uAccent[3];
uniform vec3 uBase[2];
uniform float uHaze;
vec3 stage(vec2 ndc, vec4 far, vec4 near) {
  float mask = 1.0 - smoothstep(0.1, 0.5, length(ndc * 0.5));
  vec3 base = mix(uBase[1], uBase[0], ndc.y * 0.5 + 0.5);
  base *= 0.74 + 0.26 * (1.0 - smoothstep(0.3, 1.5, length(ndc)));
  vec3 haze = uAccent[0] * far.r + uAccent[1] * far.g * 0.85 + uAccent[2] * near.r * (0.4 + 0.6 * mask);
  float stars = far.b * 0.8 + near.b * 0.45 * mask;
  return base + haze * uHaze + vec3(0.86, 0.9, 1.0) * stars;
}
`

/** 尘埃的调色：按 0~1 在三个强调色之间取色 */
export const ACCENT_GLSL = `
uniform vec3 uAccent[3];
vec3 accentAt(float t) {
  return t < 0.5 ? mix(uAccent[0], uAccent[1], t * 2.0) : mix(uAccent[1], uAccent[2], t * 2.0 - 1.0);
}
`

export const DUST_FRAG = `
precision mediump float;
varying vec3 vColor;
varying float vAlpha;
void main() {
  vec2 uv = gl_PointCoord * 2.0 - 1.0;
  float a = (1.0 - smoothstep(0.15, 1.0, length(uv))) * vAlpha;
  gl_FragColor = vec4(vColor * a, a);
}
`

/** 光晕：采样预渲染的光晕纹理，输出预乘 alpha，配 (ONE, ONE) 加色混合 */
export const HALO_FRAG = `
precision mediump float;
uniform sampler2D uSprite;
uniform float uGain;
varying vec3 vColor;
varying float vAlpha;
void main() {
  float a = texture2D(uSprite, gl_PointCoord).r * vAlpha * uGain;
  gl_FragColor = vec4(vColor * a, a);
}
`

/** 选中光环：常亮细环 + 向外扩散的脉冲环（uPing=0 时只留常亮环） */
export const RING_FRAG = `
precision mediump float;
uniform float uPhase;
uniform float uPing;
uniform float uAlpha;
varying vec3 vColor;
varying float vPixel;
void main() {
  float r = length(gl_PointCoord * 2.0 - 1.0);
  float steady = 1.0 - smoothstep(vPixel * 0.5, vPixel * 1.5, abs(r - 0.44));
  float ping = (1.0 - smoothstep(vPixel * 0.5, vPixel * 2.5, abs(r - mix(0.44, 0.97, uPhase)))) * (1.0 - uPhase) * uPing;
  float a = (steady * 0.9 + ping * 0.7) * uAlpha;
  gl_FragColor = vec4(mix(vColor, vec3(1.0), 0.45) * a, a);
}
`

/**
 * 缩略图卡片：像素空间的圆角距离场 + 玻璃描边（顶边更亮）+ 投影 + 选中外发光 + 左下角标签芯片。
 * vLocal 是 -1~1 的片内坐标（y 朝上，可超出 ±1 画外圈），uHalfPx 是卡片半宽高（设备像素）。
 * 输出预乘 alpha，配 (ONE, ONE_MINUS_SRC_ALPHA)。
 */
export const CARD_FRAG = `
precision mediump float;
uniform sampler2D uTexture;
uniform sampler2D uAtlas;
uniform vec2 uHalfPx;
uniform float uRadius;
uniform float uStroke;
uniform float uSelect;
uniform vec4 uChip;    // x: 可见度, y: 芯片高, z: 文字宽, w: 文字行高（像素）
uniform vec4 uChipUv;
varying vec2 vLocal;
varying vec4 vTint;
float roundedBox(vec2 p, vec2 halfSize, float radius) {
  vec2 q = abs(p) - halfSize + radius;
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}
void main() {
  vec2 p = vLocal * uHalfPx;
  float d = roundedBox(p, uHalfPx, uRadius);
  float fill = clamp(0.5 - d, 0.0, 1.0);
  vec4 texel = texture2D(uTexture, clamp(vLocal * 0.5 + 0.5, 0.0, 1.0));
  vec3 color = texel.rgb * (1.0 - smoothstep(-0.25, 1.0, -vLocal.y) * 0.24);
  color += vTint.rgb * smoothstep(-uStroke * 7.0, 0.0, d) * 0.14;
  float stroke = 1.0 - smoothstep(uStroke * 0.5, uStroke * 1.4, abs(d + uStroke * 0.9));
  vec3 rim = mix(vec3(1.0), mix(vTint.rgb, vec3(1.0), 0.35), uSelect);
  color = mix(color, rim, stroke * (0.12 + 0.3 * smoothstep(-0.2, 1.0, vLocal.y) + 0.5 * uSelect));
  if (uChip.x > 0.0) {
    float h = uChip.y;
    float w = uChip.z > 0.0 ? h * 0.92 + uChip.z + h * 0.42 : h;
    float m = h * 0.42;
    vec2 q = p - vec2(-uHalfPx.x + m + w * 0.5, -uHalfPx.y + m + h * 0.5);
    float cd = roundedBox(q, vec2(w, h) * 0.5, h * 0.5);
    vec3 chip = mix(color, vec3(0.03, 0.04, 0.09), 0.64);
    chip = mix(chip, vec3(1.0), (1.0 - smoothstep(uStroke * 0.4, uStroke * 1.3, abs(cd + uStroke * 0.8))) * 0.22);
    float r = length(q - vec2(-w * 0.5 + h * 0.5, 0.0));
    chip += vTint.rgb * exp(-r / (h * 0.2)) * 0.45;
    chip = mix(chip, mix(vTint.rgb, vec3(1.0), 0.18), clamp(h * 0.17 - r + 0.5, 0.0, 1.0));
    vec2 t = vec2(q.x + w * 0.5 - h * 0.92, (h * 0.5 - q.y) - (h - uChip.w) * 0.5) / vec2(max(uChip.z, 1.0), uChip.w);
    float ink = texture2D(uAtlas, mix(uChipUv.xy, uChipUv.zw, clamp(t, 0.0, 1.0))).r;
    ink *= step(0.0, t.x) * step(t.x, 1.0) * step(0.0, t.y) * step(t.y, 1.0) * step(1.0, uChip.z);
    chip = mix(chip, vec3(0.95, 0.97, 1.0), ink);
    color = mix(color, chip, clamp(0.5 - cd, 0.0, 1.0) * uChip.x);
  }
  float alpha = fill * texel.a;
  float outside = max(d, 0.0);
  float shadow = (1.0 - fill) * exp(-outside / (uStroke * 4.0)) * 0.34;
  vec3 glow = mix(vTint.rgb, vec3(1.0), 0.3) * (1.0 - fill) * uSelect * exp(-outside / (uStroke * 5.5));
  gl_FragColor = vec4(color * alpha + glow, alpha + shadow * (1.0 - alpha)) * vTint.a;
}
`

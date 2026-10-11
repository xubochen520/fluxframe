<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ palette?: string[]; speed?: number; inline?: boolean; paused?: boolean; edgeColor?: string; edgeHighlight?: boolean }>(), {
  palette: () => ['#4f46e5', '#06b6d4', '#f472b6'],
  speed: 0.7,
  inline: false,
  paused: false,
  edgeColor: '#8b5cf6',
  edgeHighlight: false,
})

const canvas = ref<HTMLCanvasElement>()

/* ===== 每实例独立的性能控制状态（参考视频类 App：分档限帧、空闲即停、后台即停） ===== */
let updateColors: (() => void) | undefined
let wake: (() => void) | undefined
let resizeObserver: ResizeObserver | undefined
let abort: AbortController | undefined
let themeObserver: MutationObserver | undefined
let rafId = 0
let scheduled = false
let destroyed = false
let paused = props.paused
let frozen = true
let visible = true
let lastDraw = 0
let lastActivity = 0
let targetFps = 60
let speedFactor = 1
let edgeX = 0
let edgeXAt = Number.NEGATIVE_INFINITY

function hexToRgb(hex: string) {
  const value = hex.replace('#', '')
  const normalized = value.length === 3 ? value.split('').map((part) => part + part).join('') : value
  const number = Number.parseInt(normalized, 16)
  return [((number >> 16) & 255) / 255, ((number >> 8) & 255) / 255, (number & 255) / 255] as const
}

/* ===== 只改配色参数：自适应档位（像素预算 / 帧率分档 / 空闲冻结 / 后台暂停）一概不动 ===== */

/** 色彩空间用的三原色是归一化 RGB 三元组（与上方 hexToRgb 的返回形状一致） */
type Rgb = readonly [number, number, number]

/**
 * 浅色主题下页面底色是亮的，着色器仍按「加色/滤色」的直觉给颜色会被冲淡，
 * 所以浅色时把配色压暗一档并抬一点饱和度：叠在亮底上反而更实。
 * 只做一次，帧内不参与。
 */
function normalizeRgb(rgb: Rgb, dark: boolean): Rgb {
  if (dark) return rgb
  const max = Math.max(rgb[0], rgb[1], rgb[2])
  const min = Math.min(rgb[0], rgb[1], rgb[2])
  const lightness = (max + min) / 2
  const span = max - min
  const saturation = span < 1e-4 ? 0 : span / (1 - Math.abs(2 * lightness - 1))
  const targetL = Math.min(lightness * 0.78, 0.6)
  const targetS = Math.min(1, saturation * 1.12 + 0.06)
  const chroma = (1 - Math.abs(2 * targetL - 1)) * targetS
  const h = span < 1e-4 ? 0 : ((max === rgb[0] ? (rgb[1] - rgb[2]) / span : max === rgb[1] ? (rgb[2] - rgb[0]) / span + 2 : (rgb[0] - rgb[1]) / span + 4) + 6) % 6
  const x = chroma * (1 - Math.abs((h % 2) - 1))
  const m = targetL - chroma / 2
  const sector: Rgb[] = [[chroma, x, 0], [x, chroma, 0], [0, chroma, x], [0, x, chroma], [x, 0, chroma], [chroma, 0, x]]
  const picked = sector[Math.floor(h) % 6]
  return [picked[0] + m, picked[1] + m, picked[2] + m] as const
}

/** 已归一化的配色缓存：键是「原始配色 + 明暗」的指纹，命中就不重算 */
let paletteKey = ''
let palette: { colors: Rgb[]; edge: Rgb; alpha: number } = {
  colors: [[0.31, 0.275, 0.9], [0.024, 0.71, 0.831], [0.957, 0.447, 0.714]],
  edge: [0.024, 0.71, 0.831],
  alpha: 0.62,
}

function resolvePalette(dark: boolean) {
  const key = `${dark ? 'dark' : 'light'}|${props.palette.slice(0, 3).join(',')}|${props.edgeColor}`
  if (key === paletteKey) return palette
  paletteKey = key
  const colors: Rgb[] = []
  for (const value of props.palette.slice(0, 3)) colors.push(normalizeRgb(hexToRgb(value), dark))
  /* 服务端只给了一两个颜色时，缺的位沿用默认极光色，别掉成纯黑 */
  while (colors.length < 3) colors.push(normalizeRgb(hexToRgb(['#4f46e5', '#06b6d4', '#f472b6'][colors.length]), dark))
  palette = { colors, edge: normalizeRgb(hexToRgb(props.edgeColor), dark), alpha: dark ? 0.62 : 0.78 }
  return palette
}

onMounted(() => {
  const el = canvas.value
  if (!el) return
  const gl = el.getContext('webgl2', { alpha: true, antialias: false })
  if (!gl) return

  const mediaMobile = window.matchMedia('(max-width: 720px)')
  const mediaReduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const mobile = mediaMobile.matches
  const reducedMotion = () => mediaReduced.matches
  const hw = (navigator as { hardwareConcurrency?: number }).hardwareConcurrency || 8
  const mem = (navigator as { deviceMemory?: number }).deviceMemory || 8
  const lowEnd = hw <= 4 || mem <= 4
  /* 自适应画质档位：
     - 手机：像素预算约 62 万（约 DPR1 的 720p 级），帧率 20~30fps，速度减半（慢速在低帧率下观感更顺）
     - 电脑：像素预算 180 万、DPR≤1.75、45~60fps
     作用：把 GPU 填充量降到原来的 1/4~1/6（B站/抖音等也是按设备档位限制渲染规模） */
  targetFps = mobile ? (lowEnd ? 20 : 30) : lowEnd ? 45 : 60
  speedFactor = mobile ? 0.55 : 1
  const dprMax = mobile ? 1.25 : 1.75
  const pixelBudget = mobile ? 620000 : 1800000
  const IDLE_FREEZE_MS = 3000

  const vertex = `#version 300 es
    in vec2 position;
    void main(){ gl_Position=vec4(position,0.0,1.0); }
  `
  const fragment = `#version 300 es
    precision highp float;
    uniform float uTime;
    uniform float uSpeed;
    uniform vec2 uResolution;
    uniform vec3 uColorA;
    uniform vec3 uColorB;
    uniform vec3 uColorC;
    uniform vec3 uEdgeColor;
    uniform float uEdgeX;
    uniform float uEdgeStrength;
    uniform float uAlpha;
    out vec4 color;
    float hash(vec2 p){ return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
    float noise(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f); return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y); }
    void main(){
      vec2 uv=gl_FragCoord.xy/uResolution; vec2 p=uv*2.0-1.0; p.x*=uResolution.x/uResolution.y;
      float t=uTime*uSpeed*.07; float n=noise(p*2.0+vec2(t,-t))*.6+noise(p*4.0-vec2(t*.5,t))*.25;
      float blend=smoothstep(-.2,.8,sin(p.x*2.0+n*4.0+t));
      vec3 rgb=mix(uColorA,uColorB,blend); rgb=mix(rgb,uColorC,smoothstep(.45,1.0,n));
      float edgeGlow=exp(-abs(gl_FragCoord.x-uEdgeX*uResolution.x)/13.0)*uEdgeStrength;
      float edgeFlow=.58+.42*sin(t*1.7+uv.y*5.0+n*3.0);
      rgb=mix(rgb,uEdgeColor,clamp(edgeGlow*edgeFlow,0.0,.94));
      float alpha=.22+.22*smoothstep(.15,.95,n)+edgeGlow*.14; color=vec4(rgb*alpha,uAlpha);
    }
  `
  const compile = (type: number, source: string) => { const shader = gl.createShader(type)!; gl.shaderSource(shader, source); gl.compileShader(shader); return shader }

  let timeLoc: WebGLUniformLocation | null = null
  let speedLoc: WebGLUniformLocation | null = null
  let resLoc: WebGLUniformLocation | null = null
  let colorLocs: (WebGLUniformLocation | null)[] = []
  let edgeColorLoc: WebGLUniformLocation | null = null
  let edgeXLoc: WebGLUniformLocation | null = null
  let edgeStrLoc: WebGLUniformLocation | null = null
  let alphaLoc: WebGLUniformLocation | null = null

  const createProgram = () => {
    const program = gl.createProgram()!
    gl.attachShader(program, compile(gl.VERTEX_SHADER, vertex))
    gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragment))
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return false
    gl.useProgram(program)
    const buffer = gl.createBuffer()!
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW)
    const position = gl.getAttribLocation(program, 'position')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    timeLoc = gl.getUniformLocation(program, 'uTime')
    speedLoc = gl.getUniformLocation(program, 'uSpeed')
    resLoc = gl.getUniformLocation(program, 'uResolution')
    colorLocs = [gl.getUniformLocation(program, 'uColorA'), gl.getUniformLocation(program, 'uColorB'), gl.getUniformLocation(program, 'uColorC')]
    edgeColorLoc = gl.getUniformLocation(program, 'uEdgeColor')
    edgeXLoc = gl.getUniformLocation(program, 'uEdgeX')
    edgeStrLoc = gl.getUniformLocation(program, 'uEdgeStrength')
    alphaLoc = gl.getUniformLocation(program, 'uAlpha')
    return true
  }

  let sidebar: HTMLElement | null = null
  /* 侧栏边缘光：最多每 500ms 读一次布局（避免每帧 getBoundingClientRect 强制 reflow） */
  const updateEdge = (now: number) => {
    if (!props.edgeHighlight) {
      if (edgeX !== 0) { edgeX = 0; gl.uniform1f(edgeXLoc, 0); gl.uniform1f(edgeStrLoc, 0) }
      return
    }
    if (now - edgeXAt < 500) return
    edgeXAt = now
    sidebar ||= document.querySelector<HTMLElement>('.sidebar')
    const right = sidebar?.getBoundingClientRect().right || 0
    const next = Math.max(0, Math.min(1, right / Math.max(1, window.innerWidth)))
    if (next !== edgeX) { edgeX = next; gl.uniform1f(edgeXLoc, next); gl.uniform1f(edgeStrLoc, next > 0 ? 1 : 0) }
  }

  const setColors = () => {
    if (!speedLoc) return
    /* 明暗只在换主题时才读一次；归一化配色带指纹缓存，重算不发生在帧内 */
    const dark = document.documentElement.dataset.theme !== 'light'
    const next = resolvePalette(dark)
    next.colors.forEach((rgb, index) => { const loc = colorLocs[index]; if (loc) gl.uniform3f(loc, rgb[0], rgb[1], rgb[2]) })
    if (edgeColorLoc) gl.uniform3f(edgeColorLoc, next.edge[0], next.edge[1], next.edge[2])
    if (alphaLoc) gl.uniform1f(alphaLoc, next.alpha)
    gl.uniform1f(speedLoc, props.speed * speedFactor)
    updateEdge(performance.now())
  }
  updateColors = setColors

  const draw = (now: number) => {
    if (!timeLoc || !resLoc) return
    gl.uniform1f(timeLoc, now / 1000)
    updateEdge(now)
    gl.drawArrays(gl.TRIANGLES, 0, 6)
  }

  const schedule = () => { if (!scheduled && !destroyed && !paused) { scheduled = true; rafId = requestAnimationFrame(tick) } }
  const tick = (now: number) => {
    scheduled = false
    if (destroyed || paused || frozen || !visible || reducedMotion()) return
    if (now - lastActivity > IDLE_FREEZE_MS) { frozen = true; return } // 静止 3 秒 → 停画，GPU 归零
    if (now - lastDraw >= 1000 / targetFps) { lastDraw = now; draw(now) }
    schedule()
  }
  const doWake = () => {
    if (destroyed || paused || !visible || reducedMotion()) return
    lastActivity = performance.now()
    if (frozen) { frozen = false; lastDraw = 0 }
    schedule()
  }
  wake = doWake

  /* 画布尺寸变化（旋转屏幕/窗口缩放）：立即重算分辨率；
     若当前处于静止/暂停态，直接补绘一帧，避免旋转后画面消失或拉伸失真 */
  const resize = () => {
    if (!gl || !resLoc) return
    const cssW = Math.max(1, el.clientWidth || 1)
    const cssH = Math.max(1, el.clientHeight || 1)
    let scale = Math.min(window.devicePixelRatio || 1, dprMax)
    const area = cssW * scale * cssH * scale
    if (area > pixelBudget) scale = Math.max(0.85, Math.sqrt(pixelBudget / (cssW * cssH)))
    const w = Math.max(2, Math.round(cssW * scale))
    const h = Math.max(2, Math.round(cssH * scale))
    if (el.width !== w || el.height !== h) { el.width = w; el.height = h }
    gl.viewport(0, 0, w, h)
    gl.uniform2f(resLoc, w, h)
    edgeXAt = Number.NEGATIVE_INFINITY
    lastDraw = 0
    if (!scheduled) draw(performance.now())
  }

  if (!createProgram()) return
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(el)
  setColors()
  resize() // 首帧（覆盖静止/减动效场景）

  const onVisibility = () => { visible = !document.hidden; if (visible) doWake() }
  const onInteract = () => doWake()
  const onContextLost = (event: Event) => { event.preventDefault(); frozen = true }
  const onContextRestored = () => { if (createProgram()) { setColors(); resize(); doWake() } }

  abort = new AbortController()
  const signal = abort.signal
  document.addEventListener('visibilitychange', onVisibility, { signal })
  window.addEventListener('pointerdown', onInteract, { passive: true, signal })
  window.addEventListener('pointermove', onInteract, { passive: true, signal })
  window.addEventListener('touchstart', onInteract, { passive: true, signal })
  window.addEventListener('wheel', onInteract, { passive: true, signal })
  window.addEventListener('keydown', onInteract, { signal })
  window.addEventListener('resize', onInteract, { passive: true, signal })
  window.addEventListener('orientationchange', onInteract, { signal })
  el.addEventListener('webglcontextlost', onContextLost, { signal })
  el.addEventListener('webglcontextrestored', onContextRestored, { signal })

  /* 深色 / 浅色切换时配色要跟着换（只重设 uniform，不重开上下文、不动档位） */
  if (typeof MutationObserver !== 'undefined') {
    themeObserver = new MutationObserver(() => { setColors(); doWake() })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  }

  if (!paused && !reducedMotion()) doWake()
})

watch(() => [props.palette, props.speed, props.edgeColor, props.edgeHighlight], () => { updateColors?.() }, { deep: true })
watch(() => props.paused, (value) => {
  paused = value
  if (value) {
    frozen = true
    if (scheduled) { cancelAnimationFrame(rafId); scheduled = false }
  } else {
    wake?.()
  }
})
onBeforeUnmount(() => {
  destroyed = true
  if (scheduled) cancelAnimationFrame(rafId)
  themeObserver?.disconnect()
  themeObserver = undefined
  resizeObserver?.disconnect()
  abort?.abort()
})
</script>

<template><canvas ref="canvas" :class="['fluid-canvas', { 'fluid-canvas-inline': inline }]" aria-hidden="true" /></template>

<style scoped>
/* 画布本体（旧的全局 .fluid-canvas 已经随 styles.css 一起下线，这里补回来）。
   只定位置、透明度与遮罩：渲染规模、帧率、空闲冻结仍全部由脚本里的档位控制。 */
.fluid-canvas {
  position: fixed;
  z-index: 0;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  opacity: .5;
  pointer-events: none;
  /* 左右两端渐隐，让极光从中间自然过渡到页面底色 */
  -webkit-mask-image: linear-gradient(90deg, transparent 0%, #000 24%, #000 76%, transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0%, #000 24%, #000 76%, transparent 100%);
}

/* 作为壳层背景（.app-fluid-background）时叠在深色底上发光 */
.fluid-canvas.app-fluid-background {
  opacity: .82;
  mix-blend-mode: screen;
  filter: saturate(1.2);
}

/* 设置页里的内联预览：填满 .fluid-preview 预览框，不做遮罩 */
.fluid-canvas.fluid-canvas-inline {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 1;
  border-radius: inherit;
  -webkit-mask-image: none;
  mask-image: none;
  mix-blend-mode: normal;
  filter: none;
}

/* 设置页的预览框：给内联画布一个定位父级（原样式随 settings.css 下线） */
:deep(.fluid-preview) {
  position: relative;
  min-height: 220px;
  overflow: hidden;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  background: var(--media-bg);
}

:deep(.fluid-preview-caption) {
  position: absolute;
  left: 17px;
  bottom: 14px;
  z-index: 1;
  color: var(--text-1);
  font: 500 11px var(--font-mono);
  letter-spacing: .08em;
  text-shadow: 0 2px 12px var(--bg-0);
}

:deep(.fluid-disabled) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  color: var(--text-3);
  font-size: 12px;
}

/* 浅色主题：底色是亮的，改用正常混合，压住亮度免得糊成一片大白 */
:root[data-theme='light'] .fluid-canvas.app-fluid-background {
  opacity: .62;
  mix-blend-mode: normal;
  filter: saturate(1.25);
}

/* 减少动效：静态一帧，降低存在感 */
:root[data-motion='reduced'] .fluid-canvas.app-fluid-background {
  opacity: .58;
}
</style>

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
let edgeColor = props.edgeColor
let edgeX = 0
let edgeXAt = Number.NEGATIVE_INFINITY

function hexToRgb(hex: string) {
  const value = hex.replace('#', '')
  const normalized = value.length === 3 ? value.split('').map((part) => part + part).join('') : value
  const number = Number.parseInt(normalized, 16)
  return [((number >> 16) & 255) / 255, ((number >> 8) & 255) / 255, (number & 255) / 255] as const
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
      float alpha=.22+.22*smoothstep(.15,.95,n)+edgeGlow*.14; color=vec4(rgb*alpha,.62);
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
    props.palette.slice(0, 3).forEach((value, index) => { const rgb = hexToRgb(value); const loc = colorLocs[index]; if (loc) gl.uniform3f(loc, rgb[0], rgb[1], rgb[2]) })
    const accent = hexToRgb(edgeColor)
    if (edgeColorLoc) gl.uniform3f(edgeColorLoc, accent[0], accent[1], accent[2])
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

  if (!paused && !reducedMotion()) doWake()
})

watch(() => [props.palette, props.speed, props.edgeColor, props.edgeHighlight], () => { edgeColor = props.edgeColor; updateColors?.() }, { deep: true })
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
  resizeObserver?.disconnect()
  abort?.abort()
})
</script>

<template><canvas ref="canvas" :class="['fluid-canvas', { 'fluid-canvas-inline': inline }]" aria-hidden="true" /></template>

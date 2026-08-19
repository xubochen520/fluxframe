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
let frame = 0
let resizeObserver: ResizeObserver | undefined
let updateColors: (() => void) | undefined
let drawFrame: ((ms: number) => void) | undefined
let paused = false
let edgeColor = props.edgeColor

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
  const program = gl.createProgram()!
  gl.attachShader(program, compile(gl.VERTEX_SHADER, vertex)); gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragment)); gl.linkProgram(program); gl.useProgram(program)
  const buffer = gl.createBuffer()!; gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]), gl.STATIC_DRAW)
  const position = gl.getAttribLocation(program, 'position'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
  const time = gl.getUniformLocation(program, 'uTime'); const speed = gl.getUniformLocation(program, 'uSpeed'); const resolution = gl.getUniformLocation(program, 'uResolution')
  const colors = [gl.getUniformLocation(program, 'uColorA'), gl.getUniformLocation(program, 'uColorB'), gl.getUniformLocation(program, 'uColorC')]
  const edgeUniform = gl.getUniformLocation(program, 'uEdgeColor')
  const edgeXUniform = gl.getUniformLocation(program, 'uEdgeX')
  const edgeStrengthUniform = gl.getUniformLocation(program, 'uEdgeStrength')
  let edgeX = 0
  let sidebar: HTMLElement | null = null
  const updateEdge = () => {
    if (!props.edgeHighlight) { edgeX = 0; gl.uniform1f(edgeXUniform, edgeX); gl.uniform1f(edgeStrengthUniform, 0); return }
    sidebar ||= document.querySelector<HTMLElement>('.sidebar')
    const right = sidebar?.getBoundingClientRect().right || 0
    edgeX = Math.max(0, Math.min(1, right / Math.max(1, window.innerWidth)))
    gl.uniform1f(edgeXUniform, edgeX)
    gl.uniform1f(edgeStrengthUniform, edgeX > 0 ? 1 : 0)
  }
  const setColors = () => { props.palette.slice(0, 3).forEach((value, index) => { const rgb = hexToRgb(value); gl.uniform3f(colors[index], rgb[0], rgb[1], rgb[2]) }); const accent = hexToRgb(edgeColor); gl.uniform3f(edgeUniform, accent[0], accent[1], accent[2]); gl.uniform1f(speed, props.speed); updateEdge() }
  updateColors = setColors; setColors()
  const resize = () => { const dpr = Math.min(window.devicePixelRatio, 1.5); el.width = Math.max(1, el.clientWidth * dpr); el.height = Math.max(1, el.clientHeight * dpr); gl.viewport(0, 0, el.width, el.height); gl.uniform2f(resolution, el.width, el.height) }
  resizeObserver = new ResizeObserver(resize); resizeObserver.observe(el); resize()
  paused = props.paused
  const render = (ms: number) => {
    gl.uniform1f(time, ms / 1000)
    updateEdge()
    gl.drawArrays(gl.TRIANGLES, 0, 6)
    if (!paused) frame = requestAnimationFrame(render)
  }
  drawFrame = render
  frame = requestAnimationFrame(render)
})

watch(() => [props.palette, props.speed, props.edgeColor, props.edgeHighlight], () => { edgeColor = props.edgeColor; updateColors?.() }, { deep: true })
watch(() => props.paused, (value) => {
  paused = value
  cancelAnimationFrame(frame)
  if (value) drawFrame?.(0)
  else if (drawFrame) frame = requestAnimationFrame(drawFrame)
})
onBeforeUnmount(() => { cancelAnimationFrame(frame); resizeObserver?.disconnect(); drawFrame = undefined })
</script>

<template><canvas ref="canvas" :class="['fluid-canvas', { 'fluid-canvas-inline': inline }]" aria-hidden="true" /></template>

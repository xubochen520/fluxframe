<script setup lang="ts">
/**
 * 3D 星系空间。
 *
 * 与二维那张星系图（SimilarGalaxy.vue）是并排的两个视图，共用同一份关系网数据，
 * 但坐标是三元的（服务端 space=3d 返回），渲染走透视投影：
 *
 *   · 拖动绕中心旋转、滚轮/双指拉近拉远
 *   · 默认缓慢自转（一圈约 105 秒）
 *   · 点一张图 → 电影镜头推近，然后进「相框」定格查看
 *   · 手机陀螺仪给一点视差，像光栅画那样随视角晃动
 *
 * 标签名走分级显示：缩小时只留图最多的几个，放大时一个个放出来。
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Galaxy3DEngine, type Edge3D, type Node3D } from '../galaxy/engine3d'
import { labelBudget, rankLabels } from '../galaxy/camera3d'
import type { EmbedGraphEdge, EmbedGraphGroup, EmbedGraphMode } from '../api'
import type { ImageItem } from '../types'

const props = defineProps<{
  nodes: ImageItem[]
  edges: EmbedGraphEdge[]
  groups: EmbedGraphGroup[]
  positions: Record<string, number[]>
  characters: Record<string, string>
  threshold: number
  mode?: EmbedGraphMode
  tagAnchors?: Record<string, [number, number]>
}>()

const emit = defineEmits<{ open: [item: ImageItem] }>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
const engine = shallowRef<Galaxy3DEngine | null>(null)
const canvasKey = ref(0)
const built = computed(() => build())
const tooltip = ref<{ x: number; y: number; index: number } | null>(null)
const selectedId = ref<string | null>(null)
const focusedId = ref<string | null>(null)
const gyroOn = ref(false)
const webglError = ref('')
/** 每帧上报的相机状态；只用于标签分级与按钮文案，不参与渲染 */
const view = ref({ distance: 1, yaw: 0, focused: false })
const homeDistance = ref(1)
/** 标签位置：每帧按相机投影算一次，所以单独存一份（不放进 computed，否则不会随帧刷新） */
const labelScreen = ref<Array<{ key: string; text: string; x: number; y: number; size: number; color: string }>>([])

/** 主标签（视觉模式=主角色，标签模式=最有区分度的标签）：不在 ImageItem 上，得从 characters 取 */
function characterOf(item: ImageItem | null | undefined) {
  return item ? (props.characters?.[item.id] || '') : ''
}

const selected = computed(() => props.nodes.find((item) => item.id === selectedId.value) ?? null)
const focused = computed(() => props.nodes.find((item) => item.id === focusedId.value) ?? null)

/* ------------------------------ 建图 ------------------------------ */

function build() {
  const items: ImageItem[] = []
  const nodes: Node3D[] = []
  const indexById = new Map<string, number>()
  for (const item of props.nodes) {
    const position = props.positions[item.id]
    if (!position || position.length < 3) continue
    const character = props.characters?.[item.id] || ''
    indexById.set(item.id, nodes.length)
    items.push(item)
    nodes.push({
      id: item.id,
      name: item.name,
      thumb: item.thumb,
      thumbLarge: item.thumbLarge || item.thumb,
      x: position[0],
      y: position[1],
      z: position[2],
      color: characterColor(character),
      character,
      degree: 0,
      aspect: item.width > 0 && item.height > 0 ? item.width / item.height : 1,
    })
  }
  const edges: Edge3D[] = []
  for (const edge of props.edges) {
    const a = indexById.get(edge.a)
    const b = indexById.get(edge.b)
    if (a === undefined || b === undefined) continue
    edges.push({ a, b, score: edge.score })
    nodes[a].degree += 1
    nodes[b].degree += 1
  }
  return { items, nodes, edges, indexById }
}

/** 与二维那套完全一致的着色：名字哈希到色相，同一个角色在两种视图里颜色一样 */
function characterColor(name: string): [number, number, number] {
  let hash = 2166136261
  for (let i = 0; i < name.length; i++) {
    hash ^= name.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  const hue = ((hash >>> 0) % 360) / 360
  const saturation = 0.72
  const lightness = 0.62
  const channel = (n: number) => {
    const k = (n + hue * 12) % 12
    const a = saturation * Math.min(lightness, 1 - lightness)
    return lightness - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
  }
  return [channel(0), channel(8), channel(4)]
}

/* ------------------------------ 标签 ------------------------------ */

/**
 * 标签及其图片数。
 * 标签模式下用服务端给的关系（可重叠，一张图属于多个）；视觉模式用分组。
 */
const allLabels = computed(() => {
  const counts = new Map<string, number>()
  for (const item of props.nodes) for (const tag of item.tags) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  const anchors = props.tagAnchors ?? {}
  if (props.mode === 'tag' && Object.keys(anchors).length) {
    return Object.entries(anchors).map(([tag]) => ({ key: `tag:${tag}`, text: `${tag} · ${counts.get(tag) ?? 0}`, character: tag, size: counts.get(tag) ?? 1 }))
  }
  return props.groups
    .filter((group) => group.size >= 3)
    .map((group) => {
      const tally = new Map<string, number>()
      for (const id of group.members) {
        const character = props.characters?.[id]
        if (character) tally.set(character, (tally.get(character) ?? 0) + 1)
      }
      let best = ''
      let bestCount = 0
      for (const [name, value] of tally) if (value > bestCount) { best = name; bestCount = value }
      return { key: `${group.members[0]}-${group.size}`, text: best ? `${best} · ${group.size}` : `${group.size} 张`, character: best, size: group.size }
    })
})

/** 缩放到什么程度就放几个标签名 */
const maxLabels = computed(() => {
  const ratio = homeDistance.value / Math.max(0.001, view.value.distance)
  return labelBudget(ratio, allLabels.value.length)
})

const visibleLabels = computed(() =>
  rankLabels(allLabels.value, (label) => label.size, (label) => label.text).slice(0, maxLabels.value),
)

/* ------------------------------ 生命周期 ------------------------------ */

function createEngine() {
  const canvas = canvasRef.value
  if (!canvas) return
  engine.value?.destroy()
  try {
    const instance = new Galaxy3DEngine(canvas, built.value.nodes, built.value.edges, {
      onHover: (index, clientX, clientY) => {
        tooltip.value = index === null ? null : { x: clientX, y: clientY, index }
      },
      onSelect: (index) => {
        selectedId.value = index === null ? null : built.value.items[index]?.id ?? null
      },
      onOpen: (index) => {
        const item = built.value.items[index]
        if (item) emit('open', item)
      },
      onFocusChange: (index) => {
        focusedId.value = index === null ? null : built.value.items[index]?.id ?? null
      },
      /*
       * 推近动画走完再把大图叫起来 —— 用户要的是"先电影镜头切近，等它几乎停下，
       * 再打开大图"，而不是推近的同时甩一个相框出来。
       * 这里是唯一会 emit open 的地方：点选只负责把镜头推过去。
       */
      onFocusSettled: (index) => {
        const item = built.value.items[index]
        if (item) emit('open', item)
      },
      onView: (state) => { view.value = state },
    })
    engine.value = instance
    homeDistance.value = instance.home
    instance.start()
  } catch (error) {
    webglError.value = error instanceof Error ? error.message : 'WebGL 初始化失败'
  }
}

let observer: ResizeObserver | null = null
let labelFrame = 0

/** 标签位置得跟着相机走，所以每帧单独同步一次（放 computed 里不会随帧刷新） */
function pumpLabels() {
  labelFrame = requestAnimationFrame(pumpLabels)
  const instance = engine.value
  if (!instance) return
  const next: typeof labelScreen.value = []
  for (const label of visibleLabels.value) {
    /* 标签模式下锚点是世界坐标的一个点，视觉模式退回用成员中心点 */
    const point = anchorPoint(label)
    if (!point) continue
    const projected = instance.projectWorld(point[0], point[1], point[2])
    if (!projected || projected.depth <= 0.1) continue
    next.push({ key: label.key, text: label.text, x: projected.x, y: projected.y, size: label.size, color: `rgb(${characterColor(label.character).map((v) => Math.round(v * 255)).join(',')})` })
  }
  labelScreen.value = next
}

/** 标签锚点的世界坐标：标签模式直接用服务端锚点（z 由同批图片的均值兜底） */
function anchorPoint(label: { key: string; character: string }): [number, number, number] | null {
  if (label.key.startsWith('tag:')) {
    const tag = label.key.slice(4)
    let sumX = 0
    let sumY = 0
    let sumZ = 0
    let count = 0
    for (const item of built.value.items) {
      if (!item.tags.includes(tag)) continue
      const position = props.positions[item.id]
      if (!position || position.length < 3) continue
      sumX += position[0]
      sumY += position[1]
      sumZ += position[2]
      count++
    }
    return count ? [sumX / count, sumY / count, sumZ / count] : null
  }
  const members = props.groups.find((group) => label.key.startsWith(`${group.members[0]}-`))
  if (!members) return null
  let sumX = 0
  let sumY = 0
  let sumZ = 0
  let count = 0
  for (const id of members.members) {
    const position = props.positions[id]
    if (!position || position.length < 3) continue
    sumX += position[0]
    sumY += position[1]
    sumZ += position[2]
    count++
  }
  return count ? [sumX / count, sumY / count, sumZ / count] : null
}

function rebuild() {
  canvasKey.value += 1
  requestAnimationFrame(() => createEngine())
}

watch(() => [props.nodes, props.edges, props.positions], rebuild)

onMounted(() => {
  createEngine()
  pumpLabels()
  const canvas = canvasRef.value
  if (canvas && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => engine.value?.resize())
    observer.observe(canvas)
  }
})

onBeforeUnmount(() => {
  cancelAnimationFrame(labelFrame)
  observer?.disconnect()
  engine.value?.destroy()
  engine.value = null
})

/* ------------------------------ 交互 ------------------------------ */

function resetView() {
  engine.value?.resetView()
  focusedId.value = null
}

/**
 * 陀螺仪视差。iOS 需要用户手势里显式申请权限，安卓直接能拿；
 * 拿不到就静默关掉，不打扰用户。
 */
async function toggleGyro() {
  const orientation = window.DeviceOrientationEvent as (typeof DeviceOrientationEvent & {
    requestPermission?: () => Promise<'granted' | 'denied'>
  }) | undefined
  if (!orientation) return
  try {
    if (typeof orientation.requestPermission === 'function') {
      const granted = await orientation.requestPermission()
      if (granted !== 'granted') return
    }
    const handler = (event: DeviceOrientationEvent) => engine.value?.setOrientation(event.beta, event.gamma)
    window.addEventListener('deviceorientation', handler)
    gyroOn.value = true
    ;(engine.value as unknown as { _gyroHandler?: unknown })._gyroHandler = handler
  } catch { /* 用户拒绝或设备不支持：保持关闭 */ }
}

const tooltipItem = computed(() => (tooltip.value ? built.value.items[tooltip.value.index] : null))
</script>

<template>
  <div class="galaxy galaxy3d">
    <canvas :key="canvasKey" ref="canvasRef" class="galaxy-canvas" />

    <!-- 星云名称：跟着相机投影走，缩小时只留图最多的几个 -->
    <div class="galaxy-label-layer">
      <span
        v-for="label in labelScreen"
        :key="label.key"
        class="galaxy-label"
        :style="{ left: `${label.x}px`, top: `${label.y}px`, color: label.color, borderColor: label.color }"
      >{{ label.text }}</span>
    </div>

    <div v-if="webglError" class="galaxy-webgl-error">
      <strong>3D 视图起不来</strong>
      <p>{{ webglError }}。可以切回上面的「星系图」看二维版本。</p>
    </div>

    <div class="galaxy-hint">
      拖动旋转 · 滚轮/双指缩放 · 点一张图推近并打开
      <button v-if="!gyroOn" class="ghost-button ghost-button-sm" @click="toggleGyro">开启陀螺仪视差</button>
    </div>

    <div class="galaxy-zoom">
      <button title="转正视角" @click="resetView">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3a9 9 0 1 0 9 9" /><path d="M21 3v6h-6" /></svg>
      </button>
    </div>

    <teleport to="body">
      <div v-if="tooltipItem" class="galaxy-tip" :style="{ left: `${tooltip!.x + 14}px`, top: `${tooltip!.y + 14}px` }">
        <strong>{{ tooltipItem.name }}</strong>
        <small>{{ characterOf(tooltipItem) || '未标注' }}</small>
      </div>
    </teleport>
  </div>
</template>

<style scoped>
/* ============================================================================
   3D 星系空间（深空舞台 + 发光节点 + 玻璃控件），与二维那张图共用同一套舞台语言。
   舞台底色、星云、尘埃、连线、节点光晕都由 WebGL 画（galaxy/engine3d.ts），
   这里只负责 DOM 层：星云名、提示、缩放按钮、悬停气泡。
============================================================================ */
.galaxy {
  /* 深空玻璃的局部令牌：舞台始终是深空，浅色主题下也保持深色玻璃 */
  --galaxy-ink: #f2f4fb;
  --galaxy-ink-2: rgba(226, 232, 248, .74);
  --galaxy-ink-3: rgba(206, 215, 242, .52);
  --galaxy-glass: rgba(9, 12, 26, .74);
  --galaxy-glass-solid: rgba(11, 14, 30, .9);
  --galaxy-glass-raise: rgba(14, 18, 38, .92);
  --galaxy-line: rgba(255, 255, 255, .16);
  --galaxy-line-soft: rgba(255, 255, 255, .1);
  --galaxy-line-strong: rgba(255, 255, 255, .3);
  --galaxy-hl: inset 0 1px 0 rgba(255, 255, 255, .16);

  position: relative;
  height: clamp(460px, calc(100vh - 340px), 980px);
  min-height: 300px;
  overflow: hidden;
  border-radius: var(--r-xl);
  border: 1px solid var(--stroke);
  isolation: isolate;
  background:
    radial-gradient(68% 58% at 16% 2%, color-mix(in srgb, var(--accent-1) 34%, transparent), transparent 62%),
    radial-gradient(58% 54% at 88% 98%, color-mix(in srgb, var(--accent-2) 24%, transparent), transparent 62%),
    radial-gradient(46% 46% at 74% 6%, color-mix(in srgb, var(--accent-3) 16%, transparent), transparent 66%),
    linear-gradient(180deg, color-mix(in srgb, var(--bg-1) 78%, #000), color-mix(in srgb, var(--bg-0) 86%, #000));
  box-shadow: var(--shadow-2), 0 40px 90px -60px color-mix(in srgb, var(--accent-1) 55%, transparent);
}

/* 浅色主题下阴影里的主色辉光收敛一点（舞台本身仍是深空） */
:root[data-theme='light'] .galaxy {
  box-shadow: var(--shadow-2), 0 40px 90px -70px color-mix(in srgb, var(--accent-1) 40%, transparent);
}

.galaxy-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  /* 三维视图里画布要接指针事件（拖动旋转），标签层本身不接 */
  touch-action: none;
  cursor: grab;
}
.galaxy-canvas:active { cursor: grabbing; }

/* 四边往里收，让星系自然融进舞台而不是被硬边裁断 */
.galaxy::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  border-radius: inherit;
  pointer-events: none;
  background: radial-gradient(126% 122% at 50% 46%, transparent 58%, color-mix(in srgb, #000 46%, transparent));
  box-shadow: inset 0 0 0 1px var(--galaxy-line-soft);
}

/* ----------------------------- 星云名称 ----------------------------- */
.galaxy-label-layer {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  overflow: hidden;
}
/* 三维视图里标签位置每帧都在变，所以用 transform 定位（left/top 会触发重排，转动时会掉帧） */
.galaxy-label {
  position: absolute;
  left: 0;
  top: 0;
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border: 1px solid color-mix(in srgb, currentColor 46%, transparent);
  border-radius: var(--r-pill);
  background: var(--galaxy-glass);
  -webkit-backdrop-filter: blur(6px) saturate(1.3);
  backdrop-filter: blur(6px) saturate(1.3);
  box-shadow: var(--galaxy-hl);
  font: 600 11px/1 var(--font-display);
  letter-spacing: .02em;
  white-space: nowrap;
  text-shadow: 0 1px 10px color-mix(in srgb, #000 80%, transparent);
  transform: translate(-50%, -50%);
  will-change: transform;
  transition: background var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.galaxy-label:hover { background: var(--galaxy-glass-raise); transform: translate(-50%, -50%) scale(1.05); }

/* ------------------------------ 缩放控件 ------------------------------ */
.galaxy-zoom {
  position: absolute;
  z-index: 3;
  right: 12px;
  top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.galaxy-zoom button {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--galaxy-line);
  border-radius: var(--r-sm);
  background: var(--galaxy-glass);
  -webkit-backdrop-filter: blur(12px) saturate(1.4);
  backdrop-filter: blur(12px) saturate(1.4);
  box-shadow: var(--galaxy-hl), var(--shadow-1);
  color: var(--galaxy-ink-2);
  transition: color var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.galaxy-zoom button:hover { color: #fff; border-color: color-mix(in srgb, var(--accent-1) 60%, transparent); }
.galaxy-zoom button:active { transform: scale(.94); }

/* -------------------------------- 提示 -------------------------------- */
.galaxy-hint {
  position: absolute;
  z-index: 3;
  left: 14px;
  bottom: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  max-width: calc(100% - 28px);
  padding: 6px 8px 6px 12px;
  border: 1px solid var(--galaxy-line-soft);
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--galaxy-glass-solid) 78%, transparent);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
  color: var(--galaxy-ink-3);
  font: 500 10.5px var(--font-mono);
  letter-spacing: .02em;
}
/* 陀螺仪按钮在提示条里：深空玻璃自己的配色（全局 .ghost-button 是给页面用的） */
.galaxy-hint .ghost-button {
  height: 26px;
  padding: 0 11px;
  border-color: var(--galaxy-line);
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, .08);
  color: var(--galaxy-ink-2);
  font-size: 11px;
  transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.galaxy-hint .ghost-button:hover {
  background: color-mix(in srgb, var(--accent-1) 32%, var(--galaxy-glass-solid));
  border-color: color-mix(in srgb, var(--accent-1) 58%, transparent);
  color: #fff;
}
.galaxy-hint .ghost-button:active { transform: scale(.96); }

/* ----------------------------- 悬停气泡 ----------------------------- */
/* Teleport 到 body：作用域标记还在，但拿不到 .galaxy 上的局部令牌，所以用到的值原样再写一次 */
.galaxy-tip {
  --galaxy-ink: #f2f4fb;
  --galaxy-ink-3: rgba(206, 215, 242, .52);
  --galaxy-glass-solid: rgba(11, 14, 30, .9);
  --galaxy-line: rgba(255, 255, 255, .16);
  --galaxy-hl: inset 0 1px 0 rgba(255, 255, 255, .16);
  position: fixed;
  z-index: 40;
  min-width: 130px;
  max-width: 240px;
  padding: 9px 12px;
  border: 1px solid var(--galaxy-line);
  border-radius: var(--r-md);
  background: var(--galaxy-glass-solid);
  -webkit-backdrop-filter: blur(18px) saturate(1.4);
  backdrop-filter: blur(18px) saturate(1.4);
  box-shadow: var(--galaxy-hl), var(--shadow-2);
  color: var(--galaxy-ink);
  pointer-events: none;
}
.galaxy-tip strong {
  display: block;
  color: var(--galaxy-ink);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.galaxy-tip small { display: block; margin-top: 4px; color: var(--galaxy-ink-3); font: 500 10.5px var(--font-mono); }

/* ------------------------------ 失败态 ------------------------------ */
.galaxy-webgl-error {
  position: absolute;
  z-index: 3;
  inset: auto 16px 16px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--danger) 42%, transparent);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--danger) 16%, var(--galaxy-glass-solid));
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  box-shadow: var(--galaxy-hl), var(--shadow-1);
  color: #ffe6ea;
  font-size: 12px;
  line-height: 1.6;
}
.galaxy-webgl-error strong { display: block; font-weight: 600; }
.galaxy-webgl-error p { margin: 5px 0 0; opacity: .85; }

/* ------------------------------- 响应式 ------------------------------- */
@media (max-width: 720px) {
  .galaxy {
    height: clamp(400px, calc(100vh - 300px), 720px);
    border-radius: var(--r-lg);
  }
  /* 手机端全局禁用 backdrop-filter（性能守则），玻璃靠 tint 与高光 */
  .galaxy-label,
  .galaxy-zoom button,
  .galaxy-hint,
  .galaxy-tip,
  .galaxy-webgl-error {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  .galaxy-label { background: var(--galaxy-glass-solid); }
  .galaxy-zoom button { width: 32px; height: 32px; background: var(--galaxy-glass-solid); }
  /* 提示条在手机上会盖住画布：字号收小，文案与按钮都保留 */
  .galaxy-hint { background: var(--galaxy-glass-solid); font-size: 10px; }
  .galaxy-tip { display: none; }
}
</style>

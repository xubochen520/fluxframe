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
const frameOpen = ref(false)
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
        frameOpen.value = index !== null
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
  frameOpen.value = false
  focusedId.value = null
}

/** 相框里那张图：打开大图查看器 */
function openFocused() {
  const item = focused.value
  if (!item) return
  emit('open', item)
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

    <!-- 相框：推近之后定格看这一张 -->
    <transition name="frame">
      <div v-if="frameOpen && focused" class="galaxy-frame" @click.stop>
        <div class="galaxy-frame-inner">
          <img :src="focused.thumbLarge || focused.thumb" :alt="focused.name" />
          <div class="galaxy-frame-bar">
            <div class="galaxy-frame-text">
              <strong>{{ focused.name }}</strong>
              <small>{{ characterOf(focused) || '未标注' }}{{ focused.width ? ` · ${focused.width}×${focused.height}` : '' }}</small>
            </div>
            <button class="ghost-button" @click="openFocused">打开</button>
            <button class="ghost-button" @click="resetView">回到全景</button>
          </div>
        </div>
      </div>
    </transition>

    <div class="galaxy-hint">
      拖动旋转 · 滚轮/双指缩放 · 点一张图推近看 · 双击打开
      <button v-if="!gyroOn" class="ghost-button ghost-button-sm" @click="toggleGyro">开启陀螺仪视差</button>
    </div>

    <div class="galaxy-zoom">
      <button title="转正视角" @click="resetView">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M12 3a9 9 0 1 0 9 9" /><path d="M21 3v6h-6" /></svg>
      </button>
    </div>

    <teleport to="body">
      <div v-if="tooltipItem && !frameOpen" class="galaxy-tip" :style="{ left: `${tooltip!.x + 14}px`, top: `${tooltip!.y + 14}px` }">
        <strong>{{ tooltipItem.name }}</strong>
        <small>{{ characterOf(tooltipItem) || '未标注' }}</small>
      </div>
    </teleport>
  </div>
</template>

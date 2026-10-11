<script setup lang="ts">
/**
 * 相似图星系图：用 WebGL 把「哪些图和哪些图像」画成一片星云。
 *
 * 分工很清楚——引擎（../galaxy/engine.ts）只管画和交互，这个组件管状态与界面：
 *   · 把接口回来的 nodes/positions/characters 组装成引擎要的数据（含按角色算颜色）
 *   · 搜索框、角色图例 → 转成每个点的明暗权重喂给引擎
 *   · 悬停气泡、星云名称、选中卡片这些 DOM 层
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ChevronRight, Focus, LoaderCircle, Minus, Plus, Search, Sparkles, X } from 'lucide-vue-next'
import { GalaxyEngine, type GalaxyEdge, type GalaxyNode } from '../galaxy/engine'
import type { EmbedGraphEdge, EmbedGraphGroup, EmbedGraphMode } from '../api'
import type { ImageItem } from '../types'

const props = defineProps<{
  nodes: ImageItem[]
  edges: EmbedGraphEdge[]
  groups: EmbedGraphGroup[]
  /** 二维坐标 [x, y]；类型放宽成 number[] 是因为三维视图共用同一份 payload 类型 */
  positions: Record<string, number[]>
  characters: Record<string, string>
  threshold: number
  /** 当前关系依据：视觉指纹 / 标签。影响文案与星云名称的来源 */
  mode?: EmbedGraphMode
  /**
   * 标签锚点（只有标签模式有）：标签名 → [x, y]。
   * 标签模式下的星云名称直接用它们——图本来就是围着标签聚起来的。
   */
  tagAnchors?: Record<string, [number, number]>
}>()

const emit = defineEmits<{ (event: 'open', image: ImageItem): void }>()

const wrapper = ref<HTMLElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
/**
 * 画布的 key。重建时必须换一个全新的 canvas 元素：WebGL 上下文和 canvas 是一对一的，
 * destroy() 里已经调了 loseContext()，再对同一个元素 getContext 只会拿回那个「已经丢掉」的上下文。
 */
const canvasKey = ref(0)
const engine = shallowRef<GalaxyEngine | null>(null)
const failure = ref('')

const hoverIndex = ref<number | null>(null)
const hoverAt = ref({ x: 0, y: 0 })
const selectedIndex = ref<number | null>(null)
const query = ref('')
const activeCharacter = ref('')
/** 引擎每帧回调一次，用来让 DOM 标签跟着镜头走（只递增一个数字，开销可忽略） */
const frameTick = ref(0)
const zoomLevel = ref(1)
const thumbVisible = ref(0)

/* ---------------- 颜色：同一个角色永远是同一个色相 ---------------- */

function hslToRgb(hue: number, saturation: number, lightness: number): [number, number, number] {
  const k = (n: number) => (n + hue * 12) % 12
  const a = saturation * Math.min(lightness, 1 - lightness)
  const channel = (n: number) => lightness - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)))
  return [channel(0), channel(8), channel(4)]
}

/** 角色名 → 色相。用哈希而不是调色板轮转：角色数量不定，哈希不会撞色也永远稳定 */
function characterColor(name: string): [number, number, number] {
  if (!name) return [0.34, 0.4, 0.55]
  let hash = 2166136261 >>> 0
  for (let i = 0; i < name.length; i++) {
    hash ^= name.charCodeAt(i)
    hash = Math.imul(hash, 16777619) >>> 0
  }
  return hslToRgb((hash % 360) / 360, 0.72, 0.62)
}

/* ---------------- 组装引擎数据 ---------------- */

interface Built {
  nodes: GalaxyNode[]
  edges: GalaxyEdge[]
  items: ImageItem[]
}

function build(): Built {
  const items: ImageItem[] = []
  const indexById = new Map<string, number>()
  const nodes: GalaxyNode[] = []
  for (const item of props.nodes) {
    const position = props.positions?.[item.id]
    if (!position) continue
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
      color: characterColor(character),
      character,
      degree: 0,
      /* 缩略图按原图比例画，不裁成正方形（见 engine.ts 的 thumbHalfExtent）。
         接口没给尺寸时按正方形兜底。 */
      aspect: item.width > 0 && item.height > 0 ? item.width / item.height : 1,
    })
  }
  const edges: GalaxyEdge[] = []
  for (const edge of props.edges) {
    const a = indexById.get(edge.a)
    const b = indexById.get(edge.b)
    if (a === undefined || b === undefined) continue
    edges.push({ a, b, score: edge.score })
    nodes[a].degree += 1
    nodes[b].degree += 1
  }
  return { nodes, edges, items }
}

const built = shallowRef<Built>({ nodes: [], edges: [], items: [] })

/**
 * 后端给了节点却没有坐标——只可能是服务端还是旧版本（positions 是后加的字段）。
 * 这时候画出来是一片空白，比报错更让人困惑，所以直接说清楚原因。
 */
const missingPositions = computed(() => props.nodes.length > 0 && built.value.nodes.length === 0 && !failure.value)

/* ---------------- 搜索与角色筛选 ---------------- */

const normalizedQuery = computed(() => query.value.trim().toLowerCase())

const matches = computed(() => {
  const text = normalizedQuery.value
  const weights = new Float32Array(built.value.nodes.length)
  for (let i = 0; i < built.value.nodes.length; i++) {
    const item = built.value.items[i]
    const node = built.value.nodes[i]
    let hit = true
    if (activeCharacter.value && node.character !== activeCharacter.value) hit = false
    if (hit && text) {
      hit = item.name.toLowerCase().includes(text)
        || node.character.toLowerCase().includes(text)
        || item.tags.some((tag) => tag.toLowerCase().includes(text))
    }
    weights[i] = hit ? 1 : 0.1
  }
  return weights
})

const hitCount = computed(() => {
  let total = 0
  for (let i = 0; i < matches.value.length; i++) if (matches.value[i] > 0.5) total += 1
  return total
})

const filtering = computed(() => Boolean(normalizedQuery.value) || Boolean(activeCharacter.value))

/** 角色图例：按张数降序，最多列 12 个 */
const legend = computed(() => {
  const counts = new Map<string, number>()
  for (const node of built.value.nodes) {
    if (!node.character) continue
    counts.set(node.character, (counts.get(node.character) ?? 0) + 1)
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([name, count]) => ({ name, count, color: `rgb(${characterColor(name).map((v) => Math.round(v * 255)).join(',')})` }))
})

/* ---------------- 星云标签：分组的「中心点」+ 组里最多的角色名 ---------------- */

interface LabelAnchor { key: string; text: string; character: string; x: number; y: number; size: number; color: string }
interface Label extends LabelAnchor { screenX: number; screenY: number }

/**
 * 标签锚点用「中心点（medoid）」而不是质心。
 * 并查集分组可能是拉得很长的链：质心会落到两团之间的空白处——实测「纳西妲 · 37」
 * 就飘到了画布下方，离它那团蓝星云十万八千里。取组内到其它成员距离和最小的那个点，
 * 标签一定落在最密的地方。
 *
 * 标签模式下不用这套：服务端直接给了标签锚点（图本来就是围着标签聚起来的），
 * 标在锚点上最直观，也顺便告诉用户「这团为什么在这儿」。
 */
const labelAnchors = computed<LabelAnchor[]>(() => {
  const anchors = props.tagAnchors
  if (props.mode === 'tag' && anchors && Object.keys(anchors).length) {
    /* 每个标签带一个"有多少张图带它"的计数：只有一张图的标签标出来没意义 */
    const counts = new Map<string, number>()
    for (const item of props.nodes) {
      for (const tag of item.tags) counts.set(tag, (counts.get(tag) ?? 0) + 1)
    }
    return Object.entries(anchors)
      .map(([tag, point]) => ({
        key: `tag:${tag}`,
        text: `${tag} · ${counts.get(tag) ?? 0}`,
        character: tag,
        x: point[0],
        y: point[1],
        size: counts.get(tag) ?? 1,
        color: `rgb(${characterColor(tag).map((v) => Math.round(v * 255)).join(',')})`,
      }))
      .sort((a, b) => b.size - a.size)
      .slice(0, 26)
  }

  const byId = new Map(built.value.items.map((item, index) => [item.id, index]))
  const result: LabelAnchor[] = []
  for (const group of props.groups) {
    if (group.size < 3) continue
    const points: Array<{ x: number; y: number; character: string }> = []
    const tally = new Map<string, number>()
    for (const id of group.members) {
      const index = byId.get(id)
      if (index === undefined) continue
      const node = built.value.nodes[index]
      points.push({ x: node.x, y: node.y, character: node.character })
      if (node.character) tally.set(node.character, (tally.get(node.character) ?? 0) + 1)
    }
    if (points.length < 3) continue

    /* 候选最多取 80 个：几万人的大分组也不需要每帧全扫 */
    const step = Math.max(1, Math.floor(points.length / 80))
    let anchor = points[0]
    let bestScore = Infinity
    for (let c = 0; c < points.length; c += step) {
      let sum = 0
      for (const other of points) sum += (other.x - points[c].x) ** 2 + (other.y - points[c].y) ** 2
      if (sum < bestScore) { bestScore = sum; anchor = points[c] }
    }

    let best = ''
    let bestCount = 0
    for (const [name, value] of tally) if (value > bestCount) { best = name; bestCount = value }
    result.push({
      key: `${group.members[0]}-${group.size}`,
      text: best ? `${best} · ${points.length}` : `${points.length} 张`,
      character: best,
      x: anchor.x,
      y: anchor.y,
      size: points.length,
      color: best ? `rgb(${characterColor(best).map((v) => Math.round(v * 255)).join(',')})` : 'rgba(203,213,225,.85)',
    })
  }
  return result.sort((a, b) => b.size - a.size).slice(0, 22)
})

const labels = computed<Label[]>(() => {
  /* 依赖 frameTick 才能跟着镜头重算 */
  void frameTick.value
  const instance = engine.value
  if (!instance) return []
  const viewport = instance.viewportSize
  const placed: Array<{ x1: number; y1: number; x2: number; y2: number }> = []
  const kept: Label[] = []
  for (const anchor of labelAnchors.value) {
    const screen = instance.worldToScreen(anchor.x, anchor.y)
    /*
     * 锚点自己就在画布外：整个丢掉。
     * 不能"贴"到边上——那会让人以为那儿有团星云，其实目标在屏幕外很远。
     */
    if (screen.x < 0 || screen.x > viewport.width || screen.y < 0 || screen.y > viewport.height) continue

    /* 中文按 11.5px 估宽：比真测量便宜，反正只有十几个标签 */
    const width = anchor.text.length * 11.5 + 20
    const height = 22
    let left = screen.x - width / 2
    let top = screen.y - height / 2
    /*
     * 贴边的**往内挪**而不是丢掉，也不是留着被裁。
     * 直接丢：最大的那团星云往往正好在画布边上，一丢就把最重要的名字丢了。
     * 只判相交再裁：会被画布的 overflow:hidden 切掉半截（「知更鸟 · 2」变成「更鸟 · 2」）。
     * 挪进来两个问题都没有，代价只是偏一点点。
     */
    let offsetX = 0
    if (left < 0) offsetX = -left
    else if (left + width > viewport.width) offsetX = viewport.width - (left + width)
    let offsetY = 0
    if (top < 0) offsetY = -top
    else if (top + height > viewport.height) offsetY = viewport.height - (top + height)
    left += offsetX
    top += offsetY

    const box = { x1: left, y1: top, x2: left + width, y2: top + height }
    /* 小的和已经放下的框重叠就丢掉：不然「小鸟游星野 · 3」和「3 张」会糊在一起 */
    if (placed.some((other) => !(box.x2 < other.x1 || box.x1 > other.x2 || box.y2 < other.y1 || box.y1 > other.y2))) continue
    placed.push(box)
    kept.push({ ...anchor, screenX: screen.x + offsetX, screenY: screen.y + offsetY })
  }
  return kept
})

const selected = computed(() => (selectedIndex.value === null ? null : built.value.items[selectedIndex.value] ?? null))
const selectedCharacter = computed(() => (selectedIndex.value === null ? '' : built.value.nodes[selectedIndex.value]?.character ?? ''))

/** 选中图片的相似图（按分数降序，取前 8 个用于卡片里的「跟它最像的」） */
const selectedNeighbors = computed(() => {
  const index = selectedIndex.value
  if (index === null) return []
  return built.value.edges
    .filter((edge) => edge.a === index || edge.b === index)
    .map((edge) => ({ index: edge.a === index ? edge.b : edge.a, score: edge.score }))
    .sort((a, b) => b.score - a.score)
    .slice(0, 8)
    .map((entry) => ({ item: built.value.items[entry.index], score: entry.score }))
    .filter((entry) => entry.item)
})

/* ---------------- 与引擎联动 ---------------- */

function applyEmphasis() {
  engine.value?.setEmphasis(filtering.value ? matches.value : null)
}

watch(matches, applyEmphasis)

/**
 * 搜索命中但屏幕上几乎看不到时，把镜头移到命中集合的中心。
 * 不做这一步的体验很怪：用户搜「可莉」看到「高亮 13 / 125」，画面却全是暗的——
 * 因为那 13 张在星图另一头。阈值取「少于 3 张可见就飞」：只要零星看到几张就不打扰，
 * 免得每敲一个字镜头都乱跑。
 */
function revealMatches() {
  const instance = engine.value
  if (!instance || !filtering.value) return
  const viewport = instance.viewportSize
  let sumX = 0
  let sumY = 0
  let count = 0
  let visible = 0
  for (let i = 0; i < built.value.nodes.length; i++) {
    if (matches.value[i] <= 0.5) continue
    const node = built.value.nodes[i]
    sumX += node.x
    sumY += node.y
    count += 1
    const screen = instance.worldToScreen(node.x, node.y)
    if (screen.x >= 0 && screen.x <= viewport.width && screen.y >= 0 && screen.y <= viewport.height) visible += 1
  }
  if (!count || visible >= Math.min(3, count)) return
  instance.flyTo(sumX / count, sumY / count)
}

/** 输入时别每敲一个字就飞一次镜头 */
let revealTimer: number | undefined
watch([query, activeCharacter], () => {
  if (revealTimer) window.clearTimeout(revealTimer)
  revealTimer = window.setTimeout(revealMatches, 420)
})

/**
 * 搜索框和角色图例是「互斥的两种筛选」，不要叠加。
 * 叠加的结果很反直觉：选中「纳西妲」后再搜「可莉」，两个条件求交得到 0 张，
 * 用户看到的是「搜索没反应」。实测截图里就是这么翻车的。
 */
watch(query, (value) => { if (value.trim() && activeCharacter.value) activeCharacter.value = '' })

async function rebuild() {
  engine.value?.destroy()
  engine.value = null
  canvasKey.value += 1
  await nextTick()
  mount()
}

function mount() {
  const element = canvas.value
  if (!element) return
  try {
    built.value = build()
    const instance = new GalaxyEngine(element, built.value.nodes, built.value.edges, {
      onHover: (index, clientX, clientY) => {
        hoverIndex.value = index
        if (index !== null) hoverAt.value = { x: clientX, y: clientY }
      },
      onSelect: (index) => { selectedIndex.value = index },
      onOpen: (index) => { const item = built.value.items[index]; if (item) emit('open', item) },
      onView: (zoom, thumbAlpha) => { zoomLevel.value = zoom; thumbVisible.value = thumbAlpha },
      onCamera: () => { frameTick.value = (frameTick.value + 1) % 1000000 },
    })
    engine.value = instance
    applyEmphasis()
  } catch (error) {
    failure.value = error instanceof Error ? error.message : '星系图初始化失败'
  }
}

function toggleCharacter(name: string) {
  activeCharacter.value = activeCharacter.value === name ? '' : name
  /* 同理：点角色就是把范围收到这个角色上，搜索词该让位 */
  if (activeCharacter.value && query.value) query.value = ''
  if (!activeCharacter.value) return
  const first = built.value.nodes.findIndex((node) => node.character === activeCharacter.value)
  if (first >= 0) flyTo(first)
}

function flyTo(index: number) {
  const instance = engine.value
  if (!instance) return
  instance.selectNode(index)
  instance.flyToNode(index)
}

function focusSelected() {
  if (selectedIndex.value !== null) engine.value?.flyToNode(selectedIndex.value)
}

function hovered() {
  return hoverIndex.value === null ? null : built.value.items[hoverIndex.value] ?? null
}

function hoveredCharacter() {
  return hoverIndex.value === null ? '' : built.value.nodes[hoverIndex.value]?.character ?? ''
}

function hoveredSimilar() {
  const index = hoverIndex.value
  if (index === null) return 0
  return built.value.nodes[index]?.degree ?? 0
}

/** 气泡贴着鼠标，靠右/靠下时自动翻到另一侧，别被视口切掉 */
const tooltipStyle = computed(() => {
  const width = 232
  const height = 268
  const x = hoverAt.value.x + 18 + width > window.innerWidth ? hoverAt.value.x - width - 18 : hoverAt.value.x + 18
  const y = hoverAt.value.y + 18 + height > window.innerHeight ? Math.max(8, hoverAt.value.y - height - 18) : hoverAt.value.y + 18
  return { left: `${x}px`, top: `${y}px` }
})

const labelStyle = (label: Label) => ({
  transform: `translate(${label.screenX}px, ${label.screenY}px) translate(-50%, -50%)`,
  color: label.color,
  /* 缩略图一出来就把名字收掉，免得压在图上 */
  opacity: Math.max(0, 1 - thumbVisible.value * 1.6),
})

/* ---------------- 生命周期 ---------------- */

let observer: ResizeObserver | null = null
let detachKeys: (() => void) | null = null

onMounted(() => {
  mount()
  observer = new ResizeObserver(() => engine.value?.resize())
  if (wrapper.value) observer.observe(wrapper.value)
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') { engine.value?.clearSelection(); selectedIndex.value = null }
    if (event.key === '+' || event.key === '=') engine.value?.zoomBy(1.35)
    if (event.key === '-' || event.key === '_') engine.value?.zoomBy(1 / 1.35)
    if (event.key === '0') engine.value?.resetView()
  }
  window.addEventListener('keydown', onKey)
  detachKeys = () => window.removeEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  if (revealTimer) window.clearTimeout(revealTimer)
  revealTimer = undefined
  detachKeys?.()
  detachKeys = null
  engine.value?.destroy()
  engine.value = null
})

watch([() => props.nodes, () => props.edges, () => props.positions, () => props.tagAnchors], rebuild)
</script>

<template>
  <div ref="wrapper" class="galaxy">
    <canvas :key="canvasKey" ref="canvas" class="galaxy-canvas" />

    <div v-if="failure || missingPositions" class="galaxy-failure">
      <Sparkles :size="26" />
      <strong>{{ missingPositions ? '还没拿到星系坐标' : '星系图无法显示' }}</strong>
      <p v-if="missingPositions">服务端这次没有返回 positions 字段——多半是后端还没更新到带二维布局的版本。切到「分组列表」可以先看同样的内容。</p>
      <p v-else>{{ failure }}</p>
      <p class="galaxy-failure-hint">切到「分组列表」看同样的内容。</p>
    </div>

    <template v-else>
      <div class="galaxy-labels">
        <button
          v-for="label in labels"
          :key="label.key"
          class="galaxy-label"
          :style="labelStyle(label)"
          :disabled="Boolean(activeCharacter)"
          @click="label.character && toggleCharacter(label.character)"
        >{{ label.text }}</button>
      </div>

      <!-- 悬停气泡：Teleport 到 body。
           不能留在组件里——.page-content 被 GSAP 的入场动画留下了 transform，
           祖先只要有 transform，position:fixed 就以它为包含块，气泡会整体偏到画布右下角
           （实测偏差正好等于 .page-content 的位置 246/74）。挂到 body 上就永远相对视口。 -->
      <Teleport to="body">
        <div v-if="hovered()" class="galaxy-tip" :style="tooltipStyle">
          <img :src="hovered()!.thumb" :alt="hovered()!.name" loading="lazy" decoding="async" />
          <strong>{{ hovered()!.name }}</strong>
          <div class="galaxy-tip-meta">
            <span v-if="hoveredCharacter()" class="galaxy-chip" :style="{ '--chip': `rgb(${characterColor(hoveredCharacter()).map((v) => Math.round(v * 255)).join(',')})` }">{{ hoveredCharacter() }}</span>
            <span class="galaxy-tip-dim">{{ hoveredSimilar() }} 张相似</span>
          </div>
          <p v-if="hovered()!.tags.length">{{ hovered()!.tags.slice(0, 5).join(' · ') }}</p>
        </div>
      </Teleport>

      <!-- 工具栏 -->
      <div class="galaxy-hud">
        <div class="galaxy-search">
          <Search :size="14" />
          <input v-model="query" placeholder="搜图片名 / 角色 / 标签…" spellcheck="false" />
          <button v-if="query" class="galaxy-search-clear" title="清空" @click="query = ''"><X :size="13" /></button>
        </div>
        <div v-if="filtering" class="galaxy-hit">
          高亮 {{ hitCount }} / {{ built.nodes.length }} 张
          <button class="galaxy-hit-action" @click="revealMatches">定位</button>
          <button class="galaxy-hit-action" @click="query = ''; activeCharacter = ''">全部显示</button>
        </div>
        <div class="galaxy-legend">
          <button
            v-for="entry in legend"
            :key="entry.name"
            class="galaxy-legend-item"
            :class="{ active: activeCharacter === entry.name }"
            @click="toggleCharacter(entry.name)"
          >
            <i :style="{ background: entry.color }" />{{ entry.name }}<small>{{ entry.count }}</small>
          </button>
        </div>
      </div>

      <!-- 缩放控件 -->
      <div class="galaxy-zoom">
        <button title="放大（+）" @click="engine?.zoomBy(1.35)"><Plus :size="15" /></button>
        <button title="缩小（-）" @click="engine?.zoomBy(1 / 1.35)"><Minus :size="15" /></button>
        <button title="回到全景（0）" @click="engine?.resetView()"><Focus :size="15" /></button>
      </div>

      <div class="galaxy-hint">
        <LoaderCircle v-if="!built.nodes.length" :size="13" class="spin" />
        <template v-else>
          滚轮缩放 · 拖动平移 · 点一下看详情 · 双击打开大图
          <em v-if="thumbVisible < 0.5">（再放大些就显示缩略图）</em>
          <em v-else>· 放大 ×{{ (zoomLevel / (engine?.fitZoomLevel || zoomLevel)).toFixed(1) }}</em>
        </template>
      </div>

      <!-- 选中卡片：固定右下角，不跟着点跑，读起来稳 -->
      <transition name="galaxy-card">
        <div v-if="selected" class="galaxy-card">
          <button class="galaxy-card-close" title="取消选中" @click="engine?.clearSelection()"><X :size="14" /></button>
          <img :src="selected.thumb" :alt="selected.name" decoding="async" @click="emit('open', selected)" />
          <div class="galaxy-card-body">
            <strong :title="selected.name">{{ selected.name }}</strong>
            <div class="galaxy-card-meta">
              <span v-if="selectedCharacter" class="galaxy-chip" :style="{ '--chip': `rgb(${characterColor(selectedCharacter).map((v) => Math.round(v * 255)).join(',')})` }">{{ selectedCharacter }}</span>
              <span>{{ selected.tags.length }} 个标签</span>
              <span>{{ selected.views }} 次浏览</span>
            </div>
            <div v-if="selectedNeighbors.length" class="galaxy-card-neighbors">
              <small>最像的 {{ selectedNeighbors.length }} 张</small>
              <div class="galaxy-card-strip">
                <button v-for="entry in selectedNeighbors" :key="entry.item.id" :title="`${entry.item.name} · ${Math.round(entry.score * 100)}%`" @click="emit('open', entry.item)">
                  <img :src="entry.item.thumb" :alt="entry.item.name" loading="lazy" decoding="async" />
                  <span>{{ Math.round(entry.score * 100) }}</span>
                </button>
              </div>
            </div>
            <div class="galaxy-card-actions">
              <button class="primary-button" @click="emit('open', selected)">打开大图 <ChevronRight :size="14" /></button>
              <button class="filter-button" @click="focusSelected">居中</button>
            </div>
          </div>
        </div>
      </transition>
    </template>
  </div>
</template>

<style scoped>
/* ============================================================================
   深空舞台 + 发光节点 + 玻璃控件。
   舞台底色与星云本体由 WebGL 画（galaxy/engine.ts），这里只负责：
     · 舞台的兜底底色与两团极光晕（画布首帧之前、以及画布边缘的过渡）
     · DOM 控件：星云名、工具栏、缩放、提示、选中卡片、悬停气泡
   控件（工具栏 / 缩放 / 卡片 / 气泡）统一走「深空玻璃」：白字白描边，
   深色与浅色主题下都成立——舞台本身始终是深空，浅色下也照样压得住。
============================================================================ */
.galaxy {
  /* 深空玻璃的局部令牌：整套界面只有这一个舞台，浅色主题下也保持深色玻璃 */
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
  /* 兜底层：深空渐变 + 角上的两团极光雾（正式星云由 WebGL 画在上面） */
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

/* ------------------------------- 画布 ------------------------------- */
.galaxy-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  /* 触摸设备上必须吃掉默认手势，否则拖动会变成滚动页面而不是平移星图 */
  touch-action: none;
  cursor: grab;
}
.galaxy-canvas:active { cursor: grabbing; }

/* 画布四边往里收一点，让星云自然融进舞台底色而不是被硬边裁断 */
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
/* 位置由 JS 逐帧写进 transform，所以这里只定外观 */
.galaxy-labels { position: absolute; inset: 0; z-index: 2; pointer-events: none; }

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
  transition: opacity var(--t-mid) var(--ease), background var(--t-fast) var(--ease);
}
/* 二维视图的 transform 由 JS 每帧写入（translate 定位 + 居中），所以这里不能碰 transform，
   只用背景和亮度表达悬停/按下 */
.galaxy-label:hover { background: var(--galaxy-glass-raise); }
.galaxy-label:active { background: color-mix(in srgb, var(--accent-1) 30%, var(--galaxy-glass-solid)); }
.galaxy-label:disabled { cursor: default; }
.galaxy-label:disabled:hover { background: var(--galaxy-glass); }

/* --------------------------- 工具栏（HUD） --------------------------- */
.galaxy-hud {
  position: absolute;
  z-index: 3;
  left: 12px;
  top: 12px;
  right: 60px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  pointer-events: none;
}
.galaxy-hud > * { pointer-events: auto; }

.galaxy-search {
  display: flex;
  align-items: center;
  gap: 8px;
  width: min(290px, 100%);
  height: 36px;
  padding: 0 6px 0 12px;
  border: 1px solid var(--galaxy-line);
  border-radius: var(--r-pill);
  background: var(--galaxy-glass);
  -webkit-backdrop-filter: blur(12px) saturate(1.4);
  backdrop-filter: blur(12px) saturate(1.4);
  box-shadow: var(--galaxy-hl), var(--shadow-1);
  color: var(--galaxy-ink-3);
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-mid) var(--ease);
}
.galaxy-search:focus-within {
  border-color: color-mix(in srgb, var(--accent-2) 62%, transparent);
  box-shadow: var(--galaxy-hl), 0 0 0 4px color-mix(in srgb, var(--accent-1) 22%, transparent);
}
.galaxy-search input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--galaxy-ink);
  font-size: 12.5px;
}
.galaxy-search input::placeholder { color: var(--galaxy-ink-3); }
.galaxy-search-clear {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--galaxy-ink-3);
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.galaxy-search-clear:hover { background: rgba(255, 255, 255, .12); color: var(--galaxy-ink); }

.galaxy-hit {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  border: 1px solid var(--galaxy-line-soft);
  border-radius: var(--r-pill);
  background: var(--galaxy-glass);
  -webkit-backdrop-filter: blur(10px) saturate(1.3);
  backdrop-filter: blur(10px) saturate(1.3);
  box-shadow: var(--galaxy-hl);
  color: var(--galaxy-ink-2);
  font: 500 10.5px var(--font-mono);
  letter-spacing: .02em;
}
.galaxy-hit-action {
  padding: 0;
  background: transparent;
  color: color-mix(in oklab, var(--accent-2) 62%, white);
  font-size: 10.5px;
  font-weight: 600;
  transition: color var(--t-fast) var(--ease);
}
.galaxy-hit-action:hover { color: #fff; text-decoration: underline; }

.galaxy-legend { display: flex; flex-wrap: wrap; gap: 6px; max-width: 100%; }
.galaxy-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--galaxy-line-soft);
  border-radius: var(--r-pill);
  background: var(--galaxy-glass);
  -webkit-backdrop-filter: blur(8px) saturate(1.3);
  backdrop-filter: blur(8px) saturate(1.3);
  box-shadow: var(--galaxy-hl);
  color: var(--galaxy-ink-2);
  font-size: 11px;
  transition: color var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.galaxy-legend-item i {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  box-shadow: 0 0 9px currentColor;
}
.galaxy-legend-item small { color: var(--galaxy-ink-3); font: 500 10px var(--font-mono); }
.galaxy-legend-item:hover { color: var(--galaxy-ink); border-color: var(--galaxy-line-strong); }
.galaxy-legend-item:active { transform: scale(.96); }
.galaxy-legend-item.active {
  color: #fff;
  border-color: color-mix(in srgb, var(--accent-1) 66%, transparent);
  background: color-mix(in srgb, var(--accent-1) 34%, var(--galaxy-glass-solid));
  box-shadow: var(--galaxy-hl), 0 8px 20px -12px color-mix(in srgb, var(--accent-1) 80%, transparent);
}
.galaxy-legend-item.active small { color: rgba(255, 255, 255, .78); }

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
  align-items: center;
  gap: 7px;
  max-width: calc(100% - 28px);
  padding: 6px 12px;
  border: 1px solid var(--galaxy-line-soft);
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--galaxy-glass-solid) 78%, transparent);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
  color: var(--galaxy-ink-3);
  font: 500 10.5px var(--font-mono);
  letter-spacing: .02em;
  pointer-events: none;
}
.galaxy-hint em { font-style: normal; color: color-mix(in oklab, var(--accent-2) 58%, white); }

/* ------------------------------ 选中卡片 ------------------------------ */
.galaxy-card {
  position: absolute;
  z-index: 4;
  right: 12px;
  bottom: 12px;
  width: 258px;
  /* 小屏上卡片可能比画布还高：限高 + 自己滚，别顶出容器外 */
  max-height: calc(100% - 24px);
  overflow-y: auto;
  padding: 10px;
  border: 1px solid var(--galaxy-line);
  border-radius: var(--r-lg);
  /* 卡片不给 backdrop-filter（性能守则：网格卡片级别的大面积不糊背景），
     用接近实心的玻璃色 + 顶部高光描边做出同样的层次 */
  background: linear-gradient(180deg, rgba(255, 255, 255, .07), transparent 42%), var(--galaxy-glass-solid);
  box-shadow: var(--galaxy-hl), var(--shadow-2), 0 26px 60px -40px color-mix(in srgb, var(--accent-1) 70%, transparent);
}
.galaxy-card-close {
  position: absolute;
  z-index: 2;
  right: 14px;
  top: 14px;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--galaxy-line);
  border-radius: 50%;
  background: rgba(6, 10, 22, .68);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
  color: var(--galaxy-ink-2);
  transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), transform var(--t-mid) var(--ease);
}
.galaxy-card-close:hover { color: #fff; background: rgba(6, 10, 22, .9); transform: rotate(90deg); }
.galaxy-card > img {
  display: block;
  width: 100%;
  height: 148px;
  object-fit: cover;
  border-radius: var(--r-md);
  border: 1px solid var(--galaxy-line-soft);
  background: var(--media-bg);
  cursor: pointer;
  transition: filter var(--t-mid) var(--ease);
}
.galaxy-card > img:hover { filter: brightness(1.08); }
.galaxy-card-body { padding: 10px 2px 0; }
.galaxy-card-body > strong {
  display: block;
  color: var(--galaxy-ink);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.galaxy-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  color: var(--galaxy-ink-3);
  font: 500 10px var(--font-mono);
}
.galaxy-card-neighbors { margin-top: 12px; }
.galaxy-card-neighbors small {
  display: block;
  margin-bottom: 6px;
  color: var(--galaxy-ink-3);
  font: 500 10px var(--font-mono);
  letter-spacing: .04em;
}
.galaxy-card-strip { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 3px; }
.galaxy-card-strip button {
  position: relative;
  flex: none;
  width: 44px;
  height: 44px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--galaxy-line-soft);
  border-radius: var(--r-xs);
  background: var(--media-bg);
  transition: border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.galaxy-card-strip button:hover { border-color: color-mix(in srgb, var(--accent-1) 70%, transparent); transform: translateY(-2px); }
.galaxy-card-strip img { display: block; width: 100%; height: 100%; object-fit: cover; }
.galaxy-card-strip span {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 1px 0;
  background: rgba(4, 8, 18, .78);
  color: #e2e8f5;
  font: 500 9px var(--font-mono);
}
.galaxy-card-actions { display: flex; gap: 8px; margin-top: 13px; }
.galaxy-card-actions .primary-button { flex: 1; justify-content: center; height: 34px; padding: 0 12px; font-size: 12px; }
.galaxy-card-actions .filter-button { height: 34px; border-color: var(--galaxy-line); background: rgba(255, 255, 255, .07); color: var(--galaxy-ink-2); }
.galaxy-card-actions .filter-button:hover { background: rgba(255, 255, 255, .14); border-color: var(--galaxy-line-strong); color: #fff; }

.galaxy-card-enter-active, .galaxy-card-leave-active { transition: opacity var(--t-mid) var(--ease), transform var(--t-slow) var(--ease); }
.galaxy-card-enter-from, .galaxy-card-leave-to { opacity: 0; transform: translateY(14px) scale(.97); }

/* ------------------------------ 悬停气泡 ------------------------------ */
/* Teleport 到 body，作用域标记仍在这棵子树上，所以下面的规则照样命中；
   但它在 .galaxy 之外，拿不到那套局部令牌，所以这里把用到的几个值原样再声明一次。 */
.galaxy-tip {
  --galaxy-ink: #f2f4fb;
  --galaxy-ink-3: rgba(206, 215, 242, .52);
  --galaxy-glass-solid: rgba(11, 14, 30, .9);
  --galaxy-line: rgba(255, 255, 255, .16);
  --galaxy-line-soft: rgba(255, 255, 255, .1);
  --galaxy-hl: inset 0 1px 0 rgba(255, 255, 255, .16);
  position: fixed;
  z-index: 40;
  width: 232px;
  padding: 10px;
  border: 1px solid var(--galaxy-line);
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, rgba(255, 255, 255, .07), transparent 40%), var(--galaxy-glass-solid);
  -webkit-backdrop-filter: blur(18px) saturate(1.4);
  backdrop-filter: blur(18px) saturate(1.4);
  box-shadow: var(--galaxy-hl), var(--shadow-2);
  color: var(--galaxy-ink);
  pointer-events: none;
}
.galaxy-tip img {
  display: block;
  width: 100%;
  height: 132px;
  object-fit: cover;
  border-radius: var(--r-sm);
  border: 1px solid var(--galaxy-line-soft);
  background: var(--media-bg);
}
.galaxy-tip strong {
  display: block;
  margin: 9px 2px 0;
  color: var(--galaxy-ink);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.galaxy-tip p {
  margin: 7px 2px 0;
  color: var(--galaxy-ink-3);
  font-size: 11px;
  line-height: 1.55;
}
.galaxy-tip-meta { display: flex; align-items: center; gap: 8px; margin: 7px 2px 0; }
.galaxy-tip-dim { color: var(--galaxy-ink-3); font: 500 10px var(--font-mono); }

.galaxy-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: var(--r-pill);
  border: 1px solid color-mix(in srgb, var(--chip) 42%, transparent);
  background: color-mix(in srgb, var(--chip) 22%, transparent);
  color: var(--chip);
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

/* ------------------------------ 失败态 ------------------------------ */
.galaxy-failure {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 24px;
  color: var(--galaxy-ink-3);
  text-align: center;
}
.galaxy-failure svg { color: color-mix(in oklab, var(--accent-1) 60%, white); }
.galaxy-failure strong { color: var(--galaxy-ink); font: 600 15px var(--font-display); }
.galaxy-failure p { margin: 0; max-width: 420px; font-size: 12px; line-height: 1.7; }
.galaxy-failure-hint { color: var(--galaxy-ink-3); }

/* ------------------------------- 响应式 ------------------------------- */
@media (max-width: 900px) {
  .galaxy-card { width: 232px; }
  .galaxy-card > img { height: 128px; }
}

@media (max-width: 720px) {
  .galaxy {
    height: clamp(400px, calc(100vh - 300px), 720px);
    border-radius: var(--r-lg);
  }
  /* 手机端全局禁用 backdrop-filter（性能守则），玻璃靠 tint 与高光 */
  .galaxy-label,
  .galaxy-search,
  .galaxy-hit,
  .galaxy-legend-item,
  .galaxy-zoom button,
  .galaxy-hint,
  .galaxy-card-close,
  .galaxy-tip {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  .galaxy-search { background: var(--galaxy-glass-solid); }
  .galaxy-legend-item { background: var(--galaxy-glass-solid); }
  .galaxy-zoom button { background: var(--galaxy-glass-solid); }
  .galaxy-hud { right: 54px; gap: 6px; }
  .galaxy-search { width: 100%; height: 38px; }
  /* 图例横滑一行，别把画布挤掉 */
  .galaxy-legend {
    flex-wrap: nowrap;
    width: 100%;
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 4px;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  .galaxy-legend::-webkit-scrollbar { display: none; }
  .galaxy-zoom button { width: 32px; height: 32px; }
  .galaxy-card {
    left: 10px;
    right: 10px;
    bottom: 10px;
    width: auto;
    max-height: 62%;
  }
  .galaxy-card > img { height: 116px; }
  /* 底部提示与卡片重叠：手机上让位给卡片 */
  .galaxy-hint { display: none; }
  .galaxy-tip { display: none; }
}
</style>

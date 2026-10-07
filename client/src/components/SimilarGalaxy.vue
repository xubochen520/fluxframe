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
  positions: Record<string, [number, number]>
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

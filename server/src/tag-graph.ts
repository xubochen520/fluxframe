/**
 * 标签关系网：用**标签**而不是视觉指纹描述图片之间的关系。
 *
 * 与视觉指纹模式的区别，一句话：视觉指纹问「这两张图长得像不像」，
 * 标签模式问「这两张图被同一批人打了同样的标记没有」。
 * 两者互补 —— 指纹能发现「同一张图的不同裁切/换色」，标签能发现「同一个作者/同一本画集/同一套服装」
 * 这类看起来不像但被一起归档的东西。
 *
 * 【相似度】TF-IDF + 余弦。
 *   纯按「共享标签数」算是不行的：`萝莉` 在 125/128 张图上都有，共享它等于没共享。
 *   平滑 IDF = log((1+N)/(1+df)) + 1 正好解决这件事 ——
 *   人人皆有的标签权重 1.02，只在一张图上出现的权重 5.17，差 5 倍。
 *
 * 【为什么不用「对图片的标签向量跑 UMAP」】试过了，不行：
 *   128 张图只有约 40 种不同的标签组合，L2 归一化之后大量点完全重合，
 *   UMAP 在这种退化输入上会缩成一坨（实测 80% 画布是空的，可莉被甩到角落）。
 *   改成**先排标签、再把图片挂到自己的标签上**：标签只有 25 个、彼此关系清楚，
 *   先给标签跑一次降维拿到锚点，图片位置 = 自己所有标签的 IDF 加权重心。
 *   这样既稳定（新加图片不会挪动老图片，除非引入新标签）又可读 ——
 *   客户端还能把标签名直接画在锚点上。
 *
 * 【重合怎么办】标签组合完全相同的图片（比如那 10 张「只有萝莉」的）在标签空间里
 *   本来就不区分，会落在同一点。用黄金角螺线把它们摊开：确定性、不重叠、整体仍在原地。
 */
import { embedDir } from './embed.js'
import { computeLayout, computeLayout3, type EmbeddingSource, type Layout3Result, type LayoutResult } from './layout.js'
import { readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'

/** 算法版本：改了权重口径或铺开方式就 +1，缓存自动作废 */
export const TAG_GRAPH_VERSION = 'tags/tfidf-cos@0.8/anchor-v1'

/**
 * 标签相似度的默认阈值（余弦）。
 * 实测本库：0.8 时 720 条边、19 个分组，而且分出来的组「会说人话」——
 * 30 张全是纳西妲、11 张全是白丝、11 张猫耳、10 张可莉……低于这个值会连成一大坨，
 * 高于它则只剩「标签组合几乎一样」的才算相关。
 */
export const TAG_SIMILARITY_THRESHOLD = 0.8

/** 标签图里一条边的上限比指纹模式宽：这里一条边就是一个确凿的共同标签，不是噪声 */
export const TAG_GRAPH_EDGE_LIMIT = 1000

export interface TagRow {
  id: string
  tags: string[]
}

/**
 * 一个「关系」。
 *
 * 标签模式下关系就是标签本身 —— **一张图有几个标签，就同时属于几个关系**。
 * 这跟并查集连通分量是两回事：连通分量会把「纳西妲 + 白丝」的图只归到其中一组
 * （谁先合并算谁的），而用户想看到的是它在两个关系里都出现。
 */
export interface TagGroup {
  /** 关系名 = 标签名 */
  label: string
  members: string[]
  size: number
  /** 这个标签的 IDF 权重：越稀有的标签，关系越"紧" */
  weight: number
}

export interface TagGraphResult {
  version: string
  threshold: number
  /** 每张图的位置：自己标签锚点的 IDF 加权重心（重合的按螺线摊开） */
  positions: Record<string, [number, number]>
  /** 每个标签的锚点位置，客户端可以画成星云名称 */
  tagAnchors: Record<string, [number, number]>
  /** 每张图最有区分度的标签（IDF 最高），用来上色与列表标题 */
  primaryTags: Record<string, string>
  /** 图片的 TF-IDF 向量（已 L2 归一化），交给 neighborsOf 算边 */
  source: EmbeddingSource
  /** 关系列表：**可重叠**，按图片数从多到少 */
  groups: TagGroup[]
  /** 标签总数与图片总数，前端展示用 */
  tagCount: number
  /** 参与计算的图片数（有标签的） */
  covered: number
}

/** 平滑 IDF：df=N 时趋近 1，df=1 时约 5.2 */
function smoothIdf(total: number, df: number) {
  return Math.log((1 + total) / (1 + df)) + 1
}

/** 黄金角，用来把重合的点摊成不重叠的螺线 */
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))
/** 螺线的相邻间距（世界单位）。0.022 时 35 张重合的图摊开半径约 0.13，放大能看清是 35 个点 */
const SPIRAL_SPACING = 0.022

export function buildTagGraph(rows: TagRow[], tagLayout: LayoutResult): TagGraphResult {
  const usable = rows.filter((row) => row.tags.length > 0)
  const total = usable.length
  const df = new Map<string, number>()
  for (const row of usable) for (const tag of new Set(row.tags)) df.set(tag, (df.get(tag) ?? 0) + 1)
  const idf = (tag: string) => smoothIdf(total, df.get(tag) ?? 0)

  /* ---- 图片的 TF-IDF 向量（稠密词表，L2 归一化后余弦就是点积）---- */
  const vocabulary = [...df.keys()].sort()
  const entries: Record<string, { vec: number[]; sha256: string }> = {}
  for (const row of usable) {
    const vector = new Array<number>(vocabulary.length).fill(0)
    for (const tag of new Set(row.tags)) {
      const index = vocabulary.indexOf(tag)
      if (index >= 0) vector[index] = idf(tag)
    }
    let norm = 0
    for (const value of vector) norm += value * value
    norm = Math.sqrt(norm) || 1
    for (let i = 0; i < vector.length; i++) vector[i] /= norm
    /* sha256 字段在这里当「内容指纹」用，layout.ts 的缓存键靠它判断要不要重算 */
    entries[row.id] = { vec: vector, sha256: `tags:${[...row.tags].sort().join(',')}` }
  }

  /* ---- 每张图最有区分度的标签 + 位置 = 自己标签锚点的 IDF 加权重心 ---- */
  const primaryTags: Record<string, string> = {}
  const tagAnchors: Record<string, [number, number]> = {}
  for (const tag of vocabulary) {
    const point = tagLayout.points[tag]
    if (point) tagAnchors[tag] = [point[0], point[1]]
  }

  /* 标签组合完全相同的图片先分到一组，组内再按螺线摊开 */
  const buckets = new Map<string, TagRow[]>()
  for (const row of usable) {
    const key = [...new Set(row.tags)].sort().join('\u0000')
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key)!.push(row)
  }

  const positions: Record<string, [number, number]> = {}
  for (const [, bucket] of buckets) {
    let sumX = 0
    let sumY = 0
    let sumWeight = 0
    for (const tag of new Set(bucket[0].tags)) {
      const anchor = tagAnchors[tag]
      if (!anchor) continue
      const weight = idf(tag)
      sumX += anchor[0] * weight
      sumY += anchor[1] * weight
      sumWeight += weight
    }
    const baseX = sumWeight > 0 ? sumX / sumWeight : 0
    const baseY = sumWeight > 0 ? sumY / sumWeight : 0
    bucket.forEach((row, index) => {
      if (index === 0) {
        positions[row.id] = [baseX, baseY]
      } else {
        const radius = SPIRAL_SPACING * Math.sqrt(index)
        const angle = index * GOLDEN_ANGLE
        positions[row.id] = [baseX + Math.cos(angle) * radius, baseY + Math.sin(angle) * radius]
      }
    })
  }

  for (const row of usable) {
    let best = ''
    let bestIdf = -1
    for (const tag of new Set(row.tags)) {
      const value = idf(tag)
      if (value > bestIdf) { bestIdf = value; best = tag }
    }
    primaryTags[row.id] = best
  }

  /* ---- 关系 = 标签：一个标签一个关系，可重叠 ---- */
  const groups: TagGroup[] = []
  for (const tag of vocabulary) {
    const members = usable.filter((row) => row.tags.includes(tag)).map((row) => row.id).sort()
    /* 只有一张图的标签算不上「关系」，列出来只是噪音 */
    if (members.length < 2) continue
    groups.push({ label: tag, members, size: members.length, weight: idf(tag) })
  }
  /* 图多的排前面：客户端的标签名分级显示直接按这个顺序取前 N 个 */
  groups.sort((a, b) => b.size - a.size || a.label.localeCompare(b.label))

  return {
    version: TAG_GRAPH_VERSION,
    threshold: TAG_SIMILARITY_THRESHOLD,
    positions,
    tagAnchors,
    primaryTags,
    source: { version: TAG_GRAPH_VERSION, entries },
    groups,
    tagCount: vocabulary.length,
    covered: total,
  }
}

/* ---------------- 三维版：给 3D 星系视图用 ---------------- */

/**
 * 三维标签锚点 + 图片位置。
 *
 * 与二维同一套语义（先排标签、再把图片挂到自己标签的加权重心上），只是多了一个自由度。
 * 【为什么不在三维里直接对图片的标签向量跑降维】二维那边已经踩过：128 张图只有约 40 种
 * 不同的标签组合，向量大量重合，降维会缩成一坨。挂锚点则天然分散。
 *
 * 重合的图片用**黄金角球面**摊开（二维是黄金角螺线）：
 * 球面螺旋保证摊出来的点均匀、不重叠，而且确定性 —— 同一批数据每次都是同一个结果。
 */
function tagLayout3File() { return path.join(embedDir(), 'layout3-tag-anchors.json') }

let tagLayout3Cache: TagLayoutCache<Layout3Result> | null = null
let tagLayout3Inflight: Promise<Layout3Result> | null = null

export async function ensureTagLayout3(rows: TagRow[]): Promise<Layout3Result> {
  const usable = rows.filter((row) => row.tags.length > 0)
  const vocabulary = [...new Set(usable.flatMap((row) => row.tags))].sort()
  const vocabularyKey = `${vocabulary.length}|${vocabulary.join(',')}`
  if (tagLayout3Cache && tagLayout3Cache.version === TAG_GRAPH_VERSION && tagLayout3Cache.vocabularyKey === vocabularyKey) {
    return tagLayout3Cache.layout
  }
  if (tagLayout3Inflight) return tagLayout3Inflight

  tagLayout3Inflight = (async () => {
    try {
      const parsed = JSON.parse(await readFile(tagLayout3File(), 'utf8')) as TagLayoutCache<Layout3Result>
      if (parsed?.version === TAG_GRAPH_VERSION && parsed.vocabularyKey === vocabularyKey && parsed.layout?.points) {
        tagLayout3Cache = parsed
        return parsed.layout
      }
    } catch { /* 没有缓存就算一次 */ }

    const entries: Record<string, { vec: number[]; sha256: string }> = {}
    for (const tag of vocabulary) {
      const vector: number[] = usable.map((row) => (row.tags.includes(tag) ? 1 : 0))
      let norm = Math.sqrt(vector.reduce((sum: number, value: number) => sum + value * value, 0)) || 1
      entries[tag] = { vec: vector.map((value) => value / norm), sha256: `tag:${tag}` }
    }
    const layout = await computeLayout3({ version: TAG_GRAPH_VERSION, entries })
    tagLayout3Cache = { version: TAG_GRAPH_VERSION, vocabularyKey, layout }
    try {
      const target = tagLayout3File()
      const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
      await writeFile(tmp, JSON.stringify(tagLayout3Cache), 'utf8')
      await rename(tmp, target)
    } catch { /* 缓存写不进去不影响本次返回 */ }
    return layout
  })()

  try {
    return await tagLayout3Inflight
  } finally {
    tagLayout3Inflight = null
  }
}

/** 图片的三维位置 = 自己标签锚点的 IDF 加权重心；重合的按黄金角球面摊开 */
export function tagPositions3(rows: TagRow[], tagLayout: Layout3Result): Record<string, [number, number, number]> {
  const usable = rows.filter((row) => row.tags.length > 0)
  const total = usable.length
  const df = new Map<string, number>()
  for (const row of usable) for (const tag of new Set(row.tags)) df.set(tag, (df.get(tag) ?? 0) + 1)
  const idf = (tag: string) => smoothIdf(total, df.get(tag) ?? 0)

  const buckets = new Map<string, TagRow[]>()
  for (const row of usable) {
    const key = [...new Set(row.tags)].sort().join('\u0000')
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key)!.push(row)
  }

  const result: Record<string, [number, number, number]> = {}
  for (const [, bucket] of buckets) {
    let sumX = 0
    let sumY = 0
    let sumZ = 0
    let sumWeight = 0
    for (const tag of new Set(bucket[0].tags)) {
      const anchor = tagLayout.points[tag]
      if (!anchor) continue
      const weight = idf(tag)
      sumX += anchor[0] * weight
      sumY += anchor[1] * weight
      sumZ += anchor[2] * weight
      sumWeight += weight
    }
    const base: [number, number, number] = sumWeight > 0 ? [sumX / sumWeight, sumY / sumWeight, sumZ / sumWeight] : [0, 0, 0]
    bucket.forEach((row, index) => {
      if (index === 0) { result[row.id] = base; return }
      /* 黄金角球面螺旋：均匀、确定、不重叠 */
      const t = (index + 0.5) / bucket.length
      const y = 1 - 2 * t
      const ring = Math.sqrt(Math.max(0, 1 - y * y))
      const theta = GOLDEN_ANGLE * index
      const radius = SPIRAL_SPACING * Math.cbrt(index)
      result[row.id] = [
        base[0] + Math.cos(theta) * ring * radius,
        base[1] + y * radius,
        base[2] + Math.sin(theta) * ring * radius,
      ]
    })
  }
  return result
}

interface TagLayoutCache<T> {
  version: string
  /** 词表内容指纹：标签集合变了才需要重排锚点 */
  vocabularyKey: string
  layout: T
}

function tagLayoutFile() { return path.join(embedDir(), 'layout-tag-anchors.json') }

let tagLayoutCache: TagLayoutCache<LayoutResult> | null = null
let tagLayoutInflight: Promise<LayoutResult> | null = null

/**
 * 标签锚点的位置。
 *
 * 把「标签 × 图片」矩阵**转置**过来，每个标签就是一个 (图片数) 维向量，跑和视觉指纹同一套降维：
 * 于是「经常出现在同一批图片上的标签」自然靠得近，图片再挂上去就成了星云。
 *
 * 锚点只取决于标签集合、跟具体图片的归属关系不大，所以能长期缓存 ——
 * 新上传图片不会让整张标签图挪位置。
 */
export async function ensureTagLayout(rows: TagRow[]): Promise<LayoutResult> {
  const usable = rows.filter((row) => row.tags.length > 0)
  const vocabulary = [...new Set(usable.flatMap((row) => row.tags))].sort()
  const vocabularyKey = `${vocabulary.length}|${vocabulary.join(',')}`
  if (tagLayoutCache && tagLayoutCache.version === TAG_GRAPH_VERSION && tagLayoutCache.vocabularyKey === vocabularyKey) {
    return tagLayoutCache.layout
  }
  if (tagLayoutInflight) return tagLayoutInflight

  tagLayoutInflight = (async () => {
    try {
      const parsed = JSON.parse(await readFile(tagLayoutFile(), 'utf8')) as TagLayoutCache<LayoutResult>
      if (parsed?.version === TAG_GRAPH_VERSION && parsed.vocabularyKey === vocabularyKey && parsed.layout?.points) {
        tagLayoutCache = parsed
        return parsed.layout
      }
    } catch { /* 没有缓存就算一次 */ }

    /* 每个标签一个向量：出现在哪些图片上（0/1）。L2 归一化后同一套 kNN + UMAP */
    const entries: Record<string, { vec: number[]; sha256: string }> = {}
    for (const tag of vocabulary) {
      const vector: number[] = usable.map((row) => (row.tags.includes(tag) ? 1 : 0))
      let norm = Math.sqrt(vector.reduce((sum: number, value: number) => sum + value * value, 0)) || 1
      entries[tag] = { vec: vector.map((value) => value / norm), sha256: `tag:${tag}` }
    }
    const layout = await computeLayout({ version: TAG_GRAPH_VERSION, entries })
    tagLayoutCache = { version: TAG_GRAPH_VERSION, vocabularyKey, layout }
    try {
      const target = tagLayoutFile()
      const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
      await writeFile(tmp, JSON.stringify(tagLayoutCache), 'utf8')
      await rename(tmp, target)
    } catch { /* 缓存写不进去不影响本次返回 */ }
    return layout
  })()

  try {
    return await tagLayoutInflight
  } finally {
    tagLayoutInflight = null
  }
}

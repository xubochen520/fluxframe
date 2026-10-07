/**
 * 视觉指纹（CCIP）：给每张图片算一个 768 维向量，用来找「看起来像同一角色/同一张图」的图片。
 *
 * 模型：deepghs/ccip_onnx 的 ccip-caformer-24-randaug-pruned/model_feat.onnx
 *   这是 7eu7d7 训练的 Character-Centric Image Pretraining（CCIP），专为「判断两张动漫图
 *   是不是同一个角色」而训练，官方指标 F1 0.917 / precision 0.933 / recall 0.901。
 *
 * 预处理严格照 deepghs/imgutils 的 imgutils.metrics.ccip._preprocess_image：
 *   resize 384x384（PIL BILINEAR == sharp 的 'linear' 核）→ /255 → CLIP 均值方差归一化
 *
 * 【重要实测结论】官方配套的 model_metrics.onnx 头在本库上反而更差，不要用：
 *   - 直接对 feat 向量做余弦距离：AUC 0.991（同角色对 p90=0.28，异角色对 p10=0.50）
 *   - 送进 metrics 头（a-b / |a-b| 两种口径）：AUC 0.38 / 0.24，方向都是反的
 *   既然余弦已经 0.99，就用余弦，少维护一个模型。
 *
 * 【为什么不用标签向量做相似度】标签向量那条路已实测失败：只有参考图 >= 10 张的角色才准，
 * 12/18 个角色只有 1~2 张图，基本全错。视觉指纹直接从像素提特征，跟标签质量无关。
 *
 * 存储：模型目录下 embed/index.json（指纹 + 相似邻居缓存）。不写数据库、不动原图。
 */
import { existsSync } from 'node:fs'
import { access, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'
import { modelsDir } from './models-dir.js'

/**
 * resize 的 kernel 形参类型是 keyof KernelEnum（字符串字面量联合）。
 * 注意 sharp 0.33 的 index.d.ts **漏声明了 linear**（运行时 sharp.kernel 里确实有，
 * 官方文档也列了），所以这里必须断言，不能直接标成 keyof KernelEnum。
 * 用 'linear'（双线性）而不是 'cubic'：imgutils 的 _preprocess_image 用的是 PIL BILINEAR，
 * 保持与官方预处理口径一致，指纹才和官方指标可比。
 */
const RESIZE_KERNEL = 'linear' as unknown as keyof import('sharp').KernelEnum

/** 模型要求的输入边长（固定值，改了就废） */
const INPUT_SIZE = 384
/** 与 imgutils 完全一致的 CLIP 归一化参数 */
const MEAN = [0.48145466, 0.4578275, 0.40821073]
const STD = [0.26862954, 0.26130258, 0.27577711]
/** 指纹版本：换模型/换预处理口径时 +1，旧指纹会被判为过期并重算 */
const EMBED_VERSION = 'ccip-caformer-24-randaug-pruned@384-stretch@cos'
/**
 * 相似度阈值（余弦）。依据本库实测分布：同角色对 p90=0.28、异角色对 p10=0.50，
 * 0.35 落在两者之间的空带里，既不会大面积漏、也不会把无关图塞进来。
 */
export const DEFAULT_SIMILARITY_THRESHOLD = 0.35
/** 单张图最多返回多少张相似图 */
export const SIMILAR_LIMIT = 24
/** 关系网最多连多少条边（前端画图用，避免边太多糊成一团） */
export const GRAPH_EDGE_LIMIT = 600
/** 模型空闲多久后释放（毫秒）；143MB 模型，15 分钟不用就松手 */
const IDLE_UNLOAD_MS = 15 * 60 * 1000

export interface EmbeddingEntry {
  /** 768 维**已 L2 归一化**的向量；归一化后余弦距离 = 1 - 点积 */
  vec: number[]
  /** 原图 sha256：文件被替换时能发现指纹该重算 */
  sha256: string
  updatedAt: string
}

/** 一条相似关系（按相似度降序存） */
export interface SimilarNeighbor {
  id: string
  /** 余弦相似度，1 = 完全一样 */
  score: number
}

export interface EmbedIndex {
  version: string
  size: number
  threshold: number
  updatedAt: string
  entries: Record<string, EmbeddingEntry>
  /** 每张图预算好的相似邻居；缺失表示还没算过 */
  neighbors: Record<string, SimilarNeighbor[]>
}

let ortModule: typeof import('onnxruntime-node') | null = null
let session: any = null
let lastUsed = 0
let idleTimer: NodeJS.Timeout | undefined

/** 内存中的索引 + 未落盘的脏标记（避免每张图都写一次文件） */
let cache: EmbedIndex | null = null
let dirty = false
let flushTimer: NodeJS.Timeout | undefined
const FLUSH_DEBOUNCE_MS = 1200

/* 模型目录由 models-dir.ts 统一解析（layout.ts 也要用，但不该连带拉起 onnxruntime） */
export function embedDir() { return path.join(modelsDir(), 'embed') }
function modelFile() { return path.join(embedDir(), 'ccip_feat.onnx') }
function indexFile() { return path.join(embedDir(), 'index.json') }

export function embedModelReady() {
  return existsSync(modelFile())
}

async function loadOrt() {
  if (!ortModule) ortModule = await import('onnxruntime-node')
  return ortModule
}

function scheduleIdleUnload() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (Date.now() - lastUsed < IDLE_UNLOAD_MS) { scheduleIdleUnload(); return }
    session = null
    idleTimer = undefined
  }, IDLE_UNLOAD_MS)
  idleTimer.unref?.()
}

async function getSession() {
  if (session) { lastUsed = Date.now(); return session }
  const file = modelFile()
  try { await access(file) } catch { throw new Error(`指纹模型缺失：${file}`) }
  const ort = await loadOrt()
  session = await ort.InferenceSession.create(file, { graphOptimizationLevel: 'all' })
  lastUsed = Date.now()
  scheduleIdleUnload()
  return session
}

/** 模型当前是否常驻内存（前端「识别中/待机」提示用） */
export function embedSessionActive() { return session !== null }

/**
 * 算一张图的指纹。返回**已归一化**的 768 维向量。
 * 传 Buffer 而不是路径：上传流里图还在内存中，落盘前就能算。
 */
export async function extractEmbedding(buffer: Buffer): Promise<number[]> {
  const raw = await sharp(buffer, { animated: false })
    .resize(INPUT_SIZE, INPUT_SIZE, { fit: 'fill', kernel: RESIZE_KERNEL })
    .removeAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer()

  const n = INPUT_SIZE * INPUT_SIZE
  const chw = new Float32Array(3 * n)
  for (let i = 0; i < n; i++) {
    for (let c = 0; c < 3; c++) {
      const v = raw[i * 3 + c] / 255
      chw[c * n + i] = (v - MEAN[c]) / STD[c]
    }
  }

  const ort = await loadOrt()
  const active = await getSession()
  const tensor = new ort.Tensor('float32', chw, [1, 3, INPUT_SIZE, INPUT_SIZE])
  const output = await active.run({ [active.inputNames[0]]: tensor })
  const out = output[active.outputNames[0]]
  const vec = Float32Array.from(out.data as Float32Array)

  // L2 归一化：之后余弦相似度就是点积，全库比较从 3 次乘加降到 1 次
  let norm = 0
  for (let i = 0; i < vec.length; i++) norm += vec[i] * vec[i]
  norm = Math.sqrt(norm) || 1
  const result = new Array<number>(vec.length)
  for (let i = 0; i < vec.length; i++) result[i] = vec[i] / norm
  return result
}

/** 两个已归一化向量的余弦相似度（就是点积） */
export function cosine(a: number[], b: number[]): number {
  const n = Math.min(a.length, b.length)
  let dot = 0
  for (let i = 0; i < n; i++) dot += a[i] * b[i]
  return dot
}

/* ---------------- 索引读写（原子写，避免读到半截 JSON） ---------------- */

function emptyIndex(): EmbedIndex {
  return {
    version: EMBED_VERSION,
    size: INPUT_SIZE,
    threshold: DEFAULT_SIMILARITY_THRESHOLD,
    updatedAt: new Date().toISOString(),
    entries: {},
    neighbors: {},
  }
}

/** 读取索引；内存有缓存就直接用。版本不一致时视为空库（旧指纹不能用） */
export async function loadIndex(): Promise<EmbedIndex> {
  if (cache) return cache
  try {
    const text = await readFile(indexFile(), 'utf8')
    const parsed = JSON.parse(text) as EmbedIndex
    if (parsed?.version === EMBED_VERSION && parsed.entries) {
      parsed.neighbors ||= {}
      parsed.threshold ||= DEFAULT_SIMILARITY_THRESHOLD
      cache = parsed
      return cache
    }
  } catch { /* 文件不存在或损坏 -> 从空库开始 */ }
  cache = emptyIndex()
  return cache
}

async function flushNow() {
  if (!cache || !dirty) return
  const dir = embedDir()
  await mkdir(dir, { recursive: true })
  cache.updatedAt = new Date().toISOString()
  const target = indexFile()
  const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
  await writeFile(tmp, JSON.stringify(cache), 'utf8')
  await rename(tmp, target)
  dirty = false
}

/** 标脏并延迟落盘：批量补算时不要每张图都写一遍 1MB 文件 */
function markDirty() {
  dirty = true
  if (flushTimer) clearTimeout(flushTimer)
  flushTimer = setTimeout(() => { flushTimer = undefined; void flushNow().catch(() => {}) }, FLUSH_DEBOUNCE_MS)
  flushTimer.unref?.()
}

/** 立即落盘（进程退出前、或接口需要确保持久化时调用） */
export async function flushIndex() {
  if (flushTimer) { clearTimeout(flushTimer); flushTimer = undefined }
  await flushNow()
}

/* ---------------- 增删指纹 ---------------- */

export async function hasEmbedding(imageId: string, sha256?: string): Promise<boolean> {
  const index = await loadIndex()
  const entry = index.entries[imageId]
  if (!entry) return false
  if (sha256 && entry.sha256 !== sha256) return false
  return true
}

/**
 * 写入一张图的指纹。已存在且 sha256 相同则跳过（增量：只算新图）。
 * 返回是否真的新算了。
 */
export async function putEmbedding(imageId: string, buffer: Buffer, sha256: string): Promise<boolean> {
  const index = await loadIndex()
  const existing = index.entries[imageId]
  if (existing && existing.sha256 === sha256) return false
  const vec = await extractEmbedding(buffer)
  index.entries[imageId] = { vec, sha256, updatedAt: new Date().toISOString() }
  // 自己的邻居变了，且别人指向自己的相似度也变了 -> 整体失效，按需重算
  index.neighbors = {}
  markDirty()
  return true
}

/** 删除一批图的指纹（图片永久删除时调用），返回是否真的删掉了东西 */
export async function dropEmbeddings(imageIds: string[]): Promise<number> {
  const index = await loadIndex()
  let removed = 0
  for (const id of imageIds) {
    if (index.entries[id]) { delete index.entries[id]; removed++ }
    if (index.neighbors[id]) delete index.neighbors[id]
  }
  if (removed) {
    index.neighbors = {}
    markDirty()
    await flushIndex()
  }
  return removed
}

/* ---------------- 相似检索 ---------------- */

/**
 * 算全库两两相似度，返回按分数降序、截断到 top N 的邻居表。
 * 128 张图约 8 千对，纯点积毫秒级；几万张也能接受（前端只在需要时才要）。
 */
export function computeNeighbors(index: EmbedIndex, threshold = index.threshold, limit = SIMILAR_LIMIT): Record<string, SimilarNeighbor[]> {
  const ids = Object.keys(index.entries)
  const result: Record<string, SimilarNeighbor[]> = {}
  for (const id of ids) result[id] = []
  for (let i = 0; i < ids.length; i++) {
    const a = index.entries[ids[i]].vec
    for (let j = i + 1; j < ids.length; j++) {
      const score = cosine(a, index.entries[ids[j]].vec)
      if (score < threshold) continue
      result[ids[i]].push({ id: ids[j], score })
      result[ids[j]].push({ id: ids[i], score })
    }
  }
  for (const id of ids) {
    result[id].sort((x, y) => y.score - x.score)
    if (result[id].length > limit) result[id] = result[id].slice(0, limit)
  }
  return result
}

/** 确保邻居表已算好（首次访问时算一次并缓存） */
export async function ensureNeighbors(): Promise<EmbedIndex> {
  const index = await loadIndex()
  const ids = Object.keys(index.entries)
  const missing = ids.some((id) => !index.neighbors[id])
  if (missing || Object.keys(index.neighbors).length !== ids.length) {
    index.neighbors = computeNeighbors(index)
    markDirty()
  }
  return index
}

/** 某张图的相似图（已排序，不含自己） */
export async function similarTo(imageId: string, limit = SIMILAR_LIMIT): Promise<SimilarNeighbor[]> {
  const index = await ensureNeighbors()
  return (index.neighbors[imageId] || []).slice(0, limit)
}

/** 两两相似度矩阵 + 强连通分组，给「关系网」视图用 */
export async function graphPayload(edgeLimit = GRAPH_EDGE_LIMIT) {
  const index = await ensureNeighbors()
  const ids = Object.keys(index.entries)
  const edges: Array<{ a: string; b: string; score: number }> = []
  const seen = new Set<string>()
  for (const id of ids) {
    for (const nb of index.neighbors[id] || []) {
      const key = id < nb.id ? `${id}|${nb.id}` : `${nb.id}|${id}`
      if (seen.has(key)) continue
      seen.add(key)
      edges.push({ a: id, b: nb.id, score: nb.score })
    }
  }
  edges.sort((x, y) => y.score - x.score)
  const trimmed = edges.slice(0, edgeLimit)

  // 并查集分组：共享边即同组，用于「按相似聚类展示」
  const parent = new Map<string, string>()
  const find = (x: string): string => {
    let root = x
    while (parent.get(root) !== undefined && parent.get(root) !== root) root = parent.get(root)!
    while (parent.get(x) !== undefined && parent.get(x) !== x) { const next = parent.get(x)!; parent.set(x, root); x = next }
    return root
  }
  for (const id of ids) parent.set(id, id)
  for (const e of trimmed) {
    const ra = find(e.a)
    const rb = find(e.b)
    if (ra !== rb) parent.set(ra, rb)
  }
  const groups = new Map<string, string[]>()
  for (const id of ids) {
    const root = find(id)
    if (!groups.has(root)) groups.set(root, [])
    groups.get(root)!.push(id)
  }
  const groupList = [...groups.values()]
    .map((members) => ({ members: members.sort(), size: members.length }))
    .filter((g) => g.size > 1)
    .sort((a, b) => b.size - a.size)

  /* 至少有一条相似边的图片数（= 所有 size>1 分组的人数）。剩下的就是「暂无相似图」的 */
  const linkedCount = groupList.reduce((sum, g) => sum + g.size, 0)

  return {
    version: index.version,
    threshold: index.threshold,
    updatedAt: index.updatedAt,
    /** 已建指纹的图片总数（含孤立的）。前端「已建指纹」应显示这个 */
    totalIndexed: ids.length,
    /** 其中真正连上了相似关系的张数 */
    linked: linkedCount,
    /** 没有任何相似图的张数 */
    isolated: ids.length - linkedCount,
    edges: trimmed,
    groups: groupList,
  }
}

/** 已建指纹的图片总数（不触发邻居计算，很便宜） */
export async function countIndexed(): Promise<number> {
  const index = await loadIndex()
  return Object.keys(index.entries).length
}

/** 索引概览（状态接口用） */
export async function embedStats() {
  /* 先确保邻居表算好再统计，否则「平均相似数」会显示 0（邻居是懒算的） */
  const index = await ensureNeighbors()
  const ids = Object.keys(index.entries)
  const neighborCount = ids.reduce((sum, id) => sum + (index.neighbors[id]?.length || 0), 0)
  return {
    modelReady: embedModelReady(),
    sessionActive: embedSessionActive(),
    version: index.version,
    indexed: ids.length,
    threshold: index.threshold,
    /** 平均每张图有多少相似图，用来判断阈值是不是太松/太紧 */
    avgNeighbors: ids.length ? Number((neighborCount / ids.length).toFixed(2)) : 0,
    updatedAt: index.updatedAt,
  }
}

/** 更新阈值（不同图库内容差异大，允许前端调） */
export async function setThreshold(value: number): Promise<number> {
  const index = await loadIndex()
  const clamped = Math.min(0.95, Math.max(0.05, value))
  if (clamped !== index.threshold) {
    index.threshold = clamped
    index.neighbors = {}
    markDirty()
    await flushIndex()
  }
  return index.threshold
}

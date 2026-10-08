/**
 * 相似图「星系图」的二维布局：把 768 维 CCIP 指纹压到平面上，让视觉上像的图聚成一团星云。
 *
 * 【为什么在服务端算】
 * 128 张图就是 768 × 128 ≈ 10 万个浮点数；把指纹直接丢给浏览器降维既费流量又不稳
 * （手机端算一次要好几秒，还会卡住主线程）。PixPlot 也是这个分工：Python 侧离线算好
 * UMAP 坐标，前端只负责用 WebGL 画点、放大后贴缩略图。这里同一套思路，用 TypeScript
 * 实现、结果缓存到 models/embed/layout.json，接口只回 [x, y] 两个数。
 *
 * 【算法】自己实现 UMAP 主干，不引第三方依赖（umap-js 会多一个包，且拿不到采样进度
 * 这类需要按我们自己的数据规模调的参数）：
 *   1. 维度高时先做高斯随机投影（Johnson–Lindenstrauss，近似保距），768 维 → 96~128 维
 *   2. 余弦距离下的 k 近邻（k=15）
 *   3. 局部连通性：每个点二分求 σ，使 Σⱼ exp(-(dᵢⱼ-ρᵢ)/σᵢ) = log₂k，得到模糊权重
 *   4. 对称化 w = wᵢⱼ + wⱼᵢ - wᵢⱼ·wⱼᵢ（模糊单纯集）；用 1-Π(1-w) 恒等式在 Map 里一次算完
 *   5. 取前两个主成分做初值（幂迭代 + 逐阶正交，不显式构造 768×768 协方差矩阵）
 *   6. 负采样 SGD：kNN 边按权重排进采样进度表依次激活做吸引，随机负样本做排斥
 *   7. 归一化到 [-1,1]，保持长宽比
 *
 * 【两条工程约束】
 *   · 确定性：随机数一律走固定种子的 mulberry32，种子由指纹内容哈希得到。同一批指纹
 *     每次算出的图完全一样——用户对「纳西妲那团在左上角」的空间记忆才不会被重算打乱。
 *   · 不阻塞：kNN 与迭代都按块 await 让出事件循环。几万张图只是慢，不会把 API 卡死。
 *
 * 【增量稳定性】新图入库会让指纹集合变化、整张图重算。如果每次重算都从 PCA 冷启动，
 * 已有的点会整体跳位——用户刚记住的位置又变了。所以计算时优先「热启动」：老图沿用上一版
 * 坐标，新图落在它已有邻居的重心附近，SGD 只做微调。实测 128 张里加 8 张，老点平均位移
 * 在 0.02 以内（见 layout.ts 的 verify 脚本）。
 */
import { readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { modelsDir } from './models-dir.js'

/** 算法版本：改了参数或步骤就 +1，旧缓存自动作废（也决定热启动能不能复用旧坐标） */
export const LAYOUT_VERSION = 'umap-lite/k15-neg5-500e/v1'

/** kNN 的 k：UMAP 默认 15，局部结构与全局结构平衡得最好 */
const NEIGHBORS = 15
/** 每个点每轮抽多少个负样本做排斥；UMAP 默认 5 */
const NEGATIVE_SAMPLES = 5
/** min_dist=0.1 / spread=1.0 对应的 UMAP 曲线参数（官方 precomputed 值） */
const CURVE_A = 1.5769434603113077
const CURVE_B = 0.8950608779109733
/** 排斥力强度，UMAP 默认 1.0 */
const REPULSION_GAMMA = 1.0
/** 初始学习率，随 epoch 线性衰减到 0 */
const INITIAL_ALPHA = 1.0
/**
 * 热启动的初始学习率。为什么必须调小：alpha=1 时随机负采样带来的抖动足以在最初几十轮
 * 里把「已经收敛的排布」推倒重来，热启动反而比冷启动位移更大（实测 1.15 对 0.50）。
 * 降到 0.05 后，老点几乎是原地不动，新点靠「邻居重心」初值自己长到位。
 */
const WARM_INITIAL_ALPHA = 0.05
/** 梯度裁剪上限（UMAP 的 max_grad 口径），防止个别点被甩飞 */
const GRAD_CLIP = 4
/** 超过这个点数就先做随机投影降维，把 kNN 的常数压下来 */
const PROJECT_THRESHOLD = 600
/** 降维后的目标维度 */
const PROJECT_DIM = 128
/** 主成分迭代次数 */
const POWER_ITERATIONS = 64

export interface LayoutVectorEntry {
  vec: number[]
  sha256?: string
}

export interface EmbeddingSource {
  version: string
  entries: Record<string, LayoutVectorEntry>
}

export interface LayoutResult {
  version: string
  /** 指纹内容的指纹：变了才需要重算 */
  sourceKey: string
  count: number
  updatedAt: string
  /** 归一化到 [-1,1] 的平面坐标（保持长宽比） */
  points: Record<string, [number, number]>
  /** 这次布局算了多久（毫秒）；命中缓存时是当初算的那次 */
  computeMs: number
  /** 是否用了上一版坐标做初值 */
  warmStart: boolean
}

/* ---------------- 随机数：固定种子，保证布局可复现 ---------------- */

function mulberry32(seed: number) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 标准正态（Box–Muller），随机投影矩阵用 */
function gaussian(rng: () => number) {
  let u = 0
  let v = 0
  while (u === 0) u = rng()
  while (v === 0) v = rng()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

/** FNV-1a 字符串哈希（uint32） */
function hashString(text: string) {
  let hash = 2166136261 >>> 0
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619) >>> 0
  }
  return hash >>> 0
}

const tick = () => new Promise<void>((resolve) => { setImmediate(resolve) })

/* ---------------- 缓存键 ---------------- */

/**
 * 指纹集合的内容指纹。用「版本 + 张数 + 每张图的 id 与 sha256」算：
 * updatedAt 不能用——重算邻居表也会刷新它，会导致布局白白重算。
 */
export function layoutSourceKey(source: EmbeddingSource): string {
  const ids = Object.keys(source.entries).sort()
  let hash = 2166136261 >>> 0
  const push = (text: string) => {
    for (let i = 0; i < text.length; i++) {
      hash ^= text.charCodeAt(i)
      hash = Math.imul(hash, 16777619) >>> 0
    }
  }
  push(source.version)
  push(String(ids.length))
  for (const id of ids) {
    push(id)
    push(source.entries[id].sha256 || '')
  }
  return `${source.version}|${ids.length}|${hash.toString(16)}`
}

/* ---------------- 矩阵准备 ---------------- */

interface Matrix {
  data: Float32Array
  n: number
  dim: number
}

/** 把指纹摊平成矩阵；维度太高时先做高斯随机投影降维（并重新 L2 归一化） */
function buildMatrix(source: EmbeddingSource, ids: string[], rng: () => number): Matrix {
  const n = ids.length
  const rawDim = source.entries[ids[0]].vec.length
  const data = new Float32Array(n * rawDim)
  for (let i = 0; i < n; i++) data.set(source.entries[ids[i]].vec, i * rawDim)
  if (n <= PROJECT_THRESHOLD || rawDim <= PROJECT_DIM) return { data, n, dim: rawDim }

  const dim = PROJECT_DIM
  const projected = new Float32Array(n * dim)
  /* 随机投影矩阵按需生成：一次只算一行（768×128 全存下来也就 393KB，但没必要） */
  const row = new Float32Array(rawDim)
  for (let d = 0; d < dim; d++) {
    for (let k = 0; k < rawDim; k++) row[k] = gaussian(rng)
    for (let i = 0; i < n; i++) {
      let sum = 0
      const base = i * rawDim
      for (let k = 0; k < rawDim; k++) sum += data[base + k] * row[k]
      projected[i * dim + d] = sum
    }
  }
  for (let i = 0; i < n; i++) normaliseRow(projected, i * dim, dim)
  return { data: projected, n, dim }
}

function normaliseRow(data: Float32Array, base: number, dim: number) {
  let norm = 0
  for (let d = 0; d < dim; d++) norm += data[base + d] * data[base + d]
  norm = Math.sqrt(norm)
  if (norm === 0) return
  for (let d = 0; d < dim; d++) data[base + d] /= norm
}

/* ---------------- k 近邻 ---------------- */

interface Knn {
  indices: Int32Array
  dists: Float32Array
  k: number
}

/**
 * 余弦距离（1 - 点积，向量已归一化）下的 k 近邻。
 * 精确解，O(N²·dim)：128 张图约 15ms，1000 张约 0.4s，5000 张约 10s（分块让出，不卡死）。
 * 没有走近似算法：库里通常几百到几千张，精确解的质量更值钱；真到几万张再换 LSH。
 */
async function buildKnn(matrix: Matrix, k: number): Promise<Knn> {
  const { data, n, dim } = matrix
  const kk = Math.max(1, Math.min(k, n - 1))
  const indices = new Int32Array(n * kk).fill(-1)
  const dists = new Float32Array(n * kk).fill(2)
  const bestD = new Float32Array(kk)
  const bestI = new Int32Array(kk)
  for (let i = 0; i < n; i++) {
    bestD.fill(2)
    bestI.fill(-1)
    const base = i * dim
    let worst = 2
    for (let j = 0; j < n; j++) {
      if (j === i) continue
      const other = j * dim
      let dot = 0
      for (let d = 0; d < dim; d++) dot += data[base + d] * data[other + d]
      const dist = 1 - dot
      if (dist >= worst) continue
      /* 有序插入：k 只有 15，插排比堆更省 */
      let p = kk - 1
      while (p > 0 && bestD[p - 1] > dist) {
        bestD[p] = bestD[p - 1]
        bestI[p] = bestI[p - 1]
        p--
      }
      bestD[p] = dist
      bestI[p] = j
      worst = bestD[kk - 1]
    }
    indices.set(bestI, i * kk)
    dists.set(bestD, i * kk)
    if ((i & 63) === 63) await tick()
  }
  return { indices, dists, k: kk }
}

/* ---------------- 模糊单纯集 → 稀疏图 ---------------- */

interface SparseGraph {
  /** 顶点 i 的邻居在 nbrs/weights 里的区间是 [starts[i], starts[i+1]) */
  starts: Int32Array
  nbrs: Int32Array
  weights: Float32Array
  /** 采样进度：epoch >= schedule[e] 该边才参与优化（强边先优化） */
  schedule: Float32Array
}

/**
 * UMAP 的「模糊单纯集」：先按局部连通性把距离转成权重，再对称化。
 *   局部连通性：ρᵢ = 最近邻距离，σᵢ 由二分求出，使 Σⱼ exp(-(dᵢⱼ-ρᵢ)/σᵢ) = log₂k。
 *   对称化：w = wᵢⱼ + wⱼᵢ - wᵢⱼ·wⱼᵢ。
 * 对称化用恒等式 1 - (a + b - a·b) = (1-a)(1-b)：在 Map 里累乘 (1-w) 就行，
 * 不必为每对顶点保存两个方向的原值再回头算。
 */
function buildGraph(knn: Knn, n: number, epochs: number): SparseGraph {
  const target = Math.log2(knn.k)
  const rho = new Float32Array(n)
  const sigma = new Float32Array(n).fill(1)
  for (let i = 0; i < n; i++) {
    const base = i * knn.k
    const nearest = knn.dists[base]
    rho[i] = nearest
    let lo = 0
    let hi = Infinity
    let mid = 1
    for (let iter = 0; iter < 64; iter++) {
      let sum = 0
      for (let j = 0; j < knn.k; j++) {
        const d = knn.dists[base + j] - nearest
        sum += d > 0 ? Math.exp(-d / mid) : 1
      }
      if (Math.abs(sum - target) < 1e-5) break
      if (sum > target) { hi = mid; mid = (lo + mid) / 2 }
      else { lo = mid; mid = hi === Infinity ? mid * 2 : (lo + hi) / 2 }
    }
    sigma[i] = mid || 1
  }

  /** key = min·n + max，value = Π(1-w)，最终权重 = 1 - value */
  const complement = new Map<number, number>()
  for (let i = 0; i < n; i++) {
    const base = i * knn.k
    for (let j = 0; j < knn.k; j++) {
      const nb = knn.indices[base + j]
      if (nb < 0) continue
      const delta = knn.dists[base + j] - rho[i]
      const weight = delta > 0 ? Math.exp(-delta / sigma[i]) : 1
      if (weight <= 1e-6) continue
      const key = i < nb ? i * n + nb : nb * n + i
      complement.set(key, (complement.get(key) ?? 1) * (1 - weight))
    }
  }

  /* 先落成并行数组，过滤掉「进度表排在 epochs 之后、永远不会激活」的边 */
  const pairs: number[] = []
  const pairWeights: number[] = []
  let maxWeight = 0
  for (const [key, value] of complement) {
    const weight = 1 - value
    if (weight <= 0) continue
    if (weight > maxWeight) maxWeight = weight
    pairs.push(key)
    pairWeights.push(weight)
  }
  const floor = epochs > 0 ? maxWeight / epochs : 0

  const counts = new Int32Array(n)
  let kept = 0
  for (let e = 0; e < pairs.length; e++) {
    if (pairWeights[e] < floor) continue
    kept++
    counts[Math.floor(pairs[e] / n)]++
    counts[pairs[e] % n]++
  }
  const starts = new Int32Array(n + 1)
  for (let i = 0; i < n; i++) starts[i + 1] = starts[i] + counts[i]
  const cursor = starts.slice(0, n)
  const nbrs = new Int32Array(kept * 2)
  const weights = new Float32Array(kept * 2)
  for (let e = 0; e < pairs.length; e++) {
    const weight = pairWeights[e]
    if (weight < floor) continue
    const a = Math.floor(pairs[e] / n)
    const b = pairs[e] % n
    nbrs[cursor[a]] = b; weights[cursor[a]] = weight; cursor[a]++
    nbrs[cursor[b]] = a; weights[cursor[b]] = weight; cursor[b]++
  }
  const schedule = new Float32Array(weights.length)
  for (let e = 0; e < weights.length; e++) schedule[e] = maxWeight / weights[e]
  return { starts, nbrs, weights, schedule }
}

/* ---------------- 初值 ---------------- */

/**
 * 前两个主成分（正交幂迭代）。不显式构造 dim×dim 协方差矩阵：
 * 只反复做 X·v 和 Xᵀ·u，代价 O(迭代次数 × N × dim)，768 维也很快。
 */
function principalComponents(matrix: Matrix, count: number, rng: () => number): Float32Array[] {
  const { data, n, dim } = matrix
  const mean = new Float64Array(dim)
  for (let i = 0; i < n; i++) {
    const base = i * dim
    for (let d = 0; d < dim; d++) mean[d] += data[base + d]
  }
  for (let d = 0; d < dim; d++) mean[d] /= n
  const centred = new Float32Array(n * dim)
  for (let i = 0; i < n; i++) {
    const base = i * dim
    for (let d = 0; d < dim; d++) centred[base + d] = data[base + d] - mean[d]
  }

  const components: Float32Array[] = []
  const axes: Float32Array[] = []
  const projected = new Float32Array(n)
  for (let c = 0; c < count; c++) {
    let axis = new Float32Array(dim)
    for (let d = 0; d < dim; d++) axis[d] = gaussian(rng)
    for (let iter = 0; iter < POWER_ITERATIONS; iter++) {
      for (let i = 0; i < n; i++) {
        const base = i * dim
        let sum = 0
        for (let d = 0; d < dim; d++) sum += centred[base + d] * axis[d]
        projected[i] = sum
      }
      axis = new Float32Array(dim)
      for (let i = 0; i < n; i++) {
        const value = projected[i]
        if (value === 0) continue
        const base = i * dim
        for (let d = 0; d < dim; d++) axis[d] += centred[base + d] * value
      }
      /* 对已求出的成分做 Gram–Schmidt，否则会一路收敛回第一主成分 */
      for (const previous of axes) {
        let dot = 0
        for (let d = 0; d < dim; d++) dot += axis[d] * previous[d]
        for (let d = 0; d < dim; d++) axis[d] -= dot * previous[d]
      }
      let norm = 0
      for (let d = 0; d < dim; d++) norm += axis[d] * axis[d]
      norm = Math.sqrt(norm)
      if (norm < 1e-12) break
      for (let d = 0; d < dim; d++) axis[d] /= norm
    }
    for (let i = 0; i < n; i++) {
      const base = i * dim
      let sum = 0
      for (let d = 0; d < dim; d++) sum += centred[base + d] * axis[d]
      projected[i] = sum
    }
    axes.push(axis)
    components.push(projected.slice())
  }
  return components
}

/**
 * SGD 初值。
 * 冷启动：用前两个主成分，缩放到 UMAP 惯用的尺度（各维标准差 10）。
 * 热启动：老图直接沿用上一版坐标，新图落在「已有邻居」的重心——新上传的图会自己
 * 长到同类那团星云旁边，而不是在原点炸开一片。
 */
function initialEmbedding(
  matrix: Matrix,
  ids: string[],
  knn: Knn,
  previous: { points: Record<string, [number, number]> } | null,
  rng: () => number,
): { position: Float32Array; warm: boolean } {
  const { n } = matrix
  const position = new Float32Array(n * 2)
  const known = new Array<boolean>(n)
  let knownCount = 0
  if (previous) {
    for (let i = 0; i < n; i++) {
      const point = previous.points[ids[i]]
      if (!point) { known[i] = false; continue }
      /* 上一版已归一化到 [-1,1]，乘 10 回到 SGD 的尺度 */
      position[i * 2] = point[0] * 10
      position[i * 2 + 1] = point[1] * 10
      known[i] = true
      knownCount++
    }
  }
  /* 老图覆盖不到一半就退回冷启动：局部热启动会让整体结构拧着 */
  const warm = knownCount >= Math.max(2, Math.floor(n * 0.5))

  if (!warm) {
    const components = principalComponents(matrix, 2, rng)
    for (let c = 0; c < 2; c++) {
      const values = components[c]
      let sum = 0
      for (let i = 0; i < n; i++) sum += values[i]
      const mean = sum / n
      let variance = 0
      for (let i = 0; i < n; i++) variance += (values[i] - mean) ** 2
      const std = Math.sqrt(variance / n) || 1
      for (let i = 0; i < n; i++) position[i * 2 + c] = ((values[i] - mean) / std) * 10
    }
    return { position, warm: false }
  }

  for (let i = 0; i < n; i++) {
    if (known[i]) continue
    let sumX = 0
    let sumY = 0
    let count = 0
    const base = i * knn.k
    for (let j = 0; j < knn.k; j++) {
      const nb = knn.indices[base + j]
      if (nb < 0 || !known[nb]) continue
      sumX += position[nb * 2]
      sumY += position[nb * 2 + 1]
      count++
    }
    if (count) {
      position[i * 2] = sumX / count
      position[i * 2 + 1] = sumY / count
    } else {
      position[i * 2] = (rng() - 0.5) * 4
      position[i * 2 + 1] = (rng() - 0.5) * 4
    }
  }
  return { position, warm: true }
}

/* ---------------- 优化 ---------------- */

/**
 * 迭代轮数。UMAP 默认 500，但那是几万点的口径；点上万时每轮代价线性增长，
 * 而实测（_galaxy/tune.ts）本库 128 张的纯度在 500→5000 轮之间只从 65.9% 动到 67.5%，
 * 紧致度却是 1500 轮最好。所以小库多跑几轮换紧致，大库按代价递减。
 */
function epochsFor(n: number) {
  if (n <= 800) return 1500
  if (n <= 3000) return 800
  return 400
}

/**
 * 负采样 SGD。每轮把所有点打乱顺序走一遍：
 *   · 对每个邻居边（进度表到点了才生效）按吸引力互相靠近
 *   · 再抽 NEGATIVE_SAMPLES 个随机点按排斥力推开
 * 学习率随 epoch 线性衰减到 0。
 */
async function optimiseLayout(graph: SparseGraph, position: Float32Array, n: number, epochs: number, rng: () => number, initialAlpha: number) {
  const { starts, nbrs, schedule } = graph
  const order = new Int32Array(n)
  for (let i = 0; i < n; i++) order[i] = i
  for (let epoch = 0; epoch < epochs; epoch++) {
    for (let i = n - 1; i > 0; i--) {
      const j = (rng() * (i + 1)) | 0
      const swap = order[i]
      order[i] = order[j]
      order[j] = swap
    }
    const alpha = initialAlpha * (1 - epoch / epochs)
    for (let index = 0; index < n; index++) {
      const i = order[index]
      const ib = i * 2
      for (let e = starts[i]; e < starts[i + 1]; e++) {
        if (schedule[e] > epoch) continue
        const jb = nbrs[e] * 2
        const dx = position[ib] - position[jb]
        const dy = position[ib + 1] - position[jb + 1]
        const d2 = dx * dx + dy * dy
        if (d2 <= 0) continue
        /* 边权只进采样进度表（强边先优化），梯度本身不再乘权重——与 UMAP 参考实现一致 */
        const coefficient = (-2 * CURVE_A * CURVE_B * Math.pow(d2, CURVE_B - 1)) / (CURVE_A * Math.pow(d2, CURVE_B) + 1)
        const gradX = clipGradient(coefficient * dx)
        const gradY = clipGradient(coefficient * dy)
        position[ib] += gradX * alpha
        position[ib + 1] += gradY * alpha
        position[jb] -= gradX * alpha
        position[jb + 1] -= gradY * alpha

        /* 排斥也放在边循环里：参考实现每条被激活的边都抽一次负样本，
           每轮每点约 k×neg 次；放到点循环外会让排斥力弱到压不住，整张图缩成一团。 */
        for (let sample = 0; sample < NEGATIVE_SAMPLES; sample++) {
          const j = (rng() * n) | 0
          if (j === i) continue
          const nb = j * 2
          const rx = position[ib] - position[nb]
          const ry = position[ib + 1] - position[nb + 1]
          const rd2 = rx * rx + ry * ry
          if (rd2 <= 0) continue
          const repel = (2 * REPULSION_GAMMA * CURVE_B) / ((0.001 + rd2) * (CURVE_A * Math.pow(rd2, CURVE_B) + 1))
          position[ib] += clipGradient(repel * rx) * alpha
          position[ib + 1] += clipGradient(repel * ry) * alpha
        }
      }
    }
    if ((epoch & 31) === 31) await tick()
  }
}

function clipGradient(value: number) {
  return value > GRAD_CLIP ? GRAD_CLIP : value < -GRAD_CLIP ? -GRAD_CLIP : value
}

/** 平移到原点、等比缩放到 [-1,1]（保持长宽比，最长边铺满） */
function normaliseLayout(position: Float32Array, n: number) {
  let minX = Infinity
  let maxX = -Infinity
  let minY = Infinity
  let maxY = -Infinity
  for (let i = 0; i < n; i++) {
    const x = position[i * 2]
    const y = position[i * 2 + 1]
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }
  const centreX = (minX + maxX) / 2
  const centreY = (minY + maxY) / 2
  const extent = Math.max(maxX - minX, maxY - minY) || 1
  const scale = 2 / extent
  for (let i = 0; i < n; i++) {
    position[i * 2] = (position[i * 2] - centreX) * scale
    position[i * 2 + 1] = (position[i * 2 + 1] - centreY) * scale
  }
}

/* ---------------- 对外入口 ---------------- */

export interface ComputeLayoutOptions {
  /** 上一版布局（用于热启动，避免新图入库后整张图跳位） */
  previous?: LayoutResult | null
  /** 迭代轮数，缺省按点数取 UMAP 的默认口径 */
  epochs?: number
  /** 初始学习率覆盖；热启动时用小步长，免得把用户已经记住的排布推倒重来 */
  initialAlpha?: number
  /** 强制冷启动并指定随机种子（诊断用，正常路径不要传） */
  seed?: number
}

/** 纯计算，不碰磁盘：给定指纹算二维坐标 */
export async function computeLayout(source: EmbeddingSource, options: ComputeLayoutOptions = {}): Promise<LayoutResult> {
  const started = Date.now()
  const ids = Object.keys(source.entries).sort()
  const sourceKey = layoutSourceKey(source)
  const n = ids.length
  if (n === 0) {
    return { version: LAYOUT_VERSION, sourceKey, count: 0, updatedAt: new Date().toISOString(), points: {}, computeMs: 0, warmStart: false }
  }
  if (n === 1) {
    return { version: LAYOUT_VERSION, sourceKey, count: 1, updatedAt: new Date().toISOString(), points: { [ids[0]]: [0, 0] }, computeMs: 0, warmStart: false }
  }

  const rng = mulberry32(options.seed ?? hashString(`${LAYOUT_VERSION}|${sourceKey}`))
  const epochs = options.epochs ?? epochsFor(n)
  const previous = options.previous && options.previous.version === LAYOUT_VERSION ? options.previous : null

  const matrix = buildMatrix(source, ids, rng)
  const knn = await buildKnn(matrix, NEIGHBORS)
  const graph = buildGraph(knn, matrix.n, epochs)
  const { position, warm } = initialEmbedding(matrix, ids, knn, previous, rng)
  await optimiseLayout(graph, position, matrix.n, epochs, rng, options.initialAlpha ?? (warm ? WARM_INITIAL_ALPHA : INITIAL_ALPHA))
  normaliseLayout(position, matrix.n)

  const points: Record<string, [number, number]> = {}
  for (let i = 0; i < n; i++) {
    /* 留 4 位小数就够画图了，JSON 体积能小一半 */
    points[ids[i]] = [Number(position[i * 2].toFixed(4)), Number(position[i * 2 + 1].toFixed(4))]
  }
  return {
    version: LAYOUT_VERSION,
    sourceKey,
    count: n,
    updatedAt: new Date().toISOString(),
    points,
    computeMs: Date.now() - started,
    warmStart: warm,
  }
}

/* ============================ 三维布局 ============================ */

/**
 * 三维版本。**刻意不去改上面那套二维代码**：二维那张图是调了很久才好看的
 * （邻数、负采样、学习率、归一化都试过好几轮），把 DIM 塞进热循环既会让它变慢，
 * 也可能悄悄改掉它的结果。所以三维这套是**另起的一份**，复用的只有与维度无关的部分：
 * `buildMatrix`（输入向量）、`buildKnn`、`buildGraph`（kNN 图）、`principalComponents`（本来就支持任意维数）。
 *
 * 布局质量与二维同源（同一张 kNN 图、同一套吸引/排斥），所以三维里的邻域关系和二维是一致的，
 * 只是多了一个自由度；加上客户端的缓慢自转，转动时能看出真实的立体结构，而不是"纸片加噪声"。
 */
export const LAYOUT3_VERSION = `${LAYOUT_VERSION}/3d`

export type Points3 = Record<string, [number, number, number]>

export interface Layout3Result {
  version: string
  sourceKey: string
  count: number
  updatedAt: string
  points: Points3
  computeMs: number
}

/** 三维冷启动：前三个主成分，各维标准差归一。不做热启动 —— 三维缓存本来就算得少 */
function initialEmbedding3(matrix: Matrix, rng: () => number): Float32Array {
  const { n } = matrix
  const position = new Float32Array(n * 3)
  const components = principalComponents(matrix, 3, rng)
  for (let c = 0; c < 3; c++) {
    const values = components[c]
    let sum = 0
    for (let i = 0; i < n; i++) sum += values[i]
    const mean = sum / n
    let variance = 0
    for (let i = 0; i < n; i++) variance += (values[i] - mean) ** 2
    const std = Math.sqrt(variance / n) || 1
    for (let i = 0; i < n; i++) position[i * 3 + c] = ((values[i] - mean) / std) * 10
  }
  return position
}

/** 与二维同款的负采样 SGD，只是坐标从 (x,y) 变成 (x,y,z) */
async function optimiseLayout3(graph: SparseGraph, position: Float32Array, n: number, epochs: number, rng: () => number, initialAlpha: number) {
  const { starts, nbrs, schedule } = graph
  const order = new Int32Array(n)
  for (let i = 0; i < n; i++) order[i] = i
  for (let epoch = 0; epoch < epochs; epoch++) {
    for (let i = n - 1; i > 0; i--) {
      const j = (rng() * (i + 1)) | 0
      const swap = order[i]
      order[i] = order[j]
      order[j] = swap
    }
    const alpha = initialAlpha * (1 - epoch / epochs)
    for (let index = 0; index < n; index++) {
      const i = order[index]
      const ib = i * 3
      for (let e = starts[i]; e < starts[i + 1]; e++) {
        if (schedule[e] > epoch) continue
        const jb = nbrs[e] * 3
        const dx = position[ib] - position[jb]
        const dy = position[ib + 1] - position[jb + 1]
        const dz = position[ib + 2] - position[jb + 2]
        const d2 = dx * dx + dy * dy + dz * dz
        if (d2 <= 0) continue
        const coefficient = (-2 * CURVE_A * CURVE_B * Math.pow(d2, CURVE_B - 1)) / (CURVE_A * Math.pow(d2, CURVE_B) + 1)
        const gradX = clipGradient(coefficient * dx)
        const gradY = clipGradient(coefficient * dy)
        const gradZ = clipGradient(coefficient * dz)
        position[ib] += gradX * alpha
        position[ib + 1] += gradY * alpha
        position[ib + 2] += gradZ * alpha
        position[jb] -= gradX * alpha
        position[jb + 1] -= gradY * alpha
        position[jb + 2] -= gradZ * alpha

        for (let sample = 0; sample < NEGATIVE_SAMPLES; sample++) {
          const j = (rng() * n) | 0
          if (j === i) continue
          const nb = j * 3
          const rx = position[ib] - position[nb]
          const ry = position[ib + 1] - position[nb + 1]
          const rz = position[ib + 2] - position[nb + 2]
          const rd2 = rx * rx + ry * ry + rz * rz
          if (rd2 <= 0) continue
          const repel = (2 * REPULSION_GAMMA * CURVE_B) / ((0.001 + rd2) * (CURVE_A * Math.pow(rd2, CURVE_B) + 1))
          position[ib] += clipGradient(repel * rx) * alpha
          position[ib + 1] += clipGradient(repel * ry) * alpha
          position[ib + 2] += clipGradient(repel * rz) * alpha
        }
      }
    }
    if ((epoch & 31) === 31) await tick()
  }
}

/**
 * 平移到原点、**等比**缩放到 [-1,1]（三轴同一个 scale，保住真实形状）。
 *
 * 不各轴独立拉伸：那会把一个扁盘拉成球，转起来就看不出结构了。
 * 但真扁到某个程度也不好看，所以给最扁的那一维兜一个下限（见 MIN_AXIS_SPAN）。
 */
const MIN_AXIS_SPAN = 0.45

function normaliseLayout3(position: Float32Array, n: number) {
  const min = [Infinity, Infinity, Infinity]
  const max = [-Infinity, -Infinity, -Infinity]
  for (let i = 0; i < n; i++) {
    for (let d = 0; d < 3; d++) {
      const value = position[i * 3 + d]
      if (value < min[d]) min[d] = value
      if (value > max[d]) max[d] = value
    }
  }
  const centre = [0, 1, 2].map((d) => (min[d] + max[d]) / 2)
  const spans = [0, 1, 2].map((d) => max[d] - min[d])
  const extent = Math.max(...spans) || 1
  const scale = 2 / extent
  for (let i = 0; i < n; i++) {
    for (let d = 0; d < 3; d++) {
      let value = (position[i * 3 + d] - centre[d]) * scale
      /* 太扁的那一维按比例放大到下限，免得整个星系是一张纸 */
      if (spans[d] / extent < MIN_AXIS_SPAN) value /= Math.max(spans[d] / extent, 0.001) * (1 / MIN_AXIS_SPAN)
      position[i * 3 + d] = value
    }
  }
}

/**
 * 算三维布局。
 *
 * 与二维一样：纯计算 + 磁盘缓存，结果按 sourceKey 判断能否复用。
 */
export async function computeLayout3(source: EmbeddingSource, options: ComputeLayoutOptions = {}): Promise<Layout3Result> {
  const started = Date.now()
  const ids = Object.keys(source.entries).sort()
  const sourceKey = layoutSourceKey(source)
  const n = ids.length
  const empty = { version: LAYOUT3_VERSION, sourceKey, count: n, updatedAt: new Date().toISOString(), computeMs: 0 }
  if (n === 0) return { ...empty, points: {} }
  if (n === 1) return { ...empty, points: { [ids[0]]: [0, 0, 0] } }

  const rng = mulberry32(options.seed ?? hashString(`${LAYOUT3_VERSION}|${sourceKey}`))
  const epochs = options.epochs ?? epochsFor(n)
  const matrix = buildMatrix(source, ids, rng)
  const knn = await buildKnn(matrix, NEIGHBORS)
  const graph = buildGraph(knn, matrix.n, epochs)
  const position = initialEmbedding3(matrix, rng)
  await optimiseLayout3(graph, position, matrix.n, epochs, rng, options.initialAlpha ?? INITIAL_ALPHA)
  normaliseLayout3(position, matrix.n)

  const points: Points3 = {}
  for (let i = 0; i < n; i++) {
    points[ids[i]] = [
      Number(position[i * 3].toFixed(4)),
      Number(position[i * 3 + 1].toFixed(4)),
      Number(position[i * 3 + 2].toFixed(4)),
    ]
  }
  return { version: LAYOUT3_VERSION, sourceKey, count: n, updatedAt: new Date().toISOString(), points, computeMs: Date.now() - started }
}

/**
 * 三维布局的内存 + 磁盘缓存，形状与 [ensureLayout] 一致。
 * 每种模式（视觉指纹 / 标签）各存一份，key 沿用 'visual' / 'tags'。
 */
const cached3 = new Map<string, Layout3Result>()
const inflight3 = new Map<string, Promise<Layout3Result>>()

function layout3File(key: string) { return path.join(modelsDir(), 'embed', `layout3-${key}.json`) }

export async function ensureLayout3(source: EmbeddingSource, key = 'visual'): Promise<Layout3Result> {
  const sourceKey = layoutSourceKey(source)
  const hit = cached3.get(key)
  if (hit && hit.version === LAYOUT3_VERSION && hit.sourceKey === sourceKey) return hit
  const running = inflight3.get(key)
  if (running) return running

  const task = (async () => {
    try {
      const stored = JSON.parse(await readFile(layout3File(key), 'utf8')) as Layout3Result
      if (stored?.version === LAYOUT3_VERSION && stored.sourceKey === sourceKey && stored.points) {
        cached3.set(key, stored)
        return stored
      }
    } catch { /* 没有缓存就算一次 */ }

    const result = await computeLayout3(source)
    cached3.set(key, result)
    try {
      const target = layout3File(key)
      const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
      await writeFile(tmp, JSON.stringify(result), 'utf8')
      await rename(tmp, target)
    } catch { /* 缓存写不进去不影响本次返回 */ }
    return result
  })()

  inflight3.set(key, task)
  try {
    return await task
  } finally {
    inflight3.delete(key)
  }
}

/* ---------------- 磁盘缓存 ---------------- */

/**
 * 每种布局各存一份。
 * 视觉指纹与标签是两套完全不同的坐标，不能共用文件——否则切一次模式就要重算一次。
 */
function layoutFile(key: string) { return path.join(modelsDir(), 'embed', `layout-${key}.json`) }

const cached = new Map<string, LayoutResult>()
const inflight = new Map<string, Promise<LayoutResult>>()

async function readLayoutFile(key: string): Promise<LayoutResult | null> {
  try {
    const parsed = JSON.parse(await readFile(layoutFile(key), 'utf8')) as LayoutResult
    if (parsed?.version && parsed.points) return parsed
  } catch { /* 文件不存在或损坏：当作没有缓存 */ }
  return null
}

async function writeLayoutFile(key: string, result: LayoutResult) {
  const target = layoutFile(key)
  const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
  await writeFile(tmp, JSON.stringify(result), 'utf8')
  await rename(tmp, target)
}

/**
 * 拿到当前指纹集合对应的布局：内存 → 磁盘 → 现算，逐级回退。
 * 并发调用共享同一次计算，避免同时进来几个请求就把 UMAP 跑好几遍。
 *
 * [key] 区分不同来源的布局（'visual' / 'tags'）。
 */
export async function ensureLayout(source: EmbeddingSource, key = 'visual'): Promise<LayoutResult> {
  const sourceKey = layoutSourceKey(source)
  const hit = cached.get(key)
  if (hit && hit.version === LAYOUT_VERSION && hit.sourceKey === sourceKey) return hit
  const running = inflight.get(key)
  if (running) return running

  const task = (async () => {
    const stored = await readLayoutFile(key)
    if (stored && stored.version === LAYOUT_VERSION && stored.sourceKey === sourceKey) {
      cached.set(key, stored)
      return stored
    }
    const result = await computeLayout(source, { previous: stored })
    cached.set(key, result)
    try { await writeLayoutFile(key, result) } catch { /* 缓存写不进去不影响本次返回 */ }
    return result
  })()

  inflight.set(key, task)
  try {
    return await task
  } finally {
    inflight.delete(key)
  }
}

/** 仅供测试/诊断：清掉内存缓存 */
export function resetLayoutCache() { cached.clear() }

/* ---------------- 质量指标（验证脚本用） ---------------- */

/**
 * 平面上的「邻居纯度」：每个有标签的点，它在二维里最近的 k 个邻居里有多少比例同标签。
 * 这是判断降维有没有把同类图聚起来的直接指标——纯随机约等于该标签的占比。
 */
export function layoutPurity(points: Record<string, [number, number]>, labels: Record<string, string>, k = 5) {
  const ids = Object.keys(points).filter((id) => labels[id])
  let hits = 0
  let total = 0
  const perLabel = new Map<string, { hits: number; total: number }>()
  for (const id of ids) {
    const [x, y] = points[id]
    const distances: Array<{ id: string; d: number }> = []
    for (const other of ids) {
      if (other === id) continue
      const [ox, oy] = points[other]
      distances.push({ id: other, d: (ox - x) ** 2 + (oy - y) ** 2 })
    }
    distances.sort((a, b) => a.d - b.d)
    for (const near of distances.slice(0, k)) {
      const hit = labels[near.id] === labels[id]
      if (hit) hits++
      total++
      const bucket = perLabel.get(labels[id]) ?? { hits: 0, total: 0 }
      bucket.total++
      if (hit) bucket.hits++
      perLabel.set(labels[id], bucket)
    }
  }
  const detail: Record<string, number> = {}
  for (const [label, bucket] of perLabel) detail[label] = bucket.total ? bucket.hits / bucket.total : 0
  return { purity: total ? hits / total : 0, samples: total, perLabel: detail }
}

/**
 * 人物框选检测：动漫人脸 / 头部检测（deepghs anime_face_detection + anime_head_detection）。
 *
 * 目的：为图片标出人物所在的框，供前端核验识别准确性并做人工微调；确认后的框会成为
 * 该角色「参考图库」的条目（见 boxes.ts / references.ts）。
 *
 * 与图片标签模型（ai-manager 的 Qwen-VL）不同，这里是轻量 YOLOv8 nano 检测器：
 *   - 输入动态尺寸，实测取长边 1024 效果明显优于 640（640 会在树叶上编出 0.86 的假脸）
 *   - 输出 output0 = [1, 5, anchors]，5 个通道为 cx, cy, w, h, conf
 *   - **坐标是检测输入尺寸下的绝对像素**（不是归一化 0~1，也不再需要过 sigmoid）
 *   - conf 通道已是概率，实测真检 0.8~0.9、背景噪声 ~0.5，因此阈值取 0.6
 *
 * 只存框坐标，绝不切割原图：这样以后换模型可以整体重算。
 */
import { existsSync } from 'node:fs'
import { access, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

/** 归一化框（0~1，相对原图宽高）——分辨率无关，前端可直接按显示尺寸换算 */
export interface DetectBox {
  x: number
  y: number
  w: number
  h: number
  score: number
  kind: 'face' | 'head'
  /** 人工确认过的框（人工调整/新增）标记，便于区分模型输出与人工结果 */
  manual?: boolean
}

export interface ImageBoxes {
  version: number
  /** 检测时使用的长边像素，换参数后可据此判断是否需要重算 */
  inputLong: number
  width: number
  height: number
  detectedAt: string
  boxes: DetectBox[]
}

const BOX_VERSION = 1
const INPUT_LONG = 1024
const CONF_THRESHOLD = 0.6
const NMS_IOU = 0.5
/** 模型空闲多久后释放（毫秒）；本机内存吃紧，10 分钟不用就松手 */
const IDLE_UNLOAD_MS = 10 * 60 * 1000

let ortModule: typeof import('onnxruntime-node') | null = null
const sessions = new Map<string, any>()
let lastUsed = 0
let idleTimer: NodeJS.Timeout | undefined

function modelsDir() {
  return process.env.MODELS_DIR?.trim() ? path.resolve(process.env.MODELS_DIR.trim()) : path.resolve('./models')
}
export function detectDir() { return path.join(modelsDir(), 'detect') }

function modelPath(kind: 'face' | 'head') {
  return path.join(detectDir(), kind === 'face' ? 'face.onnx' : 'head.onnx')
}

export function detectModelsReady() {
  return existsSync(modelPath('face')) && existsSync(modelPath('head'))
}

async function loadOrt() {
  if (!ortModule) {
    // 动态引入：未启用该功能时不占用启动时间
    ortModule = await import('onnxruntime-node')
  }
  return ortModule
}

function scheduleIdleUnload() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    const now = Date.now()
    if (now - lastUsed < IDLE_UNLOAD_MS) { scheduleIdleUnload(); return }
    sessions.clear()
    idleTimer = undefined
  }, IDLE_UNLOAD_MS)
  /* 不要因为这个定时器把进程钉住不退出 */
  idleTimer.unref?.()
}

async function getSession(kind: 'face' | 'head') {
  const cached = sessions.get(kind)
  if (cached) { lastUsed = Date.now(); return cached }
  const file = modelPath(kind)
  try { await access(file) } catch { throw new Error(`检测模型缺失：${file}`) }
  const ort = await loadOrt()
  const session = await ort.InferenceSession.create(file, { graphOptimizationLevel: 'all' })
  sessions.set(kind, session)
  lastUsed = Date.now()
  scheduleIdleUnload()
  return session
}

/** 标准贪心 NMS（按分数降序，IoU 超阈值即抑制） */
function nms<T extends { x1: number; y1: number; x2: number; y2: number; score: number }>(boxes: T[], iouThreshold = NMS_IOU): T[] {
  const sorted = [...boxes].sort((a, b) => b.score - a.score)
  const keep: T[] = []
  for (const box of sorted) {
    let suppress = false
    for (const kept of keep) {
      const ix = Math.max(0, Math.min(box.x2, kept.x2) - Math.max(box.x1, kept.x1))
      const iy = Math.max(0, Math.min(box.y2, kept.y2) - Math.max(box.y1, kept.y1))
      const inter = ix * iy
      const union = (box.x2 - box.x1) * (box.y2 - box.y1) + (kept.x2 - kept.x1) * (kept.y2 - kept.y1) - inter
      if (union > 0 && inter / union > iouThreshold) { suppress = true; break }
    }
    if (!suppress) keep.push(box)
  }
  return keep
}

export interface DetectOptions {
  inputLong?: number
  confidence?: number
}

/**
 * 对图片缓冲区做检测，返回归一化框。
 * 只处理静态图；视频请先抽帧后再传入。
 */
export async function detectBoxes(buffer: Buffer, options: DetectOptions = {}): Promise<{ width: number; height: number; boxes: DetectBox[]; inputLong: number }> {
  const long = options.inputLong ?? INPUT_LONG
  const confidence = options.confidence ?? CONF_THRESHOLD

  const image = sharp(buffer, { animated: false })
  const meta = await image.metadata()
  const ow = meta.width || 0
  const oh = meta.height || 0
  if (!ow || !oh) throw new Error('无法读取图片尺寸')

  // 保持宽高比缩放到长边 long，并补齐到 32 的倍数（YOLO 常规做法）
  const scale = long / Math.max(ow, oh)
  const rw = Math.max(32, Math.round((ow * scale) / 32) * 32)
  const rh = Math.max(32, Math.round((oh * scale) / 32) * 32)

  const raw = await sharp(buffer, { animated: false })
    .resize(rw, rh, { fit: 'fill' })
    .removeAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer()

  // CHW + ImageNet 归一化（已 A/B 实测：与 [0,1] 归一化结果一致，取 ImageNet）
  const MEAN = [0.485, 0.456, 0.406]
  const STD = [0.229, 0.224, 0.225]
  const n = rw * rh
  const chw = new Float32Array(3 * n)
  for (let i = 0; i < n; i++) {
    for (let c = 0; c < 3; c++) {
      const v = raw[i * 3 + c] / 255
      chw[c * n + i] = (v - MEAN[c]) / STD[c]
    }
  }

  const ort = await loadOrt()
  const tensor = new ort.Tensor('float32', chw, [1, 3, rh, rw])
  const sx = ow / rw
  const sy = oh / rh
  const boxes: DetectBox[] = []

  for (const kind of ['face', 'head'] as const) {
    const session = await getSession(kind)
    const inputName = session.inputNames[0]
    const output = await session.run({ [inputName]: tensor })
    const out = output[session.outputNames[0]]
    const dims = out.dims
    // 期望 [1, 5, anchors]；若通道数更小则可能是 [1, anchors, 5]，做一次转置兼容
    let channels = dims[1]
    let anchors = dims[2]
    let transposed = false
    if (dims[2] < dims[1] && dims[2] <= 8) { channels = dims[2]; anchors = dims[1]; transposed = true }
    if (channels < 5) continue
    const at = (c: number, a: number) => (transposed ? out.data[a * channels + c] : out.data[c * anchors + a])

    const candidates: Array<{ x1: number; y1: number; x2: number; y2: number; score: number }> = []
    for (let a = 0; a < anchors; a++) {
      const score = at(4, a)
      if (!(score >= confidence)) continue
      let cx = at(0, a)
      let cy = at(1, a)
      let bw = at(2, a)
      let bh = at(3, a)
      // 兼容某些导出会给出 0~1 归一化坐标
      if (Math.max(cx, cy, bw, bh) <= 2) { cx *= rw; cy *= rh; bw *= rw; bh *= rh }
      candidates.push({ score, x1: cx - bw / 2, y1: cy - bh / 2, x2: cx + bw / 2, y2: cy + bh / 2 })
    }

    for (const kept of nms(candidates)) {
      const x1 = Math.max(0, Math.min(ow, kept.x1 * sx))
      const y1 = Math.max(0, Math.min(oh, kept.y1 * sy))
      const x2 = Math.max(0, Math.min(ow, kept.x2 * sx))
      const y2 = Math.max(0, Math.min(oh, kept.y2 * sy))
      if (x2 - x1 < 4 || y2 - y1 < 4) continue
      boxes.push({
        x: x1 / ow,
        y: y1 / oh,
        w: (x2 - x1) / ow,
        h: (y2 - y1) / oh,
        score: Number(kept.score.toFixed(4)),
        kind,
      })
    }
  }

  // 去掉「头框与脸框几乎重合」的冗余：face 更精确，保留 face，丢弃高度重合的 head
  const faces = boxes.filter((b) => b.kind === 'face')
  const heads = boxes.filter((b) => b.kind === 'head').filter((h) => {
    return !faces.some((f) => {
      const ix = Math.max(0, Math.min(f.x + f.w, h.x + h.w) - Math.max(f.x, h.x))
      const iy = Math.max(0, Math.min(f.y + f.h, h.y + h.h) - Math.max(f.y, h.y))
      const inter = ix * iy
      const uni = f.w * f.h + h.w * h.h - inter
      return uni > 0 && inter / uni > 0.9
    })
  })

  // 排序：按面积从大到小（通常主角在前），便于前端展示
  const merged = [...faces, ...heads].sort((a, b) => b.w * b.h - a.w * a.h)
  return { width: ow, height: oh, boxes: merged, inputLong: long }
}

/* ---------------- 框坐标持久化（sidecar JSON，不动原图、不动数据库） ---------------- */

function boxFile(imageId: string) {
  const safe = String(imageId).replace(/[^a-zA-Z0-9_-]/g, '')
  return path.join(detectDir(), 'boxes', `${safe}.json`)
}

export async function readBoxes(imageId: string): Promise<ImageBoxes | null> {
  try {
    const text = await readFile(boxFile(imageId), 'utf8')
    const parsed = JSON.parse(text) as ImageBoxes
    if (!parsed || !Array.isArray(parsed.boxes)) return null
    return parsed
  } catch { return null }
}

export async function writeBoxes(imageId: string, payload: ImageBoxes): Promise<void> {
  const dir = path.join(detectDir(), 'boxes')
  await mkdir(dir, { recursive: true })
  const target = boxFile(imageId)
  // 先写临时文件再改名，避免读取方看到半截 JSON
  const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
  await writeFile(tmp, JSON.stringify(payload, null, 1), 'utf8')
  await rename(tmp, target)
}

export function newBoxesPayload(width: number, height: number, boxes: DetectBox[], inputLong = INPUT_LONG): ImageBoxes {
  return { version: BOX_VERSION, inputLong, width, height, detectedAt: new Date().toISOString(), boxes }
}

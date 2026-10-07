/**
 * 角色「参考图库」：把人工确认过的框 + 图片固化成某个角色标签的参考样本。
 *
 * 设计取舍：
 *   - **不切割原图、不新增文件**：只存 { imageId, box } 引用，裁剪按需即时生成。
 *     好处是原图永远是唯一真相，以后换检测模型或调框都能重算，磁盘也不会膨胀。
 *   - **不把标签名写进参考条目**：角色的真值仍以数据库的 ImageTag 为准，
 *     避免标签改名后参考库与标签体系脱节（查询时按标签 id 反查图片）。
 *   - 存 sidecar JSON（key 为 tagId）：写频率低（仅人工确认时），无需数据库迁移。
 */
import { existsSync } from 'node:fs'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'
import { detectDir, readBoxes, type DetectBox } from './detect.js'

export interface ReferenceEntry {
  imageId: string
  /** 归一化框 0~1；与 detect.ts 的 DetectBox 同坐标系 */
  box: { x: number; y: number; w: number; h: number }
  kind: 'face' | 'head' | 'region'
  source: 'auto' | 'manual'
  note?: string
  addedAt: string
  addedById?: string
}

interface ReferenceFile {
  version: number
  tags: Record<string, { entries: ReferenceEntry[]; updatedAt: string }>
}

const REF_VERSION = 1

function refFile() { return path.join(detectDir(), 'references.json') }

export async function loadReferences(): Promise<ReferenceFile> {
  try {
    const parsed = JSON.parse(await readFile(refFile(), 'utf8')) as ReferenceFile
    if (parsed && typeof parsed.tags === 'object' && parsed.tags) return parsed
  } catch { /* 首次运行或文件损坏：返回空结构 */ }
  return { version: REF_VERSION, tags: {} }
}

async function saveReferences(data: ReferenceFile): Promise<void> {
  const dir = detectDir()
  await mkdir(dir, { recursive: true })
  const target = refFile()
  const tmp = `${target}.tmp-${process.pid}-${Date.now()}`
  await writeFile(tmp, JSON.stringify(data, null, 1), 'utf8')
  await rename(tmp, target)
}

export async function listReferences(tagId: string): Promise<ReferenceEntry[]> {
  const data = await loadReferences()
  return data.tags[tagId]?.entries || []
}

/** 同一个框重复添加时做覆盖，避免用户反复点导致重复条目 */
function sameRegion(a: ReferenceEntry['box'], b: ReferenceEntry['box']) {
  const close = (x: number, y: number) => Math.abs(x - y) < 0.002
  return close(a.x, b.x) && close(a.y, b.y) && close(a.w, b.w) && close(a.h, b.h)
}

export async function addReference(
  tagId: string,
  entry: Omit<ReferenceEntry, 'addedAt'> & { addedAt?: string },
): Promise<{ added: boolean; total: number }> {
  const data = await loadReferences()
  const bucket = data.tags[tagId] || { entries: [], updatedAt: new Date().toISOString() }
  const existingIndex = bucket.entries.findIndex((item) => item.imageId === entry.imageId && sameRegion(item.box, entry.box))
  const record: ReferenceEntry = { ...entry, addedAt: entry.addedAt || new Date().toISOString() }
  if (existingIndex >= 0) bucket.entries[existingIndex] = record
  else bucket.entries.push(record)
  bucket.updatedAt = new Date().toISOString()
  data.tags[tagId] = bucket
  await saveReferences(data)
  return { added: existingIndex < 0, total: bucket.entries.length }
}

export async function removeReference(tagId: string, imageId: string, box?: ReferenceEntry['box']): Promise<{ removed: number; total: number }> {
  const data = await loadReferences()
  const bucket = data.tags[tagId]
  if (!bucket) return { removed: 0, total: 0 }
  const before = bucket.entries.length
  bucket.entries = bucket.entries.filter((item) => {
    if (item.imageId !== imageId) return true
    if (!box) return false           // 未指定框：删掉这张图的全部参考
    return !sameRegion(item.box, box)
  })
  bucket.updatedAt = new Date().toISOString()
  // 空桶直接删掉，避免 references.json 里堆积无用的空条目
  if (bucket.entries.length === 0) delete data.tags[tagId]
  else data.tags[tagId] = bucket
  await saveReferences(data)
  return { removed: before - bucket.entries.length, total: bucket.entries.length }
}

/** 参考库总览：每个角色有多少条参考（用于显示进度，不泄露图片内容） */
export async function referenceCounts(): Promise<Record<string, number>> {
  const data = await loadReferences()
  const out: Record<string, number> = {}
  for (const [tagId, bucket] of Object.entries(data.tags)) out[tagId] = bucket.entries.length
  return out
}

/**
 * 取某张图某个框的裁剪图（按需生成，不落盘）。
 * 传入的框若是检测框下标，会先读 sidecar 里的框；否则用传入的归一化坐标。
 */
export async function cropRegion(
  originalFile: string,
  region: { x: number; y: number; w: number; h: number },
  options: { width?: number; format?: 'jpeg' | 'webp' } = {},
): Promise<Buffer> {
  const width = Math.max(32, Math.min(1024, options.width || 240))
  const image = sharp(originalFile, { animated: false, failOn: 'none' })
  const meta = await image.metadata()
  const ow = meta.width || 0
  const oh = meta.height || 0
  if (!ow || !oh) throw new Error('无法读取原图尺寸')

  // 夹到画布内，并保证至少 1px，避免 extract 抛错
  const left = Math.max(0, Math.min(ow - 1, Math.round(region.x * ow)))
  const top = Math.max(0, Math.min(oh - 1, Math.round(region.y * oh)))
  const w = Math.max(1, Math.min(ow - left, Math.round(region.w * ow)))
  const h = Math.max(1, Math.min(oh - top, Math.round(region.h * oh)))

  const pipeline = sharp(originalFile, { animated: false, failOn: 'none' })
    .extract({ left, top, width: w, height: h })
    .resize({ width, withoutEnlargement: true })
  return options.format === 'webp'
    ? pipeline.webp({ quality: 82 }).toBuffer()
    : pipeline.jpeg({ quality: 85 }).toBuffer()
}

/** 从 sidecar 里按序号取框（前端传 boxIndex 时用），找不到则返回 null */
export async function regionFromBoxIndex(imageId: string, index: number): Promise<DetectBox | null> {
  const payload = await readBoxes(imageId)
  if (!payload) return null
  const box = payload.boxes[index]
  return box || null
}

export function referencesPersisted() { return existsSync(refFile()) }

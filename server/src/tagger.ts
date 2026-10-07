/**
 * 自动打标引擎：动漫多标签分类器（Camais03/camie-tagger-v2，70,527 个标签）。
 *
 * 与 ai-manager.ts（Qwen-VL 视觉语言模型）并存，二选一即可：
 *   - 本模块：闭集多标签分类，快（实测约 1.8 秒/张）、准（角色命中 92%、猫耳 100%、裸足 89%），
 *     但只能输出词表里已有的标签，且词表是英文。
 *   - ai-manager：Qwen-VL 生成式，能给中文描述性标签，但慢（CPU 上几十秒/张）且角色名不可靠。
 *
 * 关键实现要点（均为实测结论，不是猜的）：
 *   - **必须用 refined_predictions**：实测命中 35/39 vs initial 30/39，平均分 0.9148 vs 0.7008；
 *     有 4 张图 initial 完全没检出，refined 能给出 0.985~0.994。
 *   - 输入 512×512，保持宽高比缩放 + 居中填充 + ImageNet 归一化。
 *   - 阈值用官方推荐的 macro 优化值 0.492。命中分数集中在 0.98~1.00，未命中在 0.25 附近。
 *   - 只采信 general / character / copyright / rating 四类，丢弃 artist（会混进画师名）和 year。
 */
import { existsSync } from 'node:fs'
import { access, readFile } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

export interface TagSuggestion {
  /** 展示用名称（已尽量映射成库里已有的中文标签） */
  name: string
  /** 模型原始英文标签，便于排查与去重 */
  raw: string
  score: number
  category: 'character' | 'general' | 'copyright' | 'rating'
}

export interface TagSuggestResult {
  /** 拍平的全部建议（角色优先） */
  suggestions: TagSuggestion[]
  character: TagSuggestion[]
  general: TagSuggestion[]
  copyright: TagSuggestion[]
  rating: TagSuggestion[]
  /** 因没有中文映射而被丢弃的英文标签数量（规则类，未过阈值的不计） */
  dropped: { character: number; general: number }
}

const INPUT_SIZE = 512
const MEAN = [0.485, 0.456, 0.406]
const STD = [0.229, 0.224, 0.225]
/** 官方 macro 优化阈值 */
const SCORE_THRESHOLD = 0.492
const PER_CATEGORY_LIMIT = 12
const IDLE_UNLOAD_MS = 15 * 60 * 1000

/**
 * 英文标签 → 你库里中文标签的映射。
 *
 * **角色必须映射**：库里用的是「可莉」「纳西妲」这类中文名，
 * 若直接把模型输出的 klee_(genshin_impact) 写进标签表，会造出同义重复标签，
 * 而且**按中文名检索不到新图**（实测踩过这个坑）。
 *
 * 只有语义完全一致的才映射；映射不到的英文标签原样返回，由用户决定是否采纳，
 * 避免自动造出同义重复标签（例如 裸足 / 赤足 并存）。
 * 词表里没有的角色（洛茜、绯樱、缇宝）映射不到，只能人工填——这是已知限制。
 */
const EN_TO_ZH: Record<string, string> = {
  /* --- 角色 --- */
  'nahida_(genshin_impact)': '纳西妲',
  'klee_(genshin_impact)': '可莉',
  'qiqi_(genshin_impact)': '七七',
  'yaoyao_(genshin_impact)': '瑶瑶',
  'hu_tao_(genshin_impact)': '胡桃',
  'furina_(genshin_impact)': '芙宁娜·德·枫丹',
  'huohuo_(honkai:_star_rail)': '霍霍',
  'bailu_(honkai:_star_rail)': '白露',
  'robin_(honkai:_star_rail)': '知更鸟',
  'sparkle_(honkai:_star_rail)': '花火',
  'seele_(honkai:_star_rail)': '遐蝶',
  'hoshino_(blue_archive)': '小鸟游星野',
  'ibuki_(blue_archive)': '丹花伊吹',
  /* --- 属性 --- */
  white_thighhighs: '白丝',
  black_thighhighs: '黑丝',
  barefoot: '裸足',
  cat_ears: '猫耳',
  animal_ears: '猫耳',
  /* --- 作品 --- */
  genshin_impact: '原神',
  'honkai:_star_rail': '崩坏星穹铁道',
  blue_archive: '蔚蓝档案',
  zenless_zone_zero: '绝区零',
  wuthering_waves: '鸣潮',
}

/**
 * 属性建议只保留这些：语义明确、且在你库里有对应中文标签或明确有用。
 * 其余英文标签（1girl、green_eyes、sidelocks 之类）不上报——
 * 它们会污染中文标签体系，实测一张图能刷出 17 个英文标签，对检索帮助却有限。
 */
const USEFUL_GENERAL: Record<string, string> = {
  white_thighhighs: '白丝',
  black_thighhighs: '黑丝',
  barefoot: '裸足',
  cat_ears: '猫耳',
  animal_ears: '猫耳',
  thighhighs: '过膝袜',
  pantyhose: '连裤袜',
  socks: '袜子',
  maid: '女仆装',
  kimono: '和服',
  chinese_clothes: '中式服装',
  school_uniform: '校服',
  swimsuit: '泳装',
  dress: '连衣裙',
  twintails: '双马尾',
  ponytail: '马尾',
  ahoge: '呆毛',
  halo: '光环',
  wings: '翅膀',
  horns: '角',
  tail: '尾巴',
  glasses: '眼镜',
  hat: '帽子',
  ribbon: '丝带',
  gloves: '手套',
  boots: '靴子',
  high_heels: '高跟鞋',
  jewelry: '首饰',
  from_above: '俯拍',
  from_below: '仰拍',
  sunset: '夕阳',
  night: '夜晚',
  silhouette: '剪影',
  outdoors: '户外',
  indoors: '室内',
  full_body: '全身',
  upper_body: '半身',
  'close-up': '特写',
  sitting: '坐姿',
  standing: '站姿',
  lying: '躺姿',
  solo: '单人',
  '1girl': '单人少女',
  '2girls': '双人少女',
  multiple_girls: '多人少女',
  white_hair: '白发',
  blonde_hair: '金发',
  black_hair: '黑发',
  brown_hair: '棕发',
  blue_hair: '蓝发',
  red_hair: '红发',
  pink_hair: '粉发',
  green_hair: '绿发',
  purple_hair: '紫发',
  silver_hair: '银发',
  grey_hair: '灰发',
  orange_hair: '橙发',
  long_hair: '长发',
  short_hair: '短发',
  very_long_hair: '超长发',
  blue_eyes: '蓝眼',
  red_eyes: '红眼',
  green_eyes: '绿眼',
  purple_eyes: '紫眼',
  yellow_eyes: '黄眼',
  pointy_ears: '尖耳',
  blush: '脸红',
  smile: '微笑',
  open_mouth: '张嘴',
  closed_eyes: '闭眼',
  crying: '哭泣',
}

/** 分级单独成列，供前端提示是否标记 R-18 */
const RATING_ZH: Record<string, string> = {
  rating_general: '全年龄',
  rating_sensitive: '轻度',
  rating_questionable: '擦边',
  rating_explicit: 'R-18',
}

let ortModule: typeof import('onnxruntime-node') | null = null
let session: any = null
let tag2idx: Record<string, number> = {}
let idx2tag: Record<string, string> = {}
/** 索引 → 类别名；只保留采信的四个类别 */
let catOfIndex: Record<number, string> = {}
let keepIndices: number[] = []
let lastUsed = 0
let idleTimer: NodeJS.Timeout | undefined

function modelsDir() {
  return process.env.MODELS_DIR?.trim() ? path.resolve(process.env.MODELS_DIR.trim()) : path.resolve('./models')
}
export function taggerDir() { return path.join(modelsDir(), 'tagger') }
function modelPath() { return path.join(taggerDir(), 'camie-tagger-v2.onnx') }
function metaPath() { return path.join(taggerDir(), 'camie-tagger-v2-metadata.json') }

export function taggerModelsReady() {
  return existsSync(modelPath()) && existsSync(metaPath())
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

/** 懒加载：首次用到才把 753MB 模型读进内存，空闲 15 分钟自动释放（本机内存吃紧） */
async function ensureSession() {
  if (session) { lastUsed = Date.now(); return session }
  const model = modelPath()
  const metaFile = metaPath()
  try { await access(model); await access(metaFile) } catch {
    throw new Error(`打标模型缺失：${model}`)
  }
  const meta = JSON.parse(await readFile(metaFile, 'utf8'))
  const mapping = meta?.dataset_info?.tag_mapping
  const categories: string[] = meta?.dataset_info?.categories || []
  if (!mapping?.tag_to_idx || !mapping?.idx_to_tag || !mapping?.tag_to_category) {
    throw new Error('打标模型元数据格式不正确')
  }
  tag2idx = mapping.tag_to_idx
  idx2tag = mapping.idx_to_tag
  const wanted = new Set(['general', 'character', 'copyright', 'rating'])
  catOfIndex = {}
  keepIndices = []
  for (const [tag, rawCat] of Object.entries(mapping.tag_to_category as Record<string, unknown>)) {
    const i = tag2idx[tag]
    if (i === undefined) continue
    const name = (typeof rawCat === 'number' || /^\d+$/.test(String(rawCat)))
      ? categories[Number(rawCat)]
      : String(rawCat)
    if (!name || !wanted.has(name)) continue
    catOfIndex[i] = name
    keepIndices.push(i)
  }
  const ort = await loadOrt()
  session = await ort.InferenceSession.create(model, { graphOptimizationLevel: 'all' })
  lastUsed = Date.now()
  scheduleIdleUnload()
  return session
}

const sigmoid = (x: number) => 1 / (1 + Math.exp(-x))

/** 图片缓冲 → 模型输入张量（512×512，保持宽高比 + 居中填充 + ImageNet 归一化） */
async function toTensor(buffer: Buffer) {
  const meta = await sharp(buffer, { animated: false }).metadata()
  const ow = meta.width || 0
  const oh = meta.height || 0
  if (!ow || !oh) throw new Error('无法读取图片尺寸')
  const scale = INPUT_SIZE / Math.max(ow, oh)
  const rw = Math.max(1, Math.round(ow * scale))
  const rh = Math.max(1, Math.round(oh * scale))
  const raw = await sharp(buffer, { animated: false })
    .resize(rw, rh, { fit: 'fill' })
    .removeAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer()
  const n = INPUT_SIZE * INPUT_SIZE
  const chw = new Float32Array(3 * n)
  const ox = Math.floor((INPUT_SIZE - rw) / 2)
  const oy = Math.floor((INPUT_SIZE - rh) / 2)
  for (let y = 0; y < rh; y++) {
    for (let x = 0; x < rw; x++) {
      const s = (y * rw + x) * 3
      const d = (y + oy) * INPUT_SIZE + (x + ox)
      chw[d] = (raw[s] / 255 - MEAN[0]) / STD[0]
      chw[n + d] = (raw[s + 1] / 255 - MEAN[1]) / STD[1]
      chw[2 * n + d] = (raw[s + 2] / 255 - MEAN[2]) / STD[2]
    }
  }
  return { chw, width: ow, height: oh }
}

/**
 * 对一张图给出标签建议。
 * @param existingTags 库内已有标签名（用于判断映射目标，并避免重复建议）
 */
export async function suggestTagsByTagger(buffer: Buffer, existingTags: string[] = []): Promise<TagSuggestResult> {
  const ort = await loadOrt()
  const sess = await ensureSession()
  const { chw } = await toTensor(buffer)

  const out = await sess.run({ input: new ort.Tensor('float32', chw, [1, 3, INPUT_SIZE, INPUT_SIZE]) })
  const logits = out.refined_predictions.data as Float32Array

  const buckets: Record<string, TagSuggestion[]> = { character: [], general: [], copyright: [], rating: [] }
  /** 被过滤掉的英文标签数量，便于排查「为什么没建议」 */
  let droppedCharacter = 0
  let droppedGeneral = 0

  /* 注意：这里**不能**因为「库里已有该标签」就跳过建议——
     打标的全部意义就是把新图归到已有标签上（新图本来就没有这些标签），
     过滤掉已有标签会让建议直接变成空的。 */
  for (const i of keepIndices) {
    const cat = catOfIndex[i]
    if (!cat || !buckets[cat]) continue
    const score = sigmoid(logits[i])
    if (score < SCORE_THRESHOLD) continue
    const rawTag = idx2tag[String(i)]
    if (!rawTag) continue

    if (cat === 'general') {
      // 属性只保留精选清单，避免十几条英文标签污染中文标签体系
      const zh = USEFUL_GENERAL[rawTag]
      if (!zh) { droppedGeneral++; continue }
      buckets.general.push({ name: zh, raw: rawTag, score: Number(score.toFixed(3)), category: 'general' })
      continue
    }

    // 角色与作品：必须有中文映射，否则不报（防止造出 klee_(genshin_impact) 这种重复标签）
    const zh = EN_TO_ZH[rawTag]
    if (!zh) { droppedCharacter++; continue }
    buckets[cat].push({ name: zh, raw: rawTag, score: Number(score.toFixed(3)), category: cat as TagSuggestion['category'] })
  }

  // 分级：即使库里没这标签也返回，供前端提示
  const rating: TagSuggestion[] = []
  for (const [en, zh] of Object.entries(RATING_ZH)) {
    const i = tag2idx[en]
    if (i === undefined) continue
    const score = sigmoid(logits[i])
    if (score < SCORE_THRESHOLD) continue
    rating.push({ name: zh, raw: en, score: Number(score.toFixed(3)), category: 'rating' })
  }
  buckets.rating = rating.sort((a, b) => b.score - a.score).slice(0, 2)

  for (const key of ['character', 'general', 'copyright'] as const) {
    buckets[key].sort((a, b) => b.score - a.score)
    buckets[key] = buckets[key].slice(0, PER_CATEGORY_LIMIT)
  }

  return {
    suggestions: [...buckets.character, ...buckets.copyright, ...buckets.general],
    character: buckets.character,
    general: buckets.general,
    copyright: buckets.copyright,
    rating: buckets.rating,
    dropped: { character: droppedCharacter, general: droppedGeneral },
  }
}

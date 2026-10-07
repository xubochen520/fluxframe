import type { AuditLog, DeepseekSummary, ImageItem, PersonDetail, TagItem } from './types'

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) { super(message); this.status = status }
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

function apiPath(path: string) { return path.startsWith('/api') ? `${API_BASE}${path.slice(4)}` : path }

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const requestPath = apiPath(path)
  let response: Response
  try {
    const headers = new Headers(options.headers)
    if (options.body !== undefined && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    response = await fetch(requestPath, { ...options, credentials: 'include', headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认 PostgreSQL 和 Node API 已启动', 0)
  }
  if (response.status === 204) return undefined as T
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(payload.message || '请求失败', response.status)
  return payload as T
}

async function asset(path: string) {
  let response: Response
  try { response = await fetch(apiPath(path), { credentials: 'include' }) } catch { throw new ApiError('无法加载图片资源', 0) }
  if (!response.ok) throw new ApiError('图片资源不可用', response.status)
  return URL.createObjectURL(await response.blob())
}

export interface CurrentUser { id: string; username: string; role: 'ADMIN' | 'USER'; r18Mode: boolean }
export interface DashboardPayload { stats: { imageCount: number; tagCount: number; userCount: number; totalViews: number; storage: string; storageCapacity: string; storagePercent: number; databaseImageBytes: string }; top: ImageItem[]; recent: ImageItem[]; logs: AuditLog[] }
export interface UploadAnalysisItem { sourceIndex: number; tempId: string | null; fileName: string; name: string; mimeType: string; size: number; width: number; height: number; tags: string[]; duplicate: boolean; duplicateName?: string; aiError?: string; advice?: TagAdvice }
/** 自动打标的建议（按类别分开，供确认页展示可选项） */
export interface TagAdviceItem { name: string; raw: string; score: number; category: 'character' | 'general' | 'copyright' | 'rating' }
export interface TagAdvice { character: TagAdviceItem[]; copyright: TagAdviceItem[]; general: TagAdviceItem[]; rating: TagAdviceItem[] }
export interface UploadAnalysisPayload { items: UploadAnalysisItem[]; aiEnabled: boolean; aiModel: string; taggerEnabled?: boolean }
export interface AiStatusPayload {
  running: boolean
  port: number | null
  baseUrl: string | null
  detected: { port: number; modelId: string | null } | null
  files: { server: boolean; model: boolean; mmproj: boolean; variant: string | null }
  progress: { phase: string; llamaDone: number; llamaTotal: number; modelDone: number; modelTotal: number; modelName: string; variant: string; error?: string; port?: number }
}
export interface FfmpegStatusPayload {
  found: boolean
  path: string | null
  version: string | null
  busy: boolean
  progress: { phase: string; done: number; total: number; error?: string }
}
export interface BiliQrCreatePayload { ok: boolean; error?: string; qrcodeKey?: string; image?: string }
export interface BiliQrPollPayload { ok: boolean; status: 'waiting' | 'scanned' | 'expired' | 'ok' | 'error'; error?: string; nickname?: string }

/* ---- 人物框选 ---- */
export interface DetectBox {
  x: number
  y: number
  w: number
  h: number
  score: number
  kind: 'face' | 'head'
  /** 人工调整/新增的框 */
  manual?: boolean
}
export interface ImageBoxesPayload {
  version: number
  inputLong: number
  width: number
  height: number
  detectedAt: string
  boxes: DetectBox[]
}
export interface DetectStatusPayload { modelsReady: boolean; total: number; boxed: number; pending: number }

/* ---- 相似图 / 关系网（CCIP 视觉指纹） ---- */
/** 一张相似图：图片本身的信息 + 相似度分数 */
export interface SimilarImageItem extends ImageItem { score: number }
export interface SimilarImagesPayload {
  ready: boolean
  /** 当前这张图是否已经建好指纹 */
  indexed: boolean
  /** 还没建指纹、后台正在算 */
  analyzing?: boolean
  threshold: number
  items: SimilarImageItem[]
}
export interface EmbedStatusPayload {
  modelReady: boolean
  sessionActive: boolean
  modelPath: string
  version: string
  threshold: number
  /** 已建指纹的图片数 */
  indexed: number
  /** 库内静态图片总数 */
  imageCount: number
  /** 还没建指纹的数量 */
  missing: number
  avgNeighbors: number
  updatedAt: string
  queueLength: number
  working: boolean
  done: number
  failed: number
  lastError: string
}
export interface EmbedGraphEdge { a: string; b: string; score: number }
export interface EmbedGraphGroup { members: string[]; size: number }
export interface EmbedGraphPayload {
  ready: boolean
  version?: string
  threshold?: number
  updatedAt?: string
  nodes: ImageItem[]
  edges: EmbedGraphEdge[]
  groups: EmbedGraphGroup[]
  /** 已建指纹的图片总数（含没有相似图的） */
  totalIndexed?: number
  /** 其中真正连上相似关系的张数。注意：它等于各分组大小之和 */
  linked?: number
  /** 没有任何相似图的孤立图片数量 = totalIndexed - linked */
  isolated: number
}
export interface EmbedCalibrationPayload {
  threshold: number
  pairs: { same: number; diff: number }
  same: { p10: number | null; p50: number | null; p90: number | null }
  diff: { p10: number | null; p50: number | null; p90: number | null }
}

/* ---- 角色参考图库 ---- */
export interface ReferenceItem {
  imageId: string
  name: string
  thumb: string
  crop: string
  box: { x: number; y: number; w: number; h: number }
  kind: 'face' | 'head' | 'region'
  source: 'auto' | 'manual'
  addedAt: string
  stillTagged: boolean
}
export interface TagReferencesPayload {
  tag: { id: string; name: string; person: boolean }
  total: number
  items: ReferenceItem[]
}

export const api = {
  me: () => request<CurrentUser>('/api/me'),
  login: (username: string, password: string) => request<{ user: CurrentUser }>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  register: (username: string, password: string) => request<CurrentUser>('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST', body: '{}' }),
  changePassword: (currentPassword: string, newPassword: string) => request<{ ok: true }>('/api/auth/password', { method: 'PATCH', body: JSON.stringify({ currentPassword, newPassword }) }),
  setR18Mode: (enabled: boolean) => request<{ r18Mode: boolean }>('/api/me/r18-mode', { method: 'PATCH', body: JSON.stringify({ enabled }) }),
  dashboard: () => request<DashboardPayload>('/api/dashboard'),
  asset,
  images: (params: { search?: string; tag?: string; sort?: string; trash?: boolean }) => request<{ items: ImageItem[] }>(`/api/images?${new URLSearchParams(Object.entries(params).filter(([, value]) => value !== undefined).map(([key, value]) => [key, String(value)]))}`),
  viewImage: (id: string) => request<{ ok: true; views: number }>(`/api/images/${id}/view`, { method: 'POST', body: '{}' }),
  upload: (files: FileList | File[]) => { const form = new FormData(); Array.from(files).forEach((file) => form.append('files', file)); return request<{ items: ImageItem[] }>('/api/images/upload', { method: 'POST', body: form }) },
  analyzeUpload: (files: FileList | File[]) => { const form = new FormData(); Array.from(files).forEach((file) => form.append('files', file)); return request<UploadAnalysisPayload>('/api/images/upload/analyze', { method: 'POST', body: form }) },
  completeUpload: (items: Array<{ tempId: string; name: string; tags: string[] }>) => request<{ items: ImageItem[] }>('/api/images/upload/complete', { method: 'POST', body: JSON.stringify({ items }) }),
  cancelUpload: (tempId: string) => request<{ ok: true }>(`/api/images/upload/pending/${tempId}`, { method: 'DELETE' }),
  deleteImage: (id: string) => request<{ ok: true }>(`/api/images/${id}`, { method: 'DELETE' }),
  renameImage: (id: string, name: string) => request<{ ok: true; name: string }>(`/api/images/${id}`, { method: 'PATCH', body: JSON.stringify({ name }) }),
  restoreImage: (id: string) => request<{ ok: true }>(`/api/images/${id}/restore`, { method: 'POST', body: '{}' }),
  permanentDelete: (id: string) => request<{ ok: true }>(`/api/images/${id}/permanent`, { method: 'DELETE' }),
  tags: () => request<{ items: TagItem[] }>('/api/tags'),
  createTag: (name: string, color = '#a78bfa', r18 = false, person = false) => request<TagItem>('/api/tags', { method: 'POST', body: JSON.stringify({ name, color, r18, person }) }),
  updateTag: (id: string, patch: { r18?: boolean; color?: string; person?: boolean }) => request<TagItem>(`/api/tags/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  personDetail: (id: string) => request<PersonDetail>(`/api/tags/${id}/person`),
  deleteTag: (id: string) => request<{ ok: true }>(`/api/tags/${id}`, { method: 'DELETE' }),
  addImagesToTag: (tagId: string, imageIds: string[]) => request<{ ok: true; added: number }>(`/api/tags/${tagId}/images`, { method: 'POST', body: JSON.stringify({ imageIds }) }),
  addTag: (imageId: string, name: string) => request<{ ok: true }>(`/api/images/${imageId}/tags`, { method: 'POST', body: JSON.stringify({ name }) }),
  removeTag: (imageId: string, tagId: string) => request<{ ok: true }>(`/api/images/${imageId}/tags/${tagId}`, { method: 'DELETE' }),
  logs: () => request<{ items: AuditLog[] }>('/api/audit-logs'),
  settings: () => request<Record<string, unknown>>('/api/settings'),
  saveSettings: (settings: Record<string, unknown>) => request<Record<string, unknown>>('/api/settings', { method: 'PATCH', body: JSON.stringify(settings) }),
  aiStatus: () => request<AiStatusPayload>('/api/ai/status'),
  taggerStatus: () => request<{ ready: boolean }>('/api/tagger/status'),
  aiDownload: (variant: string, mirror?: string) => request<{ ok: boolean; error?: string }>('/api/ai/download', { method: 'POST', body: JSON.stringify({ variant, mirror }) }),
  aiStart: () => request<{ ok: boolean; error?: string }>('/api/ai/start', { method: 'POST', body: '{}' }),
  aiStop: () => request<{ ok: boolean }>('/api/ai/stop', { method: 'POST', body: '{}' }),
  ffmpegStatus: () => request<FfmpegStatusPayload>('/api/ffmpeg/status'),
  ffmpegDownload: () => request<{ ok: boolean }>('/api/ffmpeg/download', { method: 'POST', body: '{}' }),
  biliQrCreate: () => request<BiliQrCreatePayload>('/api/bili/qr/create', { method: 'POST', body: '{}' }),
  biliQrPoll: (qrcodeKey: string) => request<BiliQrPollPayload>('/api/bili/qr/poll', { method: 'POST', body: JSON.stringify({ qrcodeKey }) }),
  /* ---- 人物框选 + 角色参考图库 ---- */
  detectStatus: () => request<DetectStatusPayload>('/api/detect/status'),
  imageBoxes: (imageId: string) => request<ImageBoxesPayload>(`/api/images/${imageId}/boxes`),
  detectImageBoxes: (imageId: string) => request<ImageBoxesPayload>(`/api/images/${imageId}/boxes/detect`, { method: 'POST', body: '{}' }),
  saveImageBoxes: (imageId: string, payload: { width: number; height: number; boxes: DetectBox[] }) =>
    request<ImageBoxesPayload>(`/api/images/${imageId}/boxes`, { method: 'PUT', body: JSON.stringify(payload) }),
  /** 框选区域裁剪地址（用于参考图缩略图） */
  boxCropUrl: (imageId: string, box: { x: number; y: number; w: number; h: number }, width = 200) =>
    `/api/images/${imageId}/box-crop?x=${box.x}&y=${box.y}&w=${box.w}&h=${box.h}&width=${width}`,
  tagReferences: (tagId: string) => request<TagReferencesPayload>(`/api/tags/${tagId}/references`),
  addTagReference: (tagId: string, payload: { imageId: string; boxIndex?: number; box?: DetectBox; note?: string }) =>
    request<{ ok: true; added: boolean; total: number }>(`/api/tags/${tagId}/references`, { method: 'POST', body: JSON.stringify(payload) }),
  /** 移除参考条目；带上 box 可只删这一条（同一张图可能有多个框） */
  removeTagReference: (tagId: string, imageId: string, box?: { x: number; y: number; w: number; h: number }) =>
    request<{ ok: true; removed: number; total: number }>(
      `/api/tags/${tagId}/references/${imageId}${box ? `?x=${box.x}&y=${box.y}&w=${box.w}&h=${box.h}` : ''}`,
      { method: 'DELETE' },
    ),
  referenceCounts: () => request<{ counts: Record<string, number> }>('/api/references/summary'),
  /* ---- 相似图 / 关系网（CCIP 视觉指纹） ---- */
  /** 某张图的相似图（大图上滑时调） */
  similarImages: (imageId: string, limit = 24) =>
    request<SimilarImagesPayload>(`/api/images/${imageId}/similar?limit=${limit}`),
  embedStatus: () => request<EmbedStatusPayload>('/api/embed/status'),
  /** 关系网：节点 + 相似边 + 相似分组 */
  embedGraph: (edges = 600) => request<EmbedGraphPayload>(`/api/embed/graph?edges=${edges}`),
  /** 把还没建指纹的图片排进后台队列 */
  embedBackfill: (force = false) =>
    request<{ ok: true; queued: number; scanning: boolean }>('/api/embed/backfill', { method: 'POST', body: JSON.stringify({ force }) }),
  embedSetThreshold: (threshold: number) =>
    request<{ ok: true; threshold: number }>('/api/embed/threshold', { method: 'PATCH', body: JSON.stringify({ threshold }) }),
  /** 当前阈值的实测校准数据（同角色/异角色的分数分布） */
  embedCalibration: () => request<EmbedCalibrationPayload>('/api/embed/calibration'),
  /* ---- DeepSeek 余额 / 用量记账 ---- */
  deepseekSummary: () => request<DeepseekSummary>('/api/deepseek/summary'),
  deepseekRefresh: () => request<DeepseekSummary>('/api/deepseek/refresh', { method: 'POST', body: '{}' }),
  deepseekAddKey: (payload: { name: string; apiKey: string; accountName?: string; enabled?: boolean }) => request<{ probe: { ok: boolean; error?: string; currency?: string; total?: number }; summary: DeepseekSummary }>('/api/deepseek/keys', { method: 'POST', body: JSON.stringify(payload) }),
  deepseekUpdateKey: (id: string, patch: { name?: string; apiKey?: string; accountName?: string; platformKeyId?: string; enabled?: boolean }) => request<DeepseekSummary>(`/api/deepseek/keys/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  deepseekDeleteKey: (id: string) => request<DeepseekSummary>(`/api/deepseek/keys/${id}`, { method: 'DELETE' }),
  deepseekMerge: (payload: { keyIds: string[]; name?: string }) => request<DeepseekSummary>('/api/deepseek/merge', { method: 'POST', body: JSON.stringify(payload) }),
  deepseekSaveConfig: (patch: { enabled?: boolean; refreshSeconds?: number; platformToken?: string }) => request<DeepseekSummary>('/api/deepseek/config', { method: 'PUT', body: JSON.stringify(patch) }),
}

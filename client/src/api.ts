import type { AuditLog, ImageItem, TagItem } from './types'

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
export interface UploadAnalysisItem { sourceIndex: number; tempId: string | null; fileName: string; name: string; mimeType: string; size: number; width: number; height: number; tags: string[]; duplicate: boolean; duplicateName?: string; aiError?: string }
export interface UploadAnalysisPayload { items: UploadAnalysisItem[]; aiEnabled: boolean; aiModel: string }
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
  createTag: (name: string, color = '#a78bfa', r18 = false) => request<TagItem>('/api/tags', { method: 'POST', body: JSON.stringify({ name, color, r18 }) }),
  updateTag: (id: string, patch: { r18?: boolean; color?: string }) => request<TagItem>(`/api/tags/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  deleteTag: (id: string) => request<{ ok: true }>(`/api/tags/${id}`, { method: 'DELETE' }),
  addImagesToTag: (tagId: string, imageIds: string[]) => request<{ ok: true; added: number }>(`/api/tags/${tagId}/images`, { method: 'POST', body: JSON.stringify({ imageIds }) }),
  addTag: (imageId: string, name: string) => request<{ ok: true }>(`/api/images/${imageId}/tags`, { method: 'POST', body: JSON.stringify({ name }) }),
  removeTag: (imageId: string, tagId: string) => request<{ ok: true }>(`/api/images/${imageId}/tags/${tagId}`, { method: 'DELETE' }),
  logs: () => request<{ items: AuditLog[] }>('/api/audit-logs'),
  settings: () => request<Record<string, unknown>>('/api/settings'),
  saveSettings: (settings: Record<string, unknown>) => request<Record<string, unknown>>('/api/settings', { method: 'PATCH', body: JSON.stringify(settings) }),
  aiStatus: () => request<AiStatusPayload>('/api/ai/status'),
  aiDownload: (variant: string, mirror?: string) => request<{ ok: boolean; error?: string }>('/api/ai/download', { method: 'POST', body: JSON.stringify({ variant, mirror }) }),
  aiStart: () => request<{ ok: boolean; error?: string }>('/api/ai/start', { method: 'POST', body: '{}' }),
  aiStop: () => request<{ ok: boolean }>('/api/ai/stop', { method: 'POST', body: '{}' }),
  ffmpegStatus: () => request<FfmpegStatusPayload>('/api/ffmpeg/status'),
  ffmpegDownload: () => request<{ ok: boolean }>('/api/ffmpeg/download', { method: 'POST', body: '{}' }),
}

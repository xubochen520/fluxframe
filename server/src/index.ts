import 'dotenv/config'
import Fastify, { type FastifyReply, type FastifyRequest } from 'fastify'
import cors from '@fastify/cors'
import cookie from '@fastify/cookie'
import multipart from '@fastify/multipart'
import fastifyStatic from '@fastify/static'
import argon2 from 'argon2'
import { PrismaClient, type Prisma } from '@prisma/client'
import sharp from 'sharp'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import { mkdir, readFile, readdir, rename, rm, stat, statfs, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { z } from 'zod'
import { downloadAiStack, getAiStatus, startAiServer, stopAiServer } from './ai-manager.js'
import { registerParseApi, pickUpstreamHeaders } from './parse-server.js'

const prisma = new PrismaClient()
const app = Fastify({
  // 开发模式默认只输出警告和错误，避免逐请求日志刷屏；
  // 需要详细日志时设置环境变量 LOG_LEVEL=info（或 debug）。
  logger: { level: process.env.LOG_LEVEL || 'warn', transport: { target: 'pino-pretty' } },
  trustProxy: process.env.TRUST_PROXY === 'true',
})
const defaultPort = Number(process.env.PORT || 4310)
const fixedFluidSpeed = 3
const auditRetentionDays = 30
const maintenanceIntervalMs = 24 * 60 * 60 * 1000
let runtimePort = defaultPort
let storageDir = path.resolve(process.env.STORAGE_DIR || './storage')
const sessionCookie = 'fluxframe_session'
type PendingUpload = { userId: string; tempId: string; tempKey: string; fileName: string; mimeType: string; size: number; width?: number; height?: number; sha256: string; createdAt: number }
const pendingUploads = new Map<string, PendingUpload>()

const defaultSettings = {
  siteName: 'fluxframe',
  theme: 'Aurora',
  darkMode: true,
  webglEnabled: true,
  animationEnabled: true,
  fluidColors: ['#4f46e5', '#06b6d4', '#f472b6'],
  fluidSpeed: fixedFluidSpeed,
  uploadLimitMb: 50,
  port: defaultPort,
  storageDir: process.env.STORAGE_DIR || './storage',
  recycleRetentionDays: 30,
  aiEnabled: process.env.AI_ENABLED === 'true' || Boolean(process.env.AI_API_KEY),
  aiBaseUrl: process.env.AI_BASE_URL || 'http://127.0.0.1:8080/v1',
  aiModel: process.env.AI_MODEL || 'qwen2.5-vl-7b-instruct',
}

type AuthenticatedRequest = FastifyRequest & { user?: { id: string; username: string; role: 'ADMIN' | 'USER'; r18Mode: boolean } }

function hashToken(token: string) { return createHash('sha256').update(token).digest('hex') }
function safeStoragePath(key: string) {
  const resolved = path.resolve(storageDir, key)
  if (!resolved.startsWith(storageDir)) throw new Error('Invalid storage path')
  return resolved
}
function sourceInfo(request: FastifyRequest) {
  const forwarded = request.headers['x-forwarded-for']
  const ip = process.env.TRUST_PROXY === 'true' && forwarded
    ? String(forwarded).split(',')[0].trim()
    : request.ip.replace('::ffff:', '')
  const internal = ip === '127.0.0.1' || ip === '::1' || ip.startsWith('10.') || ip.startsWith('192.168.') || /^172\.(1[6-9]|2\d|3[01])\./.test(ip)
  return { ip, scope: internal ? 'INTERNAL' as const : 'EXTERNAL' as const }
}
async function recordAudit(request: FastifyRequest, action: string, userId?: string, target?: string) {
  const source = sourceInfo(request)
  await prisma.auditLog.create({ data: { action, target, userId, ip: source.ip, scope: source.scope, userAgent: request.headers['user-agent'] } })
}
/* 访问日志展示用色阶（总览与日志页共用；注意删除优先于标签，与历史行为一致） */
function auditTone(action: string) {
  if (action.includes('删除')) return 'red'
  if (action.includes('标签')) return 'violet'
  if (action.includes('登录') || action.includes('上传')) return 'green'
  if (action.includes('下载') || action.includes('提取')) return 'orange'
  return 'blue'
}
async function cleanupOldAuditLogs() {
  const cutoff = new Date(Date.now() - auditRetentionDays * 24 * 60 * 60 * 1000)
  const result = await prisma.auditLog.deleteMany({ where: { createdAt: { lt: cutoff } } })
  if (result.count) app.log.info(`Removed ${result.count} audit logs older than ${auditRetentionDays} days`)
}
async function cleanupExpiredPendingUploads() {
  const cutoff = Date.now() - 2 * 60 * 60 * 1000
  for (const [tempId, pending] of pendingUploads) {
    if (pending.createdAt >= cutoff) continue
    await rm(safeStoragePath(pending.tempKey), { force: true })
    pendingUploads.delete(tempId)
  }
}
async function ensureSpecialTags() {
  await prisma.tag.upsert({ where: { name: 'R-18' }, create: { name: 'R-18', color: '#ef4444', r18: true }, update: { color: '#ef4444', r18: true } })
}
function isTagR18(tag: { name: string; r18?: boolean }) { return tag.name === 'R-18' || tag.r18 === true }
function isImageR18(image: { tags?: Array<{ tag: { name: string; r18?: boolean } }> }) { return (image.tags || []).some((item) => isTagR18(item.tag)) }
const r18HiddenTagsFilter: Prisma.TagWhereInput = { NOT: { OR: [{ name: 'R-18' }, { r18: true }] } }
const r18HiddenImageFilter: Prisma.ImageWhereInput = { tags: { none: { OR: [{ tag: { name: 'R-18' } }, { tag: { r18: true } }] } } }
async function getAiConfig() {
  const rows = await prisma.systemSetting.findMany({ where: { key: { in: ['aiEnabled', 'aiBaseUrl', 'aiModel', 'aiApiKey'] } } })
  const saved = Object.fromEntries(rows.map((row) => [row.key, row.value]))
  const apiKey = String(saved.aiApiKey || process.env.AI_API_KEY || '').trim()
  const baseUrl = String(saved.aiBaseUrl || process.env.AI_BASE_URL || 'http://127.0.0.1:8080/v1').replace(/\/$/, '')
  const model = String(saved.aiModel || process.env.AI_MODEL || 'qwen2.5-vl-7b-instruct')
  const envEnabled = process.env.AI_ENABLED === 'true'
  const savedEnabled = saved.aiEnabled === true
  const explicitlyDisabled = saved.aiEnabled === false || process.env.AI_ENABLED === 'false'
  // 设置页开关（savedEnabled）、环境变量、配置了 API 密钥，三者任一启用即生效；仅环境变量可强制关闭
  return { enabled: !explicitlyDisabled && (envEnabled || savedEnabled || Boolean(apiKey)), apiKey, baseUrl, model }
}
// 解析 AI 输出为标签数组。
// 支持新格式 {"matched":[...],"new":[...]}（matched 必须是已有标签中的原词，否则丢弃，
// 防模型编造）；兼容旧格式 ["a","b"] / {"tags":[...]}。
function parseAiTags(value: unknown, existingTags: string[] = []): string[] {
  const content = Array.isArray(value) ? value.map((part: any) => typeof part === 'string' ? part : part?.text || '').join('') : String(value || '')
  const cleaned = content.replace(/```(?:json)?/gi, '').replace(/```/g, '').trim()
  let parsed: unknown = cleaned
  try { parsed = JSON.parse(cleaned) } catch {
    const arrayText = cleaned.match(/\[[\s\S]*\]/)?.[0]
    if (arrayText) { try { parsed = JSON.parse(arrayText) } catch { parsed = cleaned } }
  }
  const existing = new Set(existingTags)
  const norm = (tag: unknown) => String(tag).trim().replace(/^#/, '')
  const valid = (tag: string) => tag.length >= 1 && tag.length <= 40
  let matched: string[] = []
  let added: string[] = []
  if (Array.isArray(parsed)) {
    added = parsed.map(norm).filter(valid)
  } else if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    if (Array.isArray(obj.matched)) matched = obj.matched.map(norm).filter((tag) => existing.has(tag))
    const newList = Array.isArray(obj.new) ? obj.new : Array.isArray(obj.added) ? obj.added : Array.isArray(obj.tags) ? obj.tags : []
    added = newList.map(norm).filter(valid)
  } else {
    added = String(parsed).split(/[,，、\n]/).map(norm).filter(valid)
  }
  // matched 优先，new 去掉与已有标签重复的部分，整体去重
  const seen = new Set<string>()
  const result: string[] = []
  for (const tag of [...matched, ...added]) {
    if (!tag || seen.has(tag)) continue
    if (added.includes(tag) && existing.has(tag) && !matched.includes(tag)) continue // new 中重复已有标签
    seen.add(tag)
    result.push(tag)
  }
  return result.slice(0, 20)
}
// 识别提示词：优先读取可编辑的 prompts/tagging.txt（可用 AI_PROMPT_FILE 覆盖），找不到时用内置兜底。
let promptCache: { file: string; mtimeMs: number; content: string } | null = null
const FALLBACK_TAGGING_PROMPT = `你是图片管理系统的标签助手。请分析用户提供的图片，为它匹配和补充标签，用于归档与检索。
# 分析步骤（不要输出分析过程）
1. 先完整理解图片：主体、人物、场景、风格、动作、构图、色彩、情绪、作品类型，识别得越全越好。
2. 从「已有标签」中选出所有与图片内容匹配的标签——尽量选全，这些是首选标签。
3. 只有当已有标签确实无法覆盖的重要信息时，才提出新标签，最多 3 个。
# 输出要求
- 只输出一个 JSON 对象：{"matched": ["标签1"], "new": ["新标签1"]}，不要任何解释。
- matched 只能使用「已有标签」中的原词，不能改写、拼接或编造；new 不能与 matched 重复，也不能出现在「已有标签」中。
- 标签要具体、可检索：用「女仆装」「夕阳剪影」「俯拍」而不是「好看」「图片」这类泛词。
- 中文为主，2～8 个字；人名、作品名等专有名词保留原文。
- 如果图片包含裸露、性暗示等成人内容，必须额外加上 "R-18" 标签；普通内容绝对不能误加。
# 已有标签（按使用频率从高到低排列）
{{existing_tags}}
# 请分析这张图片`
async function loadTaggingPrompt(): Promise<string> {
  const candidates = [
    process.env.AI_PROMPT_FILE,
    path.join(process.cwd(), 'prompts', 'tagging.txt'),
    path.join(process.cwd(), '..', 'prompts', 'tagging.txt'),
  ].filter((p): p is string => Boolean(p))
  for (const file of candidates) {
    try {
      const st = await stat(file)
      if (!st.isFile()) continue
      if (promptCache?.file === file && promptCache.mtimeMs === st.mtimeMs) return promptCache.content
      const content = await readFile(file, 'utf8')
      promptCache = { file, mtimeMs: st.mtimeMs, content }
      return content
    } catch { /* 尝试下一个候选路径 */ }
  }
  return FALLBACK_TAGGING_PROMPT
}

// 发给本地视觉模型前统一处理图片：超大图缩到最长边 1280、非 JPEG/PNG 转 JPEG，
// 既避免 llama.cpp 不认 WebP/GIF 等格式，也节省上下文与推理时间。
async function prepareAiImage(buffer: Buffer): Promise<{ data: Buffer; mime: string }> {
  try {
    const meta = await sharp(buffer).metadata()
    const longest = Math.max(meta.width || 0, meta.height || 0)
    if (longest <= 1280 && (meta.format === 'jpeg' || meta.format === 'png')) return { data: buffer, mime: meta.format === 'jpeg' ? 'image/jpeg' : 'image/png' }
    const data = await sharp(buffer).rotate().resize({ width: 1280, height: 1280, fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 85 }).toBuffer()
    return { data, mime: 'image/jpeg' }
  } catch {
    return { data: buffer, mime: 'image/jpeg' }
  }
}

async function suggestTags(buffer: Buffer, mimeType: string, existingTags: string[]) {
  const config = await getAiConfig()
  if (!config.enabled) return { tags: [] as string[], enabled: false, model: config.model }
  const template = await loadTaggingPrompt()
  const prompt = template.replaceAll('{{existing_tags}}', existingTags.slice(0, 200).join('、') || '暂无')
  const { data: imageData, mime: imageMime } = await prepareAiImage(buffer)
  const imageBase64 = imageData.toString('base64')
  const useNativeOllama = /:11434$/.test(config.baseUrl) || config.baseUrl.endsWith('/api')
  if (useNativeOllama) {
    const ollamaBase = config.baseUrl.endsWith('/api') ? config.baseUrl : `${config.baseUrl}/api`
    const response = await fetch(`${ollamaBase}/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: config.model, stream: false, format: 'json', messages: [{ role: 'system', content: '你是图片标签助手。只输出 JSON 对象 {"matched":["已有标签"],"new":["新标签"]}，不要解释。' }, { role: 'user', content: prompt, images: [imageBase64] }] }), signal: AbortSignal.timeout(120_000) })
    if (!response.ok) throw new Error(`本地 Ollama 返回 ${response.status}`)
    const payload: any = await response.json()
    return { tags: parseAiTags(payload?.message?.content, existingTags), enabled: true, model: config.model }
  }
  // OpenAI 兼容路径：llama.cpp / vLLM / LM Studio / Ollama 兼容层均可用。
  // 先带 response_format 请求结构化 JSON；个别旧版 llama.cpp 不支持时按 400/422 重试一次去掉该字段。
  const buildBody = (structured: boolean) => ({
    model: config.model,
    temperature: 0.2,
    max_tokens: 512,
    ...(structured ? { response_format: { type: 'json_object' as const } } : {}),
    messages: [{ role: 'system', content: '你是图片标签助手。只输出 JSON 对象 {"matched":["已有标签"],"new":["新标签"]}，不要解释。' }, { role: 'user', content: [{ type: 'text', text: prompt }, { type: 'image_url', image_url: { url: `data:${imageMime};base64,${imageBase64}` } }] }],
  })
  const request = (structured: boolean) => fetch(`${config.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: { ...(config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {}), 'Content-Type': 'application/json' },
    body: JSON.stringify(buildBody(structured)),
    signal: AbortSignal.timeout(120_000),
  })
  let response = await request(true)
  if (response.status === 400 || response.status === 422) response = await request(false)
  if (!response.ok) throw new Error(`AI 标签服务返回 ${response.status}`)
  const payload: any = await response.json()
  return { tags: parseAiTags(payload?.choices?.[0]?.message?.content, existingTags), enabled: true, model: config.model }
}
async function currentUser(request: FastifyRequest) {
  const token = request.cookies[sessionCookie]
  if (!token) return null
  const session = await prisma.session.findUnique({ where: { tokenHash: hashToken(token) }, include: { user: true } })
  if (!session || session.expiresAt < new Date()) return null
  return session.user
}
async function requireUser(request: AuthenticatedRequest, reply: FastifyReply) {
  const user = await currentUser(request)
  if (!user) { await reply.code(401).send({ message: '请先登录' }); return null }
  request.user = user
  return user
}
async function requireAdmin(request: AuthenticatedRequest, reply: FastifyReply) {
  const user = await requireUser(request, reply)
  if (!user) return null
  if (user.role !== 'ADMIN') { await reply.code(403).send({ message: '需要管理员权限' }); return null }
  return user
}
function byteSize(value: bigint | number) {
  const bytes = Number(value)
  if (bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes > 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  if (bytes > 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024).toFixed(1)} KB`
}
async function directorySize(directory: string): Promise<number> {
  const entries = await readdir(directory, { withFileTypes: true }).catch(() => [])
  const sizes = await Promise.all(entries.map(async (entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return directorySize(entryPath)
    if (!entry.isFile()) return 0
    return (await stat(entryPath)).size
  }))
  return sizes.reduce((total, size) => total + size, 0)
}
async function storageUsage() {
  const [usedBytes, filesystem] = await Promise.all([
    directorySize(storageDir),
    statfs(storageDir).catch(() => null),
  ])
  const capacityBytes = filesystem ? Number(filesystem.blocks) * Number(filesystem.bsize) : 0
  const percent = capacityBytes > 0 ? Math.min(100, (usedBytes / capacityBytes) * 100) : 0
  return { usedBytes, capacityBytes, percent }
}
function imageDto(image: any) {
  const thumbnail = image.variants?.find((variant: any) => variant.width === 320)?.key
  return {
    id: image.id,
    name: image.name,
    mimeType: image.mimeType,
    url: `/api/images/${image.id}/file`,
    thumb: thumbnail ? `/api/images/${image.id}/variant/320` : `/api/images/${image.id}/file`,
    width: image.width || 0,
    height: image.height || 0,
    size: byteSize(image.size),
    views: image.viewCount,
    uploadedAt: image.uploadedAt.toISOString(),
    capturedAt: image.capturedAt?.toISOString().slice(0, 10) || image.uploadedAt.toISOString().slice(0, 10),
    tags: image.tags?.map((item: any) => item.tag.name) || [],
    tagIds: image.tags?.map((item: any) => item.tag.id) || [],
    r18: isImageR18(image),
    deletedAt: image.deletedAt?.toISOString(),
  }
}
async function imageWithRelations(id: string) {
  return prisma.image.findUnique({ where: { id }, include: { tags: { include: { tag: true } }, variants: true } })
}

app.setErrorHandler(async (error, _request, reply) => {
  if (error instanceof z.ZodError) return reply.code(400).send({ message: '请求参数不正确', issues: error.issues })
  app.log.error(error)
  const status = (error as any).statusCode || 500
  /* 开发/内网环境透出真实原因，便于排查；生产环境保持笼统提示 */
  const detail = process.env.NODE_ENV !== 'production' && status >= 500 ? `服务器内部错误：${(error as Error).message || String(error)}` : '服务器内部错误'
  return reply.code(status).send({ message: detail })
})
await app.register(cookie)
await app.register(cors, { origin: true, credentials: true })
await app.register(multipart, { limits: { fileSize: 2048 * 1024 * 1024, files: 50 } })
app.get('/api/health', async () => ({ ok: true, service: 'fluxframe-api', time: new Date().toISOString() }))

app.post('/api/auth/register', async (request, reply) => {
  const body = z.object({ username: z.string().trim().min(3).max(30), password: z.string().min(8) }).parse(request.body)
  const exists = await prisma.user.findUnique({ where: { username: body.username } })
  if (exists) return reply.code(409).send({ message: '用户名已存在' })
  const count = await prisma.user.count()
  const user = await prisma.user.create({ data: { username: body.username, passwordHash: await argon2.hash(body.password), role: count === 0 ? 'ADMIN' : 'USER' } })
  await recordAudit(request, '注册用户', user.id, user.username)
  return reply.code(201).send({ id: user.id, username: user.username, role: user.role, r18Mode: false })
})

app.post('/api/auth/login', async (request, reply) => {
  const body = z.object({ username: z.string(), password: z.string() }).parse(request.body)
  const user = await prisma.user.findUnique({ where: { username: body.username } })
  if (!user || !(await argon2.verify(user.passwordHash, body.password))) {
    await recordAudit(request, '登录失败', undefined, body.username)
    return reply.code(401).send({ message: '用户名或密码错误' })
  }
  const rawToken = randomBytes(32).toString('hex')
  await prisma.session.create({ data: { tokenHash: hashToken(rawToken), userId: user.id, expiresAt: new Date(Date.now() + 1000 * 60 * 60 * 24 * 30) } })
  reply.setCookie(sessionCookie, rawToken, { httpOnly: true, sameSite: 'lax', secure: process.env.NODE_ENV === 'production', path: '/', maxAge: 60 * 60 * 24 * 30 })
  await recordAudit(request, '用户登录', user.id)
  return { user: { id: user.id, username: user.username, role: user.role, r18Mode: user.r18Mode } }
})

app.post('/api/auth/logout', async (request, reply) => {
  const user = await currentUser(request)
  const token = request.cookies[sessionCookie]
  if (token) await prisma.session.deleteMany({ where: { tokenHash: hashToken(token) } })
  reply.clearCookie(sessionCookie, { path: '/' })
  if (user) await recordAudit(request, '退出登录', user.id)
  return reply.code(204).send()
})

app.get('/api/me', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  return { id: user.id, username: user.username, role: user.role, r18Mode: user.r18Mode }
})

/* APK 原生下载桥专用：系统 DownloadManager 不带 WebView Cookie，下载前由本页 JS
   以已登录 Cookie 换取一次会话令牌，再以明文 Cookie 头交给系统下载器直连内网拉取。
   令牌不出本机、不进 URL，仅在同一会话内使用。 */
app.get('/api/download/session', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  return { token: request.cookies[sessionCookie] || '' }
})

app.patch('/api/me/r18-mode', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ enabled: z.boolean() }).parse(request.body)
  const updated = await prisma.user.update({ where: { id: user.id }, data: { r18Mode: body.enabled } })
  await recordAudit(request, body.enabled ? '开启 R18 模式' : '关闭 R18 模式', user.id)
  return { r18Mode: updated.r18Mode }
})

app.patch('/api/auth/password', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ currentPassword: z.string(), newPassword: z.string().min(8) }).parse(request.body)
  if (!(await argon2.verify(user.passwordHash, body.currentPassword))) return reply.code(400).send({ message: '当前密码错误' })
  await prisma.user.update({ where: { id: user.id }, data: { passwordHash: await argon2.hash(body.newPassword) } })
  await recordAudit(request, '修改密码', user.id)
  return { ok: true }
})

app.get('/api/dashboard', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const liveWhere: Prisma.ImageWhereInput = user.r18Mode ? { deletedAt: null } : { deletedAt: null, ...r18HiddenImageFilter }
  const tagWhere: Prisma.TagWhereInput = user.r18Mode ? {} : r18HiddenTagsFilter
  const [imageCount, tagCount, userCount, storage, views, top, recent, logs, disk] = await Promise.all([
    prisma.image.count({ where: liveWhere }),
    prisma.tag.count({ where: tagWhere }),
    prisma.user.count(),
    prisma.image.aggregate({ where: liveWhere, _sum: { size: true } }),
    prisma.image.aggregate({ where: liveWhere, _sum: { viewCount: true } }),
    prisma.image.findMany({ where: liveWhere, orderBy: { viewCount: 'desc' }, take: 4, include: { tags: { include: { tag: true } }, variants: true } }),
    prisma.image.findMany({ where: liveWhere, orderBy: { uploadedAt: 'desc' }, take: 4, include: { tags: { include: { tag: true } }, variants: true } }),
    prisma.auditLog.findMany({ orderBy: { createdAt: 'desc' }, take: 5, include: { user: true } }),
    storageUsage(),
  ])
  return { stats: { imageCount, tagCount, userCount, totalViews: views._sum.viewCount || 0, storage: byteSize(disk.usedBytes), storageCapacity: byteSize(disk.capacityBytes), storagePercent: Number(disk.percent.toFixed(2)), databaseImageBytes: byteSize(storage._sum.size || 0n) }, top: top.map(imageDto), recent: recent.map(imageDto), logs: logs.map((log) => ({ id: log.id, action: log.action, target: log.target, user: log.user?.username || '系统', ip: log.ip, scope: log.scope === 'INTERNAL' ? '内网' : '外网', time: log.createdAt.toISOString(), tone: auditTone(log.action) })) }
})

app.get('/api/images', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const query = z.object({ search: z.string().optional(), tag: z.string().optional(), sort: z.enum(['views', 'newest', 'name']).default('views'), trash: z.preprocess((value) => value === true || value === 'true', z.boolean()).default(false) }).parse(request.query)
  const where: Prisma.ImageWhereInput = { deletedAt: query.trash ? { not: null } : null }
  if (query.search) where.OR = [{ name: { contains: query.search, mode: 'insensitive' } }, { tags: { some: { tag: { name: { contains: query.search, mode: 'insensitive' } } } } }]
  const tagsFilter: Prisma.ImageTagListRelationFilter = {}
  if (query.tag && query.tag !== '全部标签') tagsFilter.some = { tag: { name: query.tag } }
  if (!user.r18Mode) tagsFilter.none = { OR: [{ tag: { name: 'R-18' } }, { tag: { r18: true } }] }
  if (Object.keys(tagsFilter).length) where.tags = tagsFilter
  const orderBy: Prisma.ImageOrderByWithRelationInput = query.sort === 'newest' ? { uploadedAt: 'desc' } : query.sort === 'name' ? { name: 'asc' } : { viewCount: 'desc' }
  const images = await prisma.image.findMany({ where, orderBy, include: { tags: { include: { tag: true } }, variants: true } })
  return { items: images.map(imageDto) }
})

function normalizeUploadTags(tags: unknown) {
  if (!Array.isArray(tags)) return []
  return [...new Set(tags.map((tag: unknown) => String(tag).trim().replace(/^#/, '')).filter((tag: string) => tag.length >= 1 && tag.length <= 40))].slice(0, 30)
}
async function finalizePendingUpload(request: FastifyRequest, user: { id: string }, pending: PendingUpload, name: string, tagNames: string[]) {
  const id = randomUUID()
  const ext = path.extname(pending.fileName).toLowerCase() || '.bin'
  const originalKey = `originals/${id}${ext}`
  await rename(safeStoragePath(pending.tempKey), safeStoragePath(originalKey))
  const variants: Array<{ width: number; key: string }> = []
  const isImage = pending.mimeType.startsWith('image/')
  try {
    if (isImage) {
      for (const width of [320, 768, 1600]) {
        const key = `thumbnails/${id}-${width}.webp`
        await sharp(safeStoragePath(originalKey)).resize({ width, withoutEnlargement: true }).webp({ quality: 84 }).toFile(safeStoragePath(key))
        variants.push({ width, key })
      }
    }
    const image = await prisma.$transaction(async (tx) => {
      const created = await tx.image.create({ data: { id, name, originalKey, mimeType: pending.mimeType, size: BigInt(pending.size), width: pending.width, height: pending.height, sha256: pending.sha256, uploaderId: user.id, variants: { create: variants } } })
      const tags = []
      for (const tagName of tagNames) tags.push(await tx.tag.upsert({ where: { name: tagName }, create: { name: tagName }, update: {} }))
      if (tags.length) await tx.imageTag.createMany({ data: tags.map((tag) => ({ imageId: created.id, tagId: tag.id, addedById: user.id })), skipDuplicates: true })
      return tx.image.findUnique({ where: { id: created.id }, include: { tags: { include: { tag: true } }, variants: true } })
    })
    if (!image) throw new Error('图片入库失败')
    // 入库自带的标签（如自动「视频」标签）不单独记「添加标签」，避免刷屏；
    // 上传/提取行为由各调用方按媒体类型记审计（上传图片/上传视频/提取视频/提取封面）
    return imageDto(image)
  } catch (error) {
    /* 数据安全优先：一旦文件已 rename 进 originals（或缩略图已生成），出错时也不再删除 ——
       否则会出现「数据库记录已提交、原文件却被清理」的孤儿记录（文件缺失且去重锁死）。
       残留孤儿文件无害，可由 sha256 查重跳过；rename 未发生时的临时文件由上层清理。 */
    throw error
  }
}

app.post('/api/images/upload/analyze', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const configuredLimit = await prisma.systemSetting.findUnique({ where: { key: 'uploadLimitMb' } })
  const uploadLimitMb = typeof configuredLimit?.value === 'number' ? configuredLimit.value : defaultSettings.uploadLimitMb
  // 已有标签按使用频率从高到低排列，帮助模型优先选择常用标签
  const existingTags = await prisma.tag.findMany({ where: user.r18Mode ? undefined : r18HiddenTagsFilter, orderBy: { images: { _count: 'desc' } }, select: { name: true } })
  const aiConfig = await getAiConfig()
  const items: any[] = []
  let sourceIndex = 0
  for await (const part of request.parts()) {
    if (part.type !== 'file') continue
    const currentIndex = sourceIndex++
    const isImage = part.mimetype.startsWith('image/')
    const isVideo = part.mimetype.startsWith('video/')
    if (!isImage && !isVideo) continue
    const buffer = await part.toBuffer()
    if (buffer.byteLength > uploadLimitMb * 1024 * 1024) return reply.code(413).send({ message: `单个文件不能超过 ${uploadLimitMb} MB` })
    const metadata = isImage ? await sharp(buffer).metadata() : null
    const hash = createHash('sha256').update(buffer).digest('hex')
    const duplicate = await prisma.image.findUnique({ where: { sha256: hash }, select: { name: true } })
    if (duplicate) {
      items.push({ sourceIndex: currentIndex, tempId: null, fileName: part.filename, name: part.filename.replace(/\.[^.]+$/, ''), mimeType: part.mimetype, size: buffer.byteLength, width: metadata?.width || 0, height: metadata?.height || 0, tags: isVideo ? ['视频'] : [], duplicate: true, duplicateName: duplicate.name })
      continue
    }
    const tempId = randomUUID()
    const ext = path.extname(part.filename).toLowerCase() || '.bin'
    const tempKey = `temp/${user.id}/${tempId}${ext}`
    await mkdir(path.dirname(safeStoragePath(tempKey)), { recursive: true })
    await writeFile(safeStoragePath(tempKey), buffer)
    // 视频不做视觉识别，自动带「视频」标签（可在确认页删除）
    let tags: string[] = isVideo ? ['视频'] : []
    let aiError = ''
    if (isImage && aiConfig.enabled) {
      try { tags = (await suggestTags(buffer, part.mimetype, existingTags.map((tag) => tag.name))).tags } catch (error) { aiError = error instanceof Error ? error.message : 'AI 分析失败'; app.log.warn({ error }, `AI tag analysis failed for ${part.filename}`) }
    }
    pendingUploads.set(tempId, { userId: user.id, tempId, tempKey, fileName: part.filename, mimeType: part.mimetype, size: buffer.byteLength, width: metadata?.width, height: metadata?.height, sha256: hash, createdAt: Date.now() })
    items.push({ sourceIndex: currentIndex, tempId, fileName: part.filename, name: part.filename.replace(/\.[^.]+$/, ''), mimeType: part.mimetype, size: buffer.byteLength, width: metadata?.width || 0, height: metadata?.height || 0, tags, duplicate: false, aiError })
  }
  await recordAudit(request, '分析待上传媒体', user.id, `${items.length} 个文件`)
  return { items, aiEnabled: aiConfig.enabled, aiModel: aiConfig.model }
})

app.post('/api/images/upload/complete', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ items: z.array(z.object({ tempId: z.string().uuid(), name: z.string().trim().min(1).max(200), tags: z.array(z.string()).max(30).default([]) })).min(1).max(50) }).parse(request.body)
  const pending = body.items.map((item) => ({ item, upload: pendingUploads.get(item.tempId) }))
  if (pending.some(({ upload }) => !upload || upload.userId !== user.id)) return reply.code(400).send({ message: '待上传文件已过期，请重新选择' })
  const hiddenTagNames = new Set(user.r18Mode ? [] : (await prisma.tag.findMany({ where: { OR: [{ name: 'R-18' }, { r18: true }] }, select: { name: true } })).map((tag) => tag.name))
  const saved: any[] = []
  for (const { item, upload } of pending) {
    if (!upload) continue
    const duplicate = await prisma.image.findUnique({ where: { sha256: upload.sha256 } })
    if (duplicate) { await rm(safeStoragePath(upload.tempKey), { force: true }); pendingUploads.delete(upload.tempId); continue }
    const tags = user.r18Mode ? item.tags : item.tags.filter((tag: string) => !hiddenTagNames.has(tag))
    const image = await finalizePendingUpload(request, user, upload, item.name, normalizeUploadTags(tags))
    saved.push(image)
    pendingUploads.delete(upload.tempId)
  }
  /* 按媒体类型逐条记审计，访问日志可区分「上传图片」/「上传视频」 */
  for (const item of saved) {
    const isVideo = String(item.mimeType || '').startsWith('video/')
    await recordAudit(request, isVideo ? '上传视频' : '上传图片', user.id, item.name)
  }
  return reply.code(201).send({ items: saved })
})

app.delete('/api/images/upload/pending/:tempId', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const tempId = (request.params as { tempId: string }).tempId
  const pending = pendingUploads.get(tempId)
  if (!pending || pending.userId !== user.id) return reply.code(404).send({ message: '待上传文件不存在或已过期' })
  await rm(safeStoragePath(pending.tempKey), { force: true })
  pendingUploads.delete(tempId)
  return { ok: true }
})

app.post('/api/images/upload', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const saved: any[] = []
  const configuredLimit = await prisma.systemSetting.findUnique({ where: { key: 'uploadLimitMb' } })
  const uploadLimitMb = typeof configuredLimit?.value === 'number' ? configuredLimit.value : defaultSettings.uploadLimitMb
  for await (const part of request.parts()) {
    if (part.type !== 'file') continue
    const isImage = part.mimetype.startsWith('image/')
    const isVideo = part.mimetype.startsWith('video/')
    if (!isImage && !isVideo) continue
    const buffer = await part.toBuffer()
    if (buffer.byteLength > uploadLimitMb * 1024 * 1024) return reply.code(413).send({ message: `单个文件不能超过 ${uploadLimitMb} MB` })
    const metadata = isImage ? await sharp(buffer).metadata() : null
    const hash = createHash('sha256').update(buffer).digest('hex')
    if (await prisma.image.findUnique({ where: { sha256: hash } })) continue
    const id = randomUUID()
    const ext = path.extname(part.filename).toLowerCase() || '.bin'
    const originalKey = `originals/${id}${ext}`
    await writeFile(safeStoragePath(originalKey), buffer)
    const variants: Array<{ width: number; key: string }> = []
    if (isImage) {
      for (const width of [320, 768, 1600]) {
        const key = `thumbnails/${id}-${width}.webp`
        await sharp(buffer).resize({ width, withoutEnlargement: true }).webp({ quality: 84 }).toFile(safeStoragePath(key))
        variants.push({ width, key })
      }
    }
    const image = await prisma.$transaction(async (tx) => {
      const created = await tx.image.create({ data: { id, name: part.filename.replace(/\.[^.]+$/, ''), originalKey, mimeType: part.mimetype, size: BigInt(buffer.byteLength), width: metadata?.width, height: metadata?.height, sha256: hash, uploaderId: user.id, variants: { create: variants } }, include: { tags: { include: { tag: true } }, variants: true } })
      if (isVideo) {
        const tag = await tx.tag.upsert({ where: { name: '视频' }, create: { name: '视频' }, update: {} })
        await tx.imageTag.createMany({ data: [{ imageId: created.id, tagId: tag.id, addedById: user.id }], skipDuplicates: true })
      }
      return tx.image.findUnique({ where: { id: created.id }, include: { tags: { include: { tag: true } }, variants: true } })
    })
    if (image) saved.push(imageDto(image))
  }
  /* 按媒体类型逐条记审计（兼容旧客户端直传路径） */
  for (const item of saved) {
    const isVideo = String(item.mimeType || '').startsWith('video/')
    await recordAudit(request, isVideo ? '上传视频' : '上传图片', user.id, item.name)
  }
  return reply.code(201).send({ items: saved })
})

app.post('/api/images/:id/view', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const r18Check = await prisma.imageTag.findFirst({ where: { imageId: id, OR: [{ tag: { name: 'R-18' } }, { tag: { r18: true } }] } })
  if (!user.r18Mode && r18Check) return reply.code(404).send({ message: '图片不存在' })
  const image = await prisma.image.update({ where: { id }, data: { viewCount: { increment: 1 }, lastViewed: new Date() } }).catch(() => null)
  if (!image) return reply.code(404).send({ message: '图片不存在' })
  /* 视频与图片分开记账：查看视频 / 查看图片 */
  await recordAudit(request, String(image.mimeType).startsWith('video/') ? '查看视频' : '查看图片', user.id, image.name)
  return { ok: true, views: image.viewCount }
})

async function sendImageFile(request: AuthenticatedRequest, reply: FastifyReply, variant?: number) {
  const user = await requireUser(request, reply)
  if (!user) return
  const image = await imageWithRelations((request.params as { id: string }).id)
  if (!image || image.deletedAt) return reply.code(404).send({ message: '图片不存在' })
  if (!user.r18Mode && isImageR18(image)) return reply.code(404).send({ message: '图片不存在' })
  const key = variant ? image.variants.find((item) => item.width === variant)?.key : image.originalKey
  if (!key) return reply.code(404).send({ message: '图片文件不存在' })
  const filePath = safeStoragePath(key)
  let fileInfo
  try {
    fileInfo = await stat(filePath)
  } catch {
    /* 记录存在但文件缺失（可能被外部清理）→ 友好提示而非 500 */
    return reply.code(404).send({ message: '媒体文件缺失（可能在入库后被外部删除），请重新上传或在「视频提取」中重新保存' })
  }
  const contentType = variant ? 'image/webp' : image.mimeType
  const rangeHeader = request.headers.range
  if (typeof rangeHeader === 'string') {
    const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader)
    if (!match) return reply.code(416).header('Content-Range', `bytes */${fileInfo.size}`).send()
    const start = match[1] ? Number(match[1]) : Math.max(0, fileInfo.size - Number(match[2] || 0))
    const requestedEnd = match[2] ? Number(match[2]) : fileInfo.size - 1
    const end = Math.min(requestedEnd, fileInfo.size - 1)
    if (start < 0 || start > end || start >= fileInfo.size) return reply.code(416).header('Content-Range', `bytes */${fileInfo.size}`).send()
    return reply.code(206).type(contentType).headers({ 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${fileInfo.size}`, 'Content-Length': end - start + 1, 'Cache-Control': 'private, max-age=3600' }).send(createReadStream(filePath, { start, end }))
  }
  return reply.type(contentType).headers({ 'Accept-Ranges': 'bytes', 'Content-Length': fileInfo.size, 'Cache-Control': 'private, max-age=3600' }).send(createReadStream(filePath))
}
app.get('/api/images/:id/file', async (request, reply) => sendImageFile(request as AuthenticatedRequest, reply))
app.get('/api/images/:id/variant/:width', async (request, reply) => sendImageFile(request as AuthenticatedRequest, reply, Number((request.params as { width: string }).width)))

/* 下载原文件（浏览器 / APK 原生下载器共用）：带附件响应头并记「下载图片 / 下载视频」审计。
   与 /file（播放/内联展示）分开，避免把看图、看视频的请求误记为下载。 */
app.get('/api/images/:id/download', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const image = await imageWithRelations((request.params as { id: string }).id)
  if (!image || image.deletedAt) return reply.code(404).send({ message: '图片不存在' })
  if (!user.r18Mode && isImageR18(image)) return reply.code(404).send({ message: '图片不存在' })
  const filePath = safeStoragePath(image.originalKey)
  let fileInfo
  try {
    fileInfo = await stat(filePath)
  } catch {
    return reply.code(404).send({ message: '媒体文件缺失（可能在入库后被外部删除），请重新上传或在「视频提取」中重新保存' })
  }
  const base = image.name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^[.\s]+|[.\s]+$/g, '') || '媒体文件'
  const filename = `${base}${path.extname(image.originalKey).toLowerCase()}`
  await recordAudit(request, String(image.mimeType).startsWith('video/') ? '下载视频' : '下载图片', user.id, filename)
  return reply.type(image.mimeType).headers({
    'Content-Length': fileInfo.size,
    'Content-Disposition': `attachment; filename="download"; filename*=UTF-8''${encodeURIComponent(filename)}`,
    'Cache-Control': 'private, max-age=3600',
  }).send(createReadStream(filePath))
})

app.delete('/api/images/:id', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const image = await prisma.image.update({ where: { id }, data: { deletedAt: new Date(), deletedById: user.id } }).catch(() => null)
  if (!image) return reply.code(404).send({ message: '图片不存在' })
  await recordAudit(request, '删除图片', user.id, image.name)
  return { ok: true }
})
app.post('/api/images/:id/restore', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const image = await prisma.image.update({ where: { id }, data: { deletedAt: null, deletedById: null } }).catch(() => null)
  if (!image) return reply.code(404).send({ message: '图片不存在' })
  await recordAudit(request, '恢复图片', user.id, image.name)
  return { ok: true }
})
app.delete('/api/images/:id/permanent', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const image = await imageWithRelations(id)
  if (!image) return reply.code(404).send({ message: '图片不存在' })
  await prisma.image.delete({ where: { id } })
  await Promise.all([rm(safeStoragePath(image.originalKey), { force: true }), ...image.variants.map((variant) => rm(safeStoragePath(variant.key), { force: true }))])
  await recordAudit(request, '永久删除图片', user.id, image.name)
  return { ok: true }
})

/* 修改文件（图片/视频）名称：只改展示名，物理文件与缩略图不受影响。
   与上传规则一致，入库名不带扩展名 —— 输入带扩展名时（与真实文件或常见媒体扩展名一致）
   自动剥掉，避免下载时出现「xx.jpg.jpg」双扩展名。 */
app.patch('/api/images/:id', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ name: z.string().trim().min(1).max(200) }).parse(request.body)
  const id = (request.params as { id: string }).id
  const image = await imageWithRelations(id)
  if (!image || image.deletedAt) return reply.code(404).send({ message: '图片不存在' })
  if (!user.r18Mode && isImageR18(image)) return reply.code(404).send({ message: '图片不存在' })
  const fileExt = path.extname(image.originalKey).toLowerCase()
  const commonExts = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.heic', '.heif', '.mp4', '.m4v', '.mov', '.webm', '.mkv', '.avi', '.mpg', '.mpeg', '.ts', '.3gp'])
  let clean = body.name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/^[.\s]+|[.\s]+$/g, '')
  const lower = clean.toLowerCase()
  if (fileExt && lower.endsWith(fileExt)) clean = clean.slice(0, -fileExt.length)
  else {
    const trailing = /\.([a-z0-9]{1,5})$/i.exec(clean)?.[0].toLowerCase() || ''
    if (trailing && commonExts.has(trailing)) clean = clean.slice(0, -trailing.length)
  }
  const name = clean.replace(/[.\s]+$/, '')
  if (!name) return reply.code(400).send({ message: '文件名不能为空' })
  const updated = await prisma.image.update({ where: { id }, data: { name } }).catch(() => null)
  if (!updated) return reply.code(404).send({ message: '图片不存在' })
  await recordAudit(request, '修改名称', user.id, `${image.name} → ${name}`)
  return { ok: true, name: updated.name }
})

app.get('/api/tags', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const tags = await prisma.tag.findMany({ where: user.r18Mode ? undefined : r18HiddenTagsFilter, orderBy: { name: 'asc' }, include: { _count: { select: { images: true } } } })
  return { items: tags.map((tag) => ({ id: tag.id, name: tag.name, color: tag.color, r18: isTagR18(tag), count: tag._count.images })) }
})
app.post('/api/tags', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ name: z.string().trim().min(1).max(40), color: z.string().default('#a78bfa'), r18: z.boolean().default(false) }).parse(request.body)
  const tagData = body.name.toUpperCase() === 'R-18' ? { name: 'R-18', color: '#ef4444', r18: true } : body
  const tag = await prisma.tag.create({ data: tagData }).catch(() => null)
  if (!tag) return reply.code(409).send({ message: '标签已存在' })
  await recordAudit(request, '创建标签', user.id, tag.name)
  return reply.code(201).send({ id: tag.id, name: tag.name, color: tag.color, r18: isTagR18(tag), count: 0 })
})
app.patch('/api/tags/:id', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ r18: z.boolean().optional(), color: z.string().optional() }).parse(request.body)
  const tag = await prisma.tag.findUnique({ where: { id: (request.params as { id: string }).id } })
  if (!tag) return reply.code(404).send({ message: '标签不存在' })
  const data: { r18?: boolean; color?: string } = {}
  if (body.color !== undefined) data.color = body.color
  if (body.r18 !== undefined && !isTagR18(tag)) data.r18 = body.r18
  const updated = await prisma.tag.update({ where: { id: tag.id }, data }).catch(() => null)
  if (!updated) return reply.code(409).send({ message: '标签更新失败' })
  await recordAudit(request, '更新标签', user.id, `${updated.name}${data.r18 !== undefined ? (data.r18 ? ' → R18' : ' → 普通') : ''}`)
  const count = await prisma.imageTag.count({ where: { tagId: updated.id } })
  return { id: updated.id, name: updated.name, color: updated.color, r18: isTagR18(updated), count }
})
app.delete('/api/tags/:id', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const tagId = (request.params as { id: string }).id
  const existingTag = await prisma.tag.findUnique({ where: { id: tagId } })
  if (existingTag?.name === 'R-18') return reply.code(400).send({ message: 'R-18 是系统保留标签，不能删除' })
  const tag = await prisma.tag.delete({ where: { id: tagId } }).catch(() => null)
  if (!tag) return reply.code(404).send({ message: '标签不存在' })
  await recordAudit(request, '删除标签', user.id, tag.name)
  return { ok: true }
})
app.post('/api/tags/:id/images', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const tagId = (request.params as { id: string }).id
  const body = z.object({ imageIds: z.array(z.string().min(1)).min(1).max(500) }).parse(request.body)
  const tag = await prisma.tag.findUnique({ where: { id: tagId } })
  if (!tag) return reply.code(404).send({ message: '标签不存在' })
  if (!user.r18Mode && isTagR18(tag)) return reply.code(403).send({ message: '需要开启 R18 模式才能添加该标签' })
  const images = await prisma.image.findMany({ where: { id: { in: body.imageIds }, deletedAt: null }, select: { id: true, name: true } })
  const existing = await prisma.imageTag.findMany({ where: { tagId, imageId: { in: images.map((image) => image.id) } }, select: { imageId: true } })
  const existingIds = new Set(existing.map((item) => item.imageId))
  const pending = images.filter((image) => !existingIds.has(image.id))
  if (pending.length) await prisma.imageTag.createMany({ data: pending.map((image) => ({ imageId: image.id, tagId, addedById: user.id })), skipDuplicates: true })
  await recordAudit(request, '批量添加标签', user.id, `${pending.length} 张图片 → ${tag.name}`)
  return { ok: true, added: pending.length }
})
app.post('/api/images/:id/tags', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const imageId = (request.params as { id: string }).id
  const body = z.object({ tagId: z.string().optional(), name: z.string().optional() }).refine((value) => value.tagId || value.name).parse(request.body)
  const tag = body.tagId ? await prisma.tag.findUnique({ where: { id: body.tagId } }) : await prisma.tag.findUnique({ where: { name: body.name } })
  if (!tag) return reply.code(404).send({ message: '标签不存在' })
  if (!user.r18Mode && isTagR18(tag)) return reply.code(403).send({ message: '需要开启 R18 模式才能添加该标签' })
  await prisma.imageTag.create({ data: { imageId, tagId: tag.id, addedById: user.id } }).catch(() => null)
  const image = await prisma.image.findUnique({ where: { id: imageId } })
  await recordAudit(request, '添加标签', user.id, `${image?.name || imageId} → ${tag.name}`)
  return { ok: true }
})
app.delete('/api/images/:id/tags/:tagId', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const params = request.params as { id: string; tagId: string }
  await prisma.imageTag.delete({ where: { imageId_tagId: { imageId: params.id, tagId: params.tagId } } }).catch(() => null)
  await recordAudit(request, '删除标签', user.id, `${params.id} → ${params.tagId}`)
  return { ok: true }
})

app.get('/api/audit-logs', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const logs = await prisma.auditLog.findMany({ orderBy: { createdAt: 'desc' }, take: 500, include: { user: true } })
  return { items: logs.map((log) => ({ id: log.id, action: log.action, target: log.target || '', user: log.user?.username || '系统', ip: log.ip, scope: log.scope === 'INTERNAL' ? '内网' : '外网', time: log.createdAt.toISOString(), tone: auditTone(log.action) })) }
})

app.get('/api/settings', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const rows = await prisma.systemSetting.findMany()
  const settings: Record<string, unknown> = { ...defaultSettings, ...Object.fromEntries(rows.map((row) => [row.key, row.value])), fluidSpeed: fixedFluidSpeed }
  delete settings.aiApiKey
  const aiConfig = await getAiConfig()
  return { ...settings, aiEnabled: aiConfig.enabled, aiBaseUrl: aiConfig.baseUrl, aiModel: aiConfig.model, aiConfigured: Boolean(aiConfig.apiKey) }
})
app.patch('/api/settings', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const body: Record<string, unknown> = { ...z.record(z.unknown()).parse(request.body), fluidSpeed: fixedFluidSpeed }
  if (typeof body.aiApiKey === 'string' && !body.aiApiKey.trim()) delete body.aiApiKey
  if (body.port !== undefined && (!Number.isInteger(Number(body.port)) || Number(body.port) < 1024 || Number(body.port) > 65535)) return reply.code(400).send({ message: '端口必须在 1024-65535 之间' })
  if (body.uploadLimitMb !== undefined && (!Number.isFinite(Number(body.uploadLimitMb)) || Number(body.uploadLimitMb) < 1 || Number(body.uploadLimitMb) > 2048)) return reply.code(400).send({ message: '上传限制必须在 1-2048 MB 之间' })
  if (body.recycleRetentionDays !== undefined && (!Number.isFinite(Number(body.recycleRetentionDays)) || Number(body.recycleRetentionDays) < 0 || Number(body.recycleRetentionDays) > 3650)) return reply.code(400).send({ message: '回收站保留天数不正确' })
  for (const [key, value] of Object.entries(body)) await prisma.systemSetting.upsert({ where: { key }, create: { key, value: value as Prisma.InputJsonValue }, update: { value: value as Prisma.InputJsonValue } })
  await recordAudit(request, '修改系统设置', user.id, Object.keys(body).join(', '))
  const safeBody = { ...body }
  delete safeBody.aiApiKey
  const aiConfig = await getAiConfig()
  return { ...defaultSettings, ...safeBody, fluidSpeed: fixedFluidSpeed, aiEnabled: aiConfig.enabled, aiBaseUrl: aiConfig.baseUrl, aiModel: aiConfig.model, aiConfigured: Boolean(aiConfig.apiKey) }
})

// ---------- AI 引擎自动管理（llama.cpp 检测 / 下载 / 启动） ----------
app.get('/api/ai/status', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  return await getAiStatus()
})
app.post('/api/ai/download', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ variant: z.enum(['3b', '7b']).default('7b'), mirror: z.string().optional() }).parse(request.body)
  // 后台执行下载，立即返回；进度通过 GET /api/ai/status 轮询
  void downloadAiStack(body.variant, body.mirror).then((result) => { if (!result.ok) app.log.warn(`AI 引擎下载失败：${result.error}`) })
  await recordAudit(request, 'AI 引擎下载', user.id, `${body.variant} 开始`)
  return { ok: true }
})
app.post('/api/ai/start', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const result = await startAiServer()
  if (result.ok) await recordAudit(request, 'AI 引擎启动', user.id, 'llama.cpp')
  return result
})
app.post('/api/ai/stop', async (request, reply) => {
  const user = await requireAdmin(request as AuthenticatedRequest, reply)
  if (!user) return
  const result = await stopAiServer()
  await recordAudit(request, 'AI 引擎停止', user.id, 'llama.cpp')
  return result
})

// ---------- 视频解析 → 服务端导入（后台保存到图片库：直连片源、流式写盘、查重、入库） ----------
type ParseImportJob = {
  id: string
  userId: string
  kind: 'video' | 'cover'
  status: 'working' | 'done' | 'error'
  progress: number | null // 0..1；null 表示不确定进度
  message: string
  items: any[]
  duplicate: boolean
  error: string
  cancel: boolean
}
const parseImportJobs = new Map<string, ParseImportJob>()

function fmtBytes(n: number) {
  if (n <= 0) return '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`
  return `${(n / 1073741824).toFixed(2)} GB`
}

async function runParseImportJob(job: ParseImportJob, request: FastifyRequest, input: { url: string; ref?: string; name: string; platTag?: string }) {
  let tempKey = ''
  try {
    /* 1. 直连上游（UA/Referer 防盗链头），45s 连接超时（CDN 对同 URL 并发会排队） */
    const headers = pickUpstreamHeaders(input.url, input.ref || '')
    const controller = new AbortController()
    const connectTimer = setTimeout(() => controller.abort(), 45_000)
    let res: Response
    try {
      res = await fetch(input.url, { headers, redirect: 'follow', signal: controller.signal })
    } catch {
      clearTimeout(connectTimer)
      throw new Error('片源响应超时（平台 CDN 排队或网络问题），请稍后重试')
    }
    clearTimeout(connectTimer)
    if (!res.ok) throw new Error(`片源返回 HTTP ${res.status}`)
    const ct = res.headers.get('content-type') || ''
    if (job.kind === 'video' && !/video|octet-stream/i.test(ct)) throw new Error(`片源不是可识别的视频格式（${ct}）`)
    if (job.kind === 'cover' && !/^image\//i.test(ct)) throw new Error('封面不是可识别的图片格式')
    if (!res.body) throw new Error('片源流不可用')

    /* 2. 流式写临时文件（背压感知 + sha256 + 真实进度上报） */
    const total = Number(res.headers.get('content-length')) || 0
    const ext = job.kind === 'video'
      ? (/\bwebm\b/i.test(ct) ? '.webm' : '.mp4')
      : (/\bwebp\b/i.test(ct) ? '.webp' : /\bpng\b/i.test(ct) ? '.png' : '.jpg')
    tempKey = `temp/parse-import/${job.id}${ext}`
    await mkdir(path.dirname(safeStoragePath(tempKey)), { recursive: true })
    const ws = createWriteStream(safeStoragePath(tempKey))
    const hash = createHash('sha256')
    const reader = res.body.getReader()
    let received = 0
    let lastTick = 0
    let idleSince = Date.now()
    const watchdog = setInterval(() => {
      if (Date.now() - idleSince > 90_000) { try { void reader.cancel().catch(() => undefined) } catch { /* 已结束 */ } }
    }, 15_000)
    try {
      for (;;) {
        if (job.cancel) throw new Error('已取消')
        const { done, value } = await reader.read()
        if (done) break
        if (value && value.length) {
          idleSince = Date.now()
          received += value.length
          hash.update(value)
          if (!ws.write(value)) await new Promise<void>((r) => ws.once('drain', r))
          const now = Date.now()
          if (now - lastTick > 200) {
            lastTick = now
            job.progress = total ? Math.min(1, received / total) : null
            job.message = `下载片源中 ${fmtBytes(received)}${total ? ' / ' + fmtBytes(total) : ''}`
          }
        }
      }
      ws.end()
      await new Promise<void>((resolve, reject) => {
        ws.once('error', reject)
        ws.once('finish', resolve)
      })
    } finally {
      clearInterval(watchdog)
    }
    if (job.cancel) throw new Error('已取消')
    const sha = hash.digest('hex')
    const size = received

    /* 3. 大小限制 / 查重 / 入库（复用正式上传收尾：缩略图、标签、审计） */
    const limitRow = await prisma.systemSetting.findUnique({ where: { key: 'uploadLimitMb' } })
    const limitMb = typeof limitRow?.value === 'number' ? limitRow.value : defaultSettings.uploadLimitMb
    if (size > limitMb * 1024 * 1024) throw new Error(`文件 ${fmtBytes(size)} 超过上传上限 ${limitMb} MB，可在「系统设置→服务配置」调大后重试`)
    const dup = await prisma.image.findUnique({ where: { sha256: sha }, select: { id: true, name: true, originalKey: true } })
    if (dup) {
      /* 自愈：记录存在但原文件缺失（曾被外部删除等）→ 清掉孤儿记录后重新入库，
         避免「去重锁死」导致内容永远无法保存 */
      let dupMissing = false
      try {
        await stat(safeStoragePath(dup.originalKey))
      } catch {
        dupMissing = true
      }
      if (dupMissing) {
        await prisma.image.delete({ where: { id: dup.id } }).catch(() => undefined)
        app.log.warn(`[parse-import] 清理孤儿记录 ${dup.id}（${dup.name}）：sha256 命中但原文件缺失`)
      } else {
        job.duplicate = true
        await rm(safeStoragePath(tempKey), { force: true })
        tempKey = ''
      }
    }
    if (!job.duplicate && tempKey) {
      const imageMeta = job.kind === 'cover' ? await sharp(safeStoragePath(tempKey)).metadata().catch(() => null) : null
      const fileName = `${input.name.replace(/\.[^.]+$/, '')}${ext}`
      const pending: PendingUpload = {
        userId: job.userId, tempId: job.id, tempKey,
        fileName, mimeType: ct || (job.kind === 'video' ? 'video/mp4' : 'image/jpeg'),
        size, width: imageMeta?.width, height: imageMeta?.height,
        sha256: sha, createdAt: Date.now(),
      }
      const tags = normalizeUploadTags([...(job.kind === 'video' ? ['视频'] : []), ...(input.platTag ? [input.platTag] : [])])
      const dto = await finalizePendingUpload(request, { id: job.userId }, pending, input.name, tags)
      job.items.push(dto)
      tempKey = ''
      /* 「视频提取」入库审计：视频 → 提取视频，封面 → 提取封面 */
      await recordAudit(request, job.kind === 'video' ? '提取视频' : '提取封面', job.userId, dto.name)
    }
    job.status = 'done'
    job.progress = 1
    job.message = job.duplicate ? '已在图片库中（内容相同，自动去重）' : `已保存 ${job.items.length} 个文件到图片库 ✓`
  } catch (e: any) {
    if (job.cancel) {
      if (tempKey) await rm(safeStoragePath(tempKey), { force: true }).catch(() => undefined)
      parseImportJobs.delete(job.id)
      return
    }
    job.status = 'error'
    job.error = e?.message || '导入失败，请稍后重试'
    job.message = job.error
    if (tempKey) await rm(safeStoragePath(tempKey), { force: true }).catch(() => undefined)
  }
}

app.post('/api/parse/import', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({
    url: z.string().url(),
    ref: z.string().max(500).optional(),
    name: z.string().trim().min(1).max(200),
    kind: z.enum(['video', 'cover']),
    platTag: z.string().trim().max(40).optional(),
  }).parse(request.body)
  if (!/^https?:\/\//i.test(body.url)) return reply.code(400).send({ message: '片源地址无效' })
  const job: ParseImportJob = {
    id: randomUUID(), userId: user.id, kind: body.kind,
    status: 'working', progress: null, message: '准备导入…',
    items: [], duplicate: false, error: '', cancel: false,
  }
  parseImportJobs.set(job.id, job)
  void runParseImportJob(job, request as FastifyRequest, body)
  const cleanup = setTimeout(() => { parseImportJobs.delete(job.id) }, 30 * 60_000)
  cleanup.unref()
  return reply.code(201).send({ id: job.id })
})

app.get('/api/parse/import/:id', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const job = parseImportJobs.get(id)
  if (!job || job.userId !== user.id) return reply.code(404).send({ message: '导入任务不存在' })
  return { id: job.id, status: job.status, progress: job.progress, message: job.message, items: job.items, duplicate: job.duplicate }
})

app.delete('/api/parse/import/:id', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const id = (request.params as { id: string }).id
  const job = parseImportJobs.get(id)
  if (job && job.userId === user.id) {
    if (job.status === 'working') job.cancel = true
    else parseImportJobs.delete(id)
  }
  return { ok: true }
})

// ---------- 视频解析引擎（纯享解析 PureParse）：/api/parse /api/stream /api/ping ----------
registerParseApi(app)

async function start() {
  await prisma.$connect()
  const savedPort = await prisma.systemSetting.findUnique({ where: { key: 'port' } })
  const savedStorage = await prisma.systemSetting.findUnique({ where: { key: 'storageDir' } })
  if (typeof savedPort?.value === 'number' && savedPort.value >= 1024 && savedPort.value <= 65535) runtimePort = savedPort.value
  if (typeof savedStorage?.value === 'string' && savedStorage.value.trim()) storageDir = path.resolve(savedStorage.value)
  await mkdir(path.join(storageDir, 'originals'), { recursive: true })
  await mkdir(path.join(storageDir, 'thumbnails'), { recursive: true })
  await mkdir(path.join(storageDir, 'trash'), { recursive: true })
  await rm(path.join(storageDir, 'temp'), { recursive: true, force: true })
  await mkdir(path.join(storageDir, 'temp'), { recursive: true })
  await cleanupOldAuditLogs()
  const maintenanceTimer = setInterval(() => { void cleanupOldAuditLogs(); void cleanupExpiredPendingUploads() }, maintenanceIntervalMs)
  maintenanceTimer.unref()
  await ensureSpecialTags()
  const admin = await prisma.user.findFirst({ where: { role: 'ADMIN' } })
  if (!admin) {
    const password = process.env.ADMIN_PASSWORD || 'admin123'
    await prisma.user.create({ data: { username: process.env.ADMIN_USERNAME || 'admin', passwordHash: await argon2.hash(password), role: 'ADMIN' } })
    app.log.warn(`Created initial administrator. Username: ${process.env.ADMIN_USERNAME || 'admin'}`)
  }
  // 生产模式：托管前端构建产物（client/dist），手机/内网直接访问 http://<IP>:<port> 即可
  const distDir = path.join(process.cwd(), '..', 'client', 'dist')
  try {
    const distStat = await stat(distDir)
    if (distStat.isDirectory()) {
      await app.register(fastifyStatic, { root: distDir, prefix: '/', setHeaders(reply, filePath) { if (filePath.endsWith('.html')) reply.header('Cache-Control', 'no-store') } })
      app.setNotFoundHandler((request, reply) => {
        if (request.method !== 'GET' || request.url.startsWith('/api') || request.url.startsWith('/assets')) return reply.code(404).send({ message: 'Not Found' })
        return reply.type('text/html').header('Cache-Control', 'no-store').send(createReadStream(path.join(distDir, 'index.html')))
      })
      app.log.warn(`Serving web app from ${distDir} (phone: http://<PC-IP>:${runtimePort})`)
    } else {
      app.log.warn('client/dist not found — skip static hosting (dev mode uses Vite on 5173)')
    }
  } catch {
    app.log.warn('client/dist not found — skip static hosting (dev mode uses Vite on 5173)')
  }
  await app.listen({ port: runtimePort, host: process.env.HOST || '0.0.0.0' })
  // AI 已启用且本机文件就绪时，自动拉起 llama.cpp 服务（幂等：已在运行则直接复用）
  if ((await getAiConfig()).enabled) {
    void startAiServer().then((result) => { if (!result.ok) app.log.warn(`AI 服务自动启动失败：${result.error}`) }).catch((error) => app.log.warn(`AI 服务自动启动异常：${error}`))
  }
}
start().catch(async (error) => { app.log.error(error); await prisma.$disconnect(); process.exit(1) })

import 'dotenv/config'
import Fastify, { type FastifyReply, type FastifyRequest } from 'fastify'
import cors from '@fastify/cors'
import cookie from '@fastify/cookie'
import multipart from '@fastify/multipart'
import argon2 from 'argon2'
import { PrismaClient, type Prisma } from '@prisma/client'
import sharp from 'sharp'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { mkdir, readdir, rename, rm, stat, statfs, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { z } from 'zod'

const prisma = new PrismaClient()
const app = Fastify({
  logger: { transport: { target: 'pino-pretty' } },
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
  aiBaseUrl: process.env.AI_BASE_URL || 'http://127.0.0.1:11434',
  aiModel: process.env.AI_MODEL || 'qwen2.5vl:7b',
}

type AuthenticatedRequest = FastifyRequest & { user?: { id: string; username: string; role: 'ADMIN' | 'USER' } }

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
  await prisma.tag.upsert({ where: { name: 'R-18' }, create: { name: 'R-18', color: '#ef4444' }, update: { color: '#ef4444' } })
}
async function getAiConfig() {
  const rows = await prisma.systemSetting.findMany({ where: { key: { in: ['aiEnabled', 'aiBaseUrl', 'aiModel', 'aiApiKey'] } } })
  const saved = Object.fromEntries(rows.map((row) => [row.key, row.value]))
  const apiKey = String(saved.aiApiKey || process.env.AI_API_KEY || '').trim()
  const baseUrl = String(saved.aiBaseUrl || process.env.AI_BASE_URL || 'http://127.0.0.1:11434').replace(/\/$/, '')
  const model = String(saved.aiModel || process.env.AI_MODEL || 'qwen2.5vl:7b')
  const envEnabled = process.env.AI_ENABLED === 'true'
  const explicitlyDisabled = saved.aiEnabled === false || process.env.AI_ENABLED === 'false'
  return { enabled: !explicitlyDisabled && (envEnabled || Boolean(apiKey)), apiKey, baseUrl, model }
}
function parseAiTags(value: unknown): string[] {
  const content = Array.isArray(value) ? value.map((part: any) => typeof part === 'string' ? part : part?.text || '').join('') : String(value || '')
  const cleaned = content.replace(/```(?:json)?/gi, '').replace(/```/g, '').trim()
  let parsed: unknown = cleaned
  try { parsed = JSON.parse(cleaned) } catch {
    const arrayText = cleaned.match(/\[[\s\S]*\]/)?.[0]
    if (arrayText) { try { parsed = JSON.parse(arrayText) } catch { parsed = cleaned } }
  }
  const raw: unknown[] = Array.isArray(parsed) ? parsed : (parsed && typeof parsed === 'object' && Array.isArray((parsed as any).tags) ? (parsed as any).tags : String(parsed).split(/[,，、\n]/))
  return [...new Set(raw.map((tag: unknown) => String(tag).trim().replace(/^#/, '')).filter((tag: string) => tag.length >= 1 && tag.length <= 40))].slice(0, 12) as string[]
}
async function suggestTags(buffer: Buffer, mimeType: string, existingTags: string[]) {
  const config = await getAiConfig()
  if (!config.enabled) return { tags: [] as string[], enabled: false, model: config.model }
  const imageBase64 = buffer.toString('base64')
  const prompt = `已有标签：${existingTags.slice(0, 200).join('、') || '暂无'}\n请为这张图片生成最多12个中文标签，只输出 JSON 数组，不要解释。优先使用已有标签，确实需要时才提出新标签。`
  const useNativeOllama = /:11434$/.test(config.baseUrl) || config.baseUrl.endsWith('/api')
  if (useNativeOllama) {
    const ollamaBase = config.baseUrl.endsWith('/api') ? config.baseUrl : `${config.baseUrl}/api`
    const response = await fetch(`${ollamaBase}/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: config.model, stream: false, format: 'json', messages: [{ role: 'system', content: '你是图片标签助手，只输出 JSON 数组，例如 ["人物","室内","插画"]。' }, { role: 'user', content: prompt, images: [imageBase64] }] }), signal: AbortSignal.timeout(120_000) })
    if (!response.ok) throw new Error(`本地 Ollama 返回 ${response.status}`)
    const payload: any = await response.json()
    return { tags: parseAiTags(payload?.message?.content), enabled: true, model: config.model }
  }
  const response = await fetch(`${config.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: { ...(config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {}), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: config.model,
      temperature: 0.2,
      max_tokens: 200,
      messages: [{ role: 'system', content: '你是图片标签助手。只输出 JSON 数组，例如 ["人物","室内","插画"]，不要解释。优先从给定已有标签中选择，只有确实需要时才提出新标签。标签要简洁、具体、适合图片管理。' }, { role: 'user', content: [{ type: 'text', text: `已有标签：${existingTags.slice(0, 200).join('、') || '暂无'}\n请为这张图片生成最多12个中文标签。` }, { type: 'image_url', image_url: { url: `data:${mimeType};base64,${buffer.toString('base64')}` } }] }],
    }),
    signal: AbortSignal.timeout(60_000),
  })
  if (!response.ok) throw new Error(`AI 标签服务返回 ${response.status}`)
  const payload: any = await response.json()
  return { tags: parseAiTags(payload?.choices?.[0]?.message?.content), enabled: true, model: config.model }
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
    deletedAt: image.deletedAt?.toISOString(),
  }
}
async function imageWithRelations(id: string) {
  return prisma.image.findUnique({ where: { id }, include: { tags: { include: { tag: true } }, variants: true } })
}

app.setErrorHandler(async (error, _request, reply) => {
  if (error instanceof z.ZodError) return reply.code(400).send({ message: '请求参数不正确', issues: error.issues })
  app.log.error(error)
  return reply.code((error as any).statusCode || 500).send({ message: '服务器内部错误' })
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
  return reply.code(201).send({ id: user.id, username: user.username, role: user.role })
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
  return { user: { id: user.id, username: user.username, role: user.role } }
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
  return { id: user.id, username: user.username, role: user.role }
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
  const [imageCount, tagCount, userCount, storage, views, top, recent, logs, disk] = await Promise.all([
    prisma.image.count({ where: { deletedAt: null } }),
    prisma.tag.count(),
    prisma.user.count(),
    prisma.image.aggregate({ where: { deletedAt: null }, _sum: { size: true } }),
    prisma.image.aggregate({ where: { deletedAt: null }, _sum: { viewCount: true } }),
    prisma.image.findMany({ where: { deletedAt: null }, orderBy: { viewCount: 'desc' }, take: 4, include: { tags: { include: { tag: true } }, variants: true } }),
    prisma.image.findMany({ where: { deletedAt: null }, orderBy: { uploadedAt: 'desc' }, take: 4, include: { tags: { include: { tag: true } }, variants: true } }),
    prisma.auditLog.findMany({ orderBy: { createdAt: 'desc' }, take: 5, include: { user: true } }),
    storageUsage(),
  ])
  return { stats: { imageCount, tagCount, userCount, totalViews: views._sum.viewCount || 0, storage: byteSize(disk.usedBytes), storageCapacity: byteSize(disk.capacityBytes), storagePercent: Number(disk.percent.toFixed(2)), databaseImageBytes: byteSize(storage._sum.size || 0n) }, top: top.map(imageDto), recent: recent.map(imageDto), logs: logs.map((log) => ({ id: log.id, action: log.action, target: log.target, user: log.user?.username || '系统', ip: log.ip, scope: log.scope === 'INTERNAL' ? '内网' : '外网', time: log.createdAt.toISOString(), tone: log.action.includes('删除') ? 'red' : log.action.includes('标签') ? 'violet' : 'blue' })) }
})

app.get('/api/images', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const query = z.object({ search: z.string().optional(), tag: z.string().optional(), sort: z.enum(['views', 'newest', 'name']).default('views'), trash: z.preprocess((value) => value === true || value === 'true', z.boolean()).default(false) }).parse(request.query)
  const where: Prisma.ImageWhereInput = { deletedAt: query.trash ? { not: null } : null }
  if (query.search) where.OR = [{ name: { contains: query.search, mode: 'insensitive' } }, { tags: { some: { tag: { name: { contains: query.search, mode: 'insensitive' } } } } }]
  if (query.tag && query.tag !== '全部标签') where.tags = { some: { tag: { name: query.tag } } }
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
  try {
    for (const width of [320, 768, 1600]) {
      const key = `thumbnails/${id}-${width}.webp`
      await sharp(safeStoragePath(originalKey)).resize({ width, withoutEnlargement: true }).webp({ quality: 84 }).toFile(safeStoragePath(key))
      variants.push({ width, key })
    }
    const image = await prisma.$transaction(async (tx) => {
      const created = await tx.image.create({ data: { id, name, originalKey, mimeType: pending.mimeType, size: BigInt(pending.size), width: pending.width, height: pending.height, sha256: pending.sha256, uploaderId: user.id, variants: { create: variants } } })
      const tags = []
      for (const tagName of tagNames) tags.push(await tx.tag.upsert({ where: { name: tagName }, create: { name: tagName }, update: {} }))
      if (tags.length) await tx.imageTag.createMany({ data: tags.map((tag) => ({ imageId: created.id, tagId: tag.id, addedById: user.id })), skipDuplicates: true })
      return tx.image.findUnique({ where: { id: created.id }, include: { tags: { include: { tag: true } }, variants: true } })
    })
    if (!image) throw new Error('图片入库失败')
    if (tagNames.length) await recordAudit(request, '添加标签', user.id, `${name} → ${tagNames.join('、')}`)
    return imageDto(image)
  } catch (error) {
    await rm(safeStoragePath(originalKey), { force: true })
    await Promise.all(variants.map((variant) => rm(safeStoragePath(variant.key), { force: true })))
    throw error
  }
}

app.post('/api/images/upload/analyze', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const configuredLimit = await prisma.systemSetting.findUnique({ where: { key: 'uploadLimitMb' } })
  const uploadLimitMb = typeof configuredLimit?.value === 'number' ? configuredLimit.value : defaultSettings.uploadLimitMb
  const existingTags = await prisma.tag.findMany({ orderBy: { name: 'asc' }, select: { name: true } })
  const aiConfig = await getAiConfig()
  const items: any[] = []
  let sourceIndex = 0
  for await (const part of request.parts()) {
    if (part.type !== 'file') continue
    const currentIndex = sourceIndex++
    if (!part.mimetype.startsWith('image/')) continue
    const buffer = await part.toBuffer()
    if (buffer.byteLength > uploadLimitMb * 1024 * 1024) return reply.code(413).send({ message: `单张图片不能超过 ${uploadLimitMb} MB` })
    const metadata = await sharp(buffer).metadata()
    const hash = createHash('sha256').update(buffer).digest('hex')
    const duplicate = await prisma.image.findUnique({ where: { sha256: hash }, select: { name: true } })
    if (duplicate) {
      items.push({ sourceIndex: currentIndex, tempId: null, fileName: part.filename, name: part.filename.replace(/\.[^.]+$/, ''), mimeType: part.mimetype, size: buffer.byteLength, width: metadata.width || 0, height: metadata.height || 0, tags: [], duplicate: true, duplicateName: duplicate.name })
      continue
    }
    const tempId = randomUUID()
    const ext = path.extname(part.filename).toLowerCase() || '.bin'
    const tempKey = `temp/${user.id}/${tempId}${ext}`
    await mkdir(path.dirname(safeStoragePath(tempKey)), { recursive: true })
    await writeFile(safeStoragePath(tempKey), buffer)
    let tags: string[] = []
    let aiError = ''
    if (aiConfig.enabled) {
      try { tags = (await suggestTags(buffer, part.mimetype, existingTags.map((tag) => tag.name))).tags } catch (error) { aiError = error instanceof Error ? error.message : 'AI 分析失败'; app.log.warn({ error }, `AI tag analysis failed for ${part.filename}`) }
    }
    pendingUploads.set(tempId, { userId: user.id, tempId, tempKey, fileName: part.filename, mimeType: part.mimetype, size: buffer.byteLength, width: metadata.width, height: metadata.height, sha256: hash, createdAt: Date.now() })
    items.push({ sourceIndex: currentIndex, tempId, fileName: part.filename, name: part.filename.replace(/\.[^.]+$/, ''), mimeType: part.mimetype, size: buffer.byteLength, width: metadata.width || 0, height: metadata.height || 0, tags, duplicate: false, aiError })
  }
  await recordAudit(request, '分析待上传图片', user.id, `${items.length} 张图片`)
  return { items, aiEnabled: aiConfig.enabled, aiModel: aiConfig.model }
})

app.post('/api/images/upload/complete', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ items: z.array(z.object({ tempId: z.string().uuid(), name: z.string().trim().min(1).max(200), tags: z.array(z.string()).max(30).default([]) })).min(1).max(50) }).parse(request.body)
  const pending = body.items.map((item) => ({ item, upload: pendingUploads.get(item.tempId) }))
  if (pending.some(({ upload }) => !upload || upload.userId !== user.id)) return reply.code(400).send({ message: '待上传文件已过期，请重新选择' })
  const saved: any[] = []
  for (const { item, upload } of pending) {
    if (!upload) continue
    const duplicate = await prisma.image.findUnique({ where: { sha256: upload.sha256 } })
    if (duplicate) { await rm(safeStoragePath(upload.tempKey), { force: true }); pendingUploads.delete(upload.tempId); continue }
    const image = await finalizePendingUpload(request, user, upload, item.name, normalizeUploadTags(item.tags))
    saved.push(image)
    pendingUploads.delete(upload.tempId)
  }
  await recordAudit(request, '上传图片', user.id, `${saved.length} 张图片`)
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
    if (!part.mimetype.startsWith('image/')) continue
    const buffer = await part.toBuffer()
    if (buffer.byteLength > uploadLimitMb * 1024 * 1024) return reply.code(413).send({ message: `单张图片不能超过 ${uploadLimitMb} MB` })
    const metadata = await sharp(buffer).metadata()
    const hash = createHash('sha256').update(buffer).digest('hex')
    if (await prisma.image.findUnique({ where: { sha256: hash } })) continue
    const id = randomUUID()
    const ext = path.extname(part.filename).toLowerCase() || '.bin'
    const originalKey = `originals/${id}${ext}`
    await writeFile(safeStoragePath(originalKey), buffer)
    const variants = []
    for (const width of [320, 768, 1600]) {
      const key = `thumbnails/${id}-${width}.webp`
      await sharp(buffer).resize({ width, withoutEnlargement: true }).webp({ quality: 84 }).toFile(safeStoragePath(key))
      variants.push({ width, key })
    }
    const image = await prisma.image.create({ data: { id, name: part.filename.replace(/\.[^.]+$/, ''), originalKey, mimeType: part.mimetype, size: BigInt(buffer.byteLength), width: metadata.width, height: metadata.height, sha256: hash, uploaderId: user.id, variants: { create: variants } }, include: { tags: { include: { tag: true } }, variants: true } })
    saved.push(imageDto(image))
  }
  await recordAudit(request, '上传图片', user.id, `${saved.length} 张图片`)
  return reply.code(201).send({ items: saved })
})

app.post('/api/images/:id/view', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const image = await prisma.image.update({ where: { id: (request.params as { id: string }).id }, data: { viewCount: { increment: 1 }, lastViewed: new Date() } }).catch(() => null)
  if (!image) return reply.code(404).send({ message: '图片不存在' })
  await recordAudit(request, '查看图片', user.id, image.name)
  return { ok: true, views: image.viewCount }
})

async function sendImageFile(request: AuthenticatedRequest, reply: FastifyReply, variant?: number) {
  const user = await requireUser(request, reply)
  if (!user) return
  const image = await imageWithRelations((request.params as { id: string }).id)
  if (!image || image.deletedAt) return reply.code(404).send({ message: '图片不存在' })
  const key = variant ? image.variants.find((item) => item.width === variant)?.key : image.originalKey
  if (!key) return reply.code(404).send({ message: '图片文件不存在' })
  const filePath = safeStoragePath(key)
  const fileInfo = await stat(filePath)
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

app.get('/api/tags', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const tags = await prisma.tag.findMany({ orderBy: { name: 'asc' }, include: { _count: { select: { images: true } } } })
  return { items: tags.map((tag) => ({ id: tag.id, name: tag.name, color: tag.color, count: tag._count.images })) }
})
app.post('/api/tags', async (request, reply) => {
  const user = await requireUser(request as AuthenticatedRequest, reply)
  if (!user) return
  const body = z.object({ name: z.string().trim().min(1).max(40), color: z.string().default('#a78bfa') }).parse(request.body)
  const tagData = body.name.toUpperCase() === 'R-18' ? { name: 'R-18', color: '#ef4444' } : body
  const tag = await prisma.tag.create({ data: tagData }).catch(() => null)
  if (!tag) return reply.code(409).send({ message: '标签已存在' })
  await recordAudit(request, '创建标签', user.id, tag.name)
  return reply.code(201).send(tag)
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
  return { items: logs.map((log) => ({ id: log.id, action: log.action, target: log.target || '', user: log.user?.username || '系统', ip: log.ip, scope: log.scope === 'INTERNAL' ? '内网' : '外网', time: log.createdAt.toISOString(), tone: log.action.includes('删除') ? 'red' : log.action.includes('标签') ? 'violet' : log.action.includes('登录') ? 'green' : 'blue' })) }
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
  await app.listen({ port: runtimePort, host: process.env.HOST || '0.0.0.0' })
}
start().catch(async (error) => { app.log.error(error); await prisma.$disconnect(); process.exit(1) })

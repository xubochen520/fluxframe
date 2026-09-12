/**
 * DeepSeek 余额 / 用量记账
 *
 * 数据来源（与 platform.deepseek.com/usage 页面一致）：
 *  1. 余额：GET https://api.deepseek.com/user/balance      —— 只需要 sk- 开头的 API KEY
 *  2. 精确用量：GET https://platform.deepseek.com/api/v0/usage/by_api_key/{amount,cost}
 *     —— 需要平台网页的会话令牌（可选），按 KEY / 模型 / 天返回 Tokens 与真实费用
 *
 * 记账方式参考「小鲸鱼记账」：每隔一段时间观测一次余额，余额下降的差值即当日消费，
 * 余额上升记为充值；跨天自动归日、币种切换只重置基准不记账。这样即使没有平台令牌，
 * 也能持久化出「今日已用 / 本月使用 / 30 天曲线」。
 */
import type { PrismaClient } from '@prisma/client'

type Logger = { info: (msg: string) => void; warn: (msg: string) => void }

const TIMEOUT_MS = 15_000
const DEFAULT_REFRESH_SECONDS = 60
const MIN_REFRESH_SECONDS = 30
const CHART_DAYS = 30
/** 断线超过该时长后不再补记差额（避免服务器长时间关机后记出一笔假账） */
const MAX_GAP_MS = 48 * 60 * 60 * 1000
/** 高峰时段（北京时间 周一至周五 9:00-12:00 / 14:00-18:00），其余为空闲时段（半价） */
const PEAK_HOURS: Array<[number, number]> = [[9, 12], [14, 18]]
/** 元/百万 tokens，[空闲, 高峰] */
const PRICING: Record<string, { hit: [number, number]; miss: [number, number]; out: [number, number] }> = {
  'deepseek-v4-pro': { hit: [0.15, 0.3], miss: [4.5, 9], out: [13.5, 27] },
  _default: { hit: [0.02, 0.04], miss: [1, 2], out: [4, 8] },
}

export type DeepseekBalance = {
  ok: boolean
  error?: string
  unauthorized?: boolean
  isAvailable?: boolean
  currency?: string
  total?: number
  granted?: number
  toppedUp?: number
}

export type DeepseekConfig = { enabled: boolean; refreshSeconds: number; platformToken: string }

function apiBase() { return (process.env.DEEPSEEK_API_BASE || 'https://api.deepseek.com').replace(/\/$/, '') }
function platformBase() { return (process.env.DEEPSEEK_PLATFORM_BASE || 'https://platform.deepseek.com').replace(/\/$/, '') }

function num(value: unknown) {
  const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[^\d.eE+-]/g, ''))
  return Number.isFinite(n) ? n : 0
}
function round(value: number, digits = 4) {
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}
function pad(value: number) { return String(value).padStart(2, '0') }
export function dayKey(date: Date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
export function maskKey(apiKey: string) {
  const text = String(apiKey || '').trim()
  if (text.length <= 8) return text ? `${text.slice(0, 3)}****` : ''
  return `${text.slice(0, 5)}****${text.slice(-4)}`
}
function priceFor(model: string) {
  const name = String(model || '').toLowerCase()
  for (const key of Object.keys(PRICING)) {
    if (key === '_default') continue
    if (name.includes(key)) return PRICING[key]
  }
  return PRICING._default
}
/** 分桶时间（epoch 秒）→ 北京时间所在小时，用于区分峰谷价 */
export function isPeakTime(timeSec: number) {
  if (!Number.isFinite(timeSec)) return false
  const beijing = new Date(timeSec * 1000 + 8 * 3600 * 1000)
  const day = beijing.getUTCDay()
  if (day === 0 || day === 6) return false
  const hour = beijing.getUTCHours()
  return PEAK_HOURS.some(([start, end]) => hour >= start && hour < end)
}
/** 按峰谷价把 token 分桶换算成金额（仅在平台 cost 接口不可用时作为兜底） */
export function estimateCost(model: string, timeSec: number, hit: number, miss: number, out: number) {
  const price = priceFor(model)
  const index = isPeakTime(timeSec) ? 1 : 0
  return (hit / 1e6) * price.hit[index] + (miss / 1e6) * price.miss[index] + (out / 1e6) * price.out[index]
}

type PlatformKeyInfo = { id: string; name: string }
function normalizePlatformKey(raw: unknown): PlatformKeyInfo {
  if (raw && typeof raw === 'object') {
    const value = raw as Record<string, unknown>
    const id = String(value.tracking_id ?? value.id ?? value.key ?? value.name ?? '').trim()
    const name = String(value.name ?? value.label ?? id).trim()
    return { id: id || name || '未知 KEY', name: name || id || '未知 KEY' }
  }
  const text = String(raw ?? '').trim()
  return { id: text || '未知 KEY', name: text || '未知 KEY' }
}

type SeriesBucket = { day: string; timeSec: number; cost: number; hit: number; miss: number; out: number; requests: number }

type PlatformParse = {
  perKey: Map<string, { info: PlatformKeyInfo; buckets: SeriesBucket[]; models: Map<string, { tokens: number; cost: number }> }>
  total: { cost: number; hit: number; miss: number; out: number; requests: number }
}

function parsePlatformPayload(amountData: unknown, costData: unknown): PlatformParse {
  const perKey = new Map<string, { info: PlatformKeyInfo; buckets: SeriesBucket[]; models: Map<string, { tokens: number; cost: number }> }>()
  const total = { cost: 0, hit: 0, miss: 0, out: 0, requests: 0 }
  const ensure = (info: PlatformKeyInfo) => {
    const existing = perKey.get(info.id)
    if (existing) return existing
    const created = { info, buckets: [] as SeriesBucket[], models: new Map<string, { tokens: number; cost: number }>() }
    perKey.set(info.id, created)
    return created
  }
  const bucketOf = (entry: ReturnType<typeof ensure>, timeSec: number) => {
    const found = entry.buckets.find((item) => item.timeSec === timeSec)
    if (found) return found
    const created: SeriesBucket = { day: dayKey(new Date(timeSec * 1000)), timeSec, cost: 0, hit: 0, miss: 0, out: 0, requests: 0 }
    entry.buckets.push(created)
    return created
  }

  // amount：{ data: { biz_data: { series: [{ api_key, model, buckets: [{ time, usage }] }] } } }
  const amountRoot = (amountData as any)?.data?.biz_data ?? (amountData as any)?.biz_data
  const amountSeries: any[] = Array.isArray(amountRoot?.series) ? amountRoot.series : []
  for (const series of amountSeries) {
    if (!series || typeof series !== 'object') continue
    const info = normalizePlatformKey(series.api_key ?? series.apiKey ?? series.key)
    const entry = ensure(info)
    const model = String(series.model || '未知模型')
    const buckets: any[] = Array.isArray(series.buckets) ? series.buckets : []
    for (const bucket of buckets) {
      const timeSec = Math.floor(num(bucket?.time))
      if (!timeSec) continue
      const usage = bucket?.usage ?? {}
      const hit = num(usage.PROMPT_CACHE_HIT_TOKEN)
      const miss = num(usage.PROMPT_CACHE_MISS_TOKEN)
      const out = num(usage.RESPONSE_TOKEN)
      const requests = num(usage.REQUEST)
      if (hit + miss + out + requests === 0) continue
      const target = bucketOf(entry, timeSec)
      target.hit += hit
      target.miss += miss
      target.out += out
      target.requests += requests
      total.hit += hit
      total.miss += miss
      total.out += out
      total.requests += requests
      const stat = entry.models.get(model) ?? { tokens: 0, cost: 0 }
      stat.tokens += hit + miss + out
      entry.models.set(model, stat)
    }
  }

  // cost：{ data: { biz_data: { data: [{ currency, series: [{ api_key, model, buckets: [{ time, cost }] }] }] } } }
  const costRoot = (costData as any)?.data?.biz_data ?? (costData as any)?.biz_data
  const costGroups: any[] = Array.isArray(costRoot?.data) ? costRoot.data : Array.isArray(costRoot) ? costRoot : []
  if (costGroups.length) {
    const cny = costGroups.find((group) => String(group?.currency || '').toUpperCase() === 'CNY')
    const group = cny ?? costGroups[0]
    const series: any[] = Array.isArray(group?.series) ? group.series : []
    for (const item of series) {
      if (!item || typeof item !== 'object') continue
      const info = normalizePlatformKey(item.api_key ?? item.apiKey ?? item.key)
      const entry = ensure(info)
      const model = String(item.model || '未知模型')
      const buckets: any[] = Array.isArray(item.buckets) ? item.buckets : []
      for (const bucket of buckets) {
        const timeSec = Math.floor(num(bucket?.time))
        const cost = num(bucket?.cost)
        if (!timeSec) continue
        bucketOf(entry, timeSec).cost += cost
        total.cost += cost
        const stat = entry.models.get(model) ?? { tokens: 0, cost: 0 }
        stat.cost += cost
        entry.models.set(model, stat)
      }
    }
  } else {
    // 没有 cost 数据时按峰谷定价兜底估算
    for (const entry of perKey.values()) {
      for (const bucket of entry.buckets) {
        const cost = estimateCost('', bucket.timeSec, bucket.hit, bucket.miss, bucket.out)
        bucket.cost += cost
        total.cost += cost
      }
    }
  }
  return { perKey, total }
}

/** 平台「按模型/按月」兜底接口：{ biz_data: { total: [...], days: [...] } } */
function parseMonthlyPayload(amountData: unknown, costData: unknown) {
  const out = { days: new Map<string, { cost: number; hit: number; miss: number; out: number }>(), total: { cost: 0, hit: 0, miss: 0, out: 0 } }
  const readUsage = (list: any[]) => {
    const stat = { hit: 0, miss: 0, out: 0 }
    for (const item of Array.isArray(list) ? list : []) {
      const type = String(item?.type || '')
      const amount = num(item?.amount)
      if (type === 'PROMPT_CACHE_HIT_TOKEN') stat.hit += amount
      else if (type === 'PROMPT_CACHE_MISS_TOKEN') stat.miss += amount
      else if (type === 'RESPONSE_TOKEN') stat.out += amount
    }
    return stat
  }
  const amountRoot = (amountData as any)?.data?.biz_data ?? (amountData as any)?.biz_data
  for (const day of Array.isArray(amountRoot?.days) ? amountRoot.days : []) {
    const key = String(day?.date || '')
    if (!key) continue
    const entry = out.days.get(key) ?? { cost: 0, hit: 0, miss: 0, out: 0 }
    for (const item of Array.isArray(day?.data) ? day.data : []) {
      const stat = readUsage(item?.usage)
      entry.hit += stat.hit
      entry.miss += stat.miss
      entry.out += stat.out
    }
    out.days.set(key, entry)
  }
  const costRoot = (costData as any)?.data?.biz_data ?? (costData as any)?.biz_data
  const group = (Array.isArray(costRoot) ? costRoot : [costRoot]).find((item) => item && Array.isArray(item.days)) ?? null
  for (const day of Array.isArray(group?.days) ? group.days : []) {
    const key = String(day?.date || '')
    if (!key) continue
    const entry = out.days.get(key) ?? { cost: 0, hit: 0, miss: 0, out: 0 }
    for (const item of Array.isArray(day?.data) ? day.data : []) {
      const stat = readUsage(item?.usage)
      entry.cost += stat.hit + stat.miss + stat.out
    }
    out.days.set(key, entry)
  }
  for (const entry of out.days.values()) {
    out.total.cost += entry.cost
    out.total.hit += entry.hit
    out.total.miss += entry.miss
    out.total.out += entry.out
  }
  return out
}

export function createDeepseekService(prisma: PrismaClient, log: Logger) {
  let timer: NodeJS.Timeout | null = null
  let running = false
  let lastRunAt: Date | null = null
  let lastError = ''
  let lastPlatformError = ''
  let platformSyncedAt: Date | null = null
  let platformPerKey = true

  async function readConfig(): Promise<DeepseekConfig> {
    const rows = await prisma.systemSetting.findMany({ where: { key: { in: ['deepseekEnabled', 'deepseekRefreshSeconds', 'deepseekPlatformToken'] } } })
    const saved = Object.fromEntries(rows.map((row) => [row.key, row.value]))
    const refresh = Number(saved.deepseekRefreshSeconds)
    return {
      enabled: saved.deepseekEnabled === undefined ? true : Boolean(saved.deepseekEnabled),
      refreshSeconds: Number.isFinite(refresh) && refresh >= MIN_REFRESH_SECONDS ? Math.floor(refresh) : DEFAULT_REFRESH_SECONDS,
      platformToken: typeof saved.deepseekPlatformToken === 'string' ? saved.deepseekPlatformToken.trim() : '',
    }
  }

  async function saveConfig(patch: Partial<DeepseekConfig>) {
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined) continue
      const settingKey = key === 'enabled' ? 'deepseekEnabled' : key === 'refreshSeconds' ? 'deepseekRefreshSeconds' : 'deepseekPlatformToken'
      await prisma.systemSetting.upsert({ where: { key: settingKey }, create: { key: settingKey, value: value as never }, update: { value: value as never } })
    }
    /* 平台令牌被清除：同步回来的「平台精确用量」也一并清掉，避免继续显示过期数据 */
    if (patch.platformToken === '') {
      await prisma.deepseekUsageDaily.deleteMany({ where: { source: 'platform' } })
      platformSyncedAt = null
      lastPlatformError = ''
    }
    restart()
    return await readConfig()
  }

  async function fetchBalance(apiKey: string): Promise<DeepseekBalance> {
    try {
      const response = await fetch(`${apiBase()}/user/balance`, {
        headers: { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      })
      if (!response.ok) {
        const unauthorized = response.status === 401 || response.status === 403
        return { ok: false, unauthorized, error: unauthorized ? 'API KEY 无效或已被删除（HTTP 401）' : `HTTP ${response.status}` }
      }
      const payload: any = await response.json()
      const infos: any[] = Array.isArray(payload?.balance_infos) ? payload.balance_infos : []
      if (!infos.length) return { ok: false, error: '接口未返回余额信息' }
      const parsed = infos.map((info) => ({
        currency: String(info?.currency || 'CNY'),
        total: num(info?.total_balance),
        granted: num(info?.granted_balance),
        toppedUp: num(info?.topped_up_balance),
      }))
      const picked = parsed.find((item) => item.currency === 'CNY' && item.total > 0)
        ?? parsed.find((item) => item.total > 0)
        ?? parsed.find((item) => item.currency === 'CNY')
        ?? parsed[0]
      return { ok: true, isAvailable: payload?.is_available !== false, currency: picked.currency, total: picked.total, granted: picked.granted, toppedUp: picked.toppedUp }
    } catch (error) {
      return { ok: false, error: error instanceof Error ? (error.name === 'TimeoutError' ? '请求超时' : error.message) : '请求失败' }
    }
  }

  /** 同一账户（accountName 相同）只由最早创建的那个 KEY 记账，避免共享余额被重复计算 */
  async function accountOwners(enabledOnly = false) {
    const keys = await prisma.deepseekKey.findMany({ where: enabledOnly ? { enabled: true } : undefined, orderBy: { createdAt: 'asc' } })
    const owners = new Map<string, string>()
    for (const key of keys) {
      const account = key.accountName.trim() || `key:${key.id}`
      if (!owners.has(account)) owners.set(account, key.id)
    }
    return { keys, owners }
  }

  async function recordLedgerDelta(keyId: string, previous: { balance: number | null; currency: string | null; observedAt: Date | null }, current: { balance: number; currency: string }) {
    const previousBalance = typeof previous.balance === 'number' ? previous.balance : null
    const previousCurrency = String(previous.currency || '')
    if (previousBalance === null) return '基准' as const
    if (previousCurrency && previousCurrency !== current.currency) return '基准' as const
    const delta = previousBalance - current.balance
    if (Math.abs(delta) < 0.000001) return '基准' as const
    if (previous.observedAt && Date.now() - previous.observedAt.getTime() > MAX_GAP_MS) return '基准' as const
    // 差额归属「上一次观测」所在那天：跨天时不会把昨天的消费算进今天
    const day = dayKey(previous.observedAt ?? new Date())
    const row = await prisma.deepseekUsageDaily.findUnique({ where: { day_keyId_platformKeyId: { day, keyId, platformKeyId: '' } } })
    if (delta > 0) {
      await prisma.deepseekUsageDaily.upsert({
        where: { day_keyId_platformKeyId: { day, keyId, platformKeyId: '' } },
        create: { day, keyId, platformKeyId: '', currency: current.currency, amount: round(delta), source: 'ledger' },
        update: { amount: round(num(row?.amount) + delta), currency: current.currency },
      })
      return '已用' as const
    }
    await prisma.deepseekUsageDaily.upsert({
      where: { day_keyId_platformKeyId: { day, keyId, platformKeyId: '' } },
      create: { day, keyId, platformKeyId: '', currency: current.currency, refill: round(-delta), source: 'ledger' },
      update: { refill: round(num(row?.refill) - delta), currency: current.currency },
    })
    return '充值' as const
  }

  async function observeKey(keyId: string) {
    const key = await prisma.deepseekKey.findUnique({ where: { id: keyId } })
    if (!key || !key.enabled) return null
    const balance = await fetchBalance(key.apiKey)
    if (!balance.ok) {
      await prisma.deepseekKey.update({ where: { id: key.id }, data: { lastError: balance.error || '未知错误', lastObservedAt: new Date() } })
      return balance
    }
    const { owners } = await accountOwners(true)
    const isOwner = owners.get(key.accountName.trim() || `key:${key.id}`) === key.id
    if (isOwner) await recordLedgerDelta(key.id, { balance: key.balance, currency: key.currency, observedAt: key.lastObservedAt }, { balance: balance.total || 0, currency: balance.currency || 'CNY' })
    await prisma.deepseekKey.update({
      where: { id: key.id },
      data: {
        currency: balance.currency, balance: balance.total, grantedBalance: balance.granted, toppedUpBalance: balance.toppedUp,
        isAvailable: balance.isAvailable, lastObservedAt: new Date(), lastError: null,
      },
    })
    return balance
  }

  async function fetchPlatform(path: string, query: Record<string, string>, token: string) {
    const url = `${platformBase()}${path}?${new URLSearchParams(query).toString()}`
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', 'x-client-platform': 'web' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    if (response.status === 401 || response.status === 403) return { ok: false as const, unauthorized: true, error: '平台令牌已失效，请重新获取' }
    if (!response.ok) return { ok: false as const, unauthorized: false, error: `HTTP ${response.status}` }
    const payload: any = await response.json().catch(() => null)
    const code = num(payload?.code)
    const bizCode = num(payload?.data?.biz_code)
    if (code === 40002 || code === 40003 || bizCode === 40002 || bizCode === 40003) return { ok: false as const, unauthorized: true, error: '平台令牌已失效，请重新获取' }
    if (code !== 0) return { ok: false as const, unauthorized: false, error: `平台返回 code ${code}` }
    if (bizCode !== 0) return { ok: false as const, unauthorized: false, error: `平台返回 biz_code ${bizCode}` }
    return { ok: true as const, payload }
  }

  /** 同步平台用量（按 KEY / 模型 / 天），写入 DeepseekUsageDaily(source='platform') */
  async function syncPlatformUsage(token: string) {
    const today = new Date()
    const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate())
    const start = new Date(startOfToday.getTime() - (CHART_DAYS - 1) * 24 * 60 * 60 * 1000)
    const end = new Date(startOfToday.getTime() + 24 * 60 * 60 * 1000)
    const tz = String(-today.getTimezoneOffset() * 60)
    const query = { start: String(Math.floor(start.getTime() / 1000)), end: String(Math.floor(end.getTime() / 1000)), tz }
    const [amountRes, costRes] = await Promise.all([
      fetchPlatform('/api/v0/usage/by_api_key/amount', query, token),
      fetchPlatform('/api/v0/usage/by_api_key/cost', query, token),
    ])
    if (!amountRes.ok) return { ok: false as const, unauthorized: amountRes.unauthorized, error: amountRes.error, perKey: true }
    let rows: Array<{ day: string; platformKeyId: string; platformKeyName: string; amount: number; hit: number; miss: number; out: number; requests: number; models: Record<string, { tokens: number; cost: number }> }> = []
    let perKey = true
    if (amountRes.ok) {
      const parsed = parsePlatformPayload(amountRes.payload, costRes.ok ? costRes.payload : null)
      for (const entry of parsed.perKey.values()) {
        const perDay = new Map<string, { amount: number; hit: number; miss: number; out: number; requests: number }>()
        for (const bucket of entry.buckets) {
          const day = bucket.day
          const target = perDay.get(day) ?? { amount: 0, hit: 0, miss: 0, out: 0, requests: 0 }
          target.amount += bucket.cost
          target.hit += bucket.hit
          target.miss += bucket.miss
          target.out += bucket.out
          target.requests += bucket.requests
          perDay.set(day, target)
        }
        const models = Object.fromEntries([...entry.models.entries()].map(([model, stat]) => [model, { tokens: stat.tokens, cost: round(stat.cost, 6) }]))
        for (const [day, stat] of perDay.entries()) {
          rows.push({ day, platformKeyId: entry.info.id, platformKeyName: entry.info.name, amount: round(stat.amount, 6), hit: stat.hit, miss: stat.miss, out: stat.out, requests: stat.requests, models })
        }
      }
      // 平台返回了数据但没有按 KEY 归属（老接口/空 series）→ 退回按月接口
      if (!parsed.perKey.size) {
        perKey = false
        const monthly = await syncMonthly(token, startOfToday)
        if (!monthly.ok) return monthly
        rows = monthly.rows
      }
    }
    await prisma.deepseekUsageDaily.deleteMany({ where: { source: 'platform', day: { gte: dayKey(start), lte: dayKey(end) } } })
    for (const row of rows) {
      await prisma.deepseekUsageDaily.create({
        data: {
          day: row.day, keyId: '', platformKeyId: row.platformKeyId, platformKeyName: row.platformKeyName,
          amount: row.amount, tokensHit: row.hit, tokensMiss: row.miss, tokensOut: row.out, requests: row.requests,
          models: row.models as never, source: 'platform', currency: 'CNY',
        },
      })
    }
    platformPerKey = perKey
    platformSyncedAt = new Date()
    lastPlatformError = ''
    await autoBindPlatformKeys(rows)
    return { ok: true as const, perKey, keys: [...new Set(rows.map((row) => row.platformKeyId))].length }
  }

  /** 兜底：平台「按月」接口没有 KEY 维度，只能给账号级总量 */
  async function syncMonthly(token: string, now: Date) {
    const query = { month: String(now.getMonth() + 1), year: String(now.getFullYear()) }
    const [amountRes, costRes] = await Promise.all([
      fetchPlatform('/api/v0/usage/amount', query, token),
      fetchPlatform('/api/v0/usage/cost', query, token),
    ])
    if (!amountRes.ok) return { ok: false as const, unauthorized: amountRes.unauthorized, error: amountRes.error, perKey: false, rows: [] }
    const parsed = parseMonthlyPayload(amountRes.payload, costRes.ok ? costRes.payload : null)
    const rows = [...parsed.days.entries()].map(([day, stat]) => ({
      day, platformKeyId: '', platformKeyName: '全部 KEY', amount: round(stat.cost, 6),
      hit: stat.hit, miss: stat.miss, out: stat.out, requests: 0, models: {} as Record<string, { tokens: number; cost: number }>,
    }))
    return { ok: true as const, unauthorized: false, error: '', perKey: false, rows }
  }

  /** 本地 KEY 名称与平台 KEY 名称一致时自动绑定 */
  async function autoBindPlatformKeys(rows: Array<{ platformKeyId: string; platformKeyName: string }>) {
    const names = new Map<string, string>()
    for (const row of rows) if (row.platformKeyId && row.platformKeyName) names.set(row.platformKeyName.trim().toLowerCase(), row.platformKeyId)
    if (!names.size) return
    const keys = await prisma.deepseekKey.findMany({ where: { platformKeyId: '' } })
    for (const key of keys) {
      const matched = names.get(key.name.trim().toLowerCase())
      if (matched) await prisma.deepseekKey.update({ where: { id: key.id }, data: { platformKeyId: matched } })
    }
  }

  /** 把多个「独立账户」的 KEY 合并成同一账户（共用一份余额，只记一次账），并迁移已有记账流水 */
  async function mergeAccounts(keyIds: string[], name = '') {
    const keys = await prisma.deepseekKey.findMany({ where: { id: { in: keyIds } }, orderBy: { createdAt: 'asc' } })
    if (keys.length < 2) throw new Error('至少需要两个 KEY 才能合并账户')
    const primary = keys[0].id
    const label = name.trim() || keys[0].accountName.trim() || keys[0].name
    const rows = await prisma.deepseekUsageDaily.findMany({ where: { source: 'ledger', keyId: { in: keyIds } } })
    const byDay = new Map<string, { amount: number; refill: number; currency: string }>()
    for (const row of rows) {
      const entry = byDay.get(row.day) ?? { amount: 0, refill: 0, currency: row.currency }
      entry.amount += num(row.amount)
      entry.refill += num(row.refill)
      byDay.set(row.day, entry)
    }
    await prisma.deepseekUsageDaily.deleteMany({ where: { source: 'ledger', keyId: { in: keyIds } } })
    for (const [day, entry] of byDay) {
      await prisma.deepseekUsageDaily.create({
        data: { day, keyId: primary, platformKeyId: '', currency: entry.currency, amount: round(entry.amount), refill: round(entry.refill), source: 'ledger' },
      })
    }
    await prisma.deepseekKey.updateMany({ where: { id: { in: keyIds } }, data: { accountName: label } })
    return { label, primary, days: byDay.size }
  }

  async function refreshAll(reason = 'manual') {
    if (running) return { ok: false, error: '正在刷新中', skipped: true }
    running = true
    const started = Date.now()
    try {
      const config = await readConfig()
      const keys = await prisma.deepseekKey.findMany({ where: { enabled: true }, orderBy: { createdAt: 'asc' } })
      const failures: string[] = []
      for (const key of keys) {
        const result = await observeKey(key.id)
        if (result && result.ok === false) failures.push(`${key.name}：${result.error}`)
      }
      lastError = failures.join('；')
      if (config.platformToken) {
        const synced = await syncPlatformUsage(config.platformToken)
        if (!synced.ok) lastPlatformError = synced.error
      }
      lastRunAt = new Date()
      if (process.env.DEEPSEEK_DEBUG === 'true') log.info(`DeepSeek 记账刷新完成（${reason}，${Date.now() - started}ms，${keys.length} 个 KEY）`)
      return { ok: true }
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error)
      log.warn(`DeepSeek 记账刷新失败：${lastError}`)
      return { ok: false, error: lastError }
    } finally {
      running = false
    }
  }

  function restart() {
    if (timer) { clearTimeout(timer); timer = null }
    void schedule(3000)
  }

  async function schedule(delayMs: number) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { void tick() }, delayMs)
    timer.unref?.()
  }

  async function tick() {
    try {
      const config = await readConfig()
      if (config.enabled) {
        const count = await prisma.deepseekKey.count({ where: { enabled: true } })
        if (count > 0 || config.platformToken) await refreshAll('auto')
      }
      await schedule(Math.max(MIN_REFRESH_SECONDS, config.refreshSeconds) * 1000)
    } catch (error) {
      /* 轮询失败绝不影响主服务：记录后按默认间隔重试 */
      log.warn(`DeepSeek 记账轮询异常：${error instanceof Error ? error.message : String(error)}`)
      await schedule(DEFAULT_REFRESH_SECONDS * 1000)
    }
  }

  async function getSummary() {
    const config = await readConfig()
    const keys = await prisma.deepseekKey.findMany({ orderBy: { createdAt: 'asc' } })
    /* 记账归属只看启用的 KEY；展示归属用全部 KEY（停用的 KEY 仍能看到最后一次余额） */
    const { owners: enabledOwners } = await accountOwners(true)
    const { owners: displayOwners } = await accountOwners(false)
    const owners = displayOwners
    const ownerIds = new Set(enabledOwners.values())
    const monthPrefix = dayKey().slice(0, 7)
    const today = dayKey()
    const chartStart = dayKey(new Date(Date.now() - (CHART_DAYS - 1) * 24 * 60 * 60 * 1000))
    const daily = await prisma.deepseekUsageDaily.findMany({ where: { day: { gte: chartStart } } })

    const ledgerRows = daily.filter((row) => row.source === 'ledger' && ownerIds.has(row.keyId))
    /* 平台数据只在配置了平台令牌时才算数（清空令牌时会连带删除） */
    const platformRows = config.platformToken ? daily.filter((row) => row.source === 'platform') : []
    const sum = (rows: typeof daily, pick: (row: (typeof daily)[number]) => number) => rows.reduce((total, row) => total + num(pick(row)), 0)

    const ledgerToday = sum(ledgerRows.filter((row) => row.day === today), (row) => row.amount)
    const ledgerMonth = sum(ledgerRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.amount)
    const ledgerMonthRefill = sum(ledgerRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.refill)
    const platformToday = sum(platformRows.filter((row) => row.day === today), (row) => row.amount)
    const platformMonth = sum(platformRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.amount)
    const tokensToday = sum(platformRows.filter((row) => row.day === today), (row) => row.tokensHit + row.tokensMiss + row.tokensOut)
    const tokensMonth = sum(platformRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.tokensHit + row.tokensMiss + row.tokensOut)
    const hitMonth = sum(platformRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.tokensHit)
    const missMonth = sum(platformRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.tokensMiss)
    const requestsMonth = sum(platformRows.filter((row) => row.day.startsWith(monthPrefix)), (row) => row.requests)
    const hasPlatform = platformRows.length > 0

    const ledgerByDay = new Map<string, number>()
    const platformByDay = new Map<string, { amount: number; tokens: number }>()
    for (const row of ledgerRows) ledgerByDay.set(row.day, num(ledgerByDay.get(row.day)) + num(row.amount))
    for (const row of platformRows) {
      const entry = platformByDay.get(row.day) ?? { amount: 0, tokens: 0 }
      entry.amount += num(row.amount)
      entry.tokens += num(row.tokensHit) + num(row.tokensMiss) + num(row.tokensOut)
      platformByDay.set(row.day, entry)
    }
    const chart = new Map<string, { day: string; amount: number; tokens: number; source: string }>()
    for (let index = CHART_DAYS - 1; index >= 0; index -= 1) {
      const day = dayKey(new Date(Date.now() - index * 24 * 60 * 60 * 1000))
      const platform = platformByDay.get(day)
      chart.set(day, platform
        ? { day, amount: platform.amount, tokens: platform.tokens, source: 'platform' }
        : { day, amount: num(ledgerByDay.get(day)), tokens: 0, source: 'ledger' })
    }

    const platformByKey = new Map<string, { today: number; month: number; tokensToday: number; tokensMonth: number; requestsMonth: number; hit: number; miss: number; models: Record<string, { tokens: number; cost: number }> }>()
    for (const row of platformRows) {
      const id = row.platformKeyId || ''
      const entry = platformByKey.get(id) ?? { today: 0, month: 0, tokensToday: 0, tokensMonth: 0, requestsMonth: 0, hit: 0, miss: 0, models: {} }
      const tokens = num(row.tokensHit) + num(row.tokensMiss) + num(row.tokensOut)
      if (row.day === today) { entry.today += num(row.amount); entry.tokensToday += tokens }
      if (row.day.startsWith(monthPrefix)) {
        entry.month += num(row.amount)
        entry.tokensMonth += tokens
        entry.requestsMonth += num(row.requests)
        entry.hit += num(row.tokensHit)
        entry.miss += num(row.tokensMiss)
      }
      const models = (row.models as Record<string, { tokens: number; cost: number }> | null) ?? {}
      for (const [model, stat] of Object.entries(models)) {
        const merged = entry.models[model] ?? { tokens: 0, cost: 0 }
        merged.tokens += num(stat?.tokens)
        merged.cost += num(stat?.cost)
        entry.models[model] = merged
      }
      platformByKey.set(id, entry)
    }

    /* 账户聚合：同一 accountName 的多个 KEY 共用一份余额，只由主 KEY 体现余额与记账用量 */
    const accountMap = new Map<string, { name: string; keyIds: string[]; currency: string; balance: number; granted: number; toppedUp: number; today: number; month: number; isAvailable: boolean }>()
    for (const key of keys) {
      const accountId = key.accountName.trim() || `key:${key.id}`
      const owner = owners.get(accountId)
      let entry = accountMap.get(accountId)
      if (!entry) {
        entry = { name: key.accountName.trim() || key.name, keyIds: [], currency: key.currency || 'CNY', balance: 0, granted: 0, toppedUp: 0, today: 0, month: 0, isAvailable: key.isAvailable !== false }
        accountMap.set(accountId, entry)
      }
      if (!entry.keyIds.includes(key.id)) entry.keyIds.push(key.id)
      if (owner === key.id) {
        entry.name = key.accountName.trim() || key.name
        entry.currency = key.currency || entry.currency
        entry.balance = num(key.balance)
        entry.granted = num(key.grantedBalance)
        entry.toppedUp = num(key.toppedUpBalance)
        entry.isAvailable = key.isAvailable !== false
        entry.today = sum(ledgerRows.filter((row) => row.keyId === key.id && row.day === today), (row) => row.amount)
        entry.month = sum(ledgerRows.filter((row) => row.keyId === key.id && row.day.startsWith(monthPrefix)), (row) => row.amount)
      }
    }
    const accounts = [...accountMap.values()]

    /* 平台侧发现的 KEY（用户在 platform.deepseek.com 上创建的全部 KEY） */
    const discovered = [...platformByKey.entries()].map(([id, usage]) => {
      const named = daily.find((row) => row.source === 'platform' && row.platformKeyId === id)
      return {
        id,
        name: named?.platformKeyName || id || '全部 KEY',
        today: round(usage.today),
        month: round(usage.month),
        tokensToday: usage.tokensToday,
        tokensMonth: usage.tokensMonth,
        requestsMonth: usage.requestsMonth,
        cacheHitRate: usage.hit + usage.miss > 0 ? round(usage.hit / (usage.hit + usage.miss), 4) : null,
        models: Object.fromEntries(Object.entries(usage.models).map(([model, stat]) => [model, { tokens: stat.tokens, cost: round(stat.cost, 4) }])),
        boundKeyId: keys.find((key) => key.platformKeyId === id)?.id ?? '',
      }
    })

    const keyDtos = keys.map((key) => {
      const platform = key.platformKeyId ? platformByKey.get(key.platformKeyId) : undefined
      const owner = owners.get(key.accountName.trim() || `key:${key.id}`) === key.id
      return {
        id: key.id,
        name: key.name,
        accountName: key.accountName,
        masked: maskKey(key.apiKey),
        enabled: key.enabled,
        platformKeyId: key.platformKeyId,
        platformKeyName: key.platformKeyId ? (discovered.find((item) => item.id === key.platformKeyId)?.name || key.platformKeyId) : '',
        currency: key.currency,
        balance: key.balance,
        grantedBalance: key.grantedBalance,
        toppedUpBalance: key.toppedUpBalance,
        isAvailable: key.isAvailable,
        isAccountOwner: owner,
        accountNameResolved: key.accountName.trim() || key.name,
        lastObservedAt: key.lastObservedAt?.toISOString() ?? null,
        lastError: key.lastError,
        todayAmount: owner ? round(sum(ledgerRows.filter((row) => row.keyId === key.id && row.day === today), (row) => row.amount)) : null,
        monthAmount: owner ? round(sum(ledgerRows.filter((row) => row.keyId === key.id && row.day.startsWith(monthPrefix)), (row) => row.amount)) : null,
        platform: platform ? {
          today: round(platform.today), month: round(platform.month),
          tokensToday: platform.tokensToday, tokensMonth: platform.tokensMonth,
          requestsMonth: platform.requestsMonth,
          cacheHitRate: platform.hit + platform.miss > 0 ? round(platform.hit / (platform.hit + platform.miss), 4) : null,
          models: Object.fromEntries(Object.entries(platform.models).map(([model, stat]) => [model, { tokens: stat.tokens, cost: round(stat.cost, 4) }])),
        } : null,
      }
    })

    /* 同一账户提示：两个「独立账户」观测到一模一样的余额 → 很可能属于同一个 DeepSeek 账户，
       合并记账可以避免余额被重复计入 / 用量被重复统计（按账户分组，同账户内部不会互相提示） */
    const fingerprintOf = (key: (typeof keys)[number]) => {
      const fresh = key.lastObservedAt && Date.now() - key.lastObservedAt.getTime() < 10 * 60 * 1000
      if (!fresh || key.balance === null) return ''
      return `${key.currency || ''}|${key.balance}|${key.grantedBalance ?? ''}|${key.toppedUpBalance ?? ''}`
    }
    const byFingerprint = new Map<string, { keyIds: string[]; names: string[]; accountIds: string[]; balance: number; currency: string }>()
    for (const [accountId, entry] of accountMap.entries()) {
      const ownerKey = keys.find((key) => key.id === displayOwners.get(accountId))
      const value = ownerKey ? fingerprintOf(ownerKey) : ''
      if (!value) continue
      const bucket = byFingerprint.get(value) ?? { keyIds: [], names: [], accountIds: [], balance: entry.balance, currency: entry.currency }
      bucket.keyIds.push(...entry.keyIds)
      bucket.names.push(entry.name)
      bucket.accountIds.push(accountId)
      byFingerprint.set(value, bucket)
    }
    const mergeHints = [...byFingerprint.values()]
      .filter((bucket) => bucket.accountIds.length > 1)
      .map((bucket) => ({ keyIds: bucket.keyIds, names: bucket.names, balance: round(bucket.balance, 2), currency: bucket.currency }))

    return {
      configured: keys.length > 0,
      enabled: config.enabled,
      refreshSeconds: config.refreshSeconds,
      updatedAt: lastRunAt?.toISOString() ?? null,
      refreshing: running,
      error: lastError,
      platform: {
        configured: Boolean(config.platformToken),
        perKey: platformPerKey,
        syncedAt: platformSyncedAt?.toISOString() ?? null,
        error: lastPlatformError,
        hasData: hasPlatform,
        keys: discovered,
        unboundCount: discovered.filter((item) => item.id && !item.boundKeyId).length,
      },
      stats: {
        balance: round(accounts.reduce((total, account) => total + account.balance, 0), 2),
        granted: round(accounts.reduce((total, account) => total + account.granted, 0), 2),
        toppedUp: round(accounts.reduce((total, account) => total + account.toppedUp, 0), 2),
        currency: accounts[0]?.currency || 'CNY',
        accountCount: accounts.length,
        today: round(hasPlatform ? platformToday : ledgerToday),
        month: round(hasPlatform ? platformMonth : ledgerMonth),
        ledgerToday: round(ledgerToday),
        ledgerMonth: round(ledgerMonth),
        monthRefill: round(ledgerMonthRefill),
        source: hasPlatform ? 'platform' : 'ledger',
        tokensToday,
        tokensMonth,
        cacheHitRate: hitMonth + missMonth > 0 ? round(hitMonth / (hitMonth + missMonth), 4) : null,
        requestsMonth,
        keyCount: keys.length,
      },
      accounts: accounts.map((account) => ({ ...account, balance: round(account.balance, 2), granted: round(account.granted, 2), toppedUp: round(account.toppedUp, 2), today: round(account.today), month: round(account.month) })),
      mergeHints,
      keys: keyDtos,
      chart: [...chart.values()].map((entry) => ({ ...entry, amount: round(entry.amount) })),
      balance: { ok: keys.some((key) => key.lastObservedAt && !key.lastError), message: lastError },
    }
  }

  return {
    getSummary,
    mergeAccounts,
    readConfig,
    saveConfig,
    fetchBalance,
    refreshAll,
    start() { void schedule(4000) },
    stop() { if (timer) { clearTimeout(timer); timer = null } },
    /** 新增 KEY 后立即探测一次余额，让「输入 API KEY」当场看到结果 */
    async probe(apiKey: string) { return await fetchBalance(apiKey) },
    MIN_REFRESH_SECONDS,
    DEFAULT_REFRESH_SECONDS,
  }
}

export type DeepseekService = ReturnType<typeof createDeepseekService>

export type View = 'overview' | 'library' | 'similar' | 'tags' | 'logs' | 'trash' | 'settings' | 'parse'
export type IconName = 'LayoutDashboard' | 'Images' | 'Tags' | 'ScrollText' | 'Trash2' | 'Settings2'

export interface ImageItem {
  id: string
  name: string
  mimeType?: string
  url: string
  thumb: string
  width: number
  height: number
  size: string
  views: number
  uploadedAt: string
  capturedAt: string
  tags: string[]
  tagIds: string[]
  color: string
  r18?: boolean
  deletedAt?: string
}

export interface TagItem {
  id: string
  name: string
  color: string
  r18?: boolean
  /** 人物组标签：人名索引，前端绿色圆点 + 可展开查看该人物的全部标签 */
  person?: boolean
  count: number
}

export interface PersonRelatedTag extends TagItem {}

export interface PersonDetail extends TagItem {
  imageCount: number
  related: PersonRelatedTag[]
  latest: Array<{ id: string; name: string; thumb: string }>
}

export interface AuditLog {
  id: string
  action: string
  target: string
  user: string
  ip: string
  scope: '内网' | '外网'
  time: string
  tone: 'blue' | 'violet' | 'orange' | 'red' | 'green'
}

/* ---- DeepSeek 余额 / 用量记账（总览横向长条） ---- */
export interface DeepseekPlatformUsage {
  today: number
  month: number
  tokensToday: number
  tokensMonth: number
  requestsMonth: number
  cacheHitRate: number | null
  models: Record<string, { tokens: number; cost: number }>
}

export interface DeepseekKeyItem {
  id: string
  name: string
  accountName: string
  accountNameResolved: string
  masked: string
  enabled: boolean
  platformKeyId: string
  platformKeyName: string
  currency: string | null
  balance: number | null
  grantedBalance: number | null
  toppedUpBalance: number | null
  isAvailable: boolean | null
  isAccountOwner: boolean
  lastObservedAt: string | null
  lastError: string | null
  todayAmount: number | null
  monthAmount: number | null
  platform: DeepseekPlatformUsage | null
}

export interface DeepseekAccountItem {
  name: string
  keyIds: string[]
  currency: string
  balance: number
  granted: number
  toppedUp: number
  today: number
  month: number
  isAvailable: boolean
}

export interface DeepseekChartPoint {
  day: string
  amount: number
  tokens: number
  source: string
}

export interface DeepseekSummary {
  configured: boolean
  enabled: boolean
  refreshSeconds: number
  updatedAt: string | null
  refreshing: boolean
  error: string
  platform: {
    configured: boolean
    perKey: boolean
    syncedAt: string | null
    error: string
    hasData: boolean
    unboundCount: number
    keys: Array<{
      id: string
      name: string
      today: number
      month: number
      tokensToday: number
      tokensMonth: number
      requestsMonth: number
      cacheHitRate: number | null
      models: Record<string, { tokens: number; cost: number }>
      boundKeyId: string
    }>
  }
  stats: {
    balance: number
    granted: number
    toppedUp: number
    currency: string
    accountCount: number
    today: number
    month: number
    ledgerToday: number
    ledgerMonth: number
    monthRefill: number
    source: 'ledger' | 'platform'
    tokensToday: number
    tokensMonth: number
    cacheHitRate: number | null
    requestsMonth: number
    keyCount: number
  }
  accounts: DeepseekAccountItem[]
  mergeHints: Array<{ keyIds: string[]; names: string[]; balance: number; currency: string }>
  keys: DeepseekKeyItem[]
  chart: DeepseekChartPoint[]
  balance: { ok: boolean; message: string }
}

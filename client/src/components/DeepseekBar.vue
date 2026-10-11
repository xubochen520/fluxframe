<script setup lang="ts">
/**
 * 总览「DeepSeek 余额」横向长条
 *
 * 记账方式（参考小鲸鱼余额挂件）：
 *  - 余额：每分钟观测一次 api.deepseek.com/user/balance，持久化到数据库
 *  - 今日已用 / 本月使用：余额下降的差值按天累计（余额上升记为充值，跨天自动归日）
 *  - Tokens / 每个 KEY 用量：可选填入平台网页令牌后，调用 platform.deepseek.com 的用量接口精确统计
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { AlertTriangle, Check, Coins, Eye, EyeOff, KeyRound, LoaderCircle, Plus, RefreshCw, Settings2, Trash2 } from 'lucide-vue-next'
import { api, ApiError } from '../api'
import type { DeepseekKeyItem, DeepseekSummary } from '../types'

defineProps<{ isAdmin: boolean }>()

const intervalOptions = [30, 60, 120, 300]

const summary = ref<DeepseekSummary | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const fatal = ref('')
const toast = ref('')
const toastTone = ref<'ok' | 'warn'>('ok')
const manageOpen = ref(false)
let toastTimer: number | undefined
let pollTimer: number | undefined

const quickName = ref('')
const quickKey = ref('')
const adding = ref(false)
const addingName = ref('')
const addingKey = ref('')
const addingAccount = ref('')
const platformToken = ref('')
const showToken = ref(false)
const savingConfig = ref(false)

function flash(message: string, tone: 'ok' | 'warn' = 'ok') {
  toast.value = message
  toastTone.value = tone
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toast.value = '' }, 4200)
}
function errorText(error: unknown) {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '操作失败'
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    summary.value = await api.deepseekSummary()
    fatal.value = ''
  } catch (error) {
    if (!silent) fatal.value = errorText(error)
  } finally {
    loading.value = false
  }
  schedule()
}
function schedule() {
  if (pollTimer) window.clearTimeout(pollTimer)
  const seconds = Math.max(30, summary.value?.refreshSeconds || 60)
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState === 'visible') await load(true)
    else schedule()
  }, seconds * 1000)
}
async function manualRefresh() {
  refreshing.value = true
  try {
    summary.value = await api.deepseekRefresh()
    flash(`已刷新 · 余额 ${money(summary.value?.stats.balance)}`)
  } catch (error) {
    flash(errorText(error), 'warn')
  } finally {
    refreshing.value = false
    schedule()
  }
}
async function quickAdd() {
  const value = quickKey.value.trim()
  if (!value) return flash('请先填写 API KEY', 'warn')
  adding.value = true
  try {
    const name = quickName.value.trim() || (summary.value?.keys.length ? `KEY ${summary.value.keys.length + 1}` : '主账号')
    const result = await api.deepseekAddKey({ name, apiKey: value })
    summary.value = result.summary
    quickKey.value = ''
    quickName.value = ''
    flash(result.probe.ok ? `记账已开启 · 余额 ${result.probe.currency} ${result.probe.total}` : `已保存，但探测失败：${result.probe.error || ''}`, result.probe.ok ? 'ok' : 'warn')
  } catch (error) {
    flash(errorText(error), 'warn')
  } finally {
    adding.value = false
  }
}
async function addKey() {
  const name = addingName.value.trim()
  const value = addingKey.value.trim()
  if (!name) return flash('请填写名称', 'warn')
  if (!value) return flash('请填写 API KEY', 'warn')
  adding.value = true
  try {
    const result = await api.deepseekAddKey({ name, apiKey: value, accountName: addingAccount.value.trim() })
    summary.value = result.summary
    addingName.value = ''
    addingKey.value = ''
    addingAccount.value = ''
    flash(result.probe.ok ? `已添加「${name}」· 余额 ${result.probe.currency} ${result.probe.total}` : `已添加「${name}」，探测失败：${result.probe.error || ''}`, result.probe.ok ? 'ok' : 'warn')
  } catch (error) {
    flash(errorText(error), 'warn')
  } finally {
    adding.value = false
  }
}
async function toggleKey(key: DeepseekKeyItem) {
  try {
    summary.value = await api.deepseekUpdateKey(key.id, { enabled: !key.enabled })
    flash(`${key.name} 已${key.enabled ? '停用' : '启用'}`)
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}
async function removeKey(key: DeepseekKeyItem) {
  if (!window.confirm(`删除 KEY「${key.name}」？该 KEY 的记账数据也会一起删除。`)) return
  try {
    summary.value = await api.deepseekDeleteKey(key.id)
    flash(`已删除「${key.name}」`)
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}
async function bindPlatform(key: DeepseekKeyItem, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  try {
    summary.value = await api.deepseekUpdateKey(key.id, { platformKeyId: value })
    flash(value ? `已绑定平台 KEY` : '已解除绑定')
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}
async function savePlatformToken(clear = false) {
  savingConfig.value = true
  try {
    const token = clear ? '' : platformToken.value.trim()
    summary.value = await api.deepseekSaveConfig({ platformToken: token })
    platformToken.value = ''
    flash(clear ? '平台令牌已清除' : (summary.value.platform.configured ? '平台令牌已保存，正在同步 Tokens 用量' : '平台令牌为空'))
  } catch (error) {
    flash(errorText(error), 'warn')
  } finally {
    savingConfig.value = false
  }
}
async function setRefreshSeconds(seconds: number) {
  try {
    summary.value = await api.deepseekSaveConfig({ refreshSeconds: seconds })
    flash(`刷新间隔已改为 ${seconds} 秒`)
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}
async function toggleEnabled() {
  const next = !(summary.value?.enabled ?? true)
  try {
    summary.value = await api.deepseekSaveConfig({ enabled: next })
    flash(next ? '自动记账已开启' : '自动记账已暂停')
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}

async function mergeAccounts(hint: { keyIds: string[]; names: string[]; balance: number; currency: string }) {
  try {
    summary.value = await api.deepseekMerge({ keyIds: hint.keyIds, name: hint.names[0] })
    flash(`已合并为同一账户「${hint.names[0]}」，余额只记一次`)
  } catch (error) {
    flash(errorText(error), 'warn')
  }
}

/* ---------- 展示用格式化 ---------- */
function money(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (digits === 2 && value > 0 && value < 0.01) return `¥${value.toFixed(4)}`
  return `¥${value.toFixed(digits)}`
}
function tokens(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value) || value <= 0) return '—'
  if (value >= 1e9) return `${(value / 1e9).toFixed(2)}B`
  if (value >= 1e6) return `${(value / 1e6).toFixed(2)}M`
  if (value >= 1e3) return `${(value / 1e3).toFixed(1)}K`
  return String(Math.round(value))
}
function clockText(value: string | null) {
  if (!value) return '尚未刷新'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '尚未刷新'
  const pad = (input: number) => String(input).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
function percent(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

type KeyCard = {
  id: string
  name: string
  masked: string
  balance: number | null
  currency: string
  today: number | null
  month: number | null
  tokensMonth: number | null
  requestsMonth: number | null
  hitRate: number | null
  shared: boolean
  status: string
  tone: 'ok' | 'warn' | 'idle'
  local: DeepseekKeyItem | null
  platformId: string
}

const keyCards = computed<KeyCard[]>(() => {
  const cards: KeyCard[] = []
  const accounts = summary.value?.accounts ?? []
  for (const key of summary.value?.keys ?? []) {
    const platform = key.platform
    const shared = Boolean(key.accountName.trim()) && !key.isAccountOwner
    /* 同账户的非主 KEY 没有独立余额，展示所在账户的合计（并打上「同账户」标记） */
    const account = shared ? accounts.find((item) => item.name === key.accountNameResolved) : undefined
    cards.push({
      id: key.id,
      name: key.name,
      masked: key.masked,
      balance: key.balance ?? account?.balance ?? null,
      currency: key.currency || 'CNY',
      today: platform ? platform.today : key.todayAmount ?? account?.today ?? null,
      month: platform ? platform.month : key.monthAmount ?? account?.month ?? null,
      tokensMonth: platform ? platform.tokensMonth : null,
      requestsMonth: platform ? platform.requestsMonth : null,
      hitRate: platform ? platform.cacheHitRate : null,
      shared,
      status: !key.enabled ? '已停用' : key.lastError ? key.lastError : shared ? `与账户「${key.accountNameResolved}」共用余额，用量合并记账` : key.lastObservedAt ? `更新于 ${clockText(key.lastObservedAt)}` : '等待首次探测',
      tone: !key.enabled ? 'idle' : key.lastError ? 'warn' : 'ok',
      local: key,
      platformId: key.platformKeyId,
    })
  }
  for (const item of summary.value?.platform.keys ?? []) {
    if (item.boundKeyId) continue
    cards.push({
      id: `platform:${item.id}`,
      name: item.name || '未命名 KEY',
      masked: '平台 KEY',
      balance: null,
      currency: 'CNY',
      today: item.today,
      month: item.month,
      tokensMonth: item.tokensMonth,
      requestsMonth: item.requestsMonth,
      hitRate: item.cacheHitRate,
      shared: false,
      status: '来自平台用量（未绑定本地 KEY）',
      tone: 'ok',
      local: null,
      platformId: item.id,
    })
  }
  return cards
})

const chartMax = computed(() => Math.max(0.0001, ...(summary.value?.chart ?? []).map((point) => point.amount)))
const todayKeyText = computed(() => {
  const day = summary.value?.chart?.[summary.value.chart.length - 1]?.day
  return day ? day.slice(5) : ''
})
function barHeight(amount: number) {
  return `${Math.max(amount > 0 ? 6 : 2, Math.round((amount / chartMax.value) * 100))}%`
}
function barTitle(day: string, amount: number, source: string) {
  return `${day} · ${money(amount)} · ${source === 'platform' ? '平台精确用量' : '余额差值记账'}`
}
const usageSourceText = computed(() => (summary.value?.stats.source === 'platform' ? '平台精确用量' : '余额差值记账'))
/** 管理表格里的今日/本月：平台口径优先，其次本 KEY 记账，同账户非主 KEY 退回账户合计 */
function accountOf(key: DeepseekKeyItem) {
  return (summary.value?.accounts ?? []).find((item) => item.name === key.accountNameResolved)
}
function keyToday(key: DeepseekKeyItem) {
  if (key.platform) return key.platform.today
  if (key.todayAmount !== null) return key.todayAmount
  return key.accountName.trim() ? accountOf(key)?.today ?? null : null
}
function keyMonth(key: DeepseekKeyItem) {
  if (key.platform) return key.platform.month
  if (key.monthAmount !== null) return key.monthAmount
  return key.accountName.trim() ? accountOf(key)?.month ?? null : null
}
const tokenHint = computed(() => {
  if (!summary.value) return ''
  if (!summary.value.platform.configured) return '填入平台令牌后可显示 Tokens 与每个 KEY 用量'
  if (summary.value.platform.error) return summary.value.platform.error
  if (!summary.value.platform.hasData) return '平台用量同步中…'
  return summary.value.platform.perKey ? '按平台 KEY 维度统计' : '平台按月接口（无 KEY 维度）'
})
const platformKeyOptions = computed(() => (summary.value?.platform.keys ?? []).filter((item) => item.id))

onMounted(() => { void load() })
onMounted(() => {
  document.addEventListener('visibilitychange', onVisible)
})
function onVisible() {
  if (document.visibilityState === 'visible') void load(true)
}
onBeforeUnmount(() => {
  if (pollTimer) window.clearTimeout(pollTimer)
  if (toastTimer) window.clearTimeout(toastTimer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <section class="ds-bar">
    <div class="ds-bar-head">
      <div class="ds-brand">
        <span class="ds-logo"><Coins :size="20" /></span>
        <div class="ds-brand-text">
          <b>DEEPSEEK 余额</b>
          <small>{{ summary?.configured ? `实时记账 · ${summary?.refreshSeconds || 60} 秒自动刷新 · ${clockText(summary?.updatedAt || null)}` : '未接入' }}</small>
        </div>
      </div>

      <template v-if="summary?.configured">
        <div class="ds-metrics">
          <div class="ds-metric">
            <span>余额</span>
            <strong>{{ money(summary?.stats.balance) }}</strong>
            <em>赠金 {{ money(summary?.stats.granted) }} · 充值 {{ money(summary?.stats.toppedUp) }}</em>
          </div>
          <div class="ds-metric">
            <span>今日已用</span>
            <strong>{{ money(summary?.stats.today) }}</strong>
            <em>{{ usageSourceText }}<template v-if="summary?.stats.source === 'platform'"> · 记账 {{ money(summary?.stats.ledgerToday) }}</template></em>
          </div>
          <div class="ds-metric">
            <span>本月使用</span>
            <strong>{{ money(summary?.stats.month) }}</strong>
            <em>充值 {{ money(summary?.stats.monthRefill) }}</em>
          </div>
          <div class="ds-metric">
            <span>Tokens</span>
            <strong>{{ tokens(summary?.stats.tokensToday) }}</strong>
            <em>本月 {{ tokens(summary?.stats.tokensMonth) }} · {{ tokenHint }}</em>
          </div>
          <div class="ds-metric">
            <span>缓存命中</span>
            <strong>{{ percent(summary?.stats.cacheHitRate) }}</strong>
            <em>本月 {{ Math.round(summary?.stats.requestsMonth || 0) }} 次请求 · {{ summary?.stats.keyCount || 0 }} 个 KEY</em>
          </div>
        </div>
        <div class="ds-actions">
          <button class="ds-btn" :disabled="refreshing" @click="manualRefresh"><LoaderCircle v-if="refreshing" :size="14" class="spin" /><RefreshCw v-else :size="14" />刷新</button>
          <button v-if="isAdmin" class="ds-btn" :class="{ ghost: !manageOpen }" @click="manageOpen = !manageOpen"><Settings2 :size="14" />管理</button>
        </div>
      </template>
      <div v-else class="ds-actions">
        <button v-if="isAdmin" class="ds-btn" @click="manageOpen = !manageOpen"><KeyRound :size="14" />管理</button>
      </div>
    </div>

    <div v-if="loading && !summary" class="ds-loading"><LoaderCircle :size="15" class="spin" />正在读取 DeepSeek 记账数据…</div>
    <div v-else-if="fatal" class="ds-error"><AlertTriangle :size="15" />{{ fatal }}</div>

    <template v-else-if="summary && !summary.configured">
      <div class="ds-empty">
        <b>接入 DeepSeek 余额记账</b>
        <p>填入 API KEY 后自动开始记账：余额每分钟刷新并持久化，今日已用 / 本月使用按余额差值自动累计。填入 platform.deepseek.com 的平台令牌后，还能显示 Tokens 与每个 KEY 用量。</p>
        <div v-if="isAdmin" class="ds-empty-form">
          <input v-model="quickName" class="ds-input ds-input-name" placeholder="备注名（可空，如 主账号）" />
          <input v-model="quickKey" class="ds-input" placeholder="sk-... DeepSeek API KEY" @keyup.enter="quickAdd" />
          <button class="ds-btn primary" :disabled="adding" @click="quickAdd"><LoaderCircle v-if="adding" :size="14" class="spin" /><Plus v-else :size="14" />保存并开始记账</button>
        </div>
        <p v-else class="ds-empty-hint">请让管理员在此填入 API KEY。</p>
      </div>
    </template>

    <template v-else-if="summary">
      <div class="ds-bar-body">
        <div class="ds-keys">
          <div v-for="card in keyCards" :key="card.id" class="ds-key-chip" :class="card.tone">
            <div class="ds-key-top">
              <b>{{ card.name }}</b>
              <em>{{ card.masked }}</em>
              <span v-if="card.shared" class="ds-tag">同账户</span>
              <span v-if="card.local && !card.local.enabled" class="ds-tag idle">停用</span>
            </div>
            <div class="ds-key-metrics">
              <span>余额<strong>{{ money(card.balance) }}</strong></span>
              <span>今日<strong>{{ money(card.today) }}</strong></span>
              <span>本月<strong>{{ money(card.month) }}</strong></span>
              <span>Tokens<strong>{{ tokens(card.tokensMonth) }}</strong></span>
              <span>命中<strong>{{ percent(card.hitRate) }}</strong></span>
            </div>
            <div class="ds-key-foot" :class="{ warn: card.tone === 'warn' }">{{ card.status }}</div>
          </div>
          <div v-if="!keyCards.length" class="ds-key-empty">还没有可展示的 KEY</div>
        </div>

        <div class="ds-trend" :title="`最近 30 天每日用量 · 今日 ${todayKeyText}`">
          <div class="ds-trend-head"><span>近 30 天</span><em>{{ usageSourceText }}</em></div>
          <div class="ds-trend-bars">
            <i v-for="point in summary.chart" :key="point.day" :class="point.source === 'platform' ? 'platform' : 'ledger'" :style="{ height: barHeight(point.amount) }" :title="barTitle(point.day, point.amount, point.source)" />
          </div>
        </div>
      </div>

      <div v-if="summary.error" class="ds-warn-line"><AlertTriangle :size="14" />{{ summary.error }}</div>
      <div v-for="hint in summary.mergeHints" :key="hint.keyIds.join('-')" class="ds-warn-line">
        <AlertTriangle :size="14" />
        <span>「{{ hint.names.join('」「') }}」余额都是 {{ hint.currency }} {{ hint.balance.toFixed(2) }}，可能属于同一个 DeepSeek 账户（同一账户会重复计算余额与用量）</span>
        <button v-if="isAdmin" class="ds-mini" @click="mergeAccounts(hint)">合并账本</button>
      </div>

      <div v-if="isAdmin && manageOpen" class="ds-manage">
        <div class="ds-manage-head">
          <b><KeyRound :size="14" /> API KEY 记账</b>
          <span>余额来自 api.deepseek.com/user/balance；每个 KEY 独立探测，同一账户（账户备注相同）只记一次账</span>
        </div>

        <div class="ds-table">
          <div class="ds-tr ds-th"><span>名称</span><span>KEY</span><span>账户</span><span>余额</span><span>今日</span><span>本月</span><span>绑定平台 KEY</span><span>操作</span></div>
          <div v-for="key in summary.keys" :key="key.id" class="ds-tr">
            <span class="ds-name">{{ key.name }}<em v-if="key.lastError" class="ds-err" :title="key.lastError">异常</em></span>
            <span><code>{{ key.masked }}</code></span>
            <span>{{ key.accountName || '独立账户' }}</span>
            <span>{{ money(key.balance) }}</span>
            <span>{{ money(keyToday(key)) }}</span>
            <span>{{ money(keyMonth(key)) }}</span>
            <span>
              <select class="ds-select" :value="key.platformKeyId" @change="bindPlatform(key, $event)">
                <option value="">不绑定</option>
                <option v-for="option in platformKeyOptions" :key="option.id" :value="option.id">{{ option.name }}</option>
              </select>
            </span>
            <span class="ds-row-actions">
              <button class="ds-mini" @click="toggleKey(key)">{{ key.enabled ? '停用' : '启用' }}</button>
              <button class="ds-mini danger" @click="removeKey(key)"><Trash2 :size="12" />删除</button>
            </span>
          </div>
          <div v-if="!summary.keys.length" class="ds-tr ds-empty-row">还没有 API KEY</div>
        </div>

        <div class="ds-form-row">
          <input v-model="addingName" class="ds-input ds-input-name" placeholder="名称，如 主账号 / DSH" />
          <input v-model="addingKey" class="ds-input" placeholder="sk-..." />
          <input v-model="addingAccount" class="ds-input ds-input-name" placeholder="账户备注（同账户填相同值）" list="ds-accounts" />
          <datalist id="ds-accounts">
            <option v-for="account in summary.accounts" :key="account.name" :value="account.name" />
          </datalist>
          <button class="ds-btn primary" :disabled="adding" @click="addKey"><LoaderCircle v-if="adding" :size="14" class="spin" /><Plus v-else :size="14" />添加 KEY</button>
        </div>

        <div class="ds-manage-head">
          <b><Coins :size="14" /> 平台令牌（可选 · 用于 Tokens 与每个 KEY 用量）</b>
          <span>浏览器登录 platform.deepseek.com → F12 → Network → 找 <code>usage/by_api_key/amount</code> → 复制请求头 <code>Authorization</code> 的值整段粘贴（含 Bearer 也可以）</span>
        </div>
        <div class="ds-form-row">
          <input v-model="platformToken" :type="showToken ? 'text' : 'password'" class="ds-input" :placeholder="summary.platform.configured ? '已配置（重新粘贴可覆盖，留空不会清除）' : '粘贴平台会话令牌，可留空'" />
          <button class="ds-mini" @click="showToken = !showToken"><Eye v-if="!showToken" :size="12" /><EyeOff v-else :size="12" />{{ showToken ? '隐藏' : '显示' }}</button>
          <button class="ds-btn primary" :disabled="savingConfig" @click="savePlatformToken(false)"><Check :size="14" />保存令牌</button>
          <button class="ds-btn" :disabled="savingConfig" @click="savePlatformToken(true)">清除令牌</button>
        </div>
        <div v-if="summary.platform.configured" class="ds-manage-note">
          令牌状态：{{ summary.platform.error ? summary.platform.error : `正常 · 最近同步 ${clockText(summary.platform.syncedAt)}` }}{{ summary.platform.perKey ? ' · 按 KEY 维度' : '' }}
          <template v-if="summary.platform.unboundCount"> · 有 {{ summary.platform.unboundCount }} 个平台 KEY 未绑定本地 KEY</template>
        </div>

        <div class="ds-manage-head">
          <b><Settings2 :size="14" /> 记账设置</b>
          <span>余额差值记账：余额下降的差值即当日消费；余额上升记为充值；跨天自动归日，币种切换只重置基准</span>
        </div>
        <div class="ds-form-row">
          <button class="ds-btn" @click="toggleEnabled">{{ summary.enabled ? '暂停自动记账' : '开启自动记账' }}</button>
          <span class="ds-interval">刷新间隔</span>
          <button v-for="seconds in intervalOptions" :key="seconds" class="ds-mini" :class="{ active: summary.refreshSeconds === seconds }" @click="setRefreshSeconds(seconds)">{{ seconds }}s</button>
        </div>
      </div>
    </template>

    <transition name="ds-toast">
      <div v-if="toast" class="ds-toast" :class="toastTone">{{ toast }}</div>
    </transition>
  </section>
</template>

<style scoped>
/* ============================================================
   DeepSeek 余额长条 · 极光玻璃
   一条横贯总览的玻璃带：品牌 → 指标卡 → 趋势柱 → 管理表格
   ============================================================ */
.ds-bar {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
  padding: var(--s-4) var(--s-5);
  overflow: hidden;
  isolation: isolate;
}
.ds-bar::before { content: ''; position: absolute; inset: -60% -10% auto -20%; height: 320px; z-index: -1; pointer-events: none; background: radial-gradient(60% 100% at 12% 0%, color-mix(in srgb, var(--accent-1) 26%, transparent), transparent 72%), radial-gradient(46% 90% at 92% 0%, color-mix(in srgb, var(--accent-2) 16%, transparent), transparent 70%); }
.ds-bar-head { display: flex; align-items: center; gap: var(--s-4); flex-wrap: wrap; }
.ds-brand { display: flex; align-items: center; gap: 11px; padding-right: var(--s-4); border-right: 1px solid var(--stroke); }
.ds-logo { display: grid; place-items: center; flex: none; width: 40px; height: 40px; border-radius: 14px; background: var(--accent-grad); color: var(--on-accent); box-shadow: 0 12px 26px -12px var(--glow), inset 0 1px 0 rgba(255, 255, 255, .34); }
.ds-brand-text { display: flex; flex-direction: column; gap: 2px; }
.ds-brand-text b { color: var(--text-1); font: 600 12.5px var(--font-display); letter-spacing: .1em; }
.ds-brand-text small { color: var(--text-3); font: 10.5px var(--font-mono); }

.ds-metrics { display: flex; flex: 1 1 auto; gap: var(--s-2); overflow-x: auto; padding-bottom: 2px; }
.ds-metric { display: flex; flex-direction: column; gap: 3px; min-width: 140px; padding: 9px 13px 10px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); }
.ds-metric span { color: var(--text-3); font: 500 10px var(--font-mono); letter-spacing: .1em; text-transform: uppercase; }
.ds-metric strong { color: var(--text-1); font: 600 20px var(--font-display); letter-spacing: -.03em; font-variant-numeric: tabular-nums; }
.ds-metric em { max-width: 230px; overflow: hidden; color: var(--text-3); font-size: 10.5px; font-style: normal; white-space: nowrap; text-overflow: ellipsis; }

.ds-actions { display: flex; align-items: center; gap: var(--s-2); }
.ds-btn { display: inline-flex; align-items: center; gap: 7px; height: 36px; padding: 0 14px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 12.5px; font-weight: 500; white-space: nowrap; transition: background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.ds-btn:hover { border-color: var(--stroke-strong); background: var(--field-hover); color: var(--text-1); }
.ds-btn:active { transform: scale(.97); }
.ds-btn:disabled { opacity: .55; transform: none; cursor: not-allowed; }
.ds-btn.primary { border-color: transparent; background: var(--accent-grad); color: var(--on-accent); box-shadow: 0 12px 26px -12px var(--glow), inset 0 1px 0 rgba(255, 255, 255, .28); }
.ds-btn.primary:hover { filter: brightness(1.06) saturate(1.06); }
.ds-btn.ghost { background: transparent; }
.ds-mini { display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 11.5px; white-space: nowrap; transition: background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.ds-mini:hover { border-color: var(--stroke-strong); background: var(--field-hover); color: var(--text-1); }
.ds-mini:active { transform: scale(.96); }
.ds-mini.active { border-color: var(--accent-line); background: var(--accent-soft); color: var(--accent-ink); }
.ds-mini.danger { border-color: color-mix(in srgb, var(--danger) 34%, transparent); color: var(--danger); }

.ds-loading, .ds-error { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 12px; }
.ds-error { color: var(--danger); }
.ds-warn-line { display: flex; align-items: center; gap: var(--s-2); flex-wrap: wrap; padding: 9px 12px; border-radius: var(--r-sm); border: 1px solid color-mix(in srgb, var(--warn) 26%, transparent); background: color-mix(in srgb, var(--warn) 9%, transparent); color: color-mix(in oklab, var(--warn) 74%, var(--text-1)); font-size: 11.5px; }
.ds-warn-line > span { flex: 1 1 240px; }
.ds-warn-line .ds-mini { flex: 0 0 auto; }

.ds-empty { display: flex; flex-direction: column; gap: var(--s-2); padding: 2px; }
.ds-empty b { color: var(--text-1); font: 600 14.5px var(--font-display); }
.ds-empty p { max-width: 900px; margin: 0; color: var(--text-2); font-size: 12.5px; line-height: 1.7; }
.ds-empty-hint { color: var(--text-3); }
.ds-empty-form { display: flex; gap: var(--s-2); flex-wrap: wrap; margin-top: var(--s-1); }

.ds-bar-body { display: flex; gap: var(--s-3); align-items: stretch; flex-wrap: wrap; }
.ds-keys { display: flex; flex: 1 1 520px; gap: var(--s-3); overflow-x: auto; padding-bottom: 2px; }
.ds-key-chip { display: flex; flex-direction: column; gap: 7px; min-width: 238px; padding: 11px 13px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl); }
.ds-key-chip.ok { border-color: color-mix(in srgb, var(--success) 28%, transparent); }
.ds-key-chip.warn { border-color: color-mix(in srgb, var(--danger) 34%, transparent); }
.ds-key-chip.idle { opacity: .66; }
.ds-key-top { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ds-key-top b { overflow: hidden; color: var(--text-1); font-size: 12.5px; font-weight: 600; white-space: nowrap; text-overflow: ellipsis; }
.ds-key-top em { color: var(--text-3); font: 10px var(--font-mono); font-style: normal; }
.ds-tag { flex: none; padding: 2px 7px; border-radius: var(--r-pill); border: 1px solid color-mix(in srgb, var(--accent-2) 30%, transparent); background: color-mix(in srgb, var(--accent-2) 12%, transparent); color: var(--accent-2); font-size: 9.5px; }
.ds-tag.idle { border-color: var(--stroke); background: var(--field); color: var(--text-3); }
.ds-key-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px 8px; }
.ds-key-metrics span { display: flex; flex-direction: column; gap: 1px; color: var(--text-3); font: 9.5px var(--font-mono); letter-spacing: .04em; }
.ds-key-metrics strong { color: var(--text-1); font: 600 12.5px var(--font-mono); white-space: nowrap; }
.ds-key-foot { overflow: hidden; color: var(--text-3); font-size: 10.5px; white-space: nowrap; text-overflow: ellipsis; }
.ds-key-foot.warn { color: var(--danger); }
.ds-key-empty { padding: var(--s-3); color: var(--text-3); font-size: 12px; }

.ds-trend { display: flex; flex: 0 1 320px; flex-direction: column; gap: 7px; min-width: 240px; padding: 11px 13px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl); }
.ds-trend-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.ds-trend-head span { color: var(--text-2); font-size: 11.5px; font-weight: 500; }
.ds-trend-head em { color: var(--text-3); font-size: 10px; font-style: normal; }
.ds-trend-bars { display: flex; align-items: flex-end; gap: 2px; height: 58px; }
.ds-trend-bars i { flex: 1; min-height: 2px; border-radius: 3px 3px 1px 1px; background: linear-gradient(180deg, var(--accent-1), color-mix(in srgb, var(--accent-1) 34%, transparent)); }
.ds-trend-bars i.platform { background: linear-gradient(180deg, var(--accent-2), color-mix(in srgb, var(--accent-2) 32%, transparent)); }

.ds-manage { display: flex; flex-direction: column; gap: var(--s-3); padding-top: var(--s-3); border-top: 1px dashed var(--stroke); }
.ds-manage-head { display: flex; flex-direction: column; gap: 3px; }
.ds-manage-head b { display: inline-flex; align-items: center; gap: 6px; color: var(--text-1); font-size: 12.5px; font-weight: 600; }
.ds-manage-head b svg { color: var(--accent-ink); }
.ds-manage-head span { color: var(--text-3); font-size: 11px; line-height: 1.6; }
.ds-manage-head code, .ds-table code { padding: 1px 5px; border-radius: 6px; border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font: 10.5px var(--font-mono); }
.ds-manage-note { color: var(--text-3); font-size: 11px; }
.ds-table { display: flex; flex-direction: column; gap: 4px; overflow-x: auto; }
.ds-tr { display: grid; grid-template-columns: 1.05fr 1.15fr .85fr .75fr .7fr .7fr 1.25fr 1.15fr; gap: 8px; align-items: center; min-width: 940px; padding: 8px 10px; border-radius: 10px; background: var(--field); color: var(--text-2); font-size: 11.5px; }
.ds-tr.ds-th { background: transparent; color: var(--text-3); font: 500 10.5px var(--font-mono); letter-spacing: .08em; }
.ds-tr.ds-empty-row { justify-content: center; color: var(--text-3); }
.ds-name { display: inline-flex; align-items: center; gap: 6px; }
.ds-name .ds-err { padding: 1px 6px; border-radius: var(--r-pill); border: 1px solid color-mix(in srgb, var(--danger) 32%, transparent); background: color-mix(in srgb, var(--danger) 14%, transparent); color: var(--danger); font-size: 9.5px; font-style: normal; }
.ds-row-actions { display: flex; gap: 6px; }
.ds-form-row { display: flex; gap: var(--s-2); flex-wrap: wrap; align-items: center; }
.ds-input { flex: 1 1 260px; min-width: 160px; height: 38px; padding: 0 13px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); font-size: 12px; outline: 0; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), box-shadow var(--t-mid) var(--ease); }
.ds-input:focus { border-color: var(--accent-line); background: var(--field-hover); box-shadow: 0 0 0 4px var(--accent-soft); }
.ds-input-name { flex: 0 1 200px; }
.ds-select { width: 100%; height: 32px; padding: 0 9px; border-radius: 10px; border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); font-size: 11px; outline: 0; }
.ds-select:focus { border-color: var(--accent-line); }
.ds-interval { color: var(--text-3); font-size: 11.5px; }

.ds-toast { position: absolute; right: var(--s-4); bottom: var(--s-3); padding: 9px 14px; border-radius: var(--r-pill); border: 1px solid color-mix(in srgb, var(--success) 36%, transparent); background: var(--chrome); color: color-mix(in oklab, var(--success) 72%, var(--text-1)); font-size: 11.5px; box-shadow: var(--edge-hl), var(--shadow-2); -webkit-backdrop-filter: blur(18px) saturate(1.4); backdrop-filter: blur(18px) saturate(1.4); }
.ds-toast.warn { border-color: color-mix(in srgb, var(--danger) 40%, transparent); color: var(--danger); }
.ds-toast-enter-active, .ds-toast-leave-active { transition: opacity var(--t-mid) var(--ease), transform var(--t-mid) var(--ease); }
.ds-toast-enter-from, .ds-toast-leave-to { opacity: 0; transform: translateY(6px); }
.spin { animation: ds-spin .9s linear infinite; }
@keyframes ds-spin { to { transform: rotate(360deg); } }

@media (max-width: 720px) {
  .ds-bar { padding: var(--s-3); }
  .ds-bar::before { inset: -40% -30% auto -40%; opacity: .7; }
  .ds-brand { padding-right: 0; border-right: 0; }
  .ds-logo { width: 36px; height: 36px; border-radius: 13px; }
  .ds-metric { min-width: 128px; }
  .ds-metric strong { font-size: 18px; }
  .ds-key-chip { min-width: 204px; }
  .ds-trend { flex: 1 1 100%; }
  .ds-actions { width: 100%; }
  .ds-actions .ds-btn { flex: 1; justify-content: center; }
  .ds-toast { position: static; align-self: flex-start; }
  .ds-toast, .ds-mini { -webkit-backdrop-filter: none; backdrop-filter: none; }
}
</style>

<script setup lang="ts">
import { Clock3, ShieldCheck } from 'lucide-vue-next'
import type { AuditLog } from '../types'
import { formatTime, logActionIcon } from './shared'

const props = defineProps<{
  logs: AuditLog[]
  filter: string
  groups: string[]
  userCount: number
  /** 未过滤时的总条数，用于「当前记录 / 全部记录」提示 */
  total: number
}>()
const emit = defineEmits<{ refresh: []; 'update:filter': [value: string] }>()
</script>

<template>
  <div class="logs">
    <div class="page-heading rise">
      <div>
        <p class="eyebrow"><ShieldCheck :size="14" /> AUDIT TRAIL</p>
        <h1>访问日志</h1>
        <p class="subheading">记录每一次访问和内容变更。</p>
      </div>
      <button class="filter-button" @click="emit('refresh')"><Clock3 :size="15" />刷新日志</button>
    </div>

    <div class="stats rise" style="--i: 1">
      <div><strong class="num">{{ props.logs.length }}</strong><span>{{ props.filter === '全部' ? '当前记录' : `「${props.filter}」记录` }}</span></div>
      <div><strong class="num">{{ props.userCount }}</strong><span>用户数量</span></div>
      <div><strong class="num">{{ props.total }}</strong><span>日志总量</span></div>
    </div>

    <div class="filter-pills rise" style="--i: 2">
      <button v-for="group in props.groups" :key="group" :class="['pill', { active: props.filter === group }]" @click="emit('update:filter', group)">{{ group }}</button>
    </div>

    <div class="panel log-panel rise" style="--i: 3">
      <div class="log-head"><span>操作</span><span>目标</span><span>用户</span><span>来源 IP</span><span>时间</span></div>
      <div v-for="log in props.logs" :key="log.id" class="log-row">
        <span class="log-action"><i :class="['log-icon', log.tone]"><component :is="logActionIcon(log.action)" :size="14" /></i><strong>{{ log.action }}</strong></span>
        <span class="log-target" :title="log.target">{{ log.target }}</span>
        <span class="log-user"><span class="avatar">{{ log.user?.[0]?.toUpperCase() }}</span>{{ log.user }}</span>
        <span class="log-ip"><code>{{ log.ip }}</code><em :class="log.scope === '内网' ? 'internal' : 'external'">{{ log.scope }}</em></span>
        <time class="mono">{{ formatTime(log.time) }}</time>
      </div>
      <div v-if="!props.logs.length" class="log-empty">没有符合条件的日志记录</div>
    </div>
  </div>
</template>

<style scoped>
.logs { display: flex; flex-direction: column; gap: 16px; }
.stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; overflow: hidden; border-radius: var(--r-lg); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl), var(--shadow-1); }
.stats > div { display: flex; flex-direction: column; gap: 4px; padding: 15px 18px; }
.stats > div + div { border-left: 1px solid var(--stroke); }
.stats strong { font-size: clamp(19px, 1.7vw, 25px); font-weight: 600; line-height: 1.1; }
.stats span { color: var(--text-3); font-size: 11.5px; }

.log-panel { overflow: hidden; padding: 6px; }
.log-head, .log-row { display: grid; grid-template-columns: minmax(150px, 1.1fr) minmax(0, 1.5fr) minmax(120px, .8fr) minmax(130px, .9fr) 96px; align-items: center; gap: 14px; padding: 0 14px; }
.log-head { height: 42px; color: var(--text-3); font: 500 10.5px var(--font-mono); letter-spacing: .12em; text-transform: uppercase; }
.log-row { min-height: 52px; border-radius: var(--r-md); border-top: 1px solid transparent; border-bottom: 1px solid var(--stroke); transition: background var(--t-fast) var(--ease); }
.log-row:last-of-type { border-bottom: 0; }
.log-row:hover { background: var(--glass-1); }
.log-action { display: flex; align-items: center; gap: 10px; min-width: 0; }
.log-action strong { font-size: 12.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.log-icon { display: grid; place-items: center; flex: none; width: 28px; height: 28px; border-radius: 10px; color: var(--c, var(--accent-ink)); background: color-mix(in srgb, var(--c, var(--accent-1)) 14%, transparent); }
.log-icon.blue { --c: #60a5fa; }
.log-icon.violet { --c: #a78bfa; }
.log-icon.orange { --c: #fb923c; }
.log-icon.red { --c: var(--danger); }
.log-icon.green { --c: var(--success); }
.log-target { min-width: 0; color: var(--text-2); font-size: 12.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.log-user { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--text-2); font-size: 12.5px; }
.log-user .avatar { width: 24px; height: 24px; font-size: 10.5px; }
.log-ip { display: flex; align-items: center; gap: 7px; min-width: 0; }
.log-ip code { color: var(--text-2); font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.log-ip em { flex: none; padding: 2px 7px; border-radius: var(--r-pill); border: 1px solid var(--stroke); color: var(--text-3); font-size: 10px; font-style: normal; }
.log-ip em.internal { color: color-mix(in oklab, var(--success) 72%, var(--text-1)); border-color: color-mix(in srgb, var(--success) 30%, transparent); }
.log-ip em.external { color: color-mix(in oklab, var(--warn) 72%, var(--text-1)); border-color: color-mix(in srgb, var(--warn) 30%, transparent); }
.log-row time { color: var(--text-3); font-size: 11px; white-space: nowrap; }
.log-empty { padding: 40px 0; color: var(--text-3); font-size: 12.5px; text-align: center; }

@media (max-width: 1050px) {
  .log-head { display: none; }
  .log-row { grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: 'action time' 'target target' 'user ip'; row-gap: 8px; padding: 13px 14px; }
  .log-action { grid-area: action; }
  .log-target { grid-area: target; }
  .log-user { grid-area: user; }
  .log-ip { grid-area: ip; justify-content: flex-end; }
  .log-row time { grid-area: time; }
}
@media (max-width: 720px) {
  .stats { grid-template-columns: 1fr; }
  .stats > div + div { border-left: 0; border-top: 1px solid var(--stroke); }
}
</style>

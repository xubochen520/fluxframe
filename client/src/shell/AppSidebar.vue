<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronsUpDown, Images, LayoutDashboard, Link2, LogOut, Settings2, ShieldCheck, Sparkles, Tags, Trash2 } from 'lucide-vue-next'
import type { CurrentUser } from '../api'
import type { View } from '../types'
import type { DashboardStats } from '../views/shared'

const props = defineProps<{ activeView: View; imageCount: number; trashCount: number; stats: DashboardStats; user: CurrentUser | null }>()
const emit = defineEmits<{ go: [view: View]; logout: [] }>()

const workItems: { id: View; label: string; icon: typeof LayoutDashboard }[] = [
  { id: 'overview', label: '总览', icon: LayoutDashboard },
  { id: 'library', label: '图片库', icon: Images },
  { id: 'similar', label: '相似图', icon: Sparkles },
  { id: 'parse', label: '视频提取', icon: Link2 },
  { id: 'tags', label: '智能标签', icon: Tags },
  { id: 'logs', label: '访问日志', icon: ShieldCheck },
  { id: 'trash', label: '回收站', icon: Trash2 },
]

const initial = computed(() => props.user?.username?.[0]?.toUpperCase() || '')
const percent = computed(() => Math.min(100, Math.max(0, Number(props.stats.storagePercent || 0))))
const RING = 2 * Math.PI * 18
const ringOffset = computed(() => RING * (1 - percent.value / 100))

const navEl = ref<HTMLElement | null>(null)
const indicator = ref({ top: 0, height: 0, visible: false })
let observer: ResizeObserver | undefined

function placeIndicator() {
  const active = navEl.value?.querySelector<HTMLElement>('.nav-item.active')
  if (!active) { indicator.value = { ...indicator.value, visible: false }; return }
  indicator.value = { top: active.offsetTop, height: active.offsetHeight, visible: true }
}

watch(() => props.activeView, () => nextTick(placeIndicator))
onMounted(() => {
  placeIndicator()
  if (navEl.value && 'ResizeObserver' in window) {
    observer = new ResizeObserver(() => placeIndicator())
    observer.observe(navEl.value)
  }
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <aside class="sidebar" @click.stop>
    <div class="brand">
      <div class="brand-mark"><span></span><span></span><span></span></div>
      <div class="brand-text"><strong>fluxframe</strong><small>INTRANET LIBRARY</small></div>
    </div>
    <div class="workspace-switch">
      <span class="avatar">{{ initial }}</span>
      <div class="ws-text"><strong>创作空间</strong><small>个人媒体库</small></div>
      <ChevronsUpDown :size="15" />
    </div>
    <nav ref="navEl" class="side-nav">
      <span class="side-indicator" :style="{ transform: `translateY(${indicator.top}px)`, height: `${indicator.height}px`, opacity: indicator.visible ? 1 : 0 }"></span>
      <p class="nav-label">工作区</p>
      <button v-for="item in workItems" :key="item.id" :class="['nav-item', { active: activeView === item.id }]" :title="item.label" @click="emit('go', item.id)">
        <component :is="item.icon" :size="18" />
        <span class="nav-text">{{ item.label }}</span>
        <em v-if="item.id === 'library'">{{ imageCount }}</em>
        <em v-if="item.id === 'trash'">{{ trashCount }}</em>
      </button>
      <p class="nav-label spaced">管理</p>
      <button :class="['nav-item', { active: activeView === 'settings' }]" title="系统设置" @click="emit('go', 'settings')">
        <Settings2 :size="18" />
        <span class="nav-text">系统设置</span>
      </button>
    </nav>
    <div class="sidebar-bottom">
      <div class="storage-card" :title="`磁盘容量 ${stats.storageCapacity || '未知'} · 已占 ${percent.toFixed(2)}%`">
        <svg class="storage-ring" viewBox="0 0 44 44" aria-hidden="true">
          <defs>
            <linearGradient id="ring-grad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" style="stop-color: var(--accent-1)" />
              <stop offset="100%" style="stop-color: var(--accent-2)" />
            </linearGradient>
          </defs>
          <circle class="ring-track" cx="22" cy="22" r="18" />
          <circle class="ring-value" cx="22" cy="22" r="18" :stroke-dasharray="RING" :stroke-dashoffset="ringOffset" />
        </svg>
        <div class="storage-text">
          <span>存储空间</span>
          <strong>{{ stats.storage }}</strong>
          <small>{{ stats.storageCapacity || '未知' }} · 已占 {{ percent.toFixed(2) }}%</small>
        </div>
      </div>
      <div class="profile">
        <span class="avatar">{{ initial }}</span>
        <div class="profile-text"><strong>{{ user?.username }}</strong><small>{{ user?.role === 'ADMIN' ? '超级管理员' : '普通用户' }}</small></div>
        <button class="icon-button small" title="退出登录" @click="emit('logout')"><LogOut :size="16" /></button>
      </div>
    </div>
  </aside>
</template>

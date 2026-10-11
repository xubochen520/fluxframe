<script setup lang="ts">
import { computed } from 'vue'
import { Images, LayoutDashboard, LayoutGrid, Link2, LogOut, Settings2, ShieldCheck, Sparkles, Tags, Trash2 } from 'lucide-vue-next'
import type { CurrentUser } from '../api'
import type { View } from '../types'
import type { DashboardStats } from '../views/shared'

const props = defineProps<{ activeView: View; moreOpen: boolean; user: CurrentUser | null; stats: DashboardStats; trashCount: number }>()
const emit = defineEmits<{ go: [view: View]; logout: []; 'update:moreOpen': [value: boolean] }>()

const tabs: { id: View | 'more'; label: string; icon: typeof LayoutDashboard }[] = [
  { id: 'overview', label: '总览', icon: LayoutDashboard },
  { id: 'library', label: '图片库', icon: Images },
  { id: 'tags', label: '标签', icon: Tags },
  { id: 'similar', label: '相似图', icon: Sparkles },
  { id: 'more', label: '更多', icon: LayoutGrid },
]
const moreItems: { id: View; label: string; icon: typeof LayoutDashboard }[] = [
  { id: 'parse', label: '视频提取', icon: Link2 },
  { id: 'logs', label: '访问日志', icon: ShieldCheck },
  { id: 'trash', label: '回收站', icon: Trash2 },
  { id: 'settings', label: '系统设置', icon: Settings2 },
]

const activeIndex = computed(() => {
  if (props.moreOpen) return 4
  const index = tabs.findIndex((tab) => tab.id === props.activeView)
  return index >= 0 ? index : 4
})
const initial = computed(() => props.user?.username?.[0]?.toUpperCase() || '')

function onTab(id: View | 'more') {
  if (id === 'more') { emit('update:moreOpen', !props.moreOpen); return }
  emit('update:moreOpen', false)
  emit('go', id)
}

function pick(id: View) {
  emit('update:moreOpen', false)
  emit('go', id)
}
</script>

<template>
  <nav class="dock" aria-label="主导航" @click.stop>
    <span class="dock-indicator" :style="{ '--idx': activeIndex }"></span>
    <button v-for="(tab, index) in tabs" :key="tab.id" :class="['dock-item', { active: activeIndex === index }]" @click="onTab(tab.id)">
      <component :is="tab.icon" :size="20" />
      <span>{{ tab.label }}</span>
    </button>
  </nav>
  <transition name="sheet">
    <div v-if="moreOpen" class="sheet-backdrop" @click.self="emit('update:moreOpen', false)">
      <div class="more-sheet">
        <div class="sheet-grabber"></div>
        <div class="more-grid">
          <button v-for="item in moreItems" :key="item.id" :class="['more-item', { active: activeView === item.id }]" @click="pick(item.id)">
            <span class="more-icon"><component :is="item.icon" :size="19" /></span>
            <span>{{ item.label }}</span>
            <em v-if="item.id === 'trash'">{{ trashCount }}</em>
          </button>
        </div>
        <div class="more-profile">
          <span class="avatar">{{ initial }}</span>
          <div class="profile-text">
            <strong>{{ user?.username }}</strong>
            <small>{{ user?.role === 'ADMIN' ? '超级管理员' : '普通用户' }} · 已用 {{ stats.storage }}</small>
          </div>
          <button class="filter-button" @click="emit('update:moreOpen', false); emit('logout')"><LogOut :size="15" />退出</button>
        </div>
      </div>
    </div>
  </transition>
</template>

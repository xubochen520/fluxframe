<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChevronRight, LogOut, Search, X } from 'lucide-vue-next'
import type { CurrentUser } from '../api'

const props = defineProps<{ pageTitle: string; query: string; user: CurrentUser | null }>()
const emit = defineEmits<{ 'update:query': [value: string]; searchInput: []; submit: []; clear: []; logout: []; openMore: [] }>()

const searchOpen = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)
const initial = computed(() => props.user?.username?.[0]?.toUpperCase() || '')
const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)
const phone = window.matchMedia('(max-width: 720px)')

function onInput(event: Event) {
  emit('update:query', (event.target as HTMLInputElement).value)
  emit('searchInput')
}

async function focusSearch() {
  searchOpen.value = true
  await nextTick()
  inputEl.value?.focus()
  inputEl.value?.select()
}

function toggleSearch() {
  if (searchOpen.value && !props.query) { searchOpen.value = false; return }
  void focusSearch()
}

function onKey(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    void focusSearch()
  }
}

function onEsc() {
  emit('clear')
  if (phone.matches) searchOpen.value = false
  inputEl.value?.blur()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <header class="topbar">
    <div class="crumb"><span>创作空间</span><ChevronRight :size="14" /><strong>{{ pageTitle }}</strong></div>
    <h1 class="mobile-title">{{ pageTitle }}</h1>
    <div class="top-actions">
      <div :class="['global-search', { open: searchOpen || !!query }]">
        <Search :size="16" />
        <input ref="inputEl" :value="query" placeholder="搜索图片、标签..." enterkeyhint="search" @input="onInput" @keydown.enter.prevent="emit('submit')" @keydown.esc="onEsc" />
        <button v-if="query" class="global-search-clear" aria-label="清空搜索" @click="emit('clear')"><X :size="13" /></button>
        <kbd>{{ isMac ? '⌘' : 'Ctrl' }} K</kbd>
      </div>
      <button class="icon-button search-toggle" aria-label="搜索" @click.stop="toggleSearch"><Search :size="19" /></button>
      <button class="user-chip" title="退出登录" @click="emit('logout')">
        <span class="avatar">{{ initial }}</span>
        <span class="user-name">{{ user?.username }}</span>
        <LogOut :size="14" />
      </button>
      <button class="mobile-avatar" aria-label="更多" @click.stop="emit('openMore')"><span class="avatar">{{ initial }}</span></button>
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 命令面板（⌘K / Ctrl+K）：
 * 一个入口解决「去哪」和「干什么」——导航、视图操作、主题切换、
 * 直接跳到某张图/某个标签。选中项用键盘上下移动，回车执行。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, Image as ImageIcon, Images, Palette, Search, Settings2, Sparkles, Tags, Trash2, Upload, ScrollText, Link2, Moon, Sun } from 'lucide-vue-next'
import type { ImageItem, TagItem, View } from '../types'
import type { CurrentUser } from '../api'

const props = defineProps<{
  open: boolean
  user: CurrentUser | null
  views: Array<{ view: View; label: string; icon: unknown; hint?: string }>
  images: ImageItem[]
  tags: TagItem[]
  selectedTheme: string
  isDark: boolean
}>()
const emit = defineEmits<{
  close: []
  go: [view: View]
  openImage: [image: ImageItem]
  pickTag: [name: string]
  toggleThemeMode: []
  pickTheme: [name: string]
  upload: []
  toggleSelectMode: []
}>()

const query = ref('')
const cursor = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

interface Cmd {
  id: string
  group: string
  label: string
  icon?: unknown
  thumb?: string
  hint?: string
  run: () => void
}

const THEMES = ['Aurora', 'Ember', 'Mono', 'Nebula', 'Forest', 'Rose', 'Ocean', 'Solar', 'Slate', 'Matcha', 'Sakura', 'Copper']

const commands = computed<Cmd[]>(() => {
  const list: Cmd[] = []
  const q = query.value.trim().toLowerCase()

  /* 操作：无论在哪都能干的事 */
  list.push({ id: 'act-upload', group: '操作', label: '上传媒体', icon: Upload, hint: 'U', run: () => emit('upload') })
  list.push({ id: 'act-select', group: '操作', label: '进入多选模式', icon: Images, run: () => emit('toggleSelectMode') })
  list.push({ id: 'act-mode', group: '操作', label: props.isDark ? '切换到浅色模式' : '切换到深色模式', icon: props.isDark ? Sun : Moon, run: () => emit('toggleThemeMode') })
  list.push({ id: 'act-theme', group: '操作', label: `更换主题（当前 ${props.selectedTheme}）`, icon: Palette, run: () => emit('pickTheme', nextTheme()) })

  /* 导航 */
  for (const item of props.views) {
    list.push({ id: `nav-${item.view}`, group: '导航', label: item.label, icon: item.icon, hint: '前往', run: () => emit('go', item.view) })
  }

  /* 标签跳转 */
  for (const tag of props.tags.slice(0, 40)) {
    list.push({ id: `tag-${tag.id}`, group: '标签', label: tag.name, icon: Tags, hint: `${tag.count}`, run: () => emit('pickTag', tag.name) })
  }

  /* 图片直达（有查询时才铺开，避免列表爆炸） */
  if (q) {
    for (const image of props.images.slice(0, 400)) {
      list.push({ id: `img-${image.id}`, group: '图片', label: image.name, thumb: image.thumb, run: () => emit('openImage', image) })
    }
  }

  if (!q) return list
  return list.filter((cmd) => cmd.label.toLowerCase().includes(q))
})

const grouped = computed(() => {
  const order = ['操作', '导航', '标签', '图片']
  const buckets = new Map<string, Cmd[]>()
  for (const cmd of filteredOrAll.value) {
    const bucket = buckets.get(cmd.group) || []
    bucket.push(cmd)
    buckets.set(cmd.group, bucket)
  }
  return order.filter((name) => buckets.has(name)).map((name) => ({ name, items: buckets.get(name)! }))
})

const filteredOrAll = computed(() => commands.value.slice(0, 120))
const flat = computed(() => grouped.value.flatMap((group) => group.items))

function nextTheme() {
  const index = THEMES.indexOf(props.selectedTheme)
  return THEMES[(index + 1) % THEMES.length]
}

watch(() => props.open, async (open) => {
  if (open) {
    query.value = ''
    cursor.value = 0
    await nextTick()
    inputEl.value?.focus()
  }
})

watch(filteredOrAll, () => { cursor.value = 0 })

function onKey(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    cursor.value = Math.min(cursor.value + 1, flat.value.length - 1)
    scrollCursorIntoView()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    cursor.value = Math.max(cursor.value - 1, 0)
    scrollCursorIntoView()
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const cmd = flat.value[cursor.value]
    if (cmd) { cmd.run(); emit('close') }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
  }
}

function scrollCursorIntoView() {
  nextTick(() => {
    document.querySelector('.cmdk-item.selected')?.scrollIntoView({ block: 'nearest' })
  })
}

function onPick(cmd: Cmd) { cmd.run(); emit('close') }

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <transition name="sheet">
    <div v-if="open" class="cmdk-backdrop" @mousedown.self="emit('close')">
      <div class="cmdk" role="dialog" aria-label="命令面板">
        <div class="cmdk-input-row">
          <Search :size="17" />
          <input ref="inputEl" :value="query" placeholder="搜索页面、标签、图片，或输入命令…" @input="query = ($event.target as HTMLInputElement).value" />
        </div>
        <div class="cmdk-list">
          <template v-for="group in grouped" :key="group.name">
            <p class="cmdk-group-label">{{ group.name }}</p>
            <button
              v-for="cmd in group.items"
              :key="cmd.id"
              :class="['cmdk-item', { selected: flat.indexOf(cmd) === cursor }]"
              @mousemove="cursor = flat.indexOf(cmd)"
              @click="onPick(cmd)"
            >
              <img v-if="cmd.thumb" class="cmdk-thumb" :src="cmd.thumb" alt="" loading="lazy" />
              <component :is="cmd.icon" v-else :size="16" />
              <span>{{ cmd.label }}</span>
              <span v-if="cmd.hint" class="cmdk-hint">{{ cmd.hint }}</span>
            </button>
          </template>
          <p v-if="!flat.length" class="cmdk-empty">没有匹配的命令或内容</p>
        </div>
        <div class="cmdk-footer">
          <span><kbd>↑↓</kbd> 选择</span>
          <span><kbd>↵</kbd> 执行</span>
          <span><kbd>Esc</kbd> 关闭</span>
        </div>
      </div>
    </div>
  </transition>
</template>

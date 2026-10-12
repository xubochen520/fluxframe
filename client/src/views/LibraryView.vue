<script setup lang="ts">
import { computed } from 'vue'
import { CheckSquare, FolderOpen, Images, Layers, ShieldCheck, Square, Star, Trash2, Upload } from 'lucide-vue-next'
import MediaCard from './parts/MediaCard.vue'
import type { ImageItem, TagItem } from '../types'

const props = defineProps<{
  mode: 'library' | 'trash'
  images: ImageItem[]
  tags: TagItem[]
  tagPills: Array<{ name: string; person?: boolean }>
  selectedTag: string
  sortBy: string
  r18Mode: boolean
  loading: boolean
  progress: number
  loaded: number
  total: number
  density: string
  selectMode: boolean
  selectedIds: string[]
}>()
const emit = defineEmits<{
  'update:selectedTag': [name: string]
  sort: [value: string]
  toggleR18: []
  upload: []
  open: [image: ImageItem]
  context: [image: ImageItem, event: MouseEvent]
  pressStart: [image: ImageItem, event: TouchEvent]
  pressCancel: []
  pressEnd: [event: TouchEvent]
  download: [image: ImageItem]
  mediaProgress: [image: ImageItem]
  toggleSelectMode: []
  toggleSelected: [id: string]
  selectAll: []
  star: [image: ImageItem]
  'update:density': [value: string]
}>()

const sortOptions = [
  { value: 'views', label: '观看次数' },
  { value: 'newest', label: '最近上传' },
  { value: 'oldest', label: '最早上传' },
  { value: 'name', label: '名称' },
]
const densities = [
  { value: 'compact', label: '紧凑' },
  { value: 'cozy', label: '标准' },
  { value: 'roomy', label: '宽松' },
]
const personNames = computed(() => new Set(props.tags.filter((tag) => tag.person).map((tag) => tag.name)))
const selectedSet = computed(() => new Set(props.selectedIds))

/** 桌面滚轮纵向滚动 → 横向切换标签（可横滚时才拦截） */
function onPillsWheel(event: WheelEvent) {
  const el = event.currentTarget as HTMLElement
  if (el.scrollWidth <= el.clientWidth) return
  if (Math.abs(event.deltaY) < Math.abs(event.deltaX)) return
  event.preventDefault()
  el.scrollLeft += event.deltaY
}
</script>

<template>
  <div :class="['library', `density-${density}`]">
    <div class="page-heading rise">
      <div>
        <p class="eyebrow"><FolderOpen :size="14" /> {{ mode === 'trash' ? 'RECYCLE BIN' : 'MEDIA LIBRARY' }}</p>
        <h1>{{ mode === 'trash' ? '回收站' : '图片库' }}</h1>
        <p class="subheading">{{ mode === 'trash' ? '已删除的图片会在这里等待处理。' : '浏览、筛选并管理你的全部图片。' }}</p>
      </div>
      <div class="heading-actions">
        <button v-if="mode === 'library'" :class="['filter-button', { active: selectMode }]" @click.stop="emit('toggleSelectMode')">
          <component :is="selectMode ? CheckSquare : Square" :size="15" />{{ selectMode ? '退出多选' : '多选' }}
        </button>
        <button v-if="mode === 'library'" class="primary-button" @click.stop="emit('upload')"><Upload :size="17" />上传媒体</button>
      </div>
    </div>

    <div class="library-toolbar rise" style="--i: 1">
      <div class="filter-pills" @wheel="onPillsWheel">
        <button v-for="tag in tagPills" :key="tag.name" :class="['pill', { active: selectedTag === tag.name, 'person-pill': tag.person }]" @click="emit('update:selectedTag', tag.name)"><i v-if="tag.person" class="pill-dot"></i>{{ tag.name }}</button>
      </div>
      <div class="toolbar-side">
        <button class="r18-toggle" :class="{ on: r18Mode }" :title="r18Mode ? '关闭 R18 模式' : '开启 R18 模式（显示 R18 内容与链接标签）'" @click="emit('toggleR18')"><ShieldCheck :size="14" />R18<i></i></button>
        <div class="segmented density-seg" role="radiogroup" aria-label="网格密度">
          <button v-for="option in densities" :key="option.value" :class="{ active: density === option.value }" role="radio" :aria-checked="density === option.value" :title="option.label" @click="emit('update:density', option.value)"><Layers :size="13" v-if="density === option.value" />{{ option.label }}</button>
        </div>
        <div class="segmented" role="radiogroup" aria-label="排序">
          <button v-for="option in sortOptions" :key="option.value" :class="{ active: sortBy === option.value }" role="radio" :aria-checked="sortBy === option.value" @click="emit('sort', option.value)">{{ option.label }}</button>
        </div>
        <span class="count mono">{{ images.length }} 项</span>
      </div>
      <transition name="progress-fade">
        <div v-if="loading" class="library-progress" :title="`正在加载图片 ${loaded} / ${total} 张`">
          <i :style="{ width: `${progress}%` }"></i>
        </div>
      </transition>
    </div>
    <transition name="progress-fade">
      <p v-if="loading" class="progress-note mono"><Images :size="12" />正在加载图片 · {{ progress }}% · {{ loaded }} / {{ total }} 张</p>
    </transition>

    <div v-if="selectMode && images.length" class="select-hint mono">
      <Star :size="12" />已选 {{ selectedIds.length }} / {{ images.length }} · 点击卡片选择，底部操作条可批量处理
      <button class="text-button" @click="emit('selectAll')">全选可见</button>
    </div>

    <div v-if="images.length" class="image-grid">
      <MediaCard
        v-for="image in images"
        :key="image.id"
        :image="image"
        variant="full"
        :trash="mode === 'trash'"
        :progress="true"
        :person-names="personNames"
        :select-mode="selectMode"
        :selected="selectedSet.has(image.id)"
        @open="emit('open', $event)"
        @context="(img, e) => emit('context', img, e)"
        @press-start="(img, e) => emit('pressStart', img, e)"
        @press-cancel="emit('pressCancel')"
        @press-end="(e) => emit('pressEnd', e)"
        @download="(img) => emit('download', img)"
        @media-progress="(img) => emit('mediaProgress', img)"
        @toggle-selected="(id) => emit('toggleSelected', id)"
        @star="(img) => emit('star', img)"
      />
    </div>
    <div v-else class="empty-state rise" style="--i: 2">
      <Trash2 :size="30" />
      <strong>这里还没有图片</strong>
      <span>试试调整标签或搜索条件。</span>
    </div>
  </div>
</template>

<style scoped>
.heading-actions { display: flex; align-items: center; gap: 9px; }
.library-toolbar { position: sticky; top: 84px; z-index: 12; display: flex; align-items: center; gap: 12px; margin-bottom: 18px; padding: 8px 8px 8px 10px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--chrome); -webkit-backdrop-filter: blur(20px) saturate(1.5); backdrop-filter: blur(20px) saturate(1.5); box-shadow: var(--shadow-1); }
.library-toolbar .filter-pills { flex: 1; -webkit-mask-image: linear-gradient(90deg, #000 calc(100% - 28px), transparent); mask-image: linear-gradient(90deg, #000 calc(100% - 28px), transparent); padding-right: 24px; }
.toolbar-side { display: flex; align-items: center; gap: 10px; flex: none; }
.count { padding: 0 8px 0 2px; color: var(--text-3); font-size: 11.5px; white-space: nowrap; }
.library-progress { position: absolute; left: 22px; right: 22px; top: -1px; height: 2px; overflow: hidden; border-radius: 2px; background: var(--track); }
.library-progress i { display: block; height: 100%; border-radius: inherit; background: var(--aurora-grad); box-shadow: 0 0 12px var(--accent-2); transition: width var(--t-fast) var(--ease); }
.progress-note { display: flex; align-items: center; gap: 6px; margin: -8px 0 14px 14px; color: var(--text-3); font-size: 11px; }
.progress-fade-enter-active, .progress-fade-leave-active { transition: opacity var(--t-slow) var(--ease); }
.progress-fade-enter-from, .progress-fade-leave-to { opacity: 0; }
.select-hint { display: flex; align-items: center; gap: 8px; margin: -6px 0 14px; padding: 9px 13px; border-radius: var(--r-pill); border: 1px solid var(--accent-line); background: var(--accent-soft); color: var(--accent-ink); font-size: 11.5px; }
.select-hint .text-button { margin-left: auto; }

/* ---- 网格密度：三档列宽 ---- */
.image-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); grid-auto-rows: 8px; grid-auto-flow: dense; align-items: start; row-gap: 13px; column-gap: 16px; }
.density-compact .image-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); column-gap: 12px; row-gap: 11px; }
.density-roomy .image-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); column-gap: 18px; row-gap: 15px; }
@media (min-width: 1500px) {
  .image-grid { grid-template-columns: repeat(5, minmax(0, 1fr)); }
  .density-compact .image-grid { grid-template-columns: repeat(7, minmax(0, 1fr)); }
  .density-roomy .image-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 1050px) {
  .image-grid, .density-compact .image-grid, .density-roomy .image-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .count, .density-seg { display: none; }
}
@media (max-width: 720px) {
  .library-toolbar { top: calc(64px + env(safe-area-inset-top)); flex-direction: column; align-items: stretch; gap: 8px; padding: 8px; border-radius: var(--r-lg); background: var(--chrome-solid); }
  .library-toolbar .filter-pills { -webkit-mask-image: none; mask-image: none; padding-right: 0; }
  .toolbar-side { justify-content: space-between; }
  .segmented button { padding: 0 10px; }
  .image-grid, .density-compact .image-grid, .density-roomy .image-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 12px; }
  .heading-actions { width: 100%; }
  .heading-actions .primary-button { flex: 1; }
}
</style>

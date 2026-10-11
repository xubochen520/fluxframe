<script setup lang="ts">
import { Tags, X } from 'lucide-vue-next'
import type { ImageItem, TagItem } from '../types'
import { hasPoster, isVideoItem, sizeBatchCard } from '../views/shared'

const props = defineProps<{
  tag: TagItem | null
  candidates: ImageItem[]
  query: string
  selectedIds: string[]
}>()
const emit = defineEmits<{
  close: []
  submit: []
  'update:query': [value: string]
  'update:selectedIds': [value: string[]]
}>()

function toggle(id: string) {
  emit('update:selectedIds', props.selectedIds.includes(id) ? props.selectedIds.filter((item) => item !== id) : [...props.selectedIds, id])
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal batch-tag-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon"><Tags :size="24" /></div>
      <h2>批量添加图片</h2>
      <p>选择要添加到「{{ props.tag?.name }}」的图片，可多选。</p>

      <input class="modal-input" :value="props.query" placeholder="搜索图片名称或标签..." @input="emit('update:query', ($event.target as HTMLInputElement).value)" />

      <div class="batch-toolbar">
        <button class="text-button" @click="emit('update:selectedIds', props.candidates.map((image) => image.id))">全选当前结果</button>
        <button class="text-button" @click="emit('update:selectedIds', [])">清空</button>
        <span class="mono">已选 {{ props.selectedIds.length }} 张</span>
      </div>

      <div class="batch-image-list">
        <label v-for="image in props.candidates" :key="image.id" class="batch-image-option" :class="{ picked: props.selectedIds.includes(image.id) }">
          <input type="checkbox" :checked="props.selectedIds.includes(image.id)" @change="toggle(image.id)" />
          <video v-if="isVideoItem(image) && !hasPoster(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline @loadeddata="sizeBatchCard" />
          <img v-else :src="image.thumb" :alt="image.name" loading="lazy" decoding="async" @load="sizeBatchCard" />
          <span class="batch-meta"><strong>{{ image.name }}</strong><small>{{ image.tags.join(' · ') || '未分类' }}</small></span>
        </label>
        <div v-if="!props.candidates.length" class="batch-empty">没有可添加的图片</div>
      </div>

      <button class="primary-button full" :disabled="!props.selectedIds.length" @click="emit('submit')">添加到标签</button>
    </div>
  </div>
</template>

<style scoped>
.batch-tag-modal { width: min(720px, 100%); }
.batch-toolbar { display: flex; align-items: center; gap: 16px; margin: 4px 0 12px; }
.batch-toolbar span { margin-left: auto; color: var(--text-3); font-size: 11.5px; }
.batch-image-list { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); grid-auto-rows: 8px; grid-auto-flow: dense; align-items: start; row-gap: 12px; column-gap: 12px; max-height: min(50dvh, 460px); overflow-y: auto; margin-bottom: 18px; padding: 4px; }
.batch-image-option { position: relative; display: block; overflow: hidden; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--media-bg); cursor: pointer; transition: border-color var(--t-fast) var(--ease), transform var(--t-mid) var(--ease), box-shadow var(--t-mid) var(--ease); }
.batch-image-option:hover { transform: translateY(-2px); box-shadow: var(--shadow-1); }
.batch-image-option.picked { border-color: var(--accent-line); box-shadow: 0 0 0 1px var(--accent-line), 0 12px 26px -16px var(--glow); }
.batch-image-option input { position: absolute; z-index: 2; top: 8px; left: 8px; width: 17px; height: 17px; accent-color: var(--accent-1); }
.batch-image-option img, .batch-image-option video { display: block; width: 100%; height: auto; }
.batch-meta { position: absolute; inset: auto 0 0; display: flex; flex-direction: column; gap: 2px; padding: 22px 9px 8px; background: linear-gradient(180deg, transparent, rgba(4, 6, 14, .88)); }
.batch-meta strong { color: #f2f4fb; font-size: 11.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.batch-meta small { color: rgba(242, 244, 251, .62); font-size: 10.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.batch-empty { grid-column: 1 / -1; padding: 40px 0; color: var(--text-3); font-size: 12.5px; text-align: center; }
@media (max-width: 720px) { .batch-image-list { grid-template-columns: repeat(2, minmax(0, 1fr)); max-height: 46dvh; } }
</style>

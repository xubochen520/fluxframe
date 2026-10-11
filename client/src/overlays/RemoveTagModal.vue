<script setup lang="ts">
import { Trash2, X } from 'lucide-vue-next'
import type { ImageItem } from '../types'
import { tagDot } from '../views/shared'

const props = defineProps<{ image: ImageItem | null; tags: Array<{ id: string; name: string; color: string; person?: boolean; r18?: boolean }> }>()
const emit = defineEmits<{ close: []; remove: [tagId: string, tagName: string] }>()
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon"><Trash2 :size="24" /></div>
      <h2>移除标签</h2>
      <p>选择要从「{{ props.image?.name }}」移除的标签。</p>
      <div class="tag-picker">
        <button v-for="tag in props.tags" :key="tag.id" class="danger tag-pick" @click="emit('remove', tag.id, tag.name)">
          <i :style="{ background: tagDot(tag) }"></i>{{ tag.name }}<Trash2 :size="15" />
        </button>
        <span v-if="!props.tags.length" class="picker-empty">这张图片没有可移除的标签。</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tag-picker { display: flex; flex-direction: column; gap: 8px; }
.tag-pick { display: flex; align-items: center; gap: 10px; width: 100%; padding: 11px 13px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); font-size: 13px; text-align: left; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.tag-pick i { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.tag-pick svg { margin-left: auto; color: var(--text-3); }
.tag-pick.danger:hover { border-color: color-mix(in srgb, var(--danger) 42%, transparent); background: color-mix(in srgb, var(--danger) 11%, transparent); color: var(--danger); }
.tag-pick.danger:hover svg { color: var(--danger); }
.picker-empty { padding: 14px 0; color: var(--text-3); font-size: 12.5px; text-align: center; }
</style>

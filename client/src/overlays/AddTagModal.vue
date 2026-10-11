<script setup lang="ts">
import { ref } from 'vue'
import { Check, Plus, Tags, X } from 'lucide-vue-next'
import type { ImageItem, TagItem } from '../types'
import { tagDot } from '../views/shared'

const props = defineProps<{
  image: ImageItem | null
  /** 可选标签：调用方已经剔除这张图已有的标签 */
  tags: TagItem[]
  columns: number
}>()
const emit = defineEmits<{ close: []; create: [names: string[]]; 'update:columns': [value: number] }>()

const picked = ref<string[]>([])

function toggle(name: string) {
  picked.value = picked.value.includes(name) ? picked.value.filter((item) => item !== name) : [...picked.value, name]
}
function submit() {
  if (!picked.value.length) return
  emit('create', [...picked.value])
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal add-tag-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon"><Tags :size="24" /></div>
      <h2>添加标签</h2>
      <p>为「{{ props.image?.name }}」勾选要添加的标签，可多选。</p>

      <div class="picker-head">
        <span class="picker-count mono">已选 <strong>{{ picked.length }}</strong> / {{ props.tags.length }} 个</span>
        <div class="segmented cols-switch" title="每排显示数量">
          <button v-for="n in [1, 2, 3, 4]" :key="n" :class="{ active: props.columns === n }" @click="emit('update:columns', n)">{{ n }}</button>
        </div>
      </div>

      <div class="picker-grid" :style="{ '--tag-columns': props.columns }">
        <button
          v-for="tag in props.tags"
          :key="tag.id"
          class="pick-item"
          :class="{ picked: picked.includes(tag.name), person: tag.person, r18: tag.r18 }"
          @click="toggle(tag.name)"
        >
          <i :style="{ background: tagDot(tag) }"></i>
          <span class="pick-name">{{ tag.name }}</span>
          <Check v-if="picked.includes(tag.name)" :size="14" />
          <Plus v-else :size="14" />
        </button>
        <span v-if="!props.tags.length" class="picker-empty">这张图片已经拥有全部标签。</span>
      </div>

      <button class="primary-button full" :disabled="!picked.length" @click="submit">添加所选 {{ picked.length }} 个标签</button>
    </div>
  </div>
</template>

<style scoped>
.add-tag-modal { width: min(620px, 100%); }
.picker-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.picker-count { color: var(--text-3); font-size: 11.5px; }
.picker-count strong { color: var(--accent-ink); font-size: 13px; }
.cols-switch button { height: 26px; padding: 0 10px; font-size: 11.5px; }
.picker-grid { display: grid; grid-template-columns: repeat(var(--tag-columns, 2), minmax(0, 1fr)); gap: 8px; max-height: min(46dvh, 420px); overflow-y: auto; margin-bottom: 16px; padding: 2px; }
.pick-item { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 10px 12px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 12.5px; text-align: left; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.pick-item:hover { border-color: var(--stroke-strong); color: var(--text-1); }
.pick-item:active { transform: scale(.98); }
.pick-item i { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.pick-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pick-item svg { flex: none; color: var(--text-3); }
.pick-item.person i { box-shadow: 0 0 8px color-mix(in srgb, var(--person) 70%, transparent); }
.pick-item.r18 i { box-shadow: 0 0 8px color-mix(in srgb, var(--danger) 70%, transparent); }
.pick-item.picked { color: var(--text-1); border-color: var(--accent-line); background: var(--accent-soft); }
.pick-item.picked svg { color: var(--accent-ink); }
.picker-empty { grid-column: 1 / -1; padding: 20px 0; color: var(--text-3); font-size: 12.5px; text-align: center; }
@media (max-width: 720px) { .picker-grid { grid-template-columns: repeat(min(var(--tag-columns, 2), 2), minmax(0, 1fr)); max-height: 44dvh; } }
</style>

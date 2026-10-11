<script setup lang="ts">
import { LoaderCircle, ScanFace, UserMinus, Users, X } from 'lucide-vue-next'
import type { PersonDetail } from '../types'
import { tagDot } from '../views/shared'

const props = defineProps<{
  detail: PersonDetail | null
  loading: boolean
  columns: number
  referenceCounts: Record<string, number>
}>()
const emit = defineEmits<{
  close: []
  viewTag: [tag: { name: string }]
  openReferences: [tag: PersonDetail]
  remove: []
}>()
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal person-detail-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon person-icon"><Users :size="24" /></div>
      <h2>{{ props.detail?.name || '人物' }}</h2>
      <p>共 {{ props.detail?.imageCount || 0 }} 张图片 · 关联 {{ props.detail?.related.length || 0 }} 个标签</p>

      <div v-if="props.loading" class="person-loading"><LoaderCircle :size="15" class="spin" />正在读取全部标签…</div>
      <template v-else>
        <div class="related-grid" :style="{ '--tag-columns': props.columns }">
          <button v-for="tag in props.detail?.related || []" :key="tag.id" class="related-tag" :class="{ person: tag.person }" @click="emit('viewTag', tag)">
            <i :style="{ background: tagDot(tag) }"></i>
            <span>{{ tag.name }}</span>
            <small class="mono">{{ tag.count }}</small>
          </button>
          <span v-if="!props.detail?.related?.length" class="person-empty">这个人名标签还没有关联其他标签。</span>
        </div>

        <div v-if="props.detail?.latest?.length" class="preview-grid">
          <button v-for="item in props.detail?.latest || []" :key="item.id" class="preview-thumb" :title="item.name" @click="props.detail && emit('viewTag', props.detail)">
            <img :src="item.thumb" :alt="item.name" decoding="async" />
          </button>
        </div>

        <div class="detail-actions">
          <button class="primary-button full" :disabled="!props.detail" @click="props.detail && emit('viewTag', props.detail)">查看 TA 的全部图片</button>
          <button class="filter-button" :disabled="!props.detail" @click="props.detail && emit('openReferences', props.detail)">
            <ScanFace :size="15" />参考图库{{ props.detail && props.referenceCounts[props.detail.id] ? `（${props.referenceCounts[props.detail.id]}）` : '' }}
          </button>
          <button class="filter-button" @click="emit('remove')"><UserMinus :size="15" />移出人物组</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.person-detail-modal { width: min(560px, 100%); }
.person-loading { display: flex; align-items: center; gap: 8px; padding: 22px 0; color: var(--text-3); font-size: 12.5px; }
.related-grid { display: grid; grid-template-columns: repeat(var(--tag-columns, 2), minmax(0, 1fr)); gap: 8px; max-height: min(38dvh, 320px); overflow-y: auto; padding: 2px; }
.related-tag { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 9px 12px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 12.5px; text-align: left; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.related-tag:hover { border-color: var(--stroke-strong); color: var(--text-1); }
.related-tag i { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.related-tag span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.related-tag small { flex: none; color: var(--text-3); font-size: 11px; }
.related-tag.person { border-color: color-mix(in srgb, var(--person) 30%, transparent); }
.person-empty { grid-column: 1 / -1; padding: 18px 0; color: var(--text-3); font-size: 12.5px; text-align: center; }
.preview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 14px; }
.preview-thumb { overflow: hidden; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--media-bg); aspect-ratio: 1; transition: transform var(--t-mid) var(--ease), border-color var(--t-fast) var(--ease); }
.preview-thumb:hover { transform: translateY(-2px); border-color: var(--accent-line); }
.preview-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 18px; }
.detail-actions .primary-button { flex: 1 1 100%; }
@media (max-width: 720px) { .related-grid { grid-template-columns: repeat(min(var(--tag-columns, 2), 2), minmax(0, 1fr)); } }
</style>

<script setup lang="ts">
import { LoaderCircle, ScanFace, Trash2, X } from 'lucide-vue-next'
import type { ReferenceItem } from '../api'
import type { TagItem } from '../types'

const props = defineProps<{
  tag: TagItem
  items: ReferenceItem[]
  loading: boolean
  /** 正在移除的条目键：`${imageId}-${x}-${y}` */
  removing: string
}>()
const emit = defineEmits<{ close: []; open: [item: ReferenceItem]; drop: [item: ReferenceItem] }>()

function keyOf(item: ReferenceItem) { return `${item.imageId}-${item.box.x}-${item.box.y}` }
function kindLabel(kind: ReferenceItem['kind']) { return kind === 'head' ? '头框' : kind === 'face' ? '脸框' : '自定义' }
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal references-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon person-icon"><ScanFace :size="24" /></div>
      <h2>{{ props.tag.name }} · 参考图库</h2>
      <p>这些是已确认的参考样本，用于核验识别准确性。缩略图是按框实时裁剪的——框错了这里一眼就能看出来。</p>

      <div v-if="props.loading" class="person-loading"><LoaderCircle :size="15" class="spin" />正在读取参考图…</div>
      <template v-else>
        <div v-if="props.items.length" class="ref-grid">
          <div v-for="item in props.items" :key="keyOf(item)" class="ref-card" :class="{ stale: !item.stillTagged }">
            <button class="ref-thumb" :title="`${item.name} · 点击查看大图并核对框选`" @click="emit('open', item)">
              <img :src="item.crop" :alt="item.name" decoding="async" />
            </button>
            <div class="ref-info">
              <strong :title="item.name">{{ item.name }}</strong>
              <span>
                <em :class="item.kind">{{ kindLabel(item.kind) }}</em>
                <em :class="item.source">{{ item.source === 'manual' ? '人工' : '自动' }}</em>
                <em v-if="!item.stillTagged" class="stale-tag">已无此标签</em>
              </span>
            </div>
            <button class="ref-drop" :disabled="props.removing === keyOf(item)" title="从参考图库移除" @click="emit('drop', item)"><Trash2 :size="13" /></button>
          </div>
        </div>
        <span v-else class="person-empty">这个角色还没有参考图。打开带该角色的图片 → 点「框选」→ 选中人物框 → 点「参考图」即可加入。</span>
      </template>

      <div class="detail-actions"><button class="primary-button full" @click="emit('close')">完成</button></div>
    </div>
  </div>
</template>

<style scoped>
.references-modal { width: min(760px, 100%); }
.person-loading { display: flex; align-items: center; gap: 8px; padding: 22px 0; color: var(--text-3); font-size: 12.5px; }
.person-empty { display: block; padding: 22px 0; color: var(--text-3); font-size: 12.5px; line-height: 1.7; text-align: center; }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: 12px; max-height: min(56dvh, 520px); overflow-y: auto; padding: 2px; }
.ref-card { position: relative; overflow: hidden; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--media-bg); transition: border-color var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.ref-card:hover { transform: translateY(-2px); border-color: var(--accent-line); }
.ref-card.stale { opacity: .55; border-style: dashed; }
.ref-thumb { display: block; width: 100%; aspect-ratio: 1; overflow: hidden; }
.ref-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ref-info { padding: 8px 10px 10px; }
.ref-info strong { display: block; color: var(--text-1); font-size: 11.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ref-info span { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 6px; }
.ref-info em { padding: 2px 7px; border-radius: var(--r-pill); border: 1px solid var(--stroke); color: var(--text-3); font-size: 10px; font-style: normal; }
.ref-info em.head { color: color-mix(in oklab, var(--accent-2) 70%, var(--text-1)); border-color: color-mix(in srgb, var(--accent-2) 32%, transparent); }
.ref-info em.face { color: var(--accent-ink); border-color: var(--accent-line); }
.ref-info em.manual { color: color-mix(in oklab, var(--success) 70%, var(--text-1)); border-color: color-mix(in srgb, var(--success) 32%, transparent); }
.ref-info em.stale-tag { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 34%, transparent); }
.ref-drop { position: absolute; top: 8px; right: 8px; display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(12px); backdrop-filter: blur(12px); color: var(--text-2); transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.ref-drop:hover { color: var(--danger); background: color-mix(in srgb, var(--danger) 18%, var(--chrome)); }
.detail-actions { display: flex; gap: 9px; margin-top: 18px; }
@media (max-width: 720px) { .ref-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); max-height: 52dvh; } }
</style>

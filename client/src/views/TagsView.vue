<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, Images, Plus, ScanFace, ShieldCheck, Tags, Trash2, Users } from 'lucide-vue-next'
import type { TagItem } from '../types'
import { PERSON_COLOR, tagDot } from './shared'

const props = defineProps<{
  tags: TagItem[]
  personTags: TagItem[]
  normalTags: TagItem[]
  imageCount: number
  columns: number
  personGroupOpen: boolean
  referenceCounts: Record<string, number>
  isAdmin: boolean
}>()
const emit = defineEmits<{
  newTag: [person: boolean]
  togglePersonGroup: []
  openRow: [tag: TagItem]
  context: [tag: TagItem, event: MouseEvent]
  pressStart: [tag: TagItem, event: TouchEvent]
  pressCancel: []
  pressEnd: [event: TouchEvent]
  openReferences: [tag: TagItem]
  batchTag: [tag: TagItem]
  toggleR18: [tag: TagItem]
  removeTag: [tag: TagItem]
  'update:columns': [value: number]
}>()

/** 「全部标签」的排序：默认按图片数量，也可按名称 —— 原来这颗按钮是个没接线的摆设 */
const sortMode = ref<'count' | 'name'>('count')
const sortedTags = computed(() => {
  const list = [...props.normalTags]
  return sortMode.value === 'count'
    ? list.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    : list.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})
function ratio(count: number) { return props.imageCount ? `${Math.min(100, (count / props.imageCount) * 100).toFixed(1)}%` : '0%' }
function cycleSort() { sortMode.value = sortMode.value === 'count' ? 'name' : 'count' }
</script>

<template>
  <div class="tags-view">
    <div class="page-heading rise">
      <div>
        <p class="eyebrow"><Tags :size="14" /> ORGANIZE SMARTER</p>
        <h1>智能标签</h1>
        <p class="subheading">点击人物看 TA 的全部标签，长按（手机）/ 右键标签可放入人物组或批量加图。</p>
      </div>
      <button class="primary-button" @click="emit('newTag', false)"><Plus :size="17" />新建标签</button>
    </div>

    <div class="summary rise" style="--i: 1">
      <div><span class="sum-icon violet"><Tags :size="18" /></span><strong class="num">{{ props.tags.length }}</strong><small>自定义标签</small></div>
      <div><span class="sum-icon green"><Users :size="18" /></span><strong class="num">{{ props.personTags.length }}</strong><small>人物组人名</small></div>
      <div><span class="sum-icon cyan"><Images :size="18" /></span><strong class="num">{{ props.tags.reduce((sum, tag) => sum + tag.count, 0) }}</strong><small>标签关联</small></div>
    </div>

    <section class="panel person-panel rise" style="--i: 2">
      <header class="panel-heading">
        <div><h2><Users :size="16" /> 人物组</h2><p>人名标签统一绿色圆点；点击人名查看 TA 的全部标签。</p></div>
        <div class="head-actions">
          <button class="filter-button" @click="emit('togglePersonGroup')"><ChevronDown :size="15" :class="{ flip: !props.personGroupOpen }" />{{ props.personGroupOpen ? '收起' : '展开' }}</button>
          <button class="primary-button small" @click="emit('newTag', true)"><Plus :size="15" />新建人物</button>
        </div>
      </header>

      <div v-if="props.personGroupOpen" class="person-chips">
        <div
          v-for="tag in props.personTags"
          :key="tag.id"
          class="person-chip"
          @contextmenu.prevent.stop="emit('context', tag, $event)"
          @touchstart.passive="emit('pressStart', tag, $event)"
          @touchmove.passive="emit('pressCancel')"
          @touchend="emit('pressEnd', $event)"
          @touchcancel="emit('pressCancel')"
        >
          <button class="chip-main" @click="emit('openRow', tag)">
            <span class="tag-color person" :style="{ background: PERSON_COLOR }"></span>
            <strong>{{ tag.name }}</strong>
            <small class="mono">{{ tag.count }} 张</small>
          </button>
          <button
            class="chip-action"
            :class="{ filled: (props.referenceCounts[tag.id] || 0) > 0 }"
            :title="`查看「${tag.name}」的参考图库（${props.referenceCounts[tag.id] || 0} 张）`"
            @click.stop="emit('openReferences', tag)"
          >
            <ScanFace :size="13" /><em v-if="props.referenceCounts[tag.id]">{{ props.referenceCounts[tag.id] }}</em>
          </button>
          <button class="chip-action" :title="`为「${tag.name}」批量添加图片`" @click.stop="emit('batchTag', tag)"><Tags :size="13" /></button>
        </div>
        <span v-if="!props.personTags.length" class="person-empty">还没有人物。点右上角「新建人物」，或长按下方「全部标签」里的标签 → 放入人物组。</span>
      </div>
    </section>

    <section class="panel rise" style="--i: 3">
      <header class="panel-heading">
        <div><h2>全部标签</h2><p>左键筛选图片，长按（手机）/ 右键可放入人物组或批量关联多张图片。</p></div>
        <div class="head-actions">
          <button class="filter-button" :title="sortMode === 'count' ? '当前按数量排序，点击改按名称' : '当前按名称排序，点击改按数量'" @click="cycleSort">
            <Tags :size="15" />{{ sortMode === 'count' ? '按数量' : '按名称' }}
          </button>
          <div class="segmented cols-switch" title="每排显示数量">
            <button v-for="n in [1, 2, 3]" :key="n" :class="{ active: props.columns === n }" @click="emit('update:columns', n)">{{ n }}</button>
          </div>
        </div>
      </header>

      <div class="tag-rows" :style="{ '--tag-columns': props.columns }">
        <div
          v-for="tag in sortedTags"
          :key="tag.id"
          class="tag-row"
          :class="{ 'has-person': tag.person }"
          @click="emit('openRow', tag)"
          @contextmenu.prevent.stop="emit('context', tag, $event)"
          @touchstart.passive="emit('pressStart', tag, $event)"
          @touchmove.passive="emit('pressCancel')"
          @touchend="emit('pressEnd', $event)"
          @touchcancel="emit('pressCancel')"
        >
          <span class="tag-color" :style="{ background: tagDot(tag) }"></span>
          <strong><span class="tag-name">{{ tag.name }}</span><em v-if="tag.r18 && tag.name !== 'R-18'" class="r18-chip">R18</em></strong>
          <span class="tag-count mono">{{ tag.count }} 张图片 · {{ ratio(tag.count) }}</span>
          <div class="tag-bar"><i :style="{ width: ratio(tag.count), background: tagDot(tag) }"></i></div>
          <div class="row-actions">
            <button class="icon-button small" :title="`为「${tag.name}」批量添加图片`" @click.stop="emit('batchTag', tag)"><Tags :size="15" /></button>
            <button v-if="tag.name !== 'R-18'" class="icon-button small" :class="{ on: tag.r18 }" :title="tag.r18 ? '取消 R18 链接标记' : '标记为 R18 链接标签（仅 R18 模式可见）'" @click.stop="emit('toggleR18', tag)"><ShieldCheck :size="15" /></button>
            <button v-if="props.isAdmin" class="icon-button small" title="删除标签" @click.stop="emit('removeTag', tag)"><Trash2 :size="15" /></button>
          </div>
        </div>
        <span v-if="!sortedTags.length" class="person-empty">还没有标签。点右上角「新建标签」开始整理图片。</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tags-view { display: flex; flex-direction: column; gap: 16px; }
.summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; overflow: hidden; border-radius: var(--r-lg); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl), var(--shadow-1); }
.summary > div { display: flex; align-items: center; gap: 14px; padding: 16px 18px; }
.summary > div + div { border-left: 1px solid var(--stroke); }
.sum-icon { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 14px; color: var(--c); background: color-mix(in srgb, var(--c) 15%, transparent); box-shadow: 0 0 26px -6px color-mix(in srgb, var(--c) 60%, transparent), inset 0 1px 0 var(--highlight); }
.sum-icon.violet { --c: var(--accent-1); }
.sum-icon.cyan { --c: var(--accent-2); }
.sum-icon.green { --c: var(--person); }
.summary strong { font-size: 24px; font-weight: 600; line-height: 1; }
.summary small { color: var(--text-3); font-size: 11.5px; }
.person-panel, .tags-view > section.panel { padding: 20px 22px 22px; }
.head-actions { display: flex; align-items: center; gap: 9px; }
.head-actions .filter-button svg.flip { transform: rotate(-90deg); }
.cols-switch button { height: 26px; padding: 0 10px; font-size: 11.5px; }

.person-chips { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 16px; }
.person-chip { display: flex; align-items: center; overflow: hidden; border-radius: var(--r-pill); border: 1px solid color-mix(in srgb, var(--person) 28%, transparent); background: color-mix(in srgb, var(--person) 8%, transparent); transition: border-color var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.person-chip:hover { transform: translateY(-2px); border-color: color-mix(in srgb, var(--person) 48%, transparent); }
.chip-main { display: flex; align-items: center; gap: 9px; height: 36px; padding: 0 12px; color: var(--text-1); }
.chip-main strong { font-size: 13px; font-weight: 600; }
.chip-main small { color: var(--text-3); font-size: 11px; }
.chip-action { display: flex; align-items: center; gap: 4px; height: 36px; padding: 0 11px; border-left: 1px solid color-mix(in srgb, var(--person) 22%, transparent); color: var(--text-2); transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.chip-action:hover { color: var(--text-1); background: color-mix(in srgb, var(--person) 14%, transparent); }
.chip-action.filled { color: var(--person); }
.chip-action em { font: 500 10.5px var(--font-mono); font-style: normal; }

.tag-rows { display: grid; grid-template-columns: repeat(var(--tag-columns, 2), minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
.tag-row { position: relative; display: grid; grid-template-columns: 10px minmax(0, 1fr) auto; align-items: center; gap: 6px 12px; padding: 13px 14px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); cursor: pointer; transition: transform var(--t-mid) var(--ease), border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.tag-row:hover { transform: translateY(-2px); border-color: var(--accent-line); background: var(--field-hover); }
.tag-color { width: 10px; height: 10px; border-radius: 50%; box-shadow: 0 0 10px -1px currentColor; }
.tag-color.person { box-shadow: 0 0 10px color-mix(in srgb, var(--person) 75%, transparent); }
.tag-row strong { display: flex; align-items: center; gap: 7px; min-width: 0; font-size: 13.5px; font-weight: 600; }
.tag-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.r18-chip { flex: none; padding: 1px 6px; border-radius: var(--r-pill); border: 1px solid color-mix(in srgb, var(--danger) 40%, transparent); color: var(--danger); font-size: 9.5px; font-weight: 600; font-style: normal; }
.tag-count { color: var(--text-3); font-size: 11px; white-space: nowrap; }
.tag-bar { grid-column: 2 / -1; height: 3px; overflow: hidden; border-radius: 3px; background: var(--track); }
.tag-bar i { display: block; height: 100%; border-radius: inherit; transition: width var(--t-slow) var(--ease); }
.row-actions { display: flex; align-items: center; gap: 4px; opacity: .55; transition: opacity var(--t-fast) var(--ease); }
.tag-row:hover .row-actions, .tag-row:focus-within .row-actions { opacity: 1; }
.row-actions .icon-button.on { color: var(--danger); background: color-mix(in srgb, var(--danger) 13%, transparent); }
.person-empty { display: block; grid-column: 1 / -1; padding: 18px 0 4px; color: var(--text-3); font-size: 12.5px; line-height: 1.7; }

@media (max-width: 1050px) { .tag-rows { grid-template-columns: repeat(min(var(--tag-columns, 2), 2), minmax(0, 1fr)); } }
@media (max-width: 720px) {
  .summary { grid-template-columns: 1fr; }
  .summary > div + div { border-left: 0; border-top: 1px solid var(--stroke); }
  .person-panel, .tags-view > section.panel { padding: 16px; }
  .head-actions { flex-wrap: wrap; justify-content: flex-end; }
  .tag-rows { grid-template-columns: 1fr; }
  .row-actions { opacity: 1; }
}
</style>

<script setup lang="ts">
import { ChevronLeft, Images, Search, Tags, X } from 'lucide-vue-next'
import type { TagItem } from '../types'

defineProps<{ tags: TagItem[] }>()
const emit = defineEmits<{ close: []; select: [name: string] }>()
</script>

<template>
  <div class="tag-browser-overlay">
    <div class="tag-browser-head panel">
      <button class="filter-button settings-back" @click="emit('close')"><ChevronLeft :size="17" />返回工作区</button>
      <div class="settings-overlay-title"><Tags :size="18" /><strong>智能标签</strong></div>
      <button class="icon-button settings-close" @click="emit('close')"><X :size="19" /></button>
    </div>
    <div class="tag-browser-body">
      <div class="settings-hero">
        <div>
          <p class="eyebrow"><Tags :size="14" /> ORGANIZE SMARTER</p>
          <h1>按标签浏览</h1>
          <p>点击任意标签，直接查看该标签关联的全部图片。</p>
        </div>
        <div class="tag-browser-search"><Search :size="15" /><input placeholder="搜索标签..." /></div>
      </div>
      <div class="tag-browser-grid">
        <button v-for="(tag, index) in tags" :key="tag.id" class="tag-browser-card panel glow-card lift rise" :style="{ '--i': index % 12 }" @click="emit('select', tag.name)">
          <span class="tag-browser-color" :style="{ background: tag.color }"></span>
          <span class="tag-browser-text"><strong>{{ tag.name }}</strong><small><Images :size="13" />{{ tag.count }} 张图片</small></span>
          <ChevronLeft :size="17" class="tag-browser-go" />
        </button>
        <div v-if="!tags.length" class="empty-state"><Tags :size="30" /><strong>还没有标签</strong></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   智能标签浏览器 · 极光玻璃
   全屏玻璃覆盖层：吸顶工具条 + 标签卡网格。
   注意：这里刻意不写 backdrop-filter 的自定义属性以外的模糊叠层，
   顶部条与卡片都是玻璃原语本身。
   ============================================================ */
.tag-browser-overlay { position: fixed; z-index: 55; inset: 0; overflow-y: auto; overscroll-behavior: contain; background: radial-gradient(1100px 620px at 84% -12%, color-mix(in srgb, var(--accent-1) 20%, transparent), transparent 62%), linear-gradient(180deg, var(--bg-1), var(--bg-0) 72%); animation: fade-in var(--t-mid) var(--ease); }
.tag-browser-head { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: var(--s-4); height: 62px; padding: 0 max(18px, calc((100% - 1100px) / 2 + 24px)); border-width: 0 0 1px; border-radius: 0; background: var(--chrome); -webkit-backdrop-filter: blur(22px) saturate(1.6); backdrop-filter: blur(22px) saturate(1.6); box-shadow: 0 12px 30px -26px rgba(0, 0, 0, .8); }
.tag-browser-head::after { display: none; }
.settings-overlay-title { display: flex; align-items: center; gap: 9px; font: 600 14.5px var(--font-display); letter-spacing: -.02em; color: var(--text-1); }
.settings-overlay-title svg { color: var(--accent-ink); }
.settings-close { margin-left: auto; }
.tag-browser-body { max-width: 1100px; margin: 0 auto; padding: 42px 24px 70px; }
.settings-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-5); margin-bottom: 27px; }
.settings-hero h1 { margin: 0; color: var(--text-1); font: 600 clamp(26px, 2.6vw, 34px)/1.12 var(--font-display); letter-spacing: -.04em; }
.settings-hero p:not(.eyebrow) { margin: 10px 0 0; color: var(--text-2); font-size: 13px; line-height: 1.7; }
.tag-browser-search { display: flex; align-items: center; gap: 9px; min-width: 240px; height: 42px; padding: 0 14px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-3); transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), box-shadow var(--t-mid) var(--ease); }
.tag-browser-search:focus-within { border-color: var(--accent-line); background: var(--field-hover); box-shadow: 0 0 0 4px var(--accent-soft); }
.tag-browser-search input { width: 100%; min-width: 0; height: 100%; border: 0; outline: 0; background: transparent; color: var(--text-1); font-size: 12.5px; }
.tag-browser-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--s-3); }
.tag-browser-card { display: flex; align-items: center; gap: var(--s-3); min-height: 94px; padding: 17px 18px; color: inherit; text-align: left; }
.tag-browser-text { flex: 1; min-width: 0; }
.tag-browser-card strong { display: block; overflow: hidden; color: var(--text-1); font-size: 13.5px; font-weight: 600; white-space: nowrap; text-overflow: ellipsis; }
.tag-browser-card small { display: flex; align-items: center; gap: 5px; margin-top: 7px; color: var(--text-3); font: 11px var(--font-mono); }
.tag-browser-color { width: 12px; height: 12px; flex: none; border-radius: 50%; box-shadow: 0 0 14px currentColor; }
.tag-browser-go { flex: none; color: var(--text-3); transform: rotate(180deg); transition: transform var(--t-mid) var(--ease), color var(--t-fast) var(--ease); }
.tag-browser-card:hover .tag-browser-go { color: var(--accent-ink); transform: rotate(180deg) translateX(-3px); }

@media (max-width: 900px) { .tag-browser-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) {
  .tag-browser-head { height: 58px; padding: 0 14px; -webkit-backdrop-filter: none; backdrop-filter: none; background: var(--chrome-solid); }
  .tag-browser-body { padding: 26px 15px 60px; }
  .settings-hero { flex-direction: column; align-items: stretch; gap: 16px; margin-bottom: 20px; }
  .tag-browser-search { width: 100%; min-width: 0; }
  .tag-browser-grid { grid-template-columns: 1fr; gap: var(--s-2); }
  .tag-browser-card { min-height: 84px; padding: 15px; }
  .settings-back { height: 32px; padding: 0 11px; font-size: 11.5px; }
  .settings-close { width: 32px; height: 32px; }
}
</style>

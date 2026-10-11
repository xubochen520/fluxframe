<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, ChevronDown, LoaderCircle, Plus, ShieldCheck, Sparkles, Upload, X } from 'lucide-vue-next'
import { api, type TagAdviceItem, type UploadAnalysisItem } from '../api'
import type { TagItem } from '../types'

type ReviewItem = UploadAnalysisItem & { file: File; previewUrl: string }

const props = defineProps<{ show: boolean; availableTags?: TagItem[]; r18Mode?: boolean }>()
const emit = defineEmits<{ close: []; completed: [count: number]; toggleR18: [] }>()
const items = ref<ReviewItem[]>([])
const stage = ref<'select' | 'analyzing' | 'reviewing' | 'uploading'>('select')
const error = ref('')
const aiEnabled = ref(false)
const aiModel = ref('')
const taggerEnabled = ref(false)
const tagInputs = ref<Record<string, string>>({})
const tagPickerOpen = ref<Record<string, boolean>>({})

/** 确认页里可勾选的建议：按类别拆开，用户点一下就加进这张图的标签 */
function adviceGroups(item: ReviewItem): Array<{ key: string; label: string; hint: string; list: TagAdviceItem[] }> {
  const a = item.advice
  if (!a) return []
  return [
    { key: 'character', label: '角色', hint: '识别得很准，建议保留', list: a.character },
    { key: 'copyright', label: '作品', hint: '', list: a.copyright },
    { key: 'general', label: '属性', hint: '仅供建议，白丝/黑丝这类色系判断不太可靠', list: a.general },
  ].filter((g) => g.list.length)
}
/** 建议里还没被采纳的部分（已加进 tags 的不再显示） */
function pendingAdvice(item: ReviewItem, list: TagAdviceItem[]) {
  return list.filter((s) => !item.tags.includes(s.name))
}
function applyAdvice(item: ReviewItem, name: string) {
  if (!item.tags.includes(name)) item.tags.push(name)
}
/** 一键采纳全部建议（角色与作品已自动填入，这里主要补属性） */
function applyAllAdvice(item: ReviewItem) {
  const a = item.advice
  if (!a) return
  for (const s of [...a.character, ...a.copyright, ...a.general]) applyAdvice(item, s.name)
}
function adviceCount(item: ReviewItem) {
  const a = item.advice
  if (!a) return 0
  return pendingAdvice(item, a.character).length + pendingAdvice(item, a.copyright).length + pendingAdvice(item, a.general).length
}

const readyItems = computed(() => items.value.filter((item) => item.tempId && !item.duplicate))
const selectedCount = computed(() => readyItems.value.length)

function revokePreview(item: ReviewItem) { URL.revokeObjectURL(item.previewUrl) }
function clearLocalItems() { items.value.forEach(revokePreview); items.value = []; tagInputs.value = {}; tagPickerOpen.value = {} }
async function cancelPending() {
  await Promise.all(items.value.filter((item) => item.tempId).map((item) => api.cancelUpload(item.tempId!).catch(() => undefined)))
}
async function close() {
  if (stage.value === 'uploading' || stage.value === 'analyzing') return
  await cancelPending()
  clearLocalItems()
  stage.value = 'select'
  error.value = ''
  emit('close')
}
async function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  await cancelPending()
  clearLocalItems()
  error.value = ''
  stage.value = 'analyzing'
  const files = Array.from(input.files)
  try {
    const response = await api.analyzeUpload(files)
    aiEnabled.value = response.aiEnabled
    aiModel.value = response.aiModel
    taggerEnabled.value = response.taggerEnabled === true
    items.value = response.items.map((item) => ({ ...item, file: files[item.sourceIndex], previewUrl: URL.createObjectURL(files[item.sourceIndex]) })).filter((item) => item.file)
    stage.value = 'reviewing'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片分析失败，请重试'
    stage.value = 'select'
  } finally { input.value = '' }
}
function isTagR18(name: string) { return name === 'R-18' || (props.availableTags || []).some((tag) => tag.name === name && tag.r18) }
function isVideo(item: { mimeType?: string }) { return !!item.mimeType && item.mimeType.startsWith('video/') }
function availableFor(item: ReviewItem): string[] {
  const queryText = (tagInputs.value[item.tempId!] || '').trim().replace(/^#/, '').toLowerCase()
  return (props.availableTags || []).map((tag) => tag.name).filter((name) => (props.r18Mode || !isTagR18(name)) && name !== 'R-18' && !item.tags.includes(name) && (!queryText || name.toLowerCase().includes(queryText))).slice(0, 10)
}
function tagColor(name: string) { return props.availableTags?.find((tag) => tag.name === name)?.color || '#a78bfa' }
function openTagPicker(item: ReviewItem) { if (!item.tempId) return; tagPickerOpen.value = { [item.tempId]: true } }
function toggleTagPicker(item: ReviewItem) {
  if (!item.tempId) return
  tagPickerOpen.value = tagPickerOpen.value[item.tempId] ? {} : { [item.tempId]: true }
}
function closeTagPicker(item: ReviewItem) { if (!item.tempId) return; tagPickerOpen.value = {} }
function addTagValue(item: ReviewItem, value: string, keepPickerOpen = false) {
  if (!item.tempId || !value) return
  if (!props.r18Mode && isTagR18(value)) return
  if (!item.tags.includes(value)) item.tags.push(value)
  tagInputs.value[item.tempId] = ''
  /* 从下拉里选标签时保持展开，方便连续多选；手动回车提交新标签时才收起 */
  if (!keepPickerOpen) tagPickerOpen.value = {}
}
function pickTag(item: ReviewItem, name: string) { addTagValue(item, name, true) }
function submitTagInput(item: ReviewItem) {
  const value = (tagInputs.value[item.tempId!] || '').trim().replace(/^#/, '')
  if (!value) return
  const existing = (props.availableTags || []).map((tag) => tag.name).find((name) => (props.r18Mode || !isTagR18(name)) && name !== 'R-18' && !item.tags.includes(name) && name.toLowerCase() === value.toLowerCase())
  addTagValue(item, existing || value)
}
function toggleR18(item: ReviewItem) {
  if (item.tags.includes('R-18')) item.tags = item.tags.filter((tag) => tag !== 'R-18')
  else item.tags.push('R-18')
}
function toggleTag(item: ReviewItem, tag: string) { item.tags = item.tags.filter((value) => value !== tag) }
async function removeItem(index: number) {
  const item = items.value[index]
  if (!item) return
  if (item.tempId) await api.cancelUpload(item.tempId).catch(() => undefined)
  revokePreview(item)
  items.value.splice(index, 1)
}
async function complete() {
  if (!readyItems.value.length) return
  stage.value = 'uploading'
  error.value = ''
  try {
    const response = await api.completeUpload(readyItems.value.map((item) => ({ tempId: item.tempId!, name: item.name.trim() || item.fileName, tags: item.tags })))
    clearLocalItems()
    stage.value = 'select'
    emit('completed', response.items.length)
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '上传失败，请重试'
    stage.value = 'reviewing'
  }
}
onBeforeUnmount(() => { items.value.forEach(revokePreview); void cancelPending() })
</script>

<template>
  <div v-if="props.show" class="modal-backdrop upload-review-backdrop" @click.self="close">
    <div class="modal upload-review-modal">
      <button class="modal-close" :disabled="stage === 'analyzing' || stage === 'uploading'" @click="close"><X :size="18" /></button>
      <div class="upload-icon"><Sparkles v-if="stage === 'reviewing'" :size="25" /><Upload v-else :size="25" /></div>
      <h2>{{ stage === 'reviewing' ? '确认媒体标签' : stage === 'analyzing' ? 'AI 正在分析' : stage === 'uploading' ? '正在完成上传' : '上传媒体' }}</h2>
      <p v-if="stage === 'select'">支持图片（JPG、PNG、WEBP）与视频（MP4、WebM、MOV 等），视频会自动带上「视频」标签。</p>
      <p v-else-if="stage === 'analyzing'" class="upload-status"><LoaderCircle :size="15" class="spin" />正在逐张分析图片内容，请稍候。</p>
      <p v-else-if="stage === 'uploading'" class="upload-status"><LoaderCircle :size="15" class="spin" />正在写入文件和数据库。</p>
      <p v-else class="upload-status"><Sparkles :size="15" />{{ aiEnabled ? `AI 建议已生成（${aiModel}），视频已自动标记「视频」。点击标签可删除。` : taggerEnabled ? '自动打标已完成（本地分类器），点建议即可加入标签；视频已自动标记「视频」。' : '视频已自动标记「视频」，可手动调整或补充标签。' }}</p>

      <label v-if="stage === 'select'" class="drop-zone upload-review-drop-zone">
        <input type="file" accept="image/*,video/*" multiple @change="chooseFiles" />
        <Upload :size="25" /><strong>点击选择图片或视频</strong><span>选择后会先进入标签确认</span>
      </label>

      <div v-else-if="stage === 'reviewing'" class="upload-review-content">
        <div class="upload-review-toolbar"><strong>{{ selectedCount }} 个待上传</strong><div class="upload-toolbar-right"><button class="r18-toggle" :class="{ on: props.r18Mode }" :title="props.r18Mode ? '关闭 R18 模式' : '开启 R18 模式后可选择 R18 链接标签'" @click="emit('toggleR18')"><ShieldCheck :size="13" />R18<i></i></button><span>{{ items.length }} 个已分析</span></div></div>
        <div class="upload-review-grid">
          <article v-for="(item, index) in items" :key="item.tempId || `${item.fileName}-${index}`" class="upload-review-item" :class="{ duplicate: item.duplicate }">
            <button class="review-remove" title="移除" @click="removeItem(index)"><X :size="14" /></button>
            <video v-if="isVideo(item)" class="review-preview" :src="item.previewUrl + '#t=0.5'" muted playsinline preload="metadata" :title="item.fileName" /><img v-else class="review-preview" :src="item.previewUrl" :alt="item.fileName" />
            <div class="review-info">
              <input v-model="item.name" class="review-name" :disabled="item.duplicate" />
              <small v-if="item.duplicate" class="review-warning">与已有图片重复：{{ item.duplicateName }}</small>
              <small v-else-if="item.aiError" class="review-warning">AI 分析失败，可手动添加标签</small>
              <div v-if="!item.duplicate && adviceCount(item)" class="review-advice"><div class="review-advice-head"><span><Sparkles :size="12" />识别建议</span><button type="button" class="review-advice-all" @click="applyAllAdvice(item)">全部采纳</button></div><div v-for="group in adviceGroups(item)" :key="group.key" class="review-advice-row"><em :class="group.key" :title="group.hint">{{ group.label }}</em><div class="review-advice-chips"><button v-for="s in pendingAdvice(item, group.list)" :key="s.raw" type="button" class="review-advice-chip" :title="`${s.raw} · 置信度 ${(s.score * 100).toFixed(0)}%${group.hint ? ' · ' + group.hint : ''}`" @click="applyAdvice(item, s.name)"><Plus :size="10" />{{ s.name }}<small>{{ (s.score * 100).toFixed(0) }}%</small></button></div></div></div>
              <div class="review-tags"><button v-for="tag in item.tags" :key="tag" class="review-tag" @click="toggleTag(item, tag)">#{{ tag }} <X :size="11" /></button><span v-if="!item.tags.length" class="review-empty-tag">暂无标签</span></div>
              <div v-if="!item.duplicate && props.r18Mode" class="review-r18-row"><span>R-18 标记</span><button type="button" class="r18-switch" :class="{ on: item.tags.includes('R-18') }" :aria-pressed="item.tags.includes('R-18')" @click="toggleR18(item)"><i></i><em>{{ item.tags.includes('R-18') ? '已开启' : '未开启' }}</em></button></div>
              <form v-if="!item.duplicate" class="review-add-tag" @submit.prevent="submitTagInput(item)"><div class="review-tag-combo"><input v-model="tagInputs[item.tempId!]" :placeholder="availableFor(item).length ? '选择或输入标签' : '输入新标签'" maxlength="40" @focus="openTagPicker(item)" @keydown.esc="closeTagPicker(item)" @blur="closeTagPicker(item)" /><button v-if="availableFor(item).length" type="button" class="review-tag-combo-toggle" :class="{ open: tagPickerOpen[item.tempId!] }" title="选择已有标签" @mousedown.prevent @click="toggleTagPicker(item)"><ChevronDown :size="13" /></button><button type="submit" class="review-tag-combo-submit" title="添加标签"><Plus :size="14" /></button><div v-if="tagPickerOpen[item.tempId!]" class="review-tag-dropdown"><button v-for="name in availableFor(item)" :key="name" type="button" class="review-tag-option" @mousedown.prevent @click="pickTag(item, name)"><i :style="{ background: tagColor(name) }"></i>{{ name }}</button><span v-if="!availableFor(item).length" class="review-tag-dropdown-empty">没有可选标签，可输入新标签</span></div></div></form>
            </div>
          </article>
        </div>
        <button class="primary-button full" :disabled="!selectedCount" @click="complete"><Check :size="17" />完成上传（{{ selectedCount }} 个）</button>
      </div>
      <div v-if="error" class="auth-error upload-review-error">{{ error }}</div>
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   上传审核 · 极光玻璃
   背板加极光光晕；卡片本体用 .modal 原语；网格卡片是实玻璃，无 backdrop-filter
   ============================================================ */
.upload-review-backdrop { align-items: center; }
.upload-review-backdrop::before { content: ''; position: absolute; inset: -20% -10% auto; height: 60vh; pointer-events: none; background: radial-gradient(46% 100% at 28% 0%, color-mix(in srgb, var(--accent-1) 30%, transparent), transparent 72%), radial-gradient(40% 90% at 78% 0%, color-mix(in srgb, var(--accent-2) 18%, transparent), transparent 70%); }
.upload-review-modal { display: flex; flex-direction: column; width: min(1000px, calc(100vw - 28px)); max-height: min(880px, calc(100dvh - 28px)); }
.upload-status { display: flex; align-items: center; justify-content: center; gap: 7px; }
.upload-status svg { flex: none; color: var(--accent-ink); }
.spin { animation: upload-spin .9s linear infinite; }
.upload-review-content { display: flex; flex-direction: column; gap: var(--s-4); }
.upload-review-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); padding: 10px 14px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-3); font-size: 11px; }
.upload-review-toolbar strong { color: var(--text-1); font: 600 13px var(--font-display); letter-spacing: -.01em; }
.upload-toolbar-right { display: flex; align-items: center; gap: var(--s-3); }
.upload-toolbar-right .r18-toggle { height: 28px; padding: 0 4px 0 10px; font-size: 10px; }
.upload-toolbar-right .r18-toggle i { width: 26px; height: 17px; }
.upload-toolbar-right .r18-toggle i::after { top: 2.5px; left: 2.5px; width: 12px; height: 12px; }
.upload-toolbar-right .r18-toggle.on i::after { transform: translateX(9px); }
.upload-review-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--s-3); max-height: 540px; overflow: auto; padding: 2px; }
.upload-review-item { position: relative; display: flex; flex-direction: column; min-width: 0; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl); transition: border-color var(--t-fast) var(--ease); }
.upload-review-item:hover { border-color: var(--stroke-strong); }
.upload-review-item.duplicate { opacity: .68; }
.review-remove { position: absolute; z-index: 1; top: 8px; right: 8px; display: grid; place-items: center; width: 26px; height: 26px; border-radius: 50%; border: 1px solid rgba(255, 255, 255, .18); background: rgba(8, 10, 20, .62); color: #fff; opacity: 0; transform: scale(.88); transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.upload-review-item:hover .review-remove, .review-remove:focus-visible { opacity: 1; transform: none; }
.review-remove:hover { background: var(--danger); }
.review-preview { display: block; width: 100%; height: 168px; border-radius: var(--r-md) var(--r-md) 0 0; background: var(--media-bg); object-fit: contain; }
.review-info { display: flex; flex-direction: column; padding: 11px 12px 12px; }
.review-name { width: 100%; padding: 6px 8px; border: 1px solid transparent; border-radius: 9px; outline: 0; background: transparent; color: var(--text-1); font-size: 12.5px; font-weight: 600; text-overflow: ellipsis; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.review-name:hover:not(:disabled) { background: var(--field); }
.review-name:focus { border-color: var(--accent-line); background: var(--field-hover); box-shadow: 0 0 0 4px var(--accent-soft); }
.review-tags { display: flex; flex-wrap: wrap; gap: 5px; min-height: 24px; margin-top: 9px; }
.review-tag { display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 9px; border-radius: var(--r-pill); border: 1px solid var(--accent-line); background: var(--accent-soft); color: var(--accent-ink); font-size: 10.5px; transition: background var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.review-tag:hover { background: color-mix(in srgb, var(--accent-1) 26%, transparent); transform: translateY(-1px); }
.review-empty-tag { color: var(--text-3); font-size: 10.5px; }
.review-add-tag { margin-top: 10px; }
.review-tag-combo { position: relative; display: flex; gap: 5px; min-width: 0; }
.review-tag-combo input { flex: 1; min-width: 0; height: 32px; padding: 0 10px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); font-size: 11px; outline: 0; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.review-tag-combo input:focus { border-color: var(--accent-line); background: var(--field-hover); }
.review-tag-combo-toggle, .review-tag-combo-submit { display: grid; place-items: center; width: 30px; height: 32px; flex: none; border-radius: var(--r-sm); transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.review-tag-combo-toggle { border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); }
.review-tag-combo-toggle:hover { background: var(--field-hover); color: var(--text-1); }
.review-tag-combo-toggle.open { border-color: transparent; background: var(--accent-grad); color: var(--on-accent); }
.review-tag-combo-submit { background: var(--accent-grad); color: var(--on-accent); box-shadow: 0 10px 22px -12px var(--glow), inset 0 1px 0 rgba(255, 255, 255, .26); }
.review-tag-combo-submit:active { transform: scale(.94); }
.review-tag-dropdown { position: absolute; z-index: 20; top: calc(100% + 6px); left: 0; right: 0; display: flex; flex-direction: column; gap: 2px; max-height: 168px; overflow: auto; padding: 5px; border-radius: var(--r-md); border: 1px solid var(--stroke-strong); background: var(--chrome); box-shadow: var(--edge-hl), var(--shadow-2); -webkit-backdrop-filter: blur(22px) saturate(1.5); backdrop-filter: blur(22px) saturate(1.5); }
.review-tag-option { display: flex; align-items: center; gap: 7px; padding: 7px 8px; border-radius: 9px; color: var(--text-2); font-size: 11px; text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.review-tag-option:hover { background: var(--field-hover); color: var(--text-1); }
.review-tag-option i { width: 7px; height: 7px; flex: none; border-radius: 50%; box-shadow: 0 0 8px currentColor; }
.review-tag-dropdown-empty { padding: 7px 8px; color: var(--text-3); font-size: 10.5px; }
.review-warning { display: block; margin-top: 7px; color: var(--warn); font-size: 10.5px; line-height: 1.5; }
/* 自动打标建议：按类别分组，点一下采纳 */
.review-advice { margin-top: 10px; padding: 9px; border-radius: var(--r-sm); border: 1px solid var(--accent-line); background: var(--accent-soft); }
.review-advice-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 7px; }
.review-advice-head > span { display: inline-flex; align-items: center; gap: 4px; color: var(--accent-ink); font: 600 10px var(--font-mono); letter-spacing: .06em; }
.review-advice-all { height: 22px; padding: 0 9px; border-radius: var(--r-pill); background: color-mix(in srgb, var(--success) 88%, black); color: #052117; font-size: 10px; font-weight: 600; transition: filter var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.review-advice-all:hover { filter: brightness(1.08); }
.review-advice-all:active { transform: scale(.95); }
.review-advice-row { display: flex; align-items: flex-start; gap: 6px; margin-top: 6px; }
.review-advice-row em { flex: none; padding: 3px 6px; border-radius: var(--r-xs); background: var(--field); color: var(--text-3); font: 9px var(--font-mono); font-style: normal; }
.review-advice-row em.character { border: 1px solid color-mix(in srgb, var(--success) 30%, transparent); background: color-mix(in srgb, var(--success) 14%, transparent); color: color-mix(in oklab, var(--success) 70%, var(--text-1)); }
.review-advice-row em.copyright { border: 1px solid color-mix(in srgb, var(--accent-2) 30%, transparent); background: color-mix(in srgb, var(--accent-2) 14%, transparent); color: var(--accent-2); }
.review-advice-chips { display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; }
.review-advice-chip { display: inline-flex; align-items: center; gap: 3px; height: 24px; padding: 0 8px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 10.5px; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.review-advice-chip:hover { border-color: var(--accent-line); background: var(--field-hover); color: var(--text-1); }
.review-advice-chip:active { transform: scale(.95); }
.review-advice-chip small { color: var(--text-3); font: 9px var(--font-mono); }
.review-r18-row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); margin-top: 10px; color: var(--text-3); font-size: 10.5px; }
.r18-switch { display: inline-flex; align-items: center; gap: 6px; min-width: 72px; padding: 3px 8px 3px 4px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--track); color: var(--text-2); font-size: 9.5px; transition: background var(--t-mid) var(--ease), border-color var(--t-mid) var(--ease), color var(--t-mid) var(--ease); }
.r18-switch i { width: 14px; height: 14px; flex: none; border-radius: 50%; background: #fff; box-shadow: 0 2px 6px rgba(0, 0, 0, .3); transition: transform var(--t-mid) var(--ease); }
.r18-switch.on { border-color: color-mix(in srgb, var(--danger) 45%, transparent); background: color-mix(in srgb, var(--danger) 16%, transparent); color: var(--danger); }
.r18-switch.on i { order: 2; }
.r18-switch em { font-style: normal; white-space: nowrap; }
.upload-review-error { margin-top: var(--s-3); }
@keyframes upload-spin { to { transform: rotate(360deg); } }
@media (max-width: 820px) { .upload-review-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 720px) {
  .upload-review-backdrop::before { display: none; }
  .upload-review-grid { max-height: 55vh; }
  .review-remove { opacity: 1; transform: none; }
  .review-tag-dropdown { -webkit-backdrop-filter: none; backdrop-filter: none; }
}
@media (max-width: 560px) { .upload-review-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .review-preview { height: 128px; } }
</style>

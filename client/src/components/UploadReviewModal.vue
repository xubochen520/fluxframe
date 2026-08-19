<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, ChevronDown, LoaderCircle, Plus, ShieldCheck, Sparkles, Upload, X } from 'lucide-vue-next'
import { api, type UploadAnalysisItem } from '../api'
import type { TagItem } from '../types'

type ReviewItem = UploadAnalysisItem & { file: File; previewUrl: string }

const props = defineProps<{ show: boolean; availableTags?: TagItem[]; r18Mode?: boolean }>()
const emit = defineEmits<{ close: []; completed: [count: number]; toggleR18: [] }>()
const items = ref<ReviewItem[]>([])
const stage = ref<'select' | 'analyzing' | 'reviewing' | 'uploading'>('select')
const error = ref('')
const aiEnabled = ref(false)
const aiModel = ref('')
const tagInputs = ref<Record<string, string>>({})
const tagPickerOpen = ref<Record<string, boolean>>({})

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
    items.value = response.items.map((item) => ({ ...item, file: files[item.sourceIndex], previewUrl: URL.createObjectURL(files[item.sourceIndex]) })).filter((item) => item.file)
    stage.value = 'reviewing'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片分析失败，请重试'
    stage.value = 'select'
  } finally { input.value = '' }
}
function isTagR18(name: string) { return name === 'R-18' || (props.availableTags || []).some((tag) => tag.name === name && tag.r18) }
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
function addTagValue(item: ReviewItem, value: string) {
  if (!item.tempId || !value) return
  if (!props.r18Mode && isTagR18(value)) return
  if (!item.tags.includes(value)) item.tags.push(value)
  tagInputs.value[item.tempId] = ''
  tagPickerOpen.value = {}
}
function pickTag(item: ReviewItem, name: string) { addTagValue(item, name) }
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
      <h2>{{ stage === 'reviewing' ? '确认图片标签' : stage === 'analyzing' ? 'AI 正在分析' : stage === 'uploading' ? '正在完成上传' : '上传图片' }}</h2>
      <p v-if="stage === 'select'">支持 JPG、PNG、WEBP，可一次选择多张图片。</p>
      <p v-else-if="stage === 'analyzing'" class="upload-status"><LoaderCircle :size="15" class="spin" />正在逐张识别图片内容和标签，请稍候。</p>
      <p v-else-if="stage === 'uploading'" class="upload-status"><LoaderCircle :size="15" class="spin" />正在写入原图、缩略图和数据库。</p>
      <p v-else class="upload-status"><Sparkles :size="15" />{{ aiEnabled ? `AI 建议已生成（${aiModel}），点击标签可删除。` : '未配置 AI 服务，可手动添加标签后上传。' }}</p>

      <label v-if="stage === 'select'" class="drop-zone upload-review-drop-zone">
        <input type="file" accept="image/*" multiple @change="chooseFiles" />
        <Upload :size="25" /><strong>点击选择图片</strong><span>选择后会先进入标签确认</span>
      </label>

      <div v-else-if="stage === 'reviewing'" class="upload-review-content">
        <div class="upload-review-toolbar"><strong>{{ selectedCount }} 张待上传</strong><div class="upload-toolbar-right"><button class="r18-toggle" :class="{ on: props.r18Mode }" :title="props.r18Mode ? '关闭 R18 模式' : '开启 R18 模式后可选择 R18 链接标签'" @click="emit('toggleR18')"><ShieldCheck :size="13" />R18<i></i></button><span>{{ items.length }} 张已分析</span></div></div>
        <div class="upload-review-grid">
          <article v-for="(item, index) in items" :key="item.tempId || `${item.fileName}-${index}`" class="upload-review-item" :class="{ duplicate: item.duplicate }">
            <button class="review-remove" title="移除" @click="removeItem(index)"><X :size="14" /></button>
            <img class="review-preview" :src="item.previewUrl" :alt="item.fileName" />
            <div class="review-info">
              <input v-model="item.name" class="review-name" :disabled="item.duplicate" />
              <small v-if="item.duplicate" class="review-warning">与已有图片重复：{{ item.duplicateName }}</small>
              <small v-else-if="item.aiError" class="review-warning">AI 分析失败，可手动添加标签</small>
              <div class="review-tags"><button v-for="tag in item.tags" :key="tag" class="review-tag" @click="toggleTag(item, tag)">#{{ tag }} <X :size="11" /></button><span v-if="!item.tags.length" class="review-empty-tag">暂无标签</span></div>
              <div v-if="!item.duplicate && props.r18Mode" class="review-r18-row"><span>R-18 标记</span><button type="button" class="r18-switch" :class="{ on: item.tags.includes('R-18') }" :aria-pressed="item.tags.includes('R-18')" @click="toggleR18(item)"><i></i><em>{{ item.tags.includes('R-18') ? '已开启' : '未开启' }}</em></button></div>
              <form v-if="!item.duplicate" class="review-add-tag" @submit.prevent="submitTagInput(item)"><div class="review-tag-combo"><input v-model="tagInputs[item.tempId!]" :placeholder="availableFor(item).length ? '选择或输入标签' : '输入新标签'" maxlength="40" @focus="openTagPicker(item)" @keydown.esc="closeTagPicker(item)" @blur="closeTagPicker(item)" /><button v-if="availableFor(item).length" type="button" class="review-tag-combo-toggle" :class="{ open: tagPickerOpen[item.tempId!] }" title="选择已有标签" @mousedown.prevent @click="toggleTagPicker(item)"><ChevronDown :size="13" /></button><button type="submit" class="review-tag-combo-submit" title="添加标签"><Plus :size="14" /></button><div v-if="tagPickerOpen[item.tempId!]" class="review-tag-dropdown"><button v-for="name in availableFor(item)" :key="name" type="button" class="review-tag-option" @mousedown.prevent @click="pickTag(item, name)"><i :style="{ background: tagColor(name) }"></i>{{ name }}</button><span v-if="!availableFor(item).length" class="review-tag-dropdown-empty">没有可选标签，可输入新标签</span></div></div></form>
            </div>
          </article>
        </div>
        <button class="primary-button full" :disabled="!selectedCount" @click="complete"><Check :size="17" />完成上传（{{ selectedCount }} 张）</button>
      </div>
      <div v-if="error" class="auth-error upload-review-error">{{ error }}</div>
    </div>
  </div>
</template>

<style scoped>
.upload-review-modal { width: min(980px, calc(100vw - 28px)); max-height: min(860px, calc(100vh - 28px)); overflow: auto; }
.upload-review-backdrop { align-items: center; }
.upload-status { display: flex; align-items: center; justify-content: center; gap: 7px; }
.spin { animation: upload-spin 1s linear infinite; }
.upload-review-content { display: flex; flex-direction: column; gap: 15px; }
.upload-review-toolbar { display: flex; justify-content: space-between; align-items: center; padding: 10px 12px; border: 1px solid var(--line); border-radius: 9px; color: #7e89a2; font-size: 10px; }
.upload-review-toolbar strong { color: var(--text); font-size: 12px; }
.upload-toolbar-right { display: flex; align-items: center; gap: 10px; }
.upload-toolbar-right .r18-toggle { padding: 5px 9px; font-size: 9px; border-radius: 7px; }
.upload-review-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; max-height: 530px; overflow: auto; padding: 2px; }
.upload-review-item { position: relative; min-width: 0; border: 1px solid var(--line); border-radius: 11px; background: rgba(255,255,255,.035); }
.upload-review-item.duplicate { opacity: .7; }
.review-remove { position: absolute; z-index: 1; top: 7px; right: 7px; display: grid; place-items: center; width: 25px; height: 25px; border-radius: 50%; color: #fff; background: rgba(11,16,32,.75); }
.review-preview { display: block; width: 100%; height: 160px; object-fit: contain; background: rgba(5,9,20,.65); border-radius: 10px 10px 0 0; }
.review-info { padding: 10px; }
.review-name { width: 100%; border: 0; outline: 0; background: transparent; color: var(--text); font-size: 11px; font-weight: 600; text-overflow: ellipsis; }
.review-tags { display: flex; flex-wrap: wrap; gap: 5px; min-height: 22px; margin-top: 8px; }
.review-tag { display: inline-flex; align-items: center; gap: 3px; padding: 4px 6px; border: 1px solid rgba(167,139,250,.27); border-radius: 5px; color: #b9a9ff; background: rgba(167,139,250,.1); font-size: 9px; }
.review-empty-tag { color: #6f7b96; font-size: 9px; }
.review-add-tag { display: flex; gap: 4px; margin-top: 8px; }
.review-tag-combo { position: relative; display: flex; gap: 4px; min-width: 0; flex: 1; }
.review-tag-combo input { min-width: 0; flex: 1; padding: 6px 7px; border: 1px solid var(--line); border-radius: 5px; outline: 0; background: rgba(255,255,255,.035); color: var(--text); font-size: 9px; }
.review-tag-combo-toggle, .review-tag-combo-submit { display: grid; place-items: center; width: 24px; flex: none; border-radius: 5px; color: #fff; background: var(--theme-primary, #7c5ce5); }
.review-tag-combo-toggle { color: #b9a9ff; background: rgba(167,139,250,.18); }
.review-tag-combo-toggle.open { color: #fff; background: var(--theme-primary, #7c5ce5); }
.review-tag-dropdown { position: absolute; z-index: 20; top: calc(100% + 4px); left: 0; right: 0; display: flex; flex-direction: column; gap: 2px; padding: 4px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-strong, #151c32); box-shadow: 0 10px 26px rgba(0,0,0,.45); max-height: 150px; overflow: auto; }
.review-tag-option { display: flex; align-items: center; gap: 6px; padding: 6px 7px; border-radius: 5px; color: #bbc3d2; font-size: 9px; text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.review-tag-option:hover { background: rgba(167,139,250,.16); color: #fff; }
.review-tag-option i { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.review-tag-dropdown-empty { padding: 6px 7px; color: #6f7b96; font-size: 9px; }
.review-warning { display: block; margin-top: 6px; color: #fbbf24; font-size: 9px; line-height: 1.4; }
.review-r18-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; color: #8b96ad; font-size: 9px; }
.r18-switch { display: inline-flex; align-items: center; gap: 5px; min-width: 66px; padding: 3px 5px 3px 4px; border-radius: 20px; color: #dbeafe; background: #2563eb; font-size: 8px; }
.r18-switch i { width: 13px; height: 13px; border-radius: 50%; background: #fff; transition: .2s; }
.r18-switch.on { color: #fff1f2; background: var(--theme-danger, #ef4444); }
.r18-switch.on i { order: 2; }
.r18-switch em { font-style: normal; white-space: nowrap; }
.upload-review-error { margin-top: 12px; }
@keyframes upload-spin { to { transform: rotate(360deg); } }
@media (max-width: 820px) { .upload-review-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 560px) { .upload-review-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); max-height: 55vh; } .review-preview { height: 125px; } }
</style>

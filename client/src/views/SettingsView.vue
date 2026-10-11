<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, Download, Fingerprint, Palette, Save, ShieldCheck, SlidersHorizontal, Sparkles, Zap } from 'lucide-vue-next'
import SettingsPanel from '../components/SettingsPanel.vue'
import type { CurrentUser, EmbedCalibrationPayload, EmbedStatusPayload } from '../api'

const props = defineProps<{
  settings: Record<string, any>
  fluidColors: string[]
  fluidSpeed: number
  selectedTheme: string
  isDark: boolean
  webglEnabled: boolean
  animationEnabled: boolean
  embedStatus: EmbedStatusPayload | null
  embedCalibration: EmbedCalibrationPayload | null
  embedThresholdDraft: number
  embedBackfilling: boolean
  embedSavingThreshold: boolean
  user: CurrentUser | null
}>()
const emit = defineEmits<{
  save: [payload: Record<string, any>]
  changePassword: []
  refresh: []
  backfill: [force: boolean]
  thresholdInput: [value: number]
  saveThreshold: []
  back: []
}>()

const panel = ref<InstanceType<typeof SettingsPanel> | null>(null)
const root = ref<HTMLElement | null>(null)
/** 分组导航由页面上真实存在的分区生成，顺序与右侧卡片一致 */
const sections = ref<Array<{ id: string; label: string; icon: unknown }>>([])
const activeId = ref('')

const ICONS: Array<[string, unknown]> = [
  ['配色', Palette],
  ['流体', Palette],
  ['显示', SlidersHorizontal],
  ['工作区', SlidersHorizontal],
  ['服务', Zap],
  ['存储', Zap],
  ['B站', Download],
  ['工具', Download],
  ['账户', ShieldCheck],
  ['安全', ShieldCheck],
  ['相似', Fingerprint],
  ['索引', Fingerprint],
  ['AI', Sparkles],
]
function iconFor(label: string) { return ICONS.find(([key]) => label.includes(key))?.[1] || SlidersHorizontal }
const canManage = computed(() => props.user?.role === 'ADMIN')

function collectSections() {
  const nodes = Array.from(root.value?.querySelectorAll<HTMLElement>('.settings-section') || [])
  sections.value = nodes.map((node, index) => {
    const label = node.querySelector('h2')?.textContent?.trim() || `分区 ${index + 1}`
    if (!node.id) node.id = `settings-section-${index}`
    return { id: node.id, label, icon: iconFor(label) }
  })
  if (!activeId.value && sections.value.length) activeId.value = sections.value[0].id
}

let frame = 0
function onScroll() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    let current = sections.value[0]?.id || ''
    for (const section of sections.value) {
      const el = document.getElementById(section.id)
      if (el && el.getBoundingClientRect().top <= 140) current = section.id
    }
    activeId.value = current
  })
}
function goTo(id: string) {
  activeId.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function save() { panel.value?.save() }

onMounted(async () => { await nextTick(); collectSections(); window.addEventListener('scroll', onScroll, { passive: true }); onScroll() })
onBeforeUnmount(() => { window.removeEventListener('scroll', onScroll); if (frame) cancelAnimationFrame(frame) })
</script>

<template>
  <div ref="root" class="settings-view">
    <div class="page-heading rise">
      <div>
        <p class="eyebrow"><Sparkles :size="14" /> CONTROL CENTER</p>
        <h1>系统设置</h1>
        <p class="subheading">调整工作区、视觉风格、服务与索引配置。所有配置保存到 PostgreSQL，重启后仍然有效。</p>
      </div>
      <div class="heading-actions">
        <button class="filter-button" @click="emit('back')"><Check :size="16" />返回工作区</button>
        <button class="primary-button" @click="save"><Save :size="17" />保存设置</button>
      </div>
    </div>

    <div class="settings-layout">
      <nav class="settings-nav panel" aria-label="设置分组">
        <p class="nav-title mono">SETTINGS</p>
        <button v-for="section in sections" :key="section.id" :class="{ active: activeId === section.id }" @click="goTo(section.id)">
          <component :is="section.icon" :size="16" />{{ section.label }}
        </button>
        <p v-if="!canManage" class="nav-note">当前账号为普通用户，服务端配置只读。</p>
      </nav>

      <div class="settings-content">
        <SettingsPanel
          ref="panel"
          :settings="props.settings"
          :fluid-colors="props.fluidColors"
          :fluid-speed="props.fluidSpeed"
          :selected-theme="props.selectedTheme"
          :is-dark="props.isDark"
          :webgl-enabled="props.webglEnabled"
          :animation-enabled="props.animationEnabled"
          :embed-status="props.embedStatus"
          :embed-calibration="props.embedCalibration"
          :embed-threshold-draft="props.embedThresholdDraft"
          :embed-backfilling="props.embedBackfilling"
          :embed-saving-threshold="props.embedSavingThreshold"
          :can-manage="canManage"
          @save="emit('save', $event)"
          @change-password="emit('changePassword')"
          @refresh="emit('refresh')"
          @backfill="emit('backfill', $event)"
          @threshold-input="emit('thresholdInput', $event)"
          @save-threshold="emit('saveThreshold')"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-view { display: flex; flex-direction: column; }
.heading-actions { display: flex; align-items: center; gap: 9px; }
.settings-layout { display: grid; grid-template-columns: 232px minmax(0, 1fr); align-items: start; gap: 20px; }
.settings-nav { position: sticky; top: 88px; display: flex; flex-direction: column; gap: 3px; padding: 14px 12px; }
.nav-title { margin: 4px 0 8px; padding: 0 10px; color: var(--text-3); font-size: 10px; letter-spacing: .18em; }
.settings-nav button { display: flex; align-items: center; gap: 11px; height: 40px; padding: 0 12px; border-radius: 13px; color: var(--text-2); font-size: 13px; font-weight: 500; text-align: left; transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.settings-nav button svg { flex: none; color: var(--text-3); transition: color var(--t-fast) var(--ease); }
.settings-nav button:hover { color: var(--text-1); background: var(--glass-1); transform: translateX(2px); }
.settings-nav button.active { color: var(--text-1); border: 1px solid var(--accent-line); background: linear-gradient(90deg, var(--accent-soft), color-mix(in srgb, var(--accent-1) 5%, transparent)); box-shadow: 0 10px 26px -18px var(--glow), inset 0 1px 0 var(--highlight); }
.settings-nav button.active svg { color: var(--accent-ink); }
.nav-note { margin: 12px 4px 2px; padding: 10px 12px; border-radius: var(--r-sm); border: 1px dashed var(--stroke-strong); color: var(--text-3); font-size: 11px; line-height: 1.6; }
.settings-content { min-width: 0; }

@media (max-width: 1050px) {
  .settings-layout { grid-template-columns: 1fr; }
  .settings-nav { position: static; flex-direction: row; overflow-x: auto; padding: 10px; }
  .nav-title, .nav-note { display: none; }
  .settings-nav button { flex: none; height: 36px; padding: 0 14px; white-space: nowrap; }
}
@media (max-width: 720px) {
  .heading-actions { width: 100%; }
  .heading-actions .primary-button { flex: 1; }
  .settings-nav { border-radius: var(--r-md); }
}
</style>

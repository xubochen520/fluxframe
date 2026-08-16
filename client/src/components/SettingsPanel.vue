<script setup lang="ts">
import { ref, watch } from 'vue'
import { ChevronLeft, Palette, Save, Settings2, ShieldCheck, SlidersHorizontal, Sparkles, X, Zap } from 'lucide-vue-next'
import FluidCanvas from './FluidCanvas.vue'

const props = defineProps<{
  settings: Record<string, any>
  fluidColors: string[]
  fluidSpeed: number
  selectedTheme: string
  isDark: boolean
  webglEnabled: boolean
  animationEnabled: boolean
}>()
const emit = defineEmits<{ close: []; save: [payload: Record<string, any>]; changePassword: [] }>()
const siteName = ref('fluxframe')
const port = ref(4310)
const storageDir = ref('./storage')
const uploadLimitMb = ref(50)
const recycleRetentionDays = ref(30)
const localColors = ref([...props.fluidColors])
const localSpeed = ref(3)
const localTheme = ref(props.selectedTheme)
const localDark = ref(props.isDark)
const localWebgl = ref(props.webglEnabled)
const localAnimation = ref(props.animationEnabled)
const aiEnabled = ref(false)
const aiBaseUrl = ref('http://127.0.0.1:11434')
const aiModel = ref('qwen2.5vl:7b')
const aiApiKey = ref('')

const themePalettes: Record<string, string[]> = {
  Aurora: ['#4f46e5', '#06b6d4', '#f472b6'],
  Ember: ['#e45757', '#f59e0b', '#f97316'],
  Mono: ['#334155', '#94a3b8', '#e2e8f0'],
}

watch(() => props.settings, (value) => { siteName.value = value.siteName || 'fluxframe'; port.value = Number(value.port || 4310); storageDir.value = value.storageDir || './storage'; uploadLimitMb.value = Number(value.uploadLimitMb || 50); recycleRetentionDays.value = Number(value.recycleRetentionDays || 30) }, { deep: true, immediate: true })
watch(() => props.fluidColors, (value) => { localColors.value = [...value] }, { deep: true })
watch(() => props.fluidSpeed, () => { localSpeed.value = 3 })
watch(() => props.selectedTheme, (value) => { localTheme.value = value })
watch(() => [props.settings.aiEnabled, props.settings.aiBaseUrl, props.settings.aiModel], ([enabled, baseUrl, model]) => { aiEnabled.value = enabled === true; aiBaseUrl.value = String(baseUrl || 'http://127.0.0.1:11434'); aiModel.value = String(model || 'qwen2.5vl:7b') }, { immediate: true })

function selectTheme(theme: string) {
  localTheme.value = theme
  localColors.value = [...(themePalettes[theme] || themePalettes.Aurora)]
}
function save() { const payload: Record<string, any> = { siteName: siteName.value, port: port.value, storageDir: storageDir.value, uploadLimitMb: uploadLimitMb.value, recycleRetentionDays: recycleRetentionDays.value, fluidColors: localColors.value, fluidSpeed: 3, theme: localTheme.value, webglEnabled: localWebgl.value, animationEnabled: localAnimation.value, darkMode: localDark.value, aiEnabled: aiEnabled.value, aiBaseUrl: aiBaseUrl.value, aiModel: aiModel.value }; if (aiApiKey.value.trim()) payload.aiApiKey = aiApiKey.value.trim(); emit('save', payload) }
</script>

<template>
  <div class="settings-overlay">
    <div class="settings-overlay-head"><button class="settings-back" @click="emit('close')"><ChevronLeft :size="17" />返回工作区</button><div class="settings-overlay-title"><Settings2 :size="18" /><strong>系统设置</strong></div><button class="settings-close" @click="emit('close')"><X :size="19" /></button></div>
    <div class="settings-overlay-body">
      <div class="settings-hero"><div><p class="eyebrow"><Sparkles :size="14" /> CONTROL CENTER</p><h1>配置你的工作区</h1><p>所有配置保存到 PostgreSQL，重启后仍然有效。</p></div><button class="primary-button" @click="save"><Save :size="16" />保存全部设置</button></div>
      <div class="settings-sections">
        <section class="settings-section panel"><div class="setting-title"><div><h2>组件配色流体预览</h2><p>仅用于预览当前主题颜色的混合比例，不会作为全网页背景。</p></div><Palette :size="20" /></div><div class="fluid-preview"><FluidCanvas v-if="localWebgl" :palette="localColors" :speed="3" :paused="!localAnimation" :inline="true" /><div v-else class="fluid-disabled">WebGL 预览已关闭</div><div class="fluid-preview-caption">{{ localTheme.toUpperCase() }} / LIVE COLOR PREVIEW</div></div><div class="color-controls"><label v-for="(color, index) in localColors" :key="index" class="color-control"><input v-model="localColors[index]" type="color" /><span>COLOR {{ index + 1 }}<br>{{ color }}</span></label></div><div class="fixed-speed field-label">流体速度 <strong>3.0（固定）</strong><small>用于观察组件颜色在流体中的占比。</small></div><div class="theme-swatches large"><button v-for="theme in ['Aurora', 'Ember', 'Mono']" :key="theme" :class="['theme-swatch', theme.toLowerCase(), { active: localTheme === theme }]" @click="selectTheme(theme)"><i></i><span>{{ theme }}</span></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>工作区与显示</h2><p>控制站点名称、主题和动画行为。</p></div><SlidersHorizontal :size="20" /></div><label class="field-label">站点名称<input v-model="siteName" /><small>显示在登录页和浏览器标题中。</small></label><div class="setting-row"><div><strong>显示模式</strong><small>切换深色或浅色工作区。</small></div><div class="segmented"><button :class="{ active: localDark }" @click="localDark = true">深色</button><button :class="{ active: !localDark }" @click="localDark = false">浅色</button></div></div><div class="setting-row"><div><strong>颜色流体预览</strong><small>仅控制设置页的 WebGL 颜色预览，不改变网页背景。</small></div><button :class="['switch', { on: localWebgl }]" @click="localWebgl = !localWebgl"><i></i></button></div><div class="setting-row"><div><strong>页面动画</strong><small>关闭后遵循减少动态效果偏好。</small></div><button :class="['switch', { on: localAnimation }]" @click="localAnimation = !localAnimation"><i></i></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>服务、上传与存储</h2><p>修改端口后需要重启 Node 服务。</p></div><Zap :size="20" /></div><label class="field-label">服务端口<input v-model.number="port" type="number" min="1024" max="65535" /><small>当前服务地址端口。</small></label><label class="field-label">图片存储目录<input v-model="storageDir" /><small>支持本机目录或已映射的 NAS 盘符。</small></label><label class="field-label">单张上传限制（MB）<input v-model.number="uploadLimitMb" type="number" min="1" max="2048" /></label><label class="field-label">回收站保留天数<input v-model.number="recycleRetentionDays" type="number" min="0" max="3650" /></label></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>账户安全</h2><p>定期更新密码，保护内网媒体库。</p></div><ShieldCheck :size="20" /></div><button class="security-action" @click="emit('changePassword')"><ShieldCheck :size="16" /><span><strong>修改当前密码</strong><small>使用当前密码确认后设置新密码。</small></span><ChevronLeft :size="16" /></button></section>
      </div>
      <section class="settings-section panel ai-settings-section"><div class="setting-title"><div><h2>AI 图片标签</h2><p>上传时调用支持视觉输入的 OpenAI 兼容模型生成建议标签。</p></div><Sparkles :size="20" /></div><div class="setting-row"><div><strong>启用 AI 标签分析</strong><small>关闭后仍可上传，但不会自动生成标签。</small></div><button :class="['switch', { on: aiEnabled }]" @click="aiEnabled = !aiEnabled"><i></i></button></div><label class="field-label">AI 接口地址<input v-model="aiBaseUrl" placeholder="https://api.openai.com/v1" /><small>也支持 Ollama、LM Studio 等 OpenAI 兼容服务。</small></label><label class="field-label">模型名称<input v-model="aiModel" placeholder="gpt-4o-mini" /></label><label class="field-label">API 密钥<input v-model="aiApiKey" type="password" placeholder="留空则保留当前密钥" /><small>密钥只保存到后端，设置接口不会回传。</small></label></section>
    </div>
  </div>
</template>

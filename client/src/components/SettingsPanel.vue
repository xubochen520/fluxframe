<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { CheckCircle2, ChevronLeft, Download, Loader2, Palette, Play, Save, Settings2, ShieldCheck, SlidersHorizontal, Sparkles, X, Zap } from 'lucide-vue-next'
import FluidCanvas from './FluidCanvas.vue'
import { api, type AiStatusPayload, type FfmpegStatusPayload } from '../api'

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
const aiBaseUrl = ref('http://127.0.0.1:8080/v1')
const aiModel = ref('qwen2.5-vl-7b-instruct')
const aiApiKey = ref('')

/* ---------- B站高清：登录 Cookie（SESSDATA）+ ffmpeg 引擎状态 ---------- */
const biliSessdata = ref('')
const biliConfigured = ref(false)
const ffmpegFound = ref(false)
const ffmpegVersion = ref('')
const ffmpegPath = ref('')
const ffmpegBusy = ref(false)
const ffmpegProgress = ref<FfmpegStatusPayload['progress'] | null>(null)
let ffmpegPollTimer: number | null = null
function stopPollFfmpeg() { if (ffmpegPollTimer !== null) { clearInterval(ffmpegPollTimer); ffmpegPollTimer = null } }
function pollFfmpegStatus() {
  stopPollFfmpeg()
  ffmpegPollTimer = window.setInterval(async () => {
    try {
      const st = await api.ffmpegStatus()
      ffmpegBusy.value = st.busy
      ffmpegProgress.value = st.progress
      if (st.found) {
        ffmpegFound.value = true
        ffmpegVersion.value = String(st.version || '')
        ffmpegPath.value = String(st.path || '')
      }
      if (!st.busy) stopPollFfmpeg()
    } catch { /* 轮询失败静默 */ }
  }, 2000)
}
async function startFfmpegDownload() {
  try {
    const result = await api.ffmpegDownload()
    if (!result.ok) return
    ffmpegBusy.value = true
    ffmpegProgress.value = { phase: 'downloading', done: 0, total: 0 }
    pollFfmpegStatus()
  } catch { /* 启动失败静默 */ }
}

const themePalettes: Record<string, string[]> = {
  Aurora: ['#4f46e5', '#06b6d4', '#f472b6'],
  Ember: ['#e45757', '#f59e0b', '#f97316'],
  Mono: ['#334155', '#94a3b8', '#e2e8f0'],
  Nebula: ['#7c3aed', '#ec4899', '#22d3ee'],
  Forest: ['#166534', '#22c55e', '#a3e635'],
  Rose: ['#be123c', '#fb7185', '#fda4af'],
  Ocean: ['#075985', '#0ea5e9', '#67e8f9'],
  Solar: ['#b45309', '#f59e0b', '#fde047'],
}
const themeNames = Object.keys(themePalettes)

watch(() => props.settings, (value) => { siteName.value = value.siteName || 'fluxframe'; port.value = Number(value.port || 4310); storageDir.value = value.storageDir || './storage'; uploadLimitMb.value = Number(value.uploadLimitMb || 50); recycleRetentionDays.value = Number(value.recycleRetentionDays || 30) }, { deep: true, immediate: true })
watch(() => props.fluidColors, (value) => { localColors.value = [...value] }, { deep: true })
watch(() => props.fluidSpeed, () => { localSpeed.value = 3 })
watch(() => props.selectedTheme, (value) => { localTheme.value = value })
watch(() => [props.settings.aiEnabled, props.settings.aiBaseUrl, props.settings.aiModel], ([enabled, baseUrl, model]) => { aiEnabled.value = enabled === true; aiBaseUrl.value = String(baseUrl || 'http://127.0.0.1:8080/v1'); aiModel.value = String(model || 'qwen2.5-vl-7b-instruct') }, { immediate: true })
watch(() => props.settings, (value) => {
  biliConfigured.value = value.biliSessdataConfigured === true || Boolean(value.biliSessdataConfigured)
  const ff = value.ffmpeg || {}
  ffmpegFound.value = ff.found === true
  ffmpegVersion.value = String(ff.version || '')
  ffmpegPath.value = String(ff.path || '')
  ffmpegBusy.value = ff.busy === true
  ffmpegProgress.value = ff.progress || null
}, { deep: true, immediate: true })

// ---------- AI 引擎状态：开关 → 检测 → 自动下载/启动 → 成功 ----------
const aiConnState = ref<'idle' | 'checking' | 'ok' | 'error' | 'busy'>('idle')
const aiConnText = ref('')
const aiProgress = ref<AiStatusPayload['progress'] | null>(null)
const aiVariant = ref('7b')
const aiUseMirror = ref(false)
const aiNeedDownload = ref(false)
const aiNeedStart = ref(false)
let aiPollTimer: number | null = null

function fmtBytes(n: number) { if (!n) return '0 MB'; const mb = n / 1024 / 1024; return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${Math.round(mb)} MB` }
function barPct(done: number, total: number) { return total > 0 ? `${Math.min(100, Math.round((done / total) * 100))}%` : '0%' }
function stopPollAi() { if (aiPollTimer !== null) { clearInterval(aiPollTimer); aiPollTimer = null } }

function applyAiReady(st: AiStatusPayload) {
  aiConnState.value = 'ok'
  aiConnText.value = `已连接本地模型${st.detected?.modelId ? `（${st.detected.modelId}）` : ''} · ${st.baseUrl}`
  if (st.baseUrl) aiBaseUrl.value = st.baseUrl
  if (st.detected?.modelId) aiModel.value = st.detected.modelId
  aiNeedStart.value = false
  aiNeedDownload.value = false
  aiProgress.value = null
  stopPollAi()
  save()
}

async function checkAiConnection() {
  aiConnState.value = 'checking'
  aiConnText.value = '正在检测本机 llama.cpp…'
  try {
    const st = await api.aiStatus()
    if (st.running && st.baseUrl) { applyAiReady(st); return }
    aiNeedDownload.value = !(st.files.server && st.files.model && st.files.mmproj)
    aiNeedStart.value = !aiNeedDownload.value
    aiConnState.value = 'error'
    aiConnText.value = aiNeedDownload.value ? '未检测到本地模型，可一键自动下载安装' : '模型文件已就绪，服务未运行，点击启动'
  } catch (error: any) {
    aiConnState.value = 'error'
    aiConnText.value = error?.message || '检测失败'
  }
}

function pollAiStatus() {
  stopPollAi()
  aiPollTimer = window.setInterval(async () => {
    try {
      const st = await api.aiStatus()
      aiProgress.value = st.progress
      if (st.running && st.baseUrl) { applyAiReady(st); return }
      if (st.progress.phase === 'error') {
        stopPollAi()
        aiConnState.value = 'error'
        aiConnText.value = st.progress.error || '下载/启动失败，可重试'
        aiNeedDownload.value = true
      } else if (['idle', 'stopped'].includes(st.progress.phase) && st.files.server && st.files.model && st.files.mmproj) {
        stopPollAi()
        aiNeedStart.value = true
        aiNeedDownload.value = false
        aiConnState.value = 'error'
        aiConnText.value = '模型文件已就绪，服务未运行，点击启动'
      }
    } catch { /* 轮询失败静默，等待下一次 */ }
  }, 2000)
}

async function toggleAi() {
  aiEnabled.value = !aiEnabled.value
  if (aiEnabled.value) {
    await checkAiConnection()
  } else {
    stopPollAi()
    aiConnState.value = 'idle'
    aiConnText.value = ''
    aiProgress.value = null
  }
}

async function startAiDownload() {
  aiConnState.value = 'busy'
  aiConnText.value = '正在下载（llama.cpp + 模型），请保持本页面打开…'
  aiProgress.value = { phase: 'fetching-release', llamaDone: 0, llamaTotal: 0, modelDone: 0, modelTotal: 0, modelName: aiVariant.value === '3b' ? 'Qwen2.5-VL-3B' : 'Qwen2.5-VL-7B', variant: aiVariant.value }
  try {
    const result = await api.aiDownload(aiVariant.value, aiUseMirror.value ? 'https://hf-mirror.com' : undefined)
    if (!result.ok) throw new Error(result.error || '下载启动失败')
    pollAiStatus()
  } catch (error: any) {
    aiConnState.value = 'error'
    aiConnText.value = error?.message || '下载启动失败'
  }
}

async function startAiServer() {
  aiConnState.value = 'busy'
  aiConnText.value = '正在启动本地模型服务（首次加载模型可能需要 30 秒以上）…'
  try {
    const result = await api.aiStart()
    if (!result.ok) throw new Error(result.error || '启动失败')
    pollAiStatus()
  } catch (error: any) {
    aiConnState.value = 'error'
    aiConnText.value = error?.message || '启动失败'
  }
}

onMounted(() => { if (aiEnabled.value) void checkAiConnection() })
onUnmounted(() => { stopPollAi(); stopPollFfmpeg() })

function selectTheme(theme: string) {
  localTheme.value = theme
  localColors.value = [...(themePalettes[theme] || themePalettes.Aurora)]
}
function save() { const payload: Record<string, any> = { siteName: siteName.value, port: port.value, storageDir: storageDir.value, uploadLimitMb: uploadLimitMb.value, recycleRetentionDays: recycleRetentionDays.value, fluidColors: localColors.value, fluidSpeed: 3, theme: localTheme.value, webglEnabled: localWebgl.value, animationEnabled: localAnimation.value, darkMode: localDark.value, aiEnabled: aiEnabled.value, aiBaseUrl: aiBaseUrl.value, aiModel: aiModel.value }; if (aiApiKey.value.trim()) payload.aiApiKey = aiApiKey.value.trim(); if (biliSessdata.value.trim()) payload.biliSessdata = biliSessdata.value.trim(); emit('save', payload) }
</script>

<template>
  <div class="settings-overlay">
    <div class="settings-overlay-head"><button class="settings-back" @click="emit('close')"><ChevronLeft :size="17" />返回工作区</button><div class="settings-overlay-title"><Settings2 :size="18" /><strong>系统设置</strong></div><button class="settings-close" @click="emit('close')"><X :size="19" /></button></div>
    <div class="settings-overlay-body">
      <div class="settings-hero"><div><p class="eyebrow"><Sparkles :size="14" /> CONTROL CENTER</p><h1>配置你的工作区</h1><p>所有配置保存到 PostgreSQL，重启后仍然有效。</p></div><button class="primary-button" @click="save"><Save :size="16" />保存全部设置</button></div>
      <div class="settings-sections">
        <section class="settings-section panel"><div class="setting-title"><div><h2>组件配色流体预览</h2><p>主题颜色会同时用于全网页背景、导航和内容面板。</p></div><Palette :size="20" /></div><div class="fluid-preview"><FluidCanvas v-if="localWebgl" :palette="localColors" :speed="3" :paused="!localAnimation" :inline="true" /><div v-else class="fluid-disabled">WebGL 预览已关闭</div><div class="fluid-preview-caption">{{ localTheme.toUpperCase() }} / LIVE COLOR PREVIEW</div></div><div class="color-controls"><label v-for="(color, index) in localColors" :key="index" class="color-control"><input v-model="localColors[index]" type="color" /><span>COLOR {{ index + 1 }}<br>{{ color }}</span></label></div><div class="fixed-speed field-label">流体速度 <strong>3.0（固定）</strong><small>用于观察组件颜色在流体中的占比。</small></div><div class="theme-swatches large"><button v-for="theme in themeNames" :key="theme" :class="['theme-swatch', theme.toLowerCase(), { active: localTheme === theme }]" @click="selectTheme(theme)"><i></i><span>{{ theme }}</span></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>工作区与显示</h2><p>控制站点名称、主题和动画行为。</p></div><SlidersHorizontal :size="20" /></div><label class="field-label">站点名称<input v-model="siteName" /><small>显示在登录页和浏览器标题中。</small></label><div class="setting-row"><div><strong>显示模式</strong><small>切换深色或浅色工作区。</small></div><div class="segmented"><button :class="{ active: localDark }" @click="localDark = true">深色</button><button :class="{ active: !localDark }" @click="localDark = false">浅色</button></div></div><div class="setting-row"><div><strong>全站流体背景</strong><small>控制 WebGL 星河背景；关闭后自动使用静态主题背景。</small></div><button :class="['switch', { on: localWebgl }]" @click="localWebgl = !localWebgl"><i></i></button></div><div class="setting-row"><div><strong>页面动画</strong><small>关闭后遵循减少动态效果偏好。</small></div><button :class="['switch', { on: localAnimation }]" @click="localAnimation = !localAnimation"><i></i></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>服务、上传与存储</h2><p>修改端口后需要重启 Node 服务。</p></div><Zap :size="20" /></div><label class="field-label">服务端口<input v-model.number="port" type="number" min="1024" max="65535" /><small>当前服务地址端口。</small></label><label class="field-label">图片存储目录<input v-model="storageDir" /><small>支持本机目录或已映射的 NAS 盘符。</small></label><label class="field-label">单张上传限制（MB）<input v-model.number="uploadLimitMb" type="number" min="1" max="2048" /></label><label class="field-label">回收站保留天数<input v-model.number="recycleRetentionDays" type="number" min="0" max="3650" /></label></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>B站高清保存</h2><p>B站 1080P 及以上为音视频分离流，配置本项后「视频提取」保存 B站视频时自动下载最高可用档位并用 ffmpeg 合并入库（预览仍为快速 720P）。</p></div><Download :size="20" /></div><div class="setting-row"><div><strong>B站登录 Cookie（SESSDATA）</strong><small>电脑浏览器登录 bilibili.com → F12 → 应用/Application → Cookie，复制 SESSDATA 的值。普通账号最高 1080P，大会员可到 1080P+/4K。仅保存在本机数据库，不回显。</small></div><span :class="['ai-badge', biliConfigured ? 'ok' : 'error']">{{ biliConfigured ? '已配置' : '未配置' }}</span></div><label class="field-label">SESSDATA Cookie<input v-model="biliSessdata" type="password" autocomplete="off" placeholder="粘贴 SESSDATA 值（留空保存 = 不修改）" /></label><div class="setting-row"><div><strong>ffmpeg 引擎</strong><small>用于合并 B站高清音视频分离流（无损 -c copy）。未安装时可一键下载到 models\ffmpeg，约 90MB。</small></div><span :class="['ai-badge', ffmpegFound ? 'ok' : 'error']">{{ ffmpegBusy ? '下载中' : (ffmpegFound ? (ffmpegVersion ? '已就绪 v' + ffmpegVersion : '已就绪') : '未安装') }}</span></div><p v-if="ffmpegPath" class="ai-conn-text">ffmpeg 路径：{{ ffmpegPath }}</p><button v-if="!ffmpegFound && !ffmpegBusy" class="primary-button" @click="startFfmpegDownload"><Download :size="15" />一键下载 ffmpeg（约 90MB）</button><div v-if="ffmpegBusy && ffmpegProgress" class="ai-progress-panel"><div class="ai-progress-row"><span>{{ ffmpegProgress.phase === 'extracting' ? '解压安装中…' : (ffmpegProgress.error ? ffmpegProgress.error : '正在下载 ffmpeg…') }}</span><div class="ai-bar"><i :style="{ width: barPct(ffmpegProgress.done, ffmpegProgress.total) }"></i></div><em>{{ ffmpegProgress.total ? barPct(ffmpegProgress.done, ffmpegProgress.total) : '…' }}</em></div></div><p class="bili-tip"><Sparkles :size="12" /> 生效条件：SESSDATA 已配置 + ffmpeg 已就绪。之后解析 B站视频点「保存到图片库」即自动保存高清；若未满足条件会自动回退默认清晰度并说明原因。</p></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>账户安全</h2><p>定期更新密码，保护内网媒体库。</p></div><ShieldCheck :size="20" /></div><button class="security-action" @click="emit('changePassword')"><ShieldCheck :size="16" /><span><strong>修改当前密码</strong><small>使用当前密码确认后设置新密码。</small></span><ChevronLeft :size="16" /></button></section>
      </div>
      <section class="settings-section panel ai-settings-section"><div class="setting-title"><div><h2>AI 图片标签</h2><p>自动下载并运行 llama.cpp 本地视觉模型，上传时生成建议标签，图片不离开本机。</p></div><Sparkles :size="20" /></div><div class="setting-row"><div><strong>启用 AI 标签分析</strong><small>开启后自动检测本机 llama.cpp，未安装时可一键自动下载。</small></div><div class="ai-switch-row"><span v-if="aiConnState === 'ok'" class="ai-badge ok"><CheckCircle2 :size="14" />成功</span><span v-else-if="aiConnState === 'checking'" class="ai-badge checking"><Loader2 :size="14" class="spin" />检测中</span><span v-else-if="aiConnState === 'busy'" class="ai-badge busy"><Loader2 :size="14" class="spin" />运行中</span><span v-else-if="aiConnState === 'error'" class="ai-badge error"><X :size="14" />未连接</span><button :class="['switch', { on: aiEnabled }]" @click="toggleAi"><i></i></button></div></div><p v-if="aiConnText" class="ai-conn-text">{{ aiConnText }}</p><div v-if="aiEnabled && aiNeedDownload && aiConnState !== 'busy'" class="ai-setup-panel"><div class="ai-setup-options"><label>模型<select v-model="aiVariant"><option value="7b">Qwen2.5-VL-7B（推荐，约 5.7GB）</option><option value="3b">Qwen2.5-VL-3B（轻量，约 3.1GB）</option></select></label><label class="ai-mirror"><input v-model="aiUseMirror" type="checkbox" />使用国内镜像下载（hf-mirror.com）</label></div><button class="primary-button" @click="startAiDownload"><Download :size="15" />自动下载并启动（无需密钥）</button></div><div v-else-if="aiEnabled && aiNeedStart && aiConnState !== 'busy'" class="ai-setup-panel"><button class="primary-button" @click="startAiServer"><Play :size="15" />启动本地模型服务</button></div><div v-if="aiProgress && aiConnState === 'busy'" class="ai-progress-panel"><div v-if="aiProgress.phase === 'downloading-llama' || aiProgress.phase === 'fetching-release'" class="ai-progress-row"><span>llama.cpp 引擎</span><div class="ai-bar"><i :style="{ width: barPct(aiProgress.llamaDone, aiProgress.llamaTotal) }"></i></div><em>{{ barPct(aiProgress.llamaDone, aiProgress.llamaTotal) }}</em></div><div v-if="aiProgress.phase === 'downloading-model'" class="ai-progress-row"><span>模型 {{ aiProgress.modelName }}</span><div class="ai-bar"><i :style="{ width: barPct(aiProgress.modelDone, aiProgress.modelTotal) }"></i></div><em>{{ barPct(aiProgress.modelDone, aiProgress.modelTotal) }} · {{ fmtBytes(aiProgress.modelDone) }}/{{ fmtBytes(aiProgress.modelTotal) }}</em></div><p v-if="aiProgress.phase === 'extracting'" class="ai-phase-text">正在解压 llama.cpp…</p><p v-else-if="aiProgress.phase === 'starting'" class="ai-phase-text">正在启动模型服务并等待就绪（首次加载模型可能需要 30 秒以上）…</p></div><label class="field-label">AI 接口地址<input v-model="aiBaseUrl" placeholder="http://127.0.0.1:8080/v1" /><small>检测到本机 llama.cpp 后自动填入；也兼容 Ollama、LM Studio 等 OpenAI 兼容服务。</small></label><label class="field-label">模型名称<input v-model="aiModel" placeholder="qwen2.5-vl-7b-instruct" /><small>llama.cpp 不校验模型名；Ollama 需填实际模型名。</small></label><label class="field-label">API 密钥<input v-model="aiApiKey" type="password" placeholder="本地模型无需密钥，留空即可" /><small>仅接入云端服务时才需要；密钥只保存到后端，不回传。</small></label></section>
    </div>
  </div>
</template>

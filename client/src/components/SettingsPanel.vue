<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { CheckCircle2, ChevronLeft, Download, Loader2, Palette, Play, Save, Settings2, ShieldCheck, SlidersHorizontal, Sparkles, X, Zap } from 'lucide-vue-next'
import FluidCanvas from './FluidCanvas.vue'
import { THEMES } from '../themes'
import { api, type AiStatusPayload, type EmbedCalibrationPayload, type EmbedStatusPayload, type FfmpegStatusPayload } from '../api'

const props = defineProps<{
  settings: Record<string, any>
  fluidColors: string[]
  fluidSpeed: number
  selectedTheme: string
  isDark: boolean
  webglEnabled: boolean
  animationEnabled: boolean
  /* ---- 相似图索引（视觉指纹 CCIP）：状态、建立索引、阈值与校准 ---- */
  embedStatus: EmbedStatusPayload | null
  embedCalibration: EmbedCalibrationPayload | null
  embedThresholdDraft: number
  embedBackfilling: boolean
  embedSavingThreshold: boolean
  /** 只有管理员能改服务端设置；普通用户看到只读提示 */
  canManage: boolean
}>()
const emit = defineEmits<{
  close: []
  save: [payload: Record<string, any>]
  changePassword: []
  refresh: []
  backfill: [force: boolean]
  thresholdInput: [value: number]
  saveThreshold: []
}>()

function pct(value: number | null | undefined) { return value == null ? '—' : `${Math.round(value * 100)}%` }
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

/* ---------- B站扫码登录（官方 passport 二维码） ---------- */
const qrActive = ref(false)
const qrImage = ref('')
const qrKey = ref('')
const qrText = ref('')
const qrOk = ref(false)
let qrTimer: number | null = null
let qrDeadline = 0
function stopPollQr() { if (qrTimer !== null) { clearInterval(qrTimer); qrTimer = null } }
async function beginBiliQr() {
  stopPollQr()
  qrActive.value = true
  qrOk.value = false
  qrText.value = '正在生成二维码…'
  qrImage.value = ''
  qrKey.value = ''
  try {
    const created = await api.biliQrCreate()
    if (!created.ok || !created.qrcodeKey) {
      qrText.value = created.error || '生成二维码失败，请稍后重试'
      return
    }
    qrImage.value = created.image || ''
    qrKey.value = created.qrcodeKey
    qrDeadline = Date.now() + 175000
    qrText.value = '请使用 B站 App 扫码登录'
    qrTimer = window.setInterval(async () => {
      if (!qrKey.value) return
      if (Date.now() > qrDeadline) {
        stopPollQr()
        qrText.value = '二维码已过期，请点击刷新'
        return
      }
      try {
        const polled = await api.biliQrPoll(qrKey.value)
        if (polled.status === 'ok') {
          stopPollQr()
          qrText.value = polled.nickname ? `登录成功（${polled.nickname}），SESSDATA 已保存` : '登录成功，SESSDATA 已保存'
          qrOk.value = true
          biliConfigured.value = true
          emit('refresh')
          return
        }
        if (polled.status === 'scanned') qrText.value = '已扫码，请在手机上确认…'
        else if (polled.status === 'expired') {
          stopPollQr()
          qrText.value = '二维码已过期，请点击刷新'
        } else if (polled.status === 'error') {
          stopPollQr()
          qrText.value = polled.error || '轮询失败，请刷新重试'
        } else {
          qrText.value = '请使用 B站 App 扫码登录'
        }
      } catch { /* 轮询失败静默，等下一次 */ }
    }, 2000)
  } catch (error: any) {
    qrText.value = error?.message || '生成二维码失败'
  }
}
function refreshBiliQr() { void beginBiliQr() }
function closeBiliQr() { stopPollQr(); qrActive.value = false }

/* 主题色板：单一真源在 themes.ts（顶栏切换器/令牌/星系引擎共用） */
const themePalettes: Record<string, string[]> = Object.fromEntries(
  THEMES.map((theme) => [theme.name, [...theme.accents]]),
)
const themeNames = THEMES.map((theme) => theme.name)
/** 色卡圆点的渐变：直接取该主题前两色，新增主题零成本上架 */
function themeSwatchStyle(name: string) {
  const theme = THEMES.find((item) => item.name === name)
  const [first, second] = theme?.accents || ['#7c6cff', '#22d3ee']
  return { background: `linear-gradient(135deg, ${first}, ${second})` }
}

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
onUnmounted(() => { stopPollAi(); stopPollFfmpeg(); stopPollQr() })

function selectTheme(theme: string) {
  localTheme.value = theme
  localColors.value = [...(themePalettes[theme] || themePalettes.Aurora)]
}
function save() { const payload: Record<string, any> = { siteName: siteName.value, port: port.value, storageDir: storageDir.value, uploadLimitMb: uploadLimitMb.value, recycleRetentionDays: recycleRetentionDays.value, fluidColors: localColors.value, fluidSpeed: 3, theme: localTheme.value, webglEnabled: localWebgl.value, animationEnabled: localAnimation.value, darkMode: localDark.value, aiEnabled: aiEnabled.value, aiBaseUrl: aiBaseUrl.value, aiModel: aiModel.value }; if (aiApiKey.value.trim()) payload.aiApiKey = aiApiKey.value.trim(); if (biliSessdata.value.trim()) payload.biliSessdata = biliSessdata.value.trim(); emit('save', payload) }
/* 设置页顶部的「保存设置」按钮由父组件触发 */
defineExpose({ save })
</script>

<template>
  <div class="settings-overlay">
    <div class="settings-overlay-head"><button class="settings-back" @click="emit('close')"><ChevronLeft :size="17" />返回工作区</button><div class="settings-overlay-title"><Settings2 :size="18" /><strong>系统设置</strong></div><button class="settings-close" @click="emit('close')"><X :size="19" /></button></div>
    <div class="settings-overlay-body">
      <div class="settings-hero"><div><p class="eyebrow"><Sparkles :size="14" /> CONTROL CENTER</p><h1>配置你的工作区</h1><p>所有配置保存到 PostgreSQL，重启后仍然有效。</p></div><button class="primary-button" @click="save"><Save :size="16" />保存全部设置</button></div>
      <div class="settings-sections">
        <section class="settings-section panel"><div class="setting-title"><div><h2>组件配色流体预览</h2><p>主题颜色会同时用于全网页背景、导航和内容面板。</p></div><Palette :size="20" /></div><div class="fluid-preview"><FluidCanvas v-if="localWebgl" :palette="localColors" :speed="3" :paused="!localAnimation" :inline="true" /><div v-else class="fluid-disabled">WebGL 预览已关闭</div><div class="fluid-preview-caption">{{ localTheme.toUpperCase() }} / LIVE COLOR PREVIEW</div></div><div class="color-controls"><label v-for="(color, index) in localColors" :key="index" class="color-control"><input v-model="localColors[index]" type="color" /><span>COLOR {{ index + 1 }}<br>{{ color }}</span></label></div><div class="fixed-speed field-label">流体速度 <strong>3.0（固定）</strong><small>用于观察组件颜色在流体中的占比。</small></div><div class="theme-swatches large"><button v-for="theme in themeNames" :key="theme" :class="['theme-swatch', { active: localTheme === theme }]" @click="selectTheme(theme)"><i :style="themeSwatchStyle(theme)"></i><span>{{ theme }}</span></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>工作区与显示</h2><p>控制站点名称、主题和动画行为。</p></div><SlidersHorizontal :size="20" /></div><label class="field-label">站点名称<input v-model="siteName" /><small>显示在登录页和浏览器标题中。</small></label><div class="setting-row"><div><strong>显示模式</strong><small>切换深色或浅色工作区。</small></div><div class="segmented"><button :class="{ active: localDark }" @click="localDark = true">深色</button><button :class="{ active: !localDark }" @click="localDark = false">浅色</button></div></div><div class="setting-row"><div><strong>全站流体背景</strong><small>控制 WebGL 星河背景；关闭后自动使用静态主题背景。</small></div><button :class="['switch', { on: localWebgl }]" @click="localWebgl = !localWebgl"><i></i></button></div><div class="setting-row"><div><strong>页面动画</strong><small>关闭后遵循减少动态效果偏好。</small></div><button :class="['switch', { on: localAnimation }]" @click="localAnimation = !localAnimation"><i></i></button></div></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>服务、上传与存储</h2><p>修改端口后需要重启 Node 服务。</p></div><Zap :size="20" /></div><label class="field-label">服务端口<input v-model.number="port" type="number" min="1024" max="65535" /><small>当前服务地址端口。</small></label><label class="field-label">图片存储目录<input v-model="storageDir" /><small>支持本机目录或已映射的 NAS 盘符。</small></label><label class="field-label">单张上传限制（MB）<input v-model.number="uploadLimitMb" type="number" min="1" max="2048" /></label><label class="field-label">回收站保留天数<input v-model.number="recycleRetentionDays" type="number" min="0" max="3650" /></label></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>B站高清保存</h2><p>B站 1080P 及以上为音视频分离流，配置本项后「视频提取」保存 B站视频时自动下载最高可用档位并用 ffmpeg 合并入库（预览仍为快速 720P）。</p></div><Download :size="20" /></div><div class="setting-row"><div><strong>B站登录</strong><small>普通账号最高 1080P，大会员可到 1080P+/4K。登录态仅保存在本机数据库，不回显。</small></div><span :class="['ai-badge', biliConfigured ? 'ok' : 'error']">{{ biliConfigured ? '已登录' : '未登录' }}</span></div><div class="setting-row bili-login-row"><div><strong>方式一：扫码登录（推荐）</strong><small>点击后用 B站 App 扫二维码，确认后自动保存登录态，无需手动复制。</small></div><button class="filter-button" @click="qrActive ? refreshBiliQr() : beginBiliQr()">{{ qrActive ? '刷新二维码' : (biliConfigured ? '重新扫码' : '扫码登录') }}</button></div><div v-if="qrActive" class="bili-qr-panel"><img v-if="qrImage" :src="qrImage" alt="B站登录二维码" /><p :class="['bili-qr-text', { ok: qrOk }]"><Loader2 v-if="!qrImage" :size="13" class="spin" />{{ qrText }}</p><button v-if="qrText.includes('过期')" class="text-button" @click="refreshBiliQr">重新生成二维码</button></div><label class="field-label">方式二：手动粘贴 Cookie（备用）<input v-model="biliSessdata" type="password" autocomplete="off" placeholder="粘贴完整 Cookie 或 SESSDATA 值（留空保存 = 不修改）" /><small>参考开源项目 Mineradio 的获取方式：浏览器登录 bilibili.com → F12 → 应用/Application → Cookie，复制整段或其中的 SESSDATA 值粘贴即可，系统会自动提取。</small></label><div class="setting-row"><div><strong>ffmpeg 引擎</strong><small>用于合并 B站高清音视频分离流（无损 -c copy）。未安装时可一键下载到 models\ffmpeg，约 90MB。</small></div><span :class="['ai-badge', ffmpegFound ? 'ok' : 'error']">{{ ffmpegBusy ? '下载中' : (ffmpegFound ? (ffmpegVersion ? '已就绪 v' + ffmpegVersion : '已就绪') : '未安装') }}</span></div><p v-if="ffmpegPath" class="ai-conn-text">ffmpeg 路径：{{ ffmpegPath }}</p><button v-if="!ffmpegFound && !ffmpegBusy" class="primary-button" @click="startFfmpegDownload"><Download :size="15" />一键下载 ffmpeg（约 90MB）</button><div v-if="ffmpegBusy && ffmpegProgress" class="ai-progress-panel"><div class="ai-progress-row"><span>{{ ffmpegProgress.phase === 'extracting' ? '解压安装中…' : (ffmpegProgress.error ? ffmpegProgress.error : '正在下载 ffmpeg…') }}</span><div class="ai-bar"><i :style="{ width: barPct(ffmpegProgress.done, ffmpegProgress.total) }"></i></div><em>{{ ffmpegProgress.total ? barPct(ffmpegProgress.done, ffmpegProgress.total) : '…' }}</em></div></div><p class="bili-tip"><Sparkles :size="12" /> 生效条件：B站已登录（扫码或 Cookie）+ ffmpeg 已就绪。之后解析 B站视频点「保存到图片库」即自动保存高清；若未满足条件会自动回退默认清晰度并说明原因。</p></section>
        <section class="settings-section panel"><div class="setting-title"><div><h2>账户安全</h2><p>定期更新密码，保护内网媒体库。</p></div><ShieldCheck :size="20" /></div><button class="security-action" @click="emit('changePassword')"><ShieldCheck :size="16" /><span><strong>修改当前密码</strong><small>使用当前密码确认后设置新密码。</small></span><ChevronLeft :size="16" /></button></section>
        <section class="settings-section panel embed-section"><div class="setting-title"><div><h2>相似图索引</h2><p>用视觉指纹（CCIP）自动找出相似的图片；上传新图会自动建指纹。</p></div><Sparkles :size="20" /></div><div class="embed-status-row"><div class="embed-status-main"><span :class="['status-dot', { off: !props.embedStatus?.modelReady }]"></span><strong>{{ props.embedStatus?.modelReady ? '指纹模型已就绪' : '指纹模型缺失' }}</strong><small>· {{ props.embedStatus ? `已建指纹 ${props.embedStatus.indexed} / ${props.embedStatus.imageCount} 张 · 平均每张 ${props.embedStatus.avgNeighbors} 个相似` : '读取中…' }}</small></div><button class="filter-button" :disabled="!props.embedStatus?.modelReady || props.embedBackfilling || !props.canManage" @click="emit('backfill', false)"><Loader2 v-if="props.embedBackfilling || props.embedStatus?.working" :size="15" class="spin" /><Sparkles v-else :size="15" />{{ props.embedStatus?.working ? `正在计算 ${props.embedStatus.queueLength} 张…` : props.embedBackfilling ? '正在排队…' : '建立索引' }}</button></div>
          <div v-if="props.embedStatus?.failed" class="embed-warn">有 {{ props.embedStatus.failed }} 张算失败{{ props.embedStatus.lastError ? `（${props.embedStatus.lastError}）` : '' }}，可再点一次「建立索引」重试。</div>
          <div v-if="props.embedStatus && !props.embedStatus.modelReady" class="embed-warn">模型文件不在服务器上（{{ props.embedStatus.modelPath || '/data/models/embed/ccip_feat.onnx' }}）。需要先放好 ccip_feat.onnx 才能建索引。</div>
          <div class="setting-row"><div><strong>相似度阈值</strong><small>越高越严格（只留很像的），越低越宽松（能捞到更多）。实测本库同角色图片多在 {{ props.embedCalibration?.same.p90 != null ? Math.round(props.embedCalibration.same.p90 * 100) : '—' }}% 以下、不同角色多在 {{ props.embedCalibration?.diff.p10 != null ? Math.round(props.embedCalibration.diff.p10 * 100) : '—' }}% 以上。</small></div><div class="embed-threshold"><input type="range" min="0.05" max="0.9" step="0.05" :value="props.embedThresholdDraft" :disabled="!props.canManage" @input="emit('thresholdInput', Number(($event.target as HTMLInputElement).value))" /><strong class="mono">{{ Math.round(props.embedThresholdDraft * 100) }}%</strong></div></div>
          <div v-if="props.embedCalibration" class="embed-calib"><span>同角色 {{ props.embedCalibration.pairs.same }} 对：中位 {{ pct(props.embedCalibration.same.p50) }}</span><span>不同角色 {{ props.embedCalibration.pairs.diff }} 对：中位 {{ pct(props.embedCalibration.diff.p50) }}</span><span>当前阈值 {{ pct(props.embedStatus?.threshold ?? 0) }}</span></div>
          <div class="embed-actions"><button class="primary-button small" :disabled="props.embedSavingThreshold || !props.embedStatus || !props.canManage" @click="emit('saveThreshold')"><Save :size="15" />保存阈值并重算关系</button><span v-if="!props.canManage" class="embed-hint">当前账号不是管理员，此项只读。</span></div>
        </section>
      </div>
      <section class="settings-section panel ai-settings-section"><div class="setting-title"><div><h2>AI 图片标签</h2><p>自动下载并运行 llama.cpp 本地视觉模型，上传时生成建议标签，图片不离开本机。</p></div><Sparkles :size="20" /></div><div class="setting-row"><div><strong>启用 AI 标签分析</strong><small>开启后自动检测本机 llama.cpp，未安装时可一键自动下载。</small></div><div class="ai-switch-row"><span v-if="aiConnState === 'ok'" class="ai-badge ok"><CheckCircle2 :size="14" />成功</span><span v-else-if="aiConnState === 'checking'" class="ai-badge checking"><Loader2 :size="14" class="spin" />检测中</span><span v-else-if="aiConnState === 'busy'" class="ai-badge busy"><Loader2 :size="14" class="spin" />运行中</span><span v-else-if="aiConnState === 'error'" class="ai-badge error"><X :size="14" />未连接</span><button :class="['switch', { on: aiEnabled }]" @click="toggleAi"><i></i></button></div></div><p v-if="aiConnText" class="ai-conn-text">{{ aiConnText }}</p><div v-if="aiEnabled && aiNeedDownload && aiConnState !== 'busy'" class="ai-setup-panel"><div class="ai-setup-options"><label>模型<select v-model="aiVariant"><option value="7b">Qwen2.5-VL-7B（推荐，约 5.7GB）</option><option value="3b">Qwen2.5-VL-3B（轻量，约 3.1GB）</option></select></label><label class="ai-mirror"><input v-model="aiUseMirror" type="checkbox" />使用国内镜像下载（hf-mirror.com）</label></div><button class="primary-button" @click="startAiDownload"><Download :size="15" />自动下载并启动（无需密钥）</button></div><div v-else-if="aiEnabled && aiNeedStart && aiConnState !== 'busy'" class="ai-setup-panel"><button class="primary-button" @click="startAiServer"><Play :size="15" />启动本地模型服务</button></div><div v-if="aiProgress && aiConnState === 'busy'" class="ai-progress-panel"><div v-if="aiProgress.phase === 'downloading-llama' || aiProgress.phase === 'fetching-release'" class="ai-progress-row"><span>llama.cpp 引擎</span><div class="ai-bar"><i :style="{ width: barPct(aiProgress.llamaDone, aiProgress.llamaTotal) }"></i></div><em>{{ barPct(aiProgress.llamaDone, aiProgress.llamaTotal) }}</em></div><div v-if="aiProgress.phase === 'downloading-model'" class="ai-progress-row"><span>模型 {{ aiProgress.modelName }}</span><div class="ai-bar"><i :style="{ width: barPct(aiProgress.modelDone, aiProgress.modelTotal) }"></i></div><em>{{ barPct(aiProgress.modelDone, aiProgress.modelTotal) }} · {{ fmtBytes(aiProgress.modelDone) }}/{{ fmtBytes(aiProgress.modelTotal) }}</em></div><p v-if="aiProgress.phase === 'extracting'" class="ai-phase-text">正在解压 llama.cpp…</p><p v-else-if="aiProgress.phase === 'starting'" class="ai-phase-text">正在启动模型服务并等待就绪（首次加载模型可能需要 30 秒以上）…</p></div><label class="field-label">AI 接口地址<input v-model="aiBaseUrl" placeholder="http://127.0.0.1:8080/v1" /><small>检测到本机 llama.cpp 后自动填入；也兼容 Ollama、LM Studio 等 OpenAI 兼容服务。</small></label><label class="field-label">模型名称<input v-model="aiModel" placeholder="qwen2.5-vl-7b-instruct" /><small>llama.cpp 不校验模型名；Ollama 需填实际模型名。</small></label><label class="field-label">API 密钥<input v-model="aiApiKey" type="password" placeholder="本地模型无需密钥，留空即可" /><small>仅接入云端服务时才需要；密钥只保存到后端，不回传。</small></label></section>
    </div>
  </div>
</template>

<style scoped>
/* 这一层原来是覆盖整页的 position:fixed 弹层；现在它是设置页里的普通内容区，
   标题栏交给 views/SettingsView.vue，这里只负责分区卡片本身。 */
.settings-overlay { display: block; }
.settings-overlay-head { display: none; }
.settings-overlay-body { display: block; }

/* 页头与「保存设置」按钮由 views/SettingsView.vue 提供，这里不再重复一份 */
.settings-hero { display: none; }

.settings-sections { display: flex; flex-direction: column; gap: 16px; }
.settings-section { padding: 22px 24px 24px; scroll-margin-top: 92px; }
.setting-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--stroke); }
.setting-title h2 { margin: 0; font: 600 16.5px/1.25 var(--font-display); letter-spacing: -.02em; color: var(--text-1); }
.setting-title p { margin: 6px 0 0; max-width: 720px; color: var(--text-3); font-size: 12.5px; line-height: 1.65; }
.setting-title > svg { flex: none; color: var(--accent-ink); opacity: .85; }
.setting-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 13px 0; border-top: 1px solid var(--stroke); }
.setting-row:first-of-type { border-top: 0; padding-top: 0; }
.setting-row strong { display: block; font-size: 13.5px; font-weight: 600; }
.setting-row small { display: block; margin-top: 4px; max-width: 620px; color: var(--text-3); font-size: 11.5px; line-height: 1.6; }
.settings-section .field-label { margin-bottom: 14px; }

.fluid-preview { position: relative; height: 190px; overflow: hidden; border-radius: var(--r-lg); border: 1px solid var(--stroke); background: var(--media-bg); }
.fluid-disabled { display: grid; place-items: center; height: 100%; color: var(--text-3); font-size: 12.5px; }
.fluid-preview-caption { position: absolute; left: 14px; bottom: 12px; padding: 5px 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(14px); backdrop-filter: blur(14px); color: var(--text-2); font: 500 10px var(--font-mono); letter-spacing: .12em; }
.color-controls { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.color-control { display: flex; align-items: center; gap: 10px; padding: 8px 12px 8px 8px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-3); font: 500 10px/1.4 var(--font-mono); letter-spacing: .06em; }
.color-control input { width: 30px; height: 30px; padding: 0; border: 1px solid var(--stroke); border-radius: 50%; background: transparent; cursor: pointer; }
.fixed-speed { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.theme-swatches { display: flex; flex-wrap: wrap; gap: 9px; }
.theme-swatch { display: flex; align-items: center; gap: 9px; height: 38px; padding: 0 14px 0 10px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 12px; font-weight: 500; transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.theme-swatch:hover { transform: translateY(-2px); color: var(--text-1); }
.theme-swatch i { width: 16px; height: 16px; border-radius: 50%; box-shadow: inset 0 1px 0 rgba(255, 255, 255, .35); }
.theme-swatch.active { color: var(--accent-ink); border-color: var(--accent-line); background: var(--accent-soft); box-shadow: 0 8px 22px -14px var(--glow); }

.security-action { display: flex; align-items: center; gap: 14px; width: 100%; padding: 14px 16px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); text-align: left; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.security-action:hover { transform: translateY(-2px); border-color: var(--accent-line); background: var(--field-hover); }
.security-action > svg:first-child { flex: none; color: var(--accent-ink); }
.security-action > svg:last-child { margin-left: auto; color: var(--text-3); transform: rotate(180deg); }
.security-action strong { display: block; font-size: 13.5px; font-weight: 600; }
.security-action small { display: block; margin-top: 4px; color: var(--text-3); font-size: 11.5px; }

.ai-settings-section { border-color: var(--accent-line); }
.ai-switch-row { display: flex; align-items: center; gap: 9px; flex: none; }
.ai-badge { display: inline-flex; align-items: center; gap: 5px; height: 26px; padding: 0 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 11.5px; font-weight: 500; white-space: nowrap; }
.ai-badge.ok { color: color-mix(in oklab, var(--success) 74%, var(--text-1)); border-color: color-mix(in srgb, var(--success) 34%, transparent); background: color-mix(in srgb, var(--success) 12%, transparent); }
.ai-badge.error { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 34%, transparent); background: color-mix(in srgb, var(--danger) 11%, transparent); }
.ai-badge.checking, .ai-badge.busy { color: var(--accent-ink); border-color: var(--accent-line); background: var(--accent-soft); }
.ai-conn-text { margin: 0 0 12px; color: var(--text-2); font: 11.5px var(--font-mono); line-height: 1.6; }
.ai-setup-panel { display: flex; flex-direction: column; gap: 12px; margin: 6px 0 14px; padding: 15px 16px; border-radius: var(--r-md); border: 1px dashed var(--accent-line); background: var(--accent-soft); }
.ai-setup-options { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; color: var(--text-2); font-size: 12.5px; }
.ai-setup-options select { height: 36px; padding: 0 10px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); }
.ai-mirror { display: inline-flex; align-items: center; gap: 7px; }
.ai-progress-panel { display: flex; flex-direction: column; gap: 10px; margin: 6px 0 14px; padding: 14px 16px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); }
.ai-progress-row { display: grid; grid-template-columns: minmax(110px, auto) minmax(0, 1fr) auto; align-items: center; gap: 12px; color: var(--text-2); font-size: 12px; }
.ai-progress-row em { color: var(--text-3); font: 11px var(--font-mono); font-style: normal; white-space: nowrap; }
.ai-bar { position: relative; height: 5px; overflow: hidden; border-radius: 5px; background: var(--track); }
.ai-bar i { display: block; height: 100%; border-radius: inherit; background: var(--aurora-grad); transition: width var(--t-slow) var(--ease); }
.ai-phase-text { margin: 0; color: var(--text-3); font-size: 11.5px; }

.bili-qr-panel { display: flex; flex-direction: column; align-items: center; gap: 10px; margin: 6px 0 16px; padding: 18px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); }
.bili-qr-panel img { width: 176px; height: 176px; border-radius: var(--r-sm); background: #fff; padding: 6px; }
.bili-qr-text { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--text-2); font-size: 12.5px; }
.bili-qr-text.ok { color: color-mix(in oklab, var(--success) 74%, var(--text-1)); }
.bili-tip { display: flex; align-items: flex-start; gap: 8px; margin: 14px 0 0; padding: 12px 14px; border-radius: var(--r-md); border: 1px solid var(--accent-line); background: var(--accent-soft); color: var(--text-2); font-size: 11.5px; line-height: 1.7; }
.bili-tip svg { flex: none; margin-top: 3px; color: var(--accent-ink); }

/* ---- 相似图索引 ---- */
.embed-section { border-color: color-mix(in srgb, var(--accent-2) 26%, var(--stroke)); }
.embed-status-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 16px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); }
.embed-status-main { display: flex; align-items: center; gap: 10px; min-width: 0; }
.embed-status-main strong { font-size: 13px; font-weight: 600; white-space: nowrap; }
.embed-status-main small { min-width: 0; color: var(--text-3); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; }
.embed-warn { margin-top: 12px; padding: 11px 14px; border-radius: var(--r-md); border: 1px solid color-mix(in srgb, var(--warn) 32%, transparent); background: color-mix(in srgb, var(--warn) 10%, transparent); color: color-mix(in oklab, var(--warn) 78%, var(--text-1)); font-size: 11.5px; line-height: 1.65; }
.embed-threshold { display: flex; align-items: center; gap: 12px; flex: none; }
.embed-threshold input { width: 190px; accent-color: var(--accent-1); }
.embed-threshold strong { min-width: 46px; color: var(--accent-ink); font-size: 14px; text-align: right; }
.embed-calib { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 14px; }
.embed-calib span { padding: 5px 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-3); font: 10.5px var(--font-mono); }
.embed-actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.embed-hint { color: var(--text-3); font-size: 11.5px; }

@media (max-width: 1050px) {
  .setting-row { flex-direction: column; align-items: stretch; gap: 12px; }
  .setting-row .segmented, .setting-row .switch { align-self: flex-start; }
  .embed-threshold input { width: 100%; }
  .embed-threshold { width: 100%; }
}
@media (max-width: 720px) {
  .settings-section { padding: 18px 16px 20px; }
  .settings-hero { flex-direction: column; align-items: stretch; }
  .theme-swatch { height: 34px; padding: 0 12px 0 9px; font-size: 11.5px; }
  .fluid-preview { height: 140px; }
  .ai-progress-row { grid-template-columns: minmax(0, 1fr) auto; }
  .ai-progress-row .ai-bar { grid-column: 1 / -1; order: 3; }
}
</style>


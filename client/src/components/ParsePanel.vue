<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Clock3, Download, Eye, Heart, Images, Link2, LoaderCircle, MessageCircle, RotateCcw, Save, Share2, Sparkles, Trash2, X } from 'lucide-vue-next'
import { startSaveTask, type SaveTaskOpts } from '../parseSaveStore'
import { isNativeAndroid } from '../nativeDownload'
import { startDownload } from '../downloadTaskStore'

/* ============================================================
   视频解析工作台（PureParse 解析引擎 · 原生嵌入 · 主题自适配）
   链接识别 → /api/parse 真实解析 → 预览 → 下载 / 保存到图片库
   保存走后台任务：左下角进度卡持续显示下载/上传进度
   ============================================================ */

const PLATFORMS: Record<string, { name: string; char: string; bg: string; color: string; tag: string }> = {
  douyin: { name: '抖音', char: '抖', bg: 'linear-gradient(135deg,#25f4ee,#fe2c55)', color: '#fe2c55', tag: '短视频 / 图文' },
  bilibili: { name: '哔哩哔哩', char: 'B', bg: 'linear-gradient(135deg,#8aa8ff,#fb7299)', color: '#fb7299', tag: '视频 / 番剧' },
  kuaishou: { name: '快手', char: '快', bg: 'linear-gradient(135deg,#ffb45e,#ff5e00)', color: '#ff7a1a', tag: '短视频' },
  xiaohongshu: { name: '小红书', char: '红', bg: 'linear-gradient(135deg,#ff9e9e,#ff2442)', color: '#ff2442', tag: '视频笔记' },
  weibo: { name: '微博', char: '博', bg: 'linear-gradient(135deg,#ffa08c,#e6162d)', color: '#e6162d', tag: '视频 / 回放' },
  xigua: { name: '西瓜视频', char: '西', bg: 'linear-gradient(135deg,#ffd66b,#ff7a1a)', color: '#ff7a1a', tag: '中视频' },
}
const HOST_RE: Array<[RegExp, string]> = [
  [/douyin\.com|iesdouyin\.com/i, 'douyin'],
  [/bilibili\.com|b23\.tv|bilibili\.tv/i, 'bilibili'],
  [/kuaishou\.com|chenzhongtech\.com|gifshow\.com/i, 'kuaishou'],
  [/xiaohongshu\.com|xhslink\.com/i, 'xiaohongshu'],
  [/weibo\.(com|cn)/i, 'weibo'],
  [/ixigua\.com/i, 'xigua'],
]
const URL_RE = /https?:\/\/[^\s"'<>《》〈〉【】〔〕「」『』（）()\[\]{}“”‘’、，。；：？！…—–·～|｜]+/gi

interface ParseTask {
  platName: string
  platChar: string
  platBg: string
  /** video=视频作品 / images=图文作品（图片集） */
  kind: 'video' | 'images'
  title: string
  authorName: string
  authorHandle: string
  verified: boolean
  like: number
  comment: number
  share: number
  view: number
  cover: string
  coverSrc: string
  /** 上游防盗链 Referer（图文图片导入用） */
  referer: string
  resLabel: string
  duration: number
  media: { url: string; src?: string; referer?: string; w: number; h: number; dur: number; size: number; type: string }
  /** 图文作品：整组图片（url 为同源代理预览地址，src 为上游原址） */
  images: { url: string; src?: string; w: number; h: number }[]
  /** B站高清 DASH 双流（配置了登录 Cookie 且服务端 ffmpeg 就绪时返回） */
  high?: { videoUrl: string; audioUrl: string; quality?: number; label?: string }
}
interface HistoryItem { url: string; plat: string; title: string; time: number }

const input = ref('')
const busy = ref(false)
const stepText = ref('')
const error = ref('')
const task = ref<ParseTask | null>(null)
const history = ref<HistoryItem[]>([])
const detectedPlat = ref<{ name: string; color: string; tag: string } | null>(null)
const showSave = ref(false)
const saveName = ref('')
const saveVideo = ref(true)
const saveImages = ref(true)
const saveCover = ref(true)
const savePlatTag = ref(true)
const saveHint = ref('')
const lastUrl = ref('')
let parseToken = 0

onMounted(() => { loadHistory(); onInput() })

function fmtNum(n: number) {
  if (!n || n <= 0) return '—'
  if (n >= 1e8) return (n / 1e8).toFixed(1).replace(/\.0$/, '') + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(1).replace(/\.0$/, '') + '万'
  return String(n)
}
function fmtSize(b: number) {
  if (!b || b <= 0) return '—'
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1048576 * 1024) return (b / 1048576).toFixed(2) + ' MB'
  return (b / 1073741824).toFixed(2) + ' GB'
}
function fmtTime(s: number) {
  s = Math.max(0, Math.floor(s || 0))
  const m = (s / 60) | 0
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
function cleanName(s: string) {
  const raw = String(s || '').trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/^[.\s]+|[.\s]+$/g, '')
  return Array.from(raw).slice(0, 60).join('') || '视频'
}
function extOfType(type: string, fallback = 'mp4') {
  if (/webm/i.test(type)) return 'webm'
  if (/mp4|m4v|mov|mpeg/i.test(type)) return 'mp4'
  return fallback
}

/* ---------- 历史 ---------- */
function loadHistory() {
  try {
    const raw = localStorage.getItem('fluxframe_parse_history')
    const list = raw ? (JSON.parse(raw) as HistoryItem[]) : []
    history.value = Array.isArray(list) ? list.slice(0, 8) : []
  } catch { history.value = [] }
}
function pushHistory(item: HistoryItem) {
  history.value = [item, ...history.value.filter((h) => h.url !== item.url)].slice(0, 8)
  try { localStorage.setItem('fluxframe_parse_history', JSON.stringify(history.value)) } catch { /* ignore */ }
}
function clearHistory() {
  history.value = []
  try { localStorage.removeItem('fluxframe_parse_history') } catch { /* ignore */ }
}

/* ---------- 识别 ---------- */
function detectPlatform(url: string) {
  try {
    const host = new URL(url).hostname
    for (const [re, id] of HOST_RE) if (re.test(host)) return id
  } catch { /* 非法链接 */ }
  return ''
}
function extractUrl(raw: string) {
  const matches = raw.match(URL_RE)
  if (!matches || !matches.length) return ''
  const known = matches.find((m) => detectPlatform(m))
  return known || matches[0]
}
function onInput() {
  error.value = ''
  const url = extractUrl(input.value)
  const id = url ? detectPlatform(url) : ''
  const info = PLATFORMS[id]
  detectedPlat.value = info ? { name: info.name, color: info.color, tag: info.tag } : null
}
function submit() {
  if (busy.value) return
  const url = extractUrl(input.value)
  if (!url) {
    error.value = '没有识别到链接：请粘贴抖音 / B站 / 快手等平台的分享链接或完整口令'
    return
  }
  void runParse(url)
}

/* ---------- 解析 ---------- */
async function runParse(url: string) {
  const token = ++parseToken
  busy.value = true
  error.value = ''
  task.value = null
  lastUrl.value = url
  const platId = detectPlatform(url)
  const plat = PLATFORMS[platId] || { name: platId ? `未内置平台：${platId}` : '未知来源', char: '?', bg: 'linear-gradient(135deg,#64748b,#334155)', color: '#94a3b8', tag: '' }
  const steps = [`校验链接格式`, `识别来源：${plat.name}`, '请求解析服务', '解析无水印直链']
  let idx = 0
  const timer = window.setInterval(() => {
    if (token === parseToken) stepText.value = `${steps[Math.min(idx++, steps.length - 1)]}…`
  }, 420)
  stepText.value = `${steps[0]}…`
  try {
    const res = await fetch('/api/parse', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, share_text: input.value.trim() }),
      credentials: 'include',
      signal: AbortSignal.timeout(60_000),
    })
    window.clearInterval(timer)
    let json: any = null
    try { json = await res.json() } catch { /* 非 JSON */ }
    if (!res.ok || !json || json.ok === false) {
      throw new Error(json?.msg || json?.message || `解析服务返回异常（HTTP ${res.status}）`)
    }
    const d = json.data || json
    const media = (Array.isArray(d.media) ? d.media[0] : d.media) || {}
    const author = d.author || {}
    const stats = d.stats || {}
    const info = PLATFORMS[String(d.platform || platId)] || plat
    /* 图文作品（抖音图片集等）：media 为空、images 有内容 */
    const images = (Array.isArray(d.images) ? d.images : [])
      .map((im: any) => ({ url: String(im?.url || ''), src: im?.src ? String(im.src) : undefined, w: Number(im?.width) || 0, h: Number(im?.height) || 0 }))
      .filter((im: { url: string }) => !!im.url)
    const isImages = String(d.kind || '') === 'images' || (!media.url && images.length > 0)
    task.value = {
      platName: info.name,
      platChar: info.char,
      platBg: info.bg,
      kind: isImages ? 'images' : 'video',
      title: d.title || (isImages ? '未命名图文' : '未命名视频'),
      authorName: author.name || '未知作者',
      authorHandle: author.handle ? '@' + String(author.handle).replace(/^@/, '') : '',
      verified: !!author.verified,
      like: Number(stats.like) || 0,
      comment: Number(stats.comment) || 0,
      share: Number(stats.share) || 0,
      view: Number(stats.view) || 0,
      cover: typeof d.cover === 'string' ? d.cover : '',
      coverSrc: typeof d.coverSrc === 'string' ? d.coverSrc : '',
      referer: String(d.referer || media.referer || ''),
      resLabel: d.qualityLabel || (media.width && media.height ? `${media.width}×${media.height} · 无水印` : isImages ? `${images.length} 张图片` : '无水印直链'),
      duration: Number(media.duration || d.duration || 0),
      images,
      high: d.high?.videoUrl && d.high.audioUrl
        ? { videoUrl: String(d.high.videoUrl), audioUrl: String(d.high.audioUrl), quality: Number(d.high.quality) || undefined, label: String(d.high.label || '高清') }
        : undefined,
      media: {
        url: String(media.url || d.url || ''),
        src: media.src ? String(media.src) : undefined,
        referer: media.referer ? String(media.referer) : undefined,
        w: Number(media.width) || 0,
        h: Number(media.height) || 0,
        dur: Number(media.duration || d.duration || 0),
        size: Number(media.size) || 0,
        type: String(media.type || 'mp4'),
      },
    }
    stepText.value = '解析完成 ✓'
    pushHistory({ url, plat: info.name, title: task.value.title, time: Date.now() })
  } catch (err: any) {
    if (token !== parseToken) return
    window.clearInterval(timer)
    stepText.value = '解析失败'
    const base = err && err.message ? String(err.message) : '解析失败，请稍后重试'
    error.value = platId === 'douyin' ? `${base}（抖音接口受平台风控影响，10~30 秒后重试通常可恢复）` : base
  } finally {
    if (token === parseToken) busy.value = false
  }
}

/* ---------- 本地下载 ---------- */
async function grabAndDownload(url: string, filename: string) {
  try {
    /* APK 端：blob 保存不生效 → 转左下角任务坞走原生媒体库下载 */
    if (isNativeAndroid) {
      startDownload({ url, filename })
      return ''
    }
    const res = await fetch(url, { credentials: 'include' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const blob = await res.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = filename
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
    window.setTimeout(() => URL.revokeObjectURL(a.href), 10_000)
    return ''
  } catch (err: any) {
    return err && err.message ? err.message : '下载失败'
  }
}
async function downloadMedia() {
  if (!task.value?.media.url) return
  const base = cleanName(task.value.title)
  error.value = ''
  const msg = await grabAndDownload(task.value.media.url, `${base}.${extOfType(task.value.media.type)}`)
  if (msg) error.value = `视频下载失败：${msg}`
}
async function downloadCover() {
  if (!task.value?.cover) return
  const base = cleanName(task.value.title)
  const msg = await grabAndDownload(task.value.cover, `${base}-封面.jpg`)
  if (msg) error.value = `封面下载失败：${msg}`
}
/** 图文作品：逐张下载整组图片（APK 端进原生下载任务坞） */
async function downloadAllImages() {
  const t = task.value
  if (!t?.images.length) return
  error.value = ''
  const base = cleanName(t.title)
  const total = t.images.length
  for (const [i, im] of t.images.entries()) {
    const msg = await grabAndDownload(im.url, `${base}-${String(i + 1).padStart(2, '0')}.jpg`)
    if (msg) {
      error.value = `第 ${i + 1}/${total} 张图片下载失败：${msg}`
      return
    }
  }
}

/* ---------- 保存到图片库（后台任务） ---------- */
function openSave() {
  if (!task.value) return
  const isImages = task.value.kind === 'images'
  saveName.value = cleanName(task.value.title)
  saveVideo.value = !isImages
  saveImages.value = isImages
  saveCover.value = !isImages && !!task.value.cover
  savePlatTag.value = true
  saveHint.value = ''
  showSave.value = true
}
function closeSave() { showSave.value = false }

function confirmSave() {
  const t = task.value
  if (!t) return
  const name = cleanName(saveName.value)
  const platTag = savePlatTag.value && !/未知|未内置/.test(t.platName) ? t.platName : null

  /* 图文作品：整组图片一次任务顺序入库（每张一个导入任务，进度合并显示） */
  if (t.kind === 'images') {
    if (!saveImages.value) {
      saveHint.value = '请勾选要保存的图片'
      return
    }
    const list = t.images
      .filter((im) => /^https?:/i.test(im.src || ''))
      .map((im, i) => ({ url: String(im.src), ref: t.referer || undefined, name: `${name}-${String(i + 1).padStart(2, '0')}`, kind: 'image' as const }))
    if (!list.length) {
      saveHint.value = '图片直链不可用，请重新解析后再试'
      return
    }
    startSaveTask({ name, video: null, cover: null, images: list, platTag })
    showSave.value = false
    return
  }

  const videoSrc = t.media.src || ''
  const coverSrc = t.coverSrc || (t.cover && !String(t.cover).startsWith('/api/stream') ? t.cover : '')
  if (saveVideo.value && !/^https?:/i.test(videoSrc)) {
    saveHint.value = '该片源缺少可导入的直链地址，请改用「下载视频」'
    return
  }
  if (saveCover.value && t.cover && !/^https?:/i.test(coverSrc)) {
    saveHint.value = '该封面缺少可导入的直链地址'
    return
  }
  const opts: SaveTaskOpts = {
    name,
    video: saveVideo.value && t.media.url
      ? { url: videoSrc, ref: t.media.referer, name, kind: 'video', high: t.high }
      : null,
    cover: saveCover.value && t.cover
      ? { url: coverSrc, ref: t.media.referer, name: `${name}-封面`, kind: 'cover' }
      : null,
    platTag,
  }
  if (!opts.video && !opts.cover) {
    saveHint.value = '请至少勾选一项要保存的内容'
    return
  }
  /* 立即关闭弹窗，转入左下角后台任务（服务端下载入库） */
  startSaveTask(opts)
  showSave.value = false
}
</script>

<template>
  <div class="pw">
    <div class="page-heading">
      <div>
        <p class="eyebrow"><Link2 :size="14" /> VIDEO EXTRACTOR</p>
        <h1>视频解析工作台</h1>
        <p class="subheading">粘贴抖音 / B站 / 快手等平台的分享链接或完整口令，解析无水印视频并预览，可下载或后台保存到图片库。</p>
      </div>
    </div>

    <!-- 输入卡 -->
    <div class="panel pw-card">
      <div class="pw-input-row">
        <div class="pw-input-box">
          <Link2 :size="16" class="pw-input-ico" />
          <input id="pwInput" v-model="input" class="pw-input" spellcheck="false"
            placeholder="粘贴分享链接或完整口令，例如 https://v.douyin.com/xxxxx/ 、BV 号链接…"
            @input="onInput" @keyup.enter="submit" />
          <span v-if="detectedPlat" class="pw-plat-chip"><i :style="{ background: detectedPlat.color }" />{{ detectedPlat.name }}<small>{{ detectedPlat.tag }}</small></span>
        </div>
        <button class="primary-button pw-parse-btn" :disabled="busy" @click="submit">
          <LoaderCircle v-if="busy" class="spin" :size="16" /><Sparkles v-else :size="16" />{{ busy ? '解析中' : '开始解析' }}
        </button>
      </div>
      <p v-if="error" class="pw-error"><X :size="13" />{{ error }}</p>
      <div v-if="busy" class="pw-steps"><i></i><span>{{ stepText }}</span></div>
    </div>

    <!-- 最近解析 -->
    <div v-if="history.length" class="pw-history">
      <span class="pw-history-label"><Clock3 :size="12" />最近解析</span>
      <button v-for="h in history" :key="h.url" class="pw-history-chip" :title="h.url"
        @click="input = h.url; onInput(); runParse(h.url)">
        <em>{{ h.plat }}</em>{{ h.title }}
      </button>
      <button class="pw-history-clear" title="清空记录" @click="clearHistory"><Trash2 :size="12" /></button>
    </div>

    <!-- 解析结果 -->
    <div v-if="task" class="pw-result">
      <div class="pw-player panel">
        <video v-if="task.kind === 'video'" :key="task.media.url" :src="task.media.url" :poster="task.cover || undefined" controls playsinline preload="metadata" />
        <div v-else class="pw-gallery">
          <a v-for="(im, i) in task.images" :key="i" class="pw-gallery-item" :href="im.url" target="_blank" rel="noopener noreferrer" :title="`查看原图 ${i + 1}`">
            <img :src="im.url" loading="lazy" :alt="`图片 ${i + 1}`" />
            <span>{{ i + 1 }}</span>
          </a>
        </div>
        <div class="pw-player-meta">
          <span class="pw-badge-plat"><i :style="{ background: task.platBg }">{{ task.platChar }}</i>{{ task.platName }} · {{ task.kind === 'images' ? '图文作品' : '无水印' }}</span>
          <span class="pw-badge-res">{{ task.resLabel }}</span>
          <span v-if="task.kind === 'video'" class="pw-badge-size">{{ fmtSize(task.media.size) }}</span>
          <span v-if="task.kind === 'video'" class="pw-badge-size">{{ fmtTime(task.duration) }}</span>
        </div>
      </div>
      <div class="panel pw-info">
        <h2>{{ task.title }}</h2>
        <p class="pw-author"><strong>{{ task.authorName }}</strong><span v-if="task.authorHandle">{{ task.authorHandle }}</span><em v-if="task.verified">已认证</em></p>
        <div class="pw-stats">
          <span><Eye :size="13" />{{ fmtNum(task.view) }}</span>
          <span><Heart :size="13" />{{ fmtNum(task.like) }}</span>
          <span><MessageCircle :size="13" />{{ fmtNum(task.comment) }}</span>
          <span><Share2 :size="13" />{{ fmtNum(task.share) }}</span>
        </div>
        <div class="pw-actions">
          <button class="primary-button" @click="openSave"><Save :size="15" />{{ task.kind === 'images' ? '保存全部图片' : '保存到图片库' }}</button>
          <button v-if="task.kind === 'video'" class="filter-button" @click="downloadMedia"><Download :size="14" />下载视频</button>
          <button v-else class="filter-button" @click="downloadAllImages"><Download :size="14" />下载全部 {{ task.images.length }} 张</button>
          <button v-if="task.kind === 'video' && task.cover" class="filter-button" @click="downloadCover"><Images :size="14" />封面</button>
          <button class="filter-button" @click="runParse(lastUrl)"><RotateCcw :size="14" />重新解析</button>
        </div>
        <p class="pw-tip"><Save :size="12" /> 保存走服务端后台导入：不占手机流量，左下角实时显示下载进度，可随时取消或继续解析其它链接。</p>
      </div>
    </div>

    <!-- 保存弹窗 -->
    <div v-if="showSave" class="modal-backdrop" @click.self="closeSave">
      <div class="modal pw-save" @click.stop>
        <button class="modal-close" @click="closeSave"><X :size="18" /></button>
        <div class="upload-icon"><Save :size="24" /></div>
        <h2>保存到图片库</h2>
        <p>由服务器后台下载并入库（左下角显示实时进度），无需占用本机流量，可继续解析其它链接。</p>
        <label class="field-label">入库名称<input v-model="saveName" class="modal-input" maxlength="80" /></label>
        <div class="pw-save-opts">
          <label v-if="task?.kind === 'images'"><input v-model="saveImages" type="checkbox" /><span><b>全部图片（{{ task?.images.length }} 张）</b><small>{{ task?.resLabel }} · 逐张入库，自动去重</small></span></label>
          <label v-else><input v-model="saveVideo" type="checkbox" /><span><b>无水印视频</b><small>自动带「视频」标签 · {{ task?.resLabel }} · {{ task?.media.size ? fmtSize(task.media.size) : '' }}</small></span></label>
          <p v-if="task?.high" class="pw-save-high"><Sparkles :size="12" />已就绪：可保存 {{ task.high.label }} 高清版（服务端下载双流并用 ffmpeg 合并，需 B站登录态已配置）</p>
          <label v-if="task?.kind === 'video' && task?.cover"><input v-model="saveCover" type="checkbox" /><span><b>封面图</b><small>与视频一起保存，无附加标签</small></span></label>
          <label><input v-model="savePlatTag" type="checkbox" /><span><b>附带来源标签「{{ task?.platName }}」</b><small>便于按平台检索</small></span></label>
        </div>
        <p v-if="saveHint" class="pw-save-hint">{{ saveHint }}</p>
        <button class="primary-button full" @click="confirmSave"><Save :size="16" />开始后台保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pw { width: min(1080px, 100%); }
.pw .page-heading { margin-bottom: 24px; }
.pw .page-heading h1 { font-size: 27px; }

/* 输入卡 */
.pw-card { padding: 18px; }
.pw-input-row { display: flex; gap: 10px; align-items: center; }
.pw-input-box {
  flex: 1; min-width: 0; position: relative;
  display: flex; align-items: center; gap: 9px;
  border: 1px solid var(--line); border-radius: 10px;
  background: rgba(255, 255, 255, .035);
  padding: 0 12px;
  transition: border-color .2s, box-shadow .2s;
}
.pw-input-box:focus-within { border-color: rgba(167, 139, 250, .55); box-shadow: 0 0 0 3px rgba(167, 139, 250, .13); }
.pw-input-ico { color: #7f8aa4; flex: none; }
.pw-input {
  flex: 1; min-width: 0; height: 46px;
  border: 0; outline: 0; background: transparent;
  color: var(--text); font-size: 13px;
}
.pw-input::placeholder { color: #68738e; }
.pw-plat-chip {
  flex: none; display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 9px; border-radius: 999px;
  border: 1px solid var(--line); background: rgba(255, 255, 255, .04);
  font-size: 11px; color: #cdd4e3; white-space: nowrap;
}
.pw-plat-chip i { width: 7px; height: 7px; border-radius: 50%; box-shadow: 0 0 8px currentColor; }
.pw-plat-chip small { color: #7c87a1; font-size: 10px; margin-left: 2px; }
.pw-parse-btn { height: 46px; flex: none; }
.pw-parse-btn:disabled { opacity: .6; cursor: not-allowed; transform: none; box-shadow: none; }
.pw-error {
  display: flex; align-items: flex-start; gap: 7px;
  margin: 13px 0 0; padding: 9px 12px;
  border-radius: 8px; font-size: 12px; line-height: 1.6;
  color: #fda4af; background: rgba(248, 113, 113, .09);
  border: 1px solid rgba(248, 113, 113, .2);
}
.pw-error svg { flex: none; margin-top: 2px; }
.pw-steps {
  display: flex; align-items: center; gap: 10px;
  margin-top: 13px; color: #a99cf6; font-size: 12px;
}
.pw-steps i {
  width: 14px; height: 14px; border-radius: 50%;
  border: 2px solid rgba(167, 139, 250, .2); border-top-color: #a78bfa;
  animation: pw-spin .7s linear infinite;
}
.spin { animation: pw-spin .9s linear infinite; }
@keyframes pw-spin { to { transform: rotate(360deg); } }

/* 历史 */
.pw-history { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin: 13px 2px 0; }
.pw-history-label { display: inline-flex; align-items: center; gap: 5px; color: #7c87a0; font-size: 11px; margin-right: 3px; }
.pw-history-chip {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 6px 11px; border-radius: 999px;
  border: 1px solid var(--line); background: rgba(255, 255, 255, .03);
  color: #a9b2c7; font-size: 11px; max-width: 260px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  transition: .18s;
}
.pw-history-chip:hover { border-color: rgba(167, 139, 250, .4); color: #d5ccff; }
.pw-history-chip em { font-style: normal; color: #a78bfa; flex: none; }
.pw-history-clear {
  margin-left: auto; width: 26px; height: 26px; border-radius: 7px;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; color: #68738e;
}
.pw-history-clear:hover { background: rgba(248, 113, 113, .1); color: #fb8294; }

/* 结果 */
.pw-result { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(280px, 1fr); gap: 13px; margin-top: 16px; }
.pw-player { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.pw-player video {
  width: 100%; aspect-ratio: 16 / 9; max-height: 58vh;
  border-radius: 10px; background: #000; object-fit: contain; outline: 0;
}
/* 图文作品：图片网格（点击看原图） */
.pw-gallery {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: 8px; max-height: 58vh; overflow-y: auto; padding-right: 2px;
}
.pw-gallery-item {
  position: relative; display: block; border-radius: 9px; overflow: hidden;
  border: 1px solid var(--line); background: rgba(255, 255, 255, .03);
  transition: border-color .18s, transform .18s;
}
.pw-gallery-item img { display: block; width: 100%; aspect-ratio: 3 / 4; object-fit: cover; background: #0b0e16; }
.pw-gallery-item span {
  position: absolute; left: 6px; bottom: 6px;
  min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
  display: inline-flex; align-items: center; justify-content: center;
  font: 10px 'DM Mono'; color: #e7ebf5;
  background: rgba(10, 13, 22, .68);
}
.pw-gallery-item:hover { border-color: rgba(167, 139, 250, .45); transform: translateY(-1px); }
.pw-player-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 0 2px; }
.pw-badge-plat, .pw-badge-res, .pw-badge-size {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px; border-radius: 999px; font-size: 11px;
  border: 1px solid var(--line); background: rgba(255, 255, 255, .03); color: #aeb7ca;
}
.pw-badge-plat i {
  width: 18px; height: 18px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  color: #fff; font-size: 10px; font-style: normal; font-weight: 700;
}
.pw-badge-res { color: #6ee7a2; border-color: rgba(74, 222, 128, .2); background: rgba(74, 222, 128, .07); }
.pw-info { padding: 20px 21px; display: flex; flex-direction: column; }
.pw-info h2 {
  font: 600 17px 'Space Grotesk'; letter-spacing: -.02em;
  color: #f0f2f7; margin: 0; line-height: 1.45;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.pw-author { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 11px 0 0; font-size: 12px; color: #8b96ad; }
.pw-author strong { color: #d7dcea; font-weight: 500; }
.pw-author span { color: #7c87a1; }
.pw-author em {
  font-style: normal; font-size: 10px; color: #67e8f9;
  border: 1px solid rgba(103, 232, 249, .25); background: rgba(103, 232, 249, .07);
  padding: 2px 7px; border-radius: 999px;
}
.pw-stats {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 6px; margin: 16px 0;
  padding: 11px 0; border-top: 1px solid rgba(157, 171, 210, .09);
  border-bottom: 1px solid rgba(157, 171, 210, .09);
}
.pw-stats span {
  display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  font: 11px 'DM Mono'; color: #9aa4bd;
}
.pw-stats svg { color: #6d7894; }
.pw-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.pw-actions .primary-button { flex: 1; min-width: 150px; justify-content: center; }
.pw-actions .filter-button { display: inline-flex; align-items: center; gap: 6px; }
.pw-tip {
  display: flex; align-items: center; gap: 6px;
  margin: 14px 0 0; font-size: 10.5px; color: #68738e; line-height: 1.6;
}
.pw-tip svg { flex: none; color: #a78bfa; }

/* 保存弹窗 */
.pw-save { width: min(430px, 100%); }
.pw-save .full { width: 100%; justify-content: center; margin-top: 16px; }
.pw-save-hint {
  margin: 8px 0 0; padding: 8px 11px; border-radius: 8px;
  font-size: 12px; line-height: 1.5;
  color: #fda4af; background: rgba(248, 113, 113, .1);
  border: 1px solid rgba(248, 113, 113, .25);
}
.pw-save-high {
  display: flex; align-items: flex-start; gap: 6px;
  margin: 10px 0 4px; padding: 8px 11px; border-radius: 8px;
  font-size: 11px; line-height: 1.55;
  color: #a5f3c9; background: rgba(74, 222, 128, .08);
  border: 1px solid rgba(74, 222, 128, .22);
}
.pw-save-high svg { flex: none; margin-top: 1px; color: #4ade80; }
.pw-save-opts { margin-top: 6px; }
.pw-save-opts label {
  display: flex; align-items: flex-start; gap: 10px; cursor: pointer;
  padding: 11px 0; border-bottom: 1px solid rgba(157, 171, 210, .08);
}
.pw-save-opts label:last-child { border-bottom: 0; }
.pw-save-opts input { margin: 3px 0 0; accent-color: #a78bfa; width: 15px; height: 15px; cursor: pointer; flex: none; }
.pw-save-opts b { display: block; font-size: 12px; font-weight: 500; }
.pw-save-opts small { display: block; color: #78839e; font-size: 10.5px; margin-top: 3px; }

@media (max-width: 720px) {
  .pw-tasks {
    left: 12px; right: 12px; bottom: calc(70px + env(safe-area-inset-bottom));
    width: auto; max-width: none;
  }
  .pw-task { padding: 11px 12px 10px; }
}
@media (max-width: 900px) {
  .pw-result { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .pw-card { padding: 13px; }
  .pw-input-row { flex-direction: column; align-items: stretch; }
  .pw-parse-btn { width: 100%; }
  .pw-stats { grid-template-columns: repeat(2, 1fr); row-gap: 9px; }
  .pw-info { padding: 16px 15px; }
  .pw-history-chip { max-width: 170px; }
  .pw-gallery { grid-template-columns: repeat(auto-fill, minmax(92px, 1fr)); max-height: 46vh; }
  .pw-actions .primary-button { min-width: 0; }
}
</style>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Images, LayoutDashboard, Link2, Settings2, ShieldCheck, Sparkles, Tags, Trash2 } from 'lucide-vue-next'
import LoginView from './components/LoginView.vue'
import FluidCanvas from './components/FluidCanvas.vue'
import UploadReviewModal from './components/UploadReviewModal.vue'
import ParsePanel from './components/ParsePanel.vue'
import TaskDock from './components/TaskDock.vue'
import AppSidebar from './shell/AppSidebar.vue'
import AppTopbar from './shell/AppTopbar.vue'
import MobileNav from './shell/MobileNav.vue'
import OverviewView from './views/OverviewView.vue'
import LibraryView from './views/LibraryView.vue'
import SimilarView from './views/SimilarView.vue'
import TagsView from './views/TagsView.vue'
import LogsView from './views/LogsView.vue'
import SettingsView from './views/SettingsView.vue'
import AppToast from './overlays/AppToast.vue'
import ContextMenu from './overlays/ContextMenu.vue'
import TagContextMenu from './overlays/TagContextMenu.vue'
import RenameModal from './overlays/RenameModal.vue'
import NewTagModal from './overlays/NewTagModal.vue'
import AddTagModal from './overlays/AddTagModal.vue'
import RemoveTagModal from './overlays/RemoveTagModal.vue'
import BatchTagModal from './overlays/BatchTagModal.vue'
import PersonDetailModal from './overlays/PersonDetailModal.vue'
import ReferencesModal from './overlays/ReferencesModal.vue'
import ViewerOverlay from './overlays/ViewerOverlay.vue'
import CommandPalette from './overlays/CommandPalette.vue'
import { enterPage, installMotion, recentPointer } from './motion'
import { themeFluidPalette } from './themes'
import { startDownload } from './downloadTaskStore'
import { api, type CurrentUser, type DetectBox, type EmbedCalibrationPayload, type EmbedGraphMode,
  type EmbedGraphSpace, type EmbedGraphPayload, type EmbedStatusPayload, type ReferenceItem, type SimilarImageItem } from './api'
import type { AuditLog, ImageItem, PersonDetail, TagItem, View } from './types'

const authenticated = ref(false)
const booting = ref(true)
const authLoading = ref(false)
const authError = ref('')
const currentUser = ref<CurrentUser | null>(null)
const activeView = ref<View>('overview')
const moreOpen = ref(false)
const query = ref('')
const sortBy = ref('views')
const selectedTag = ref('全部标签')
const selectedImage = ref<ImageItem | null>(null)
const viewerRef = ref<InstanceType<typeof ViewerOverlay> | null>(null)
/* 滚轮缩放要以查看器里真实图片元素的位置为基准，所以从子组件取回 DOM 引用 */
const previewImageElement = computed<HTMLElement | null>(() => (viewerRef.value?.imageEl as HTMLElement | null) ?? null)
const previewScale = ref(1)
const previewOffset = ref({ x: 0, y: 0 })
const previewLoading = ref(false)

/* ---- 相似图：大图预览里「上滑」拉出相似图列表 ----
   相似度来自 CCIP 视觉指纹（服务器 embed.ts 计算），与标签无关：
   标签向量已实测对只有 1~2 张图的角色几乎无效，视觉指纹则是按像素找同角色/同张图。 */
const similarOpen = ref(false)
const similarLoading = ref(false)
const similarItems = ref<SimilarImageItem[]>([])
const similarAnalyzing = ref(false)
const similarThreshold = ref(0.35)
const similarForId = ref('')
/** 上滑多少像素算「拉出相似图面板」（触摸手势用） */
const SWIPE_UP_TRIGGER = 90
/**
 * 下滑多少像素算「收起相似图面板」。
 * 比上滑阈值小：面板已经打开时用户意图很明确，不需要滑那么远；
 * 而且下滑误触的代价只是把面板收起来，比上滑误触（突然弹出一整屏）轻得多。
 */
const SWIPE_DOWN_TRIGGER = 70
/** 触摸起点：移动端用下拉手势拉出面板 */
const swipeTouchStart = ref<{ x: number; y: number } | null>(null)
/** 「还没建指纹」的自动重查次数，防止无限轮询 */
let similarRetries = 0

function resetSimilar() {
  similarOpen.value = false
  similarItems.value = []
  similarAnalyzing.value = false
  similarForId.value = ''
  similarRetries = 0
}

async function openSimilar(imageId: string, showPanel = true) {
  similarOpen.value = showPanel
  if (similarForId.value === imageId && similarItems.value.length) return
  /* 视频没有视觉指纹（只对静态图片算），直接给结论，别让用户等一个永远不来的结果 */
  if (isVideoItem(selectedImage.value)) {
    similarForId.value = imageId
    similarItems.value = []
    similarAnalyzing.value = false
    similarLoading.value = false
    return
  }
  similarForId.value = imageId
  similarLoading.value = true
  similarAnalyzing.value = false
  try {
    const result = await api.similarImages(imageId)
    // 请求期间用户可能已经切到别的图，丢弃过期结果
    if (similarForId.value !== imageId) return
    similarItems.value = result.items
    /*
     * 大图的左右切换改用相似图来翻 —— 从星图点进来时想看的就是"这张的亲戚"，
     * 那才是这条浏览线有意义的地方。此刻用户已经切到别的图就不动（避免列表和画面对不上）。
     *
     * **必须把当前这张自己也放进去**：相似图接口返回的是"别的图"，不含自己，
     * 于是按 id 找下标会得到 -1、左右按钮就永远不出现（第一版就是这么漏的）。
     * 放在开头，于是「下一张」自然走到第一个亲戚。
     */
    if (selectedImage.value?.id === imageId) {
      previewNav.value = [selectedImage.value, ...result.items]
    }
    similarThreshold.value = result.threshold
    similarAnalyzing.value = Boolean(result.analyzing)
    /* 这张图还没建指纹：服务器已把它排进后台队列，2.5 秒后自动重查一次，
       用户不用手动刷新就能等到结果（指纹约 0.7 秒算完）。最多重试 3 次就放弃，
       避免遇到「永远排不上队」的情况时无限轮询。 */
    if (result.analyzing && similarRetries < 3) {
      similarRetries += 1
      window.setTimeout(() => {
        if (similarOpen.value && similarForId.value === imageId && !similarItems.value.length) void openSimilar(imageId)
      }, 2500)
    }
  } catch (error) {
    notify(errorMessage(error))
  } finally {
    if (similarForId.value === imageId) similarLoading.value = false
  }
}

function closeSimilar() { similarOpen.value = false; similarRetries = 0 }

/** 点相似图里的某张：直接切过去（保留面板打开，便于连续翻看） */
async function openSimilarImage(item: SimilarImageItem) {
  /* 在相似图里点开一张，左右切换就沿着这一列走 */
  previewNav.value = similarItems.value
  const target = item as unknown as ImageItem
  await openImage(target)
  similarItems.value = []
  similarForId.value = ''
  if (selectedImage.value) await openSimilar(selectedImage.value.id)
}

/* ---- 关系网视图 ---- */
const graphPayload = ref<EmbedGraphPayload | null>(null)
const graphLoading = ref(false)
/** 关系网的两种看法：星系图（WebGL 看全局形状）/ 分组列表（按相似分组逐张看） */
const graphView = ref<'galaxy' | 'galaxy3d' | 'list'>('galaxy')
/**
 * 关系网的两种**依据**：
 *  · visual —— CCIP 视觉指纹（默认）。回答「这两张图长得像不像」，
 *    能发现同一张图的不同裁切/换色。
 *  · tag —— 标签的 TF-IDF 相似度。回答「这两张图被打了同一批标记没有」，
 *    能发现同一个作者/同一本画集/同一套服装这类看起来不像但被一起归档的东西。
 */
const graphMode = ref<EmbedGraphMode>('visual')

/* ---- 大图查看器的左右切换 ----
   浏览的是「当前这批图」：从图片库进来就是那一屏列表，从星图进来则是这张图的相似图
   （相似图还没到时先退回整库，到了会自动换过来）。 */
const previewNav = ref<ImageItem[]>([])
const previewNavIndex = computed(() => {
  const id = selectedImage.value?.id
  return id ? previewNav.value.findIndex((entry) => entry.id === id) : -1
})
const canPreviewNav = computed(() => previewNav.value.length > 1 && previewNavIndex.value >= 0)
function stepPreview(delta: number) {
  const list = previewNav.value
  if (list.length < 2) return
  const next = (previewNavIndex.value + delta + list.length) % list.length
  void openImage(list[next])
}

/** 切换依据要重新取数：两套坐标与两套边完全不同，不能沿用 */
async function setGraphMode(mode: EmbedGraphMode) {
  if (graphMode.value === mode) return
  graphMode.value = mode
  graphPayload.value = null
  await loadGraph(true)
}

/* ---- 设置页：相似图索引状态与阈值 ---- */
const embedStatus = ref<EmbedStatusPayload | null>(null)
const embedCalibration = ref<EmbedCalibrationPayload | null>(null)
const embedThresholdDraft = ref(0.35)
const embedBackfilling = ref(false)
const embedSavingThreshold = ref(false)
/** 进度轮询定时器：建索引期间每 2 秒刷一次状态，算完自动停 */
let embedPollTimer: number | undefined

function pct(value: number | null | undefined) {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

async function refreshEmbedStatus() {
  try {
    const status = await api.embedStatus()
    embedStatus.value = status
    if (!embedSavingThreshold.value) embedThresholdDraft.value = status.threshold
    /* 还有活儿在跑就继续轮询；跑完停掉，别一直打服务器 */
    if (status.working || status.queueLength > 0) startEmbedPoll()
    else stopEmbedPoll()
  } catch (error) {
    notify(errorMessage(error))
  }
}

function startEmbedPoll() {
  if (embedPollTimer) return
  embedPollTimer = window.setInterval(() => { void refreshEmbedStatus() }, 2000)
}

function stopEmbedPoll() {
  if (!embedPollTimer) return
  window.clearInterval(embedPollTimer)
  embedPollTimer = undefined
  /* 索引算完了，关系网缓存作废，下次进视图重新拉 */
  graphPayload.value = null
}

async function refreshEmbedCalibration() {
  try { embedCalibration.value = await api.embedCalibration() } catch { /* 校准数据只是参考，取不到就算了 */ }
}

async function loadEmbedSettings() {
  await refreshEmbedStatus()
  await refreshEmbedCalibration()
}

async function startBackfill(force: boolean) {
  embedBackfilling.value = true
  try {
    const result = await api.embedBackfill(force)
    notify(result.queued ? `已排入 ${result.queued} 张，后台开始计算指纹` : '所有图片都已有指纹')
    await refreshEmbedStatus()
  } catch (error) {
    notify(errorMessage(error))
  } finally {
    embedBackfilling.value = false
  }
}

function onThresholdInput(event: Event) {
  embedThresholdDraft.value = Number((event.target as HTMLInputElement).value)
}

async function saveThreshold() {
  embedSavingThreshold.value = true
  try {
    const result = await api.embedSetThreshold(embedThresholdDraft.value)
    embedThresholdDraft.value = result.threshold
    graphPayload.value = null
    notify(`阈值已保存为 ${Math.round(result.threshold * 100)}%，关系网已重算`)
    await refreshEmbedStatus()
    await refreshEmbedCalibration()
  } catch (error) {
    notify(errorMessage(error))
  } finally {
    embedSavingThreshold.value = false
  }
}

/* ---- 人物框选：叠在预览图上的可编辑框层 ----
   框坐标一律用归一化值（0~1，相对原图宽高）存储，与显示尺寸、缩放级别无关；
   前端按图片自然尺寸换算成像素，因此放大/缩小/拖拽时框会跟着图走。 */
const detectModelsReady = ref(false)
const boxMode = ref(false)
const boxList = ref<DetectBox[]>([])
const boxesLoaded = ref(false)
const boxesLoading = ref(false)
const boxesSaving = ref(false)
const boxesDirty = ref(false)
const boxesMeta = ref({ width: 0, height: 0, detectedAt: '' })
/** 正在拖拽的框：模式 + 起点快照 */
type BoxDragMode = 'move' | 'resize'
const boxDrag = ref<{ mode: BoxDragMode; index: number; startX: number; startY: number; origin: DetectBox } | null>(null)
/** 参考图库：给哪个标签确认参考 */
const refPickerOpen = ref(false)
const refTagId = ref('')
const refSaving = ref(false)
const referenceCounts = ref<Record<string, number>>({})
/** 当前选中的框（点一下就选中）；确认参考图时用这个框，而不是无脑取最高分 */
const selectedBoxIndex = ref(0)
/* ---- 参考图库查看页：按角色核验已识别的参考图与框选 ---- */
const showReferences = ref(false)
const referencesTag = ref<TagItem | null>(null)
const referenceItems = ref<ReferenceItem[]>([])
const referencesLoading = ref(false)
const referenceRemoving = ref('')
async function openReferences(tag: TagItem) {
  referencesTag.value = tag
  showReferences.value = true
  referencesLoading.value = true
  referenceItems.value = []
  try {
    const payload = await api.tagReferences(tag.id)
    referenceItems.value = payload.items || []
  } catch (error) { notify(errorMessage(error)) } finally { referencesLoading.value = false }
}
function closeReferences() { showReferences.value = false; referencesTag.value = null; referenceItems.value = [] }
/** 从参考图库里移除一条；同一张图可能有多个框，所以带坐标精确删除 */
async function dropReference(item: ReferenceItem) {
  const tag = referencesTag.value
  if (!tag) return
  referenceRemoving.value = `${item.imageId}-${item.box.x}-${item.box.y}`
  try {
    await api.removeTagReference(tag.id, item.imageId, item.box)
    referenceItems.value = referenceItems.value.filter((entry) => !(entry.imageId === item.imageId && entry.box.x === item.box.x && entry.box.y === item.box.y))
    await loadReferenceSummary()
    notify('已从参考图库移除')
  } catch (error) { notify(errorMessage(error)) } finally { referenceRemoving.value = '' }
}
/** 点参考条目直接跳去看那张大图（可在框选模式里核对框准不准） */
async function openReferenceImage(item: ReferenceItem) {
  const found = images.value.find((image) => image.id === item.imageId)
  closeReferences()
  if (found) await openImage(found)
  else notify('这张图不在当前列表中，请到图片库搜索')
}
const personTagsOfImage = computed(() => (selectedImage.value?.tags || []).map((name) => tagByName.value.get(name)).filter((tag): tag is TagItem => Boolean(tag?.person)))
/** 框在预览图上的像素位置（图片按自然尺寸布局，故直接乘自然尺寸） */
const boxPixels = computed(() => boxList.value.map((box, index) => ({
  index,
  box,
  style: {
    left: `${box.x * 100}%`,
    top: `${box.y * 100}%`,
    width: `${box.w * 100}%`,
    height: `${box.h * 100}%`,
  },
})))
const hasBoxes = computed(() => boxList.value.length > 0)

function resetBoxState() {
  boxMode.value = false
  boxList.value = []
  boxesLoaded.value = false
  boxesLoading.value = false
  boxesSaving.value = false
  boxesDirty.value = false
  boxDrag.value = null
  refPickerOpen.value = false
  refTagId.value = ''
  selectedBoxIndex.value = 0
}
async function loadReferenceSummary() {
  try { referenceCounts.value = (await api.referenceCounts()).counts || {} } catch { /* 未启用时忽略 */ }
}
async function loadDetectStatus() {
  try { detectModelsReady.value = (await api.detectStatus()).modelsReady } catch { detectModelsReady.value = false }
}
/** 打开框选模式：先取本地已保存的框，没有再让用户点「自动框选」 */
async function toggleBoxMode() {
  if (boxMode.value) { boxMode.value = false; return }
  const image = selectedImage.value
  if (!image) return
  if (isVideoItem(image)) { notify('视频暂不支持框选，请先在「视频提取」中抽帧保存为图片'); return }
  boxMode.value = true
  if (boxesLoaded.value) return
  boxesLoading.value = true
  try {
    const payload = await api.imageBoxes(image.id)
    boxList.value = payload.boxes || []
    boxesMeta.value = { width: payload.width, height: payload.height, detectedAt: payload.detectedAt }
    boxesLoaded.value = true
    boxesDirty.value = false
    if (!boxList.value.length && detectModelsReady.value) await runDetect()
  } catch (error) { notify(errorMessage(error)) } finally { boxesLoading.value = false }
}
/** 跑一次检测（覆盖当前框；若已有手工改动会先提示） */
async function runDetect() {
  const image = selectedImage.value
  if (!image) return
  if (boxesDirty.value && !window.confirm('自动框选会覆盖你尚未保存的调整，继续？')) return
  boxesLoading.value = true
  try {
    const payload = await api.detectImageBoxes(image.id)
    boxList.value = payload.boxes || []
    boxesMeta.value = { width: payload.width, height: payload.height, detectedAt: payload.detectedAt }
    boxesLoaded.value = true
    boxesDirty.value = false
    notify(`识别到 ${boxList.value.length} 个人物框`)
  } catch (error) { notify(errorMessage(error)) } finally { boxesLoading.value = false }
}
async function saveBoxes() {
  const image = selectedImage.value
  if (!image) return
  boxesSaving.value = true
  try {
    const payload = await api.saveImageBoxes(image.id, {
      width: boxesMeta.value.width || image.width,
      height: boxesMeta.value.height || image.height,
      boxes: boxList.value,
    })
    boxList.value = payload.boxes || []
    boxesDirty.value = false
    notify('框选已保存')
  } catch (error) { notify(errorMessage(error)) } finally { boxesSaving.value = false }
}
/* ---- 框的拖拽（移动 / 右下角缩放）。previewScale 缩放时要把像素位移换算回归一化增量 ---- */
function boxPointerDown(mode: BoxDragMode, index: number, event: PointerEvent) {
  event.preventDefault()
  event.stopPropagation()
  const box = boxList.value[index]
  if (!box) return
  selectedBoxIndex.value = index
  boxDrag.value = { mode, index, startX: event.clientX, startY: event.clientY, origin: { ...box } }
  const target = event.currentTarget as HTMLElement
  target.setPointerCapture?.(event.pointerId)
  // 监听挂在 window 上：即使指针移出框也能继续拖动
  window.addEventListener('pointermove', boxPointerMove)
  window.addEventListener('pointerup', boxPointerUp, { once: true })
}
function boxPointerMove(event: PointerEvent) {
  const drag = boxDrag.value
  const image = selectedImage.value
  if (!drag || !image) return
  const naturalW = boxesMeta.value.width || image.width || 1
  const naturalH = boxesMeta.value.height || image.height || 1
  const scale = previewScale.value || 1
  const dx = (event.clientX - drag.startX) / scale / naturalW
  const dy = (event.clientY - drag.startY) / scale / naturalH
  const box = boxList.value[drag.index]
  if (!box) return
  if (drag.mode === 'move') {
    box.x = Math.min(Math.max(0, drag.origin.x + dx), Math.max(0, 1 - drag.origin.w))
    box.y = Math.min(Math.max(0, drag.origin.y + dy), Math.max(0, 1 - drag.origin.h))
  } else {
    box.w = Math.min(Math.max(0.02, drag.origin.w + dx), 1 - box.x)
    box.h = Math.min(Math.max(0.02, drag.origin.h + dy), 1 - box.y)
  }
  box.manual = true
  boxesDirty.value = true
}
function boxPointerUp() {
  boxDrag.value = null
  window.removeEventListener('pointermove', boxPointerMove)
}
/** 新增一个框（默认放在画面中部，用户再拖到目标位置） */
function addBox() {
  boxList.value.push({ x: 0.3, y: 0.3, w: 0.3, h: 0.3, score: 0, kind: 'face', manual: true })
  boxesDirty.value = true
  notify('已新增一个框，拖动它对准人物后保存')
}
function removeBox(index: number) {
  boxList.value.splice(index, 1)
  boxesDirty.value = true
}
/** 把这个框确认成某角色的参考样本 */
async function confirmReference() {
  const image = selectedImage.value
  const tagId = refTagId.value
  if (!image || !tagId) return
  if (!boxList.value.length) { notify('请先框选人物'); return }
  // 用当前选中的框；没有选中就退回分数最高的那个
  const index = boxList.value[selectedBoxIndex.value]
    ? selectedBoxIndex.value
    : boxList.value.reduce((best, box, i) => (box.score > (boxList.value[best]?.score ?? -1) ? i : best), 0)
  refSaving.value = true
  try {
    const result = await api.addTagReference(tagId, { imageId: image.id, box: boxList.value[index] })
    await loadReferenceSummary()
    refPickerOpen.value = false
    notify(`已加入参考图库（该角色共 ${result.total} 张参考）`)
  } catch (error) { notify(errorMessage(error)) } finally { refSaving.value = false }
}
function closePreviewBoxes() {
  if (boxesDirty.value && !window.confirm('框选还没保存，确定关闭？')) return false
  return true
}
/* ---- 设备方向感知：横屏→视频大屏全屏，竖屏→退回小窗 ---- */
const landscapeQuery = window.matchMedia('(orientation: landscape)')
const isLandscape = ref(landscapeQuery.matches)
const videoFullscreen = ref(false)
let landscapeCleanup: (() => void) | undefined
const contextImage = ref<ImageItem | null>(null)
const contextPosition = ref({ x: 0, y: 0 })
const contextTag = ref<TagItem | null>(null)
const tagContextPosition = ref({ x: 0, y: 0 })
const showUpload = ref(false)
const showTagDialog = ref(false)
const showAddTag = ref(false)
const showRemoveTag = ref(false)
const showBatchTag = ref(false)
const batchTag = ref<TagItem | null>(null)
const batchImageQuery = ref('')
const selectedBatchImageIds = ref<string[]>([])
const showRename = ref(false)
const renameName = ref('')
/** 「修改名称 / 添加标签 / 移除标签」三个弹窗各自要作用于哪张图（右键菜单一关就从 contextImage 转存过来） */
const renameImage = ref<ImageItem | null>(null)
const addTagImage = ref<ImageItem | null>(null)
const removeTagImage = ref<ImageItem | null>(null)
const logFilter = ref('全部')
const toast = ref('')
const images = ref<ImageItem[]>([])
const tags = ref<TagItem[]>([])
const logs = ref<AuditLog[]>([])
const newTagPerson = ref(false)
/* ---- 人物组（人名标签）：绿色圆点，可从「全部标签」长按 / 右键放入 ---- */
const PERSON_COLOR = '#22c55e'
const personTags = computed(() => tags.value.filter((tag) => tag.person))
const normalTags = computed(() => tags.value.filter((tag) => !tag.person))
const tagByName = computed(() => new Map(tags.value.map((tag) => [tag.name, tag])))
const personGroupOpen = ref(true)
const showPersonDetail = ref(false)
const personDetail = ref<PersonDetail | null>(null)
const personDetailLoading = ref(false)
function tagDot(tag: { person?: boolean; r18?: boolean; color: string }) { return tag.person ? PERSON_COLOR : tag.r18 ? '#ef4444' : tag.color }
/** 图片库顶部标签筛选条：人物标签前面带一个绿色圆点 */
const tagPills = computed<Array<{ name: string; person?: boolean }>>(() => [{ name: '全部标签' }, ...tags.value.map((tag) => ({ name: tag.name, person: tag.person }))])
/** 「添加标签」弹窗：只列出这张图片还没有的标签，可多选一次性添加 */
const addableTags = computed(() => { const owned = new Set(addTagImage.value?.tags || []); return tags.value.filter((tag) => !owned.has(tag.name)) })
/** 「移除标签」弹窗要列出这张图已经有的标签（带 id，才能精确删除） */
const removeTagOptions = computed(() => {
  const image = removeTagImage.value
  if (!image) return []
  return image.tags.map((name, index) => tagByName.value.get(name) || { id: image.tagIds[index], name, color: '#fb7185' })
})
const savedTagPickerColumns = Number(localStorage.getItem('tagPickerColumns'))
const tagPickerColumns = ref([1, 2, 3, 4].includes(savedTagPickerColumns) ? savedTagPickerColumns : 2)
function setTagPickerColumns(n: number) { tagPickerColumns.value = n; try { localStorage.setItem('tagPickerColumns', String(n)) } catch { /* ignore */ } }
const isDark = ref(true)
const selectedTheme = ref('Aurora')
const settings = ref<Record<string, any>>({ siteName: 'fluxframe', theme: 'Aurora', webglEnabled: true, animationEnabled: true, uploadLimitMb: 50, port: 4310, storageDir: './storage', recycleRetentionDays: 30 })
const fluidColors = ref(['#4f46e5', '#06b6d4', '#f472b6'])
const fluidSpeed = ref(3)
const webglEnabled = ref(true)
const animationEnabled = ref(true)
const stats = ref({ imageCount: 0, tagCount: 0, userCount: 0, totalViews: 0, storage: '0 B', storageCapacity: '未知', storagePercent: 0, databaseImageBytes: '0 B' })
const libraryLoading = ref(false)
const libraryLoaded = ref(0)
const libraryTotal = ref(0)
const loadedImageIds = new Set<string>()
let progressHideTimer: number | undefined
const libraryProgress = computed(() => (libraryTotal.value ? Math.min(100, Math.round((libraryLoaded.value / libraryTotal.value) * 100)) : 0))
const savedTagColumns = Number(localStorage.getItem('tagColumns'))
const tagColumns = ref(savedTagColumns === 1 || savedTagColumns === 2 || savedTagColumns === 3 ? savedTagColumns : 2)
function setTagColumns(n: number) { tagColumns.value = n; try { localStorage.setItem('tagColumns', String(n)) } catch { /* ignore */ } }
/* ---- 命令面板（⌘K） ---- */
const paletteOpen = ref(false)
/* ---- 图库密度：桌面记住上次选择，手机锁两列 ---- */
const savedDensity = localStorage.getItem('gridDensity')
const gridDensity = ref(['compact', 'cozy', 'roomy'].includes(savedDensity || '') ? savedDensity! : 'cozy')
function setGridDensity(value: string) { gridDensity.value = value; try { localStorage.setItem('gridDensity', value) } catch { /* ignore */ } }
/* ---- 图库多选 ---- */
const selectMode = ref(false)
const selectedIds = ref<string[]>([])
function toggleSelectMode() {
  selectMode.value = !selectMode.value
  selectedIds.value = []
  if (selectMode.value) notify('多选模式：点击卡片加入选择')
}
function toggleImageSelected(id: string) {
  const index = selectedIds.value.indexOf(id)
  if (index >= 0) selectedIds.value.splice(index, 1)
  else selectedIds.value.push(id)
}
function selectAllVisible() { selectedIds.value = filteredImages.value.map((image) => image.id) }
function clearSelection() { selectedIds.value = []; selectMode.value = false }
async function batchDeleteSelected() {
  const count = selectedIds.value.length
  if (!count) return
  if (!window.confirm(`把选中的 ${count} 张移入回收站？`)) return
  try {
    for (const id of selectedIds.value) await api.deleteImage(id)
    notify(`已移入回收站 ${count} 张`)
    clearSelection()
    await refreshImages()
    await refreshDashboard()
  } catch (error) { notify(errorMessage(error)) }
}
async function batchStarSelected(star: boolean) {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  try {
    for (const id of ids) await api.setStarred(id, star)
    const live = images.value.filter((image) => ids.includes(image.id))
    live.forEach((image) => { image.starred = star })
    notify(star ? `已收藏 ${ids.length} 张` : `已取消收藏 ${ids.length} 张`)
    clearSelection()
  } catch (error) { notify(errorMessage(error)) }
}
/** 单张切换收藏：卡片星标、右键菜单、命令面板共用 */
async function toggleStar(image: ImageItem) {
  const next = !image.starred
  image.starred = next
  try {
    await api.setStarred(image.id, next)
    notify(next ? '已加入收藏' : '已取消收藏')
  } catch (error) {
    image.starred = !next
    notify(errorMessage(error))
  }
}
let searchTimer: number | undefined
const currentHour = ref(new Date().getHours())
let greetingTimer: number | undefined

const navItems: { id: View; label: string; icon: typeof LayoutDashboard; badge?: string }[] = [
  { id: 'overview', label: '总览', icon: LayoutDashboard },
  { id: 'library', label: '图片库', icon: Images },
  { id: 'similar', label: '相似图', icon: Sparkles },
  { id: 'parse', label: '视频提取', icon: Link2 },
  { id: 'tags', label: '智能标签', icon: Tags },
  { id: 'logs', label: '访问日志', icon: ShieldCheck },
  { id: 'trash', label: '回收站', icon: Trash2 },
]
/** 手机底部导航：5 个入口均分全宽（回收站在移动端不进底栏，由图片库内操作进入） */
const mobileNavItems = computed<{ id: View; label: string; icon: typeof LayoutDashboard }[]>(() => [...navItems.filter((item) => item.id !== 'trash'), { id: 'settings', label: '设置', icon: Settings2 }])
const pageTitle = computed(() => ({ overview: '总览', library: '图片库', similar: '相似图关系网', parse: '视频提取', tags: '智能标签', logs: '访问日志', trash: '回收站', settings: '系统设置' }[activeView.value]))
const r18Mode = computed<boolean>(() => currentUser.value?.r18Mode ?? false)
const timeGreeting = computed(() => {
  if (currentHour.value < 5) return '夜深了'
  if (currentHour.value < 12) return '早上好'
  if (currentHour.value < 14) return '中午好'
  if (currentHour.value < 19) return '下午好'
  return '晚上好'
})
const liveImages = computed(() => images.value.filter((image) => !image.deletedAt))
const trashCount = computed(() => images.value.filter((image) => image.deletedAt).length)
const filteredImages = computed(() => {
  let result = activeView.value === 'trash' ? images.value.filter((image) => image.deletedAt) : liveImages.value
  const search = query.value.trim().toLowerCase()
  if (search) result = result.filter((image) => image.name.toLowerCase().includes(search) || image.tags.some((tag) => tag.toLowerCase().includes(search)))
  if (selectedTag.value !== '全部标签') result = result.filter((image) => image.tags.includes(selectedTag.value))
  /* 与服务端一致的约定：收藏永远置顶，其余按所选排序 */
  const starredFirst = (a: ImageItem, b: ImageItem) => Number(b.starred || 0) - Number(a.starred || 0)
  if (sortBy.value === 'views') result = [...result].sort((a, b) => starredFirst(a, b) || b.views - a.views)
  if (sortBy.value === 'newest') result = [...result].sort((a, b) => starredFirst(a, b) || b.uploadedAt.localeCompare(a.uploadedAt))
  if (sortBy.value === 'oldest') result = [...result].sort((a, b) => starredFirst(a, b) || a.uploadedAt.localeCompare(b.uploadedAt))
  if (sortBy.value === 'name') result = [...result].sort((a, b) => starredFirst(a, b) || a.name.localeCompare(b.name))
  return result
})
const topImages = computed(() => [...liveImages.value].sort((a, b) => b.views - a.views).slice(0, 4))

/* ---- 关系网视图：把相似图按「连通分组」聚起来看 ---- */
/** 关系网视图：把相似图按「连通分组」聚起来看 ---- */
async function loadGraph(force = false) {
  if (graphLoading.value) return
  const space: EmbedGraphSpace = graphView.value === 'galaxy3d' ? '3d' : '2d'
  /* 手上这份数据的坐标系不对也得重取：二维和三维的坐标完全不是一套 */
  if (graphPayload.value && !force && graphPayload.value.space === space) return
  graphLoading.value = true
  try {
    /* 标签模式的边是"确凿的共同标签"，不是噪声，所以允许连得比视觉模式多 */
    const result = await api.embedGraph(graphMode.value === 'tag' ? 1000 : 600, graphMode.value, space)
    graphPayload.value = result
  } catch (error) {
    notify(errorMessage(error))
  } finally {
    graphLoading.value = false
  }
}

/** 切星系视图：二维/三维坐标不是一套，必须重新取数 */
async function setGraphView(view: 'galaxy' | 'galaxy3d' | 'list') {
  if (graphView.value === view) return
  graphView.value = view
  if (view === 'list') return
  const space: EmbedGraphSpace = view === 'galaxy3d' ? '3d' : '2d'
  if (graphPayload.value?.space === space) return
  graphPayload.value = null
  await loadGraph(true)
}

/** 分组 → 具体图片。按组大小降序，第一张当封面 */
const graphGroups = computed(() => {
  const payload = graphPayload.value
  if (!payload) return []
  const byId = new Map(payload.nodes.map((node) => [node.id, node]))
  return payload.groups
    .map((group) => ({ size: group.size, items: group.members.map((id) => byId.get(id)).filter(Boolean) as ImageItem[] }))
    .filter((group) => group.items.length > 1)
    .sort((a, b) => b.items.length - a.items.length)
})

async function refreshGraph() {
  graphPayload.value = null
  await loadGraph(true)
}

/** 点分组里的图片：打开大图并直接拉出相似图面板，顺着关系一路看下去 */
async function openFromGraph(image: ImageItem, showSimilarPanel = true) {
  /* 先用整库兜底：相似图是异步来的，等它到了再换（见 openSimilar 末尾） */
  previewNav.value = images.value
  await openImage(image)
  /*
   * 三维星系那边不把相似图面板拉起来：它是"推近 → 大图"的路子，
   * 面板一升起来就把大图盖掉三分之二，而左右箭头已经能翻亲戚了。
   * 二维星图保持原样 —— 那张视图的重点本来就是关系网本身。
   */
  await openSimilar(image.id, showSimilarPanel)
}

async function handleUploadCompleted(count: number) { showUpload.value = false; await Promise.all([refreshImages(), refreshTags(), refreshDashboard()]); notify(`已完成上传 ${count} 个文件`) }
const batchCandidates = computed(() => {
  const queryText = batchImageQuery.value.trim().toLowerCase()
  return images.value.filter((image) => !image.deletedAt && !image.tagIds.includes(batchTag.value?.id || '') && (!queryText || image.name.toLowerCase().includes(queryText) || image.tags.some((tag) => tag.toLowerCase().includes(queryText))))
})
function notify(message: string) { toast.value = message; window.setTimeout(() => { toast.value = '' }, 2600) }
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
async function refreshImages() { const response = await api.images({ search: query.value.trim() || undefined, sort: sortBy.value, tag: selectedTag.value === '全部标签' ? undefined : selectedTag.value, trash: activeView.value === 'trash' }); images.value = Array.isArray(response?.items) ? response.items : []; beginImageProgress() }
function beginImageProgress() {
  if (progressHideTimer) window.clearTimeout(progressHideTimer)
  progressHideTimer = undefined
  loadedImageIds.clear()
  libraryTotal.value = filteredImages.value.length
  libraryLoaded.value = 0
  libraryLoading.value = (activeView.value === 'library' || activeView.value === 'trash') && libraryTotal.value > 0
  if (libraryLoading.value) {
    void nextTick(() => {
      document.querySelectorAll('.image-grid img, .image-grid video').forEach((media) => {
        const element = media as HTMLImageElement | HTMLVideoElement
        const id = element.closest('.image-card')?.getAttribute('data-image-id')
        const done = element instanceof HTMLImageElement ? element.complete : (element as HTMLVideoElement).readyState >= 1
        if (done && id && !loadedImageIds.has(id)) {
          loadedImageIds.add(id)
          libraryLoaded.value = Math.min(libraryLoaded.value + 1, libraryTotal.value)
        }
      })
      maybeFinishLibraryProgress()
    })
  }
}
function maybeFinishLibraryProgress() {
  if (libraryLoading.value && libraryLoaded.value >= libraryTotal.value) {
    progressHideTimer = window.setTimeout(() => { libraryLoading.value = false }, 420)
  }
}
function isVideoItem(item: { mimeType?: string } | null | undefined) { return !!item?.mimeType && item.mimeType.startsWith('video/') }
/* 有服务端生成的封面（thumb 指向 variant 而非视频本体）时，视频卡片直接用 <img> 显示封面，
   网格不再加载/解码任何视频（这是卡顿主因）；无封面才回退旧的 <video> 取帧方案 */
function hasPoster(item: { mimeType?: string; thumb?: string; url?: string }) { return !item.url || item.thumb !== item.url }
function handleImageProgress(image: ImageItem) {
  if (!libraryLoading.value || loadedImageIds.has(image.id)) return
  loadedImageIds.add(image.id)
  libraryLoaded.value = Math.min(libraryLoaded.value + 1, libraryTotal.value)
  maybeFinishLibraryProgress()
}
async function refreshTags() { const response = await api.tags(); tags.value = Array.isArray(response?.items) ? response.items : [] }
async function refreshLogs() { if (currentUser.value?.role === 'ADMIN') { const response = await api.logs(); logs.value = Array.isArray(response?.items) ? response.items : [] } }
async function refreshDashboard() { const response = await api.dashboard(); if (response?.stats) stats.value = { ...stats.value, ...response.stats }; if (currentUser.value?.role !== 'ADMIN') logs.value = Array.isArray(response?.logs) ? response.logs : [] }
function applyPreferences() { document.documentElement.dataset.theme = isDark.value ? 'dark' : 'light'; document.documentElement.dataset.themeName = selectedTheme.value.toLowerCase(); document.documentElement.dataset.motion = animationEnabled.value ? 'full' : 'reduced'; document.title = `${settings.value.siteName || 'fluxframe'} · Intranet Image Manager` }
async function refreshSettings() { if (currentUser.value?.role !== 'ADMIN') return; const data = await api.settings(); settings.value = { ...settings.value, ...data }; selectedTheme.value = String(data.theme || 'Aurora'); /* 原先这里写死 true：管理员每次进设置页都会被强制切回深色，浅色模式等于存了也白存 */ isDark.value = data.darkMode !== false; webglEnabled.value = data.webglEnabled !== false; animationEnabled.value = data.animationEnabled !== false; fluidSpeed.value = 3; if (Array.isArray(data.fluidColors) && data.fluidColors.length >= 3) fluidColors.value = data.fluidColors.slice(0, 3).map(String); applyPreferences() }
async function loadData() { await Promise.all([refreshTags(), refreshDashboard(), refreshImages(), refreshLogs(), refreshSettings(), loadDetectStatus(), loadReferenceSummary()]) }
async function checkSession() { try { currentUser.value = await api.me(); authenticated.value = true; await loadData() } catch { authenticated.value = false } finally { booting.value = false } }
async function handleLogin(username: string, password: string) { authLoading.value = true; authError.value = ''; try { const response = await api.login(username, password); currentUser.value = response.user; authenticated.value = true; await loadData() } catch (error) { authError.value = errorMessage(error) } finally { authLoading.value = false } }
async function handleRegister(username: string, password: string) { authLoading.value = true; authError.value = ''; try { await api.register(username, password); const response = await api.login(username, password); currentUser.value = response.user; authenticated.value = true; await loadData() } catch (error) { authError.value = errorMessage(error) } finally { authLoading.value = false } }
async function logout() { await api.logout().catch(() => undefined); authenticated.value = false; currentUser.value = null; images.value = []; tags.value = []; logs.value = [] }
async function go(view: View) { closePreview(); activeView.value = view; moreOpen.value = false; selectedTag.value = '全部标签'; if (view === 'library' || view === 'trash' || view === 'tags') await refreshImages(); if (view === 'logs') await refreshLogs(); if (view === 'settings') { await refreshSettings(); await loadEmbedSettings() } if (view === 'similar') await loadGraph(); nextTick(() => enterPage()) }
function handleSearchInput() { if (searchTimer) window.clearTimeout(searchTimer); if (activeView.value !== 'library' && activeView.value !== 'trash') return; searchTimer = window.setTimeout(() => { void refreshImages() }, 280) }
async function submitGlobalSearch() { if (searchTimer) window.clearTimeout(searchTimer); activeView.value = 'library'; selectedTag.value = '全部标签'; moreOpen.value = false; await refreshImages(); nextTick(() => enterPage()) }
function setSort(value: string) { sortBy.value = value; void refreshImages() }
function clearGlobalSearch() { query.value = ''; if (activeView.value === 'library' || activeView.value === 'trash') void refreshImages() }
async function viewTag(tagName: string) { activeView.value = 'library'; selectedTag.value = tagName; moreOpen.value = false; await refreshImages(); nextTick(() => enterPage()) }
function openContextAt(image: ImageItem, x: number, y: number) {
  contextImage.value = image
  contextTag.value = null
  contextPosition.value = { x: Math.min(x, window.innerWidth - 220), y: Math.min(y, window.innerHeight - 245) }
}
function openContext(image: ImageItem, event: MouseEvent) { openContextAt(image, event.clientX, event.clientY) }
/* ---- 手机端长按打开文件菜单（与桌面右键同一菜单，<720px 时自动变为底部弹层） ---- */
const LONG_PRESS_MS = 520
let longPressTimer: number | undefined
let longPressFired = false
let suppressOpenUntil = 0
function onImageTouchStart(image: ImageItem, event: TouchEvent) {
  if (longPressTimer) window.clearTimeout(longPressTimer)
  longPressFired = false
  const touch = event.touches && event.touches[0]
  if (!touch) return
  const x = touch.clientX
  const y = touch.clientY
  longPressTimer = window.setTimeout(() => {
    longPressTimer = undefined
    longPressFired = true
    /* 长按抬起后会补发一次 click（个别浏览器即使 touchend preventDefault 也发），
       在一小段时间内拦截 openImage，避免菜单刚弹出就被点开大图 */
    suppressOpenUntil = Date.now() + 1000
    openContextAt(image, x, y)
  }, LONG_PRESS_MS)
}
function cancelImageLongPress() {
  if (longPressTimer) { window.clearTimeout(longPressTimer); longPressTimer = undefined }
  longPressFired = false
}
function onImageTouchEnd(event: TouchEvent) {
  /* 长按已触发：阻止兼容 click，防止菜单弹出后立刻又打开大图 */
  if (longPressFired) { event.preventDefault(); longPressFired = false }
  cancelImageLongPress()
}
function resetPreview() { previewScale.value = 1; previewOffset.value = { x: 0, y: 0 } }
function closePreview() {
  if (boxMode.value && !closePreviewBoxes()) return
  selectedImage.value = null
  videoFullscreen.value = false
  flipFrom.value = null
  resetPreview()
  resetBoxState()
  resetSimilar()
}
/* ---- 上滑拉出相似图：手机上整层 backdrop 都能起手 ----
   为什么判定放在整层 backdrop 而不是图片元素上：手机竖屏看横图时图片只占中间一条，
   左右留白、上下留白都点不到图片；只绑图片的话「在大图上滑」有一半区域没反应。 */

function handlePreviewWheel(event: WheelEvent) {
  /* 相似图面板已打开时，滚轮交给面板自己滚（它能滚就滚，滚到头再由浏览器/外层处理） */
  if (similarOpen.value) return
  const element = previewImageElement.value
  if (!element || isVideoItem(selectedImage.value)) return

  /* 滚轮缩放：以指针位置为锚点，scale=1 时向上滚即放大。
     这里原本被「滚轮上滑拉出相似图」占着，导致 previewScale 永远停在 1、滚轮缩放整条路走不通；
     手机端的「上滑看相似图」仍由触摸手势负责（见 onPreviewTouchMove）。 */
  const rect = element.getBoundingClientRect()
  const oldScale = previewScale.value
  const nextScale = Math.min(6, Math.max(1, oldScale * (event.deltaY < 0 ? 1.16 : 1 / 1.16)))
  if (nextScale === oldScale) return
  const baseLeft = rect.left - previewOffset.value.x
  const baseTop = rect.top - previewOffset.value.y
  const localX = (event.clientX - baseLeft - previewOffset.value.x) / oldScale
  const localY = (event.clientY - baseTop - previewOffset.value.y) / oldScale
  previewOffset.value = { x: event.clientX - baseLeft - localX * nextScale, y: event.clientY - baseTop - localY * nextScale }
  previewScale.value = nextScale
}

/* ---- 移动端：整层上滑拉出相似图面板（覆盖图片内外的所有空白区域）---- */
function onPreviewTouchStart(event: TouchEvent) {
  swipeTouchStart.value = null
  /* 在相似图面板**内部**起手的滑动不归这里管：那是在滚列表，不是「下滑收起」。
     不排除的话，手指往下滚一下列表就把整个面板关掉了。 */
  if ((event.target as HTMLElement | null)?.closest('.similar-panel')) return
  if (event.touches.length !== 1) return
  const touch = event.touches[0]
  swipeTouchStart.value = { x: touch.clientX, y: touch.clientY }
}

function onPreviewTouchMove(event: TouchEvent) {
  const start = swipeTouchStart.value
  /* 放大状态下拖动是「看图片细节」，不抢；框选模式下拖动是在调框，也不抢。 */
  if (!start || event.touches.length !== 1) return
  if (previewScale.value > 1.01 || boxMode.value) return
  const touch = event.touches[0]
  const dx = touch.clientX - start.x
  const dy = touch.clientY - start.y
  /* 只认「基本竖直」的滑动：横向位移过大说明用户在横滑，不是想看/收起相似图 */
  if (Math.abs(dx) > Math.abs(dy) * 0.8) return

  /* 面板已打开：下滑收起，回到大图 —— 和上滑拉出互为反操作，怎么打开的怎么关掉。
     在面板**内部**起手的滑动不接管（那是在滚相似图列表），只认在图片区域起手的。 */
  if (similarOpen.value) {
    if (dy > SWIPE_DOWN_TRIGGER) {
      swipeTouchStart.value = null
      closeSimilar()
    }
    return
  }

  if (dy < -SWIPE_UP_TRIGGER && selectedImage.value) {
    swipeTouchStart.value = null
    void openSimilar(selectedImage.value.id)
  }
}

function onPreviewTouchEnd() { swipeTouchStart.value = null }
async function openImage(image: ImageItem, flip: { left: number; top: number; width: number; height: number } | null = null) { flipFrom.value = flip; try { resetBoxState(); resetPreview(); resetSimilar(); previewLoading.value = true; videoFullscreen.value = isVideoItem(image) && isLandscape.value; selectedImage.value = image; const result = await api.viewImage(image.id); image.views = result.views; if (selectedImage.value) selectedImage.value.views = result.views; stats.value.totalViews += 1 /* 不再每看一张图就全量刷新 500 条审计日志（进日志页时会刷新）；旧的写法让管理员每次点图都多一个 ~100KB 请求 */ } catch (error) { notify(errorMessage(error)) } }
/** 点开大图时的缩略图位置：查看器据此从卡片处 FLIP 放大（指针位置由 motion.ts 记录） */
const flipFrom = ref<{ left: number; top: number; width: number; height: number } | null>(null)
function captureFlipSource() {
  const point = recentPointer()
  const card = point ? document.elementFromPoint(point.x, point.y)?.closest?.('.image-card') : null
  if (!card) return null
  const rect = card.getBoundingClientRect()
  return rect.width && rect.height ? { left: rect.left, top: rect.top, width: rect.width, height: rect.height } : null
}
/** 卡片点击入口：长按刚弹出菜单后浏览器补发的 click 不应打开大图（菜单内的按钮走 openImage 不受此限） */
function openFromCard(image: ImageItem) {
  if (Date.now() < suppressOpenUntil) return
  /* 从网格点进来就按网格的顺序翻 —— 用户看到的就是这个顺序 */
  previewNav.value = images.value
  void openImage(image, captureFlipSource())
}
async function softDelete(image: ImageItem) { try { await api.deleteImage(image.id); contextImage.value = null; selectedImage.value = null; await refreshImages(); await refreshDashboard(); notify('图片已移入回收站') } catch (error) { notify(errorMessage(error)) } }
async function restore(image: ImageItem) { try { await api.restoreImage(image.id); contextImage.value = null; await refreshImages(); notify('图片已恢复') } catch (error) { notify(errorMessage(error)) } }
async function permanentDelete(image: ImageItem) { try { await api.permanentDelete(image.id); contextImage.value = null; await refreshImages(); notify('图片已永久删除') } catch (error) { notify(errorMessage(error)) } }
/** 打开「修改名称」弹窗：预填当前名称（不含扩展名），聚焦全选便于直接输入 */
function beginRename(image: ImageItem) {
  if (!image || image.deletedAt) return
  renameImage.value = image
  renameName.value = image.name
  showRename.value = true
  contextImage.value = null
}
async function submitRename() {
  const image = renameImage.value
  const name = renameName.value.trim()
  if (!image || !name) return
  try {
    const result = await api.renameImage(image.id, name)
    const item = images.value.find((entry) => entry.id === image.id)
    if (item) item.name = result.name
    showRename.value = false
    renameImage.value = null
    notify('文件名称已修改')
  } catch (error) { notify(errorMessage(error)) }
}
function selectRenameText(event: FocusEvent) { (event.target as HTMLInputElement | null)?.select() }
/** 移除标签：右键菜单关掉后弹窗仍要拿到那张图，「移除标签」按钮才点得动 */
function openRemoveTag(image: ImageItem) { removeTagImage.value = image; showRemoveTag.value = true; contextImage.value = null }
async function removeImageTag(tagId: string, tagName: string) {
  const image = removeTagImage.value
  if (!image || !tagId) return
  try {
    await api.removeTag(image.id, tagId)
    showRemoveTag.value = false
    removeTagImage.value = null
    await refreshTags()
    await refreshImages()
    notify(`已从「${image.name}」移除标签「${tagName}」`)
  } catch (error) { notify(errorMessage(error)) }
}
function openTagContextAt(tag: TagItem, x: number, y: number) { contextTag.value = tag; tagContextPosition.value = { x: Math.min(x, window.innerWidth - 250), y: Math.min(y, window.innerHeight - 170) }; contextImage.value = null }
function openTagContext(tag: TagItem, event: MouseEvent) { openTagContextAt(tag, event.clientX, event.clientY) }
/* ---- 手机端长按标签行：弹出标签菜单（放入人物组 / 批量添加图片） ---- */
let tagPressTimer: number | undefined
let tagPressFired = false
let tagMenuSuppressUntil = 0
function onTagTouchStart(tag: TagItem, event: TouchEvent) {
  if (tagPressTimer) window.clearTimeout(tagPressTimer)
  tagPressFired = false
  const touch = event.touches && event.touches[0]
  if (!touch) return
  const x = touch.clientX
  const y = touch.clientY
  tagPressTimer = window.setTimeout(() => {
    tagPressTimer = undefined
    tagPressFired = true
    tagMenuSuppressUntil = Date.now() + 1000
    openTagContextAt(tag, x, y)
  }, LONG_PRESS_MS)
}
function cancelTagLongPress() { if (tagPressTimer) { window.clearTimeout(tagPressTimer); tagPressTimer = undefined } tagPressFired = false }
function onTagTouchEnd(event: TouchEvent) { if (tagPressFired) { event.preventDefault(); tagPressFired = false } cancelTagLongPress() }
/** 标签行点击：长按刚弹出菜单后浏览器补发的 click 不再触发跳转 */
function openTagRow(tag: TagItem) {
  if (Date.now() < tagMenuSuppressUntil) return
  if (tag.person) void openPerson(tag)
  else void viewTag(tag.name)
}
async function toggleTagPerson(tag: TagItem, person: boolean) {
  try {
    const updated = await api.updateTag(tag.id, { person })
    await refreshTags()
    if (personDetail.value?.id === updated.id && !person) closePersonDetail()
    notify(person ? `「${updated.name}」已放入人物组` : `「${updated.name}」已移出人物组`)
  } catch (error) { notify(errorMessage(error)) }
}
/** 打开人物详情：展示该人名标签下全部图片的「全部标签」聚合 */
async function openPerson(tag: TagItem) {
  showPersonDetail.value = true
  personDetailLoading.value = true
  personDetail.value = { ...tag, imageCount: tag.count, related: [], latest: [] }
  try { personDetail.value = await api.personDetail(tag.id) } catch (error) { notify(errorMessage(error)) } finally { personDetailLoading.value = false }
}
function closePersonDetail() { showPersonDetail.value = false; personDetail.value = null; personDetailLoading.value = false }
function viewTagFromPerson(tag: { name: string }) { closePersonDetail(); void viewTag(tag.name) }
async function removePersonFromDetail() { const tag = personDetail.value; if (!tag) return; try { await api.updateTag(tag.id, { person: false }); closePersonDetail(); await refreshTags(); notify(`「${tag.name}」已移出人物组`) } catch (error) { notify(errorMessage(error)) } }
function openTagDialog(person = false) { newTagPerson.value = person; showTagDialog.value = true }
/* ---- 为图片添加标签：多选 + 每排 1~4 个（勾选状态由 AddTagModal 自己持有） ---- */
function openAddTag(image: ImageItem) { addTagImage.value = image; showAddTag.value = true; contextImage.value = null }
function closeAddTag() { showAddTag.value = false; addTagImage.value = null }
async function submitAddTags(names: string[]) {
  const image = addTagImage.value
  if (!image || !names.length) return
  try {
    for (const name of names) await api.addTag(image.id, name)
    closeAddTag()
    await refreshTags()
    await refreshImages()
    notify(`已添加 ${names.length} 个标签`)
  } catch (error) { notify(errorMessage(error)) }
}
async function openBatchTag(tag: TagItem) { contextTag.value = null; batchTag.value = tag; batchImageQuery.value = ''; selectedBatchImageIds.value = []; showBatchTag.value = true; if (activeView.value !== 'tags') await refreshImages() }
function selectAllBatchImages() { selectedBatchImageIds.value = batchCandidates.value.map((image) => image.id) }
async function submitBatchTag() { if (!batchTag.value || !selectedBatchImageIds.value.length) return; try { const result = await api.addImagesToTag(batchTag.value.id, selectedBatchImageIds.value); showBatchTag.value = false; await refreshTags(); await refreshImages(); notify(`已将 ${result.added} 张图片添加到「${batchTag.value.name}」`) } catch (error) { notify(errorMessage(error)) } }
async function createTag(payload: { name: string; person: boolean; r18: boolean }) {
  const name = payload.name.trim()
  if (!name) return
  try {
    await api.createTag(name, payload.person ? PERSON_COLOR : '#a78bfa', payload.r18, payload.person)
    showTagDialog.value = false
    newTagPerson.value = false
    await refreshTags()
    await refreshImages()
    notify(payload.person ? `人物「${name}」已创建，圆点为绿色` : payload.r18 ? 'R18 链接标签已创建' : '新标签已创建')
  } catch (error) { notify(errorMessage(error)) }
}
async function toggleTagR18(tag: TagItem) {
  const next = !tag.r18
  try {
    const updated = await api.updateTag(tag.id, { r18: next })
    await refreshTags()
    notify(updated.r18 ? `「${updated.name}」已标记为 R18 链接标签` : `「${updated.name}」已取消 R18 标记`)
  } catch (error) { notify(errorMessage(error)) }
}
async function toggleR18Mode() {
  const next = !r18Mode.value
  try {
    const result = await api.setR18Mode(next)
    if (currentUser.value) currentUser.value.r18Mode = result.r18Mode
    selectedTag.value = '全部标签'
    await Promise.all([refreshImages(), refreshTags(), refreshDashboard()])
    notify(result.r18Mode ? '已开启 R18 模式，R18 内容与链接标签将显示' : '已关闭 R18 模式，R18 内容与链接标签已隐藏')
  } catch (error) { notify(errorMessage(error)) }
}
async function removeTag(tag: TagItem) { try { await api.deleteTag(tag.id); await refreshTags(); await refreshImages(); notify(`已删除标签「${tag.name}」`) } catch (error) { notify(errorMessage(error)) } }
async function uploadFiles(event: Event) { const input = event.target as HTMLInputElement; if (!input.files?.length) return; try { await api.upload(input.files); showUpload.value = false; await refreshImages(); await refreshDashboard(); notify(`已上传 ${input.files.length} 张图片`) } catch (error) { notify(errorMessage(error)) } finally { input.value = '' } }
async function saveSettings(payload?: Record<string, any>) { try { const next = { ...(payload || settings.value), theme: payload?.theme || selectedTheme.value, darkMode: payload?.darkMode ?? isDark.value, webglEnabled: payload?.webglEnabled ?? webglEnabled.value, animationEnabled: payload?.animationEnabled ?? animationEnabled.value, fluidColors: payload?.fluidColors || fluidColors.value, fluidSpeed: 3 }; settings.value = await api.saveSettings(next); selectedTheme.value = String(settings.value.theme || selectedTheme.value); isDark.value = settings.value.darkMode !== false; webglEnabled.value = settings.value.webglEnabled !== false; animationEnabled.value = settings.value.animationEnabled !== false; if (Array.isArray(settings.value.fluidColors)) fluidColors.value = settings.value.fluidColors.slice(0, 3).map(String); fluidSpeed.value = 3; applyPreferences(); if (currentUser.value?.role === 'ADMIN') await refreshSettings(); notify('设置已保存') } catch (error) { notify(errorMessage(error)) } }
async function changePassword() { const currentPassword = window.prompt('请输入当前密码'); const newPassword = window.prompt('请输入新密码（至少 8 位）'); if (!currentPassword || !newPassword) return; try { await api.changePassword(currentPassword, newPassword); notify('密码已修改') } catch (error) { notify(errorMessage(error)) } }
function closeContext() { contextImage.value = null; contextTag.value = null }
/* ---- 访问日志：按操作类别过滤 + 每类操作一个图标 ---- */
const logGroups = ['全部', '查看', '上传', '下载', '提取', '改名', '标签', '删除', '其他']
function logGroupOf(action: string) {
  if (action.includes('查看')) return '查看'
  if (action.includes('上传')) return '上传'
  if (action.includes('下载')) return '下载'
  if (action.includes('提取')) return '提取'
  if (action.includes('名称') || action.includes('改名')) return '改名'
  if (action.includes('人物')) return '标签'
  if (action.includes('标签')) return '标签'
  if (action.includes('删除')) return '删除'
  return '其他'
}
const filteredLogs = computed(() => (logFilter.value === '全部' ? logs.value : logs.value.filter((log) => logGroupOf(log.action) === logFilter.value)))
/** 文件扩展名：按 mime 推断（下载用） */
function downloadExt(mime: string | undefined) {
  const m = String(mime || '').toLowerCase()
  if (m.includes('png')) return 'png'
  if (m.includes('webp')) return 'webp'
  if (m.includes('gif')) return 'gif'
  if (m.includes('webm')) return 'webm'
  if (m.includes('quicktime') || m.includes('mov')) return 'mov'
  if (m.includes('video')) return 'mp4'
  if (m.includes('jpeg') || m.includes('jpg')) return 'jpg'
  return 'bin'
}
/** 下载图片 / 视频：左下角任务坞显示真实进度，完成后保存到本机下载目录。
    走专用下载端点（附件响应），服务端据此记录「下载图片 / 下载视频」访问日志 */
function downloadImage(image: ImageItem | null | undefined) {
  if (!image) return
  const base = image.name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/^[.\s]+|[.\s]+$/g, '') || '媒体文件'
  startDownload({ url: `/api/images/${image.id}/download`, filename: `${base}.${downloadExt(image.mimeType)}` })
}
/** 视频提取面板内跳转（如保存成功后去图片库） */
function onPanelGoto(view: 'library' | 'tags') { void go(view) }
function toggleTheme() { isDark.value = !isDark.value; applyPreferences() }
/** 顶栏/命令面板快速换主题：先给 420ms 的颜色过渡，再落属性 */
function withThemeTransition(mutate: () => void) {
  const root = document.documentElement
  root.classList.add('theme-anim')
  mutate()
  window.setTimeout(() => root.classList.remove('theme-anim'), 460)
}
function pickTheme(name: string) {
  if (selectedTheme.value === name) return
  /* 换主题 = 换整套情绪：强调色、底色染色、流体背景一起走 */
  withThemeTransition(() => {
    selectedTheme.value = name
    fluidColors.value = themeFluidPalette(name)
    applyPreferences()
  })
}
function toggleThemeMode() {
  withThemeTransition(() => { isDark.value = !isDark.value; applyPreferences() })
}

/** Esc：先收相似图面板，面板没开时才关大图。避免一次 Esc 把整个预览关掉。 */
function onEscapeKey(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (similarOpen.value) { closeSimilar(); return }
  if (selectedImage.value) closePreview()
}

watch([isDark, animationEnabled, selectedTheme, () => settings.value.siteName], applyPreferences)
let stopMotion: (() => void) | undefined
onMounted(() => { applyPreferences(); stopMotion = installMotion(); greetingTimer = window.setInterval(() => { currentHour.value = new Date().getHours() }, 60_000); checkSession()
  window.addEventListener('keydown', onEscapeKey)
  /* —— 设备动作与方向：进入 APP 即请求权限（iOS 需显式授权，Android 兼容兜底）；监听横竖屏变化 —— */
  const syncOrientation = () => {
    isLandscape.value = landscapeQuery.matches
    if (selectedImage.value && isVideoItem(selectedImage.value)) videoFullscreen.value = landscapeQuery.matches
  }
  const onWindowOrientation = () => syncOrientation()
  const onMediaQuery = () => syncOrientation()
  if (typeof landscapeQuery.addEventListener === 'function') landscapeQuery.addEventListener('change', onMediaQuery)
  window.addEventListener('orientationchange', onWindowOrientation)
  const screenOrientation = (screen as unknown as { orientation?: { addEventListener?: (type: string, listener: () => void) => void; removeEventListener?: (type: string, listener: () => void) => void } }).orientation
  screenOrientation?.addEventListener?.('change', onMediaQuery)
  syncOrientation()
  const sensorCtors = [window.DeviceOrientationEvent, window.DeviceMotionEvent] as unknown as { requestPermission?: () => Promise<string> }[]
  sensorCtors.forEach((ctor) => { try { ctor.requestPermission?.().catch(() => undefined) } catch { /* 忽略拒绝 */ } })
  landscapeCleanup = () => { if (typeof landscapeQuery.removeEventListener === 'function') landscapeQuery.removeEventListener('change', onMediaQuery); window.removeEventListener('orientationchange', onWindowOrientation); screenOrientation?.removeEventListener?.('change', onMediaQuery) } })
onBeforeUnmount(() => { stopMotion?.(); if (greetingTimer) window.clearInterval(greetingTimer); cancelImageLongPress(); window.removeEventListener('keydown', onEscapeKey); stopEmbedPoll(); landscapeCleanup?.() })
</script>

<template>
  <LoginView v-if="!booting && !authenticated" :loading="authLoading" :error="authError" @login="handleLogin" @register="handleRegister" />

  <div v-else-if="authenticated" class="app-shell" @click="closeContext">
    <FluidCanvas v-if="webglEnabled" class="app-fluid-background" :palette="fluidColors" :speed="3" :paused="!animationEnabled" :edge-color="fluidColors[0]" :edge-highlight="false" />
    <AppSidebar :active-view="activeView" :image-count="stats.imageCount" :trash-count="trashCount" :stats="stats" :user="currentUser" @go="go" @logout="logout" />

    <main class="main-area">
      <AppTopbar
        :page-title="pageTitle"
        :query="query"
        :user="currentUser"
        :selected-theme="selectedTheme"
        :is-dark="isDark"
        @update:query="query = $event"
        @search-input="handleSearchInput"
        @submit="submitGlobalSearch"
        @clear="clearGlobalSearch"
        @logout="logout"
        @open-more="moreOpen = true"
        @open-palette="paletteOpen = true"
        @pick-theme="pickTheme"
        @toggle-theme-mode="toggleThemeMode"
      />

      <section class="page-content">
        <OverviewView
          v-if="activeView === 'overview'"
          :user="currentUser"
          :greeting="timeGreeting"
          :stats="stats"
          :top-images="topImages"
          :logs="logs"
          :live-images="liveImages"
          @upload="showUpload = true"
          @go="go"
          @open="openFromCard"
          @context="openContext"
          @press-start="onImageTouchStart"
          @press-cancel="cancelImageLongPress"
          @press-end="onImageTouchEnd"
          @download="downloadImage"
          @media-progress="handleImageProgress"
        />

        <LibraryView
          v-else-if="activeView === 'library' || activeView === 'trash'"
          :mode="activeView === 'trash' ? 'trash' : 'library'"
          :images="filteredImages"
          :tags="tags"
          :tag-pills="tagPills"
          :selected-tag="selectedTag"
          :sort-by="sortBy"
          :r18-mode="r18Mode"
          :loading="libraryLoading"
          :progress="libraryProgress"
          :loaded="libraryLoaded"
          :total="libraryTotal"
          :density="gridDensity"
          :select-mode="selectMode"
          :selected-ids="selectedIds"
          @update:selected-tag="selectedTag = $event"
          @sort="setSort"
          @toggle-r18="toggleR18Mode"
          @upload="showUpload = true"
          @open="openFromCard"
          @context="openContext"
          @press-start="onImageTouchStart"
          @press-cancel="cancelImageLongPress"
          @press-end="onImageTouchEnd"
          @download="downloadImage"
          @media-progress="handleImageProgress"
          @toggle-select-mode="toggleSelectMode"
          @toggle-selected="toggleImageSelected"
          @select-all="selectAllVisible"
          @star="toggleStar"
          @update:density="setGridDensity"
        />

        <SimilarView
          v-else-if="activeView === 'similar'"
          :mode="graphMode"
          :view="graphView"
          :payload="graphPayload"
          :loading="graphLoading"
          :groups="graphGroups"
          @set-mode="setGraphMode"
          @set-view="setGraphView"
          @refresh="refreshGraph"
          @open-graph="openFromGraph"
          @go-settings="go('settings')"
          @go-library="go('library')"
        />

        <ParsePanel v-else-if="activeView === 'parse'" @goto="onPanelGoto" />

        <TagsView
          v-else-if="activeView === 'tags'"
          :tags="tags"
          :person-tags="personTags"
          :normal-tags="normalTags"
          :image-count="stats.imageCount"
          :columns="tagColumns"
          :person-group-open="personGroupOpen"
          :reference-counts="referenceCounts"
          :is-admin="currentUser?.role === 'ADMIN'"
          @new-tag="openTagDialog"
          @toggle-person-group="personGroupOpen = !personGroupOpen"
          @open-row="openTagRow"
          @context="openTagContext"
          @press-start="onTagTouchStart"
          @press-cancel="cancelTagLongPress"
          @press-end="onTagTouchEnd"
          @open-references="openReferences"
          @batch-tag="openBatchTag"
          @toggle-r18="toggleTagR18"
          @remove-tag="removeTag"
          @update:columns="setTagColumns"
        />

        <LogsView
          v-else-if="activeView === 'logs'"
          :logs="filteredLogs"
          :filter="logFilter"
          :groups="logGroups"
          :user-count="stats.userCount"
          :total="logs.length"
          @refresh="refreshLogs"
          @update:filter="logFilter = $event"
        />

        <SettingsView
          v-else-if="activeView === 'settings'"
          :settings="settings"
          :fluid-colors="fluidColors"
          :fluid-speed="fluidSpeed"
          :selected-theme="selectedTheme"
          :is-dark="isDark"
          :webgl-enabled="webglEnabled"
          :animation-enabled="animationEnabled"
          :embed-status="embedStatus"
          :embed-calibration="embedCalibration"
          :embed-threshold-draft="embedThresholdDraft"
          :embed-backfilling="embedBackfilling"
          :embed-saving-threshold="embedSavingThreshold"
          :user="currentUser"
          @save="saveSettings"
          @change-password="changePassword"
          @refresh="refreshSettings"
          @backfill="startBackfill"
          @threshold-input="embedThresholdDraft = $event"
          @save-threshold="saveThreshold"
          @back="go('overview')"
        />
      </section>
    </main>

    <MobileNav v-model:more-open="moreOpen" :active-view="activeView" :user="currentUser" :stats="stats" :trash-count="trashCount" @go="go" @logout="logout" />

    <ContextMenu
      v-if="contextImage"
      :image="contextImage"
      :x="contextPosition.x"
      :y="contextPosition.y"
      :trash="activeView === 'trash'"
      :video="isVideoItem(contextImage)"
      @close="contextImage = null"
      @open="openImage"
      @download="downloadImage"
      @rename="beginRename"
      @add-tag="openAddTag"
      @remove-tag="openRemoveTag"
      @star="toggleStar"
      @remove="activeView === 'trash' ? permanentDelete($event) : softDelete($event)"
      @restore="restore"
    />

    <TagContextMenu
      v-if="contextTag"
      :tag="contextTag"
      :x="tagContextPosition.x"
      :y="tagContextPosition.y"
      @close="contextTag = null"
      @toggle-person="toggleTagPerson($event, !$event.person)"
      @view-person="openPerson"
      @toggle-r18="toggleTagR18"
      @batch-tag="openBatchTag"
    />

    <PersonDetailModal
      v-if="showPersonDetail"
      :detail="personDetail"
      :loading="personDetailLoading"
      :columns="tagPickerColumns"
      :reference-counts="referenceCounts"
      @close="closePersonDetail"
      @view-tag="viewTagFromPerson"
      @open-references="openReferences($event); closePersonDetail()"
      @remove="removePersonFromDetail"
    />

    <ReferencesModal
      v-if="showReferences && referencesTag"
      :tag="referencesTag"
      :items="referenceItems"
      :loading="referencesLoading"
      :removing="referenceRemoving"
      @close="closeReferences"
      @open="openReferenceImage"
      @drop="dropReference"
    />

    <BatchTagModal
      v-if="showBatchTag"
      :tag="batchTag"
      :candidates="batchCandidates"
      :query="batchImageQuery"
      :selected-ids="selectedBatchImageIds"
      @update:query="batchImageQuery = $event"
      @update:selected-ids="selectedBatchImageIds = $event"
      @submit="submitBatchTag"
      @close="showBatchTag = false"
    />

    <RenameModal
      v-if="showRename"
      :name="renameName"
      :video="isVideoItem(renameImage)"
      @update:name="renameName = $event"
      @close="showRename = false"
      @submit="submitRename"
    />

    <NewTagModal v-if="showTagDialog" :person="newTagPerson" :r18-mode="r18Mode" @create="createTag" @close="showTagDialog = false" />

    <AddTagModal
      v-if="showAddTag"
      :image="addTagImage"
      :tags="addableTags"
      :columns="tagPickerColumns"
      @create="submitAddTags"
      @update:columns="setTagPickerColumns"
      @close="closeAddTag"
    />

    <RemoveTagModal v-if="showRemoveTag" :image="removeTagImage" :tags="removeTagOptions" @remove="removeImageTag" @close="showRemoveTag = false" />

    <UploadReviewModal :show="showUpload" :available-tags="tags" :r18-mode="r18Mode" @close="showUpload = false" @completed="handleUploadCompleted" @toggle-r18="toggleR18Mode" />

    <ViewerOverlay
      v-if="selectedImage"
      ref="viewerRef"
      :image="selectedImage"
      :loading="previewLoading"
      :scale="previewScale"
      :offset="previewOffset"
      :video-fullscreen="videoFullscreen"
      :can-nav="canPreviewNav"
      :box-mode="boxMode"
      :box-pixels="boxPixels"
      :boxes-loading="boxesLoading"
      :boxes-dirty="boxesDirty"
      :boxes-saving="boxesSaving"
      :selected-box-index="selectedBoxIndex"
      :dragging="!!boxDrag"
      :person-tags="personTagsOfImage"
      :ref-picker-open="refPickerOpen"
      :ref-saving="refSaving"
      :reference-counts="referenceCounts"
      :similar-open="similarOpen"
      :similar-loading="similarLoading"
      :similar-analyzing="similarAnalyzing"
      :similar-items="similarItems"
      :similar-threshold="similarThreshold"
      :flip-from="flipFrom"
      @close="closePreview"
      @step="stepPreview"
      @toggle-fullscreen="videoFullscreen = !videoFullscreen"
      @wheel="handlePreviewWheel"
      @touch-start="onPreviewTouchStart"
      @touch-move="onPreviewTouchMove"
      @touch-end="onPreviewTouchEnd"
      @box-move="(index, event) => boxPointerDown('move', index, event)"
      @box-resize="(index, event) => boxPointerDown('resize', index, event)"
      @box-remove="removeBox"
      @detect="runDetect"
      @add-box="addBox"
      @save-boxes="saveBoxes"
      @toggle-box-mode="toggleBoxMode"
      @download="downloadImage"
      @toggle-ref-picker="refPickerOpen = !refPickerOpen"
      @confirm-reference="refTagId = $event; confirmReference()"
      @open-similar="openSimilar($event)"
      @close-similar="closeSimilar"
      @open-similar-image="openSimilarImage"
      @media-loaded="previewLoading = false"
    />

    <CommandPalette
      :open="paletteOpen"
      :user="currentUser"
      :views="navItems.map((item) => ({ view: item.id, label: item.label, icon: item.icon }))"
      :images="liveImages"
      :tags="tags"
      :selected-theme="selectedTheme"
      :is-dark="isDark"
      @close="paletteOpen = false"
      @go="go"
      @open-image="(image) => { paletteOpen = false; openFromCard(image) }"
      @pick-tag="(name) => { paletteOpen = false; void viewTag(name) }"
      @toggle-theme-mode="toggleThemeMode"
      @pick-theme="pickTheme"
      @upload="paletteOpen = false; showUpload = true"
      @toggle-select-mode="() => { paletteOpen = false; if (activeView !== 'library') void go('library'); toggleSelectMode() }"
    />

    <div v-if="selectMode && selectedIds.length" class="selectbar" role="toolbar" aria-label="批量操作">
      <span class="count-badge">{{ selectedIds.length }}</span>
      <span class="sep"></span>
      <button @click="selectAllVisible">全选</button>
      <button @click="batchStarSelected(true)">收藏</button>
      <button @click="batchStarSelected(false)">取消收藏</button>
      <button class="danger" @click="batchDeleteSelected">移入回收站</button>
      <span class="sep"></span>
      <button @click="clearSelection">退出</button>
    </div>

    <AppToast :message="toast" />
    <TaskDock :hidden="videoFullscreen" @goto-library="onPanelGoto('library')" />
  </div>
</template>

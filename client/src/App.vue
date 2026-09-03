<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { gsap } from 'gsap'
import { Check, ChevronDown, CircleHelp, Clock3, Eye, Filter, FolderOpen, Images, LayoutDashboard, Link2, Menu, MoreHorizontal, Play, Plus, Search, Settings2, ShieldCheck, SlidersHorizontal, Sparkles, Tags, Trash2, Upload, UserRound, X, Zap } from 'lucide-vue-next'
import LoginView from './components/LoginView.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import FluidCanvas from './components/FluidCanvas.vue'
import UploadReviewModal from './components/UploadReviewModal.vue'
import ParsePanel from './components/ParsePanel.vue'
import { api, type CurrentUser } from './api'
import type { AuditLog, ImageItem, TagItem, View } from './types'

const authenticated = ref(false)
const booting = ref(true)
const authLoading = ref(false)
const authError = ref('')
const currentUser = ref<CurrentUser | null>(null)
const activeView = ref<View>('overview')
const sidebarOpen = ref(false)
const query = ref('')
const sortBy = ref('views')
const selectedTag = ref('全部标签')
const selectedImage = ref<ImageItem | null>(null)
const previewImageElement = ref<HTMLElement | null>(null)
const previewScale = ref(1)
const previewOffset = ref({ x: 0, y: 0 })
const previewLoading = ref(false)
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
const toast = ref('')
const images = ref<ImageItem[]>([])
const tags = ref<TagItem[]>([])
const logs = ref<AuditLog[]>([])
const newTag = ref('')
const newTagR18 = ref(false)
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
let searchTimer: number | undefined
const currentHour = ref(new Date().getHours())
let greetingTimer: number | undefined

const navItems: { id: View; label: string; icon: typeof LayoutDashboard; badge?: string }[] = [
  { id: 'overview', label: '总览', icon: LayoutDashboard },
  { id: 'library', label: '图片库', icon: Images },
  { id: 'parse', label: '视频提取', icon: Link2 },
  { id: 'tags', label: '智能标签', icon: Tags },
  { id: 'logs', label: '访问日志', icon: ShieldCheck },
  { id: 'trash', label: '回收站', icon: Trash2 },
]
/** 手机底部导航：5 个入口均分全宽（回收站在移动端不进底栏，由图片库内操作进入） */
const mobileNavItems = computed<{ id: View; label: string; icon: typeof LayoutDashboard }[]>(() => [...navItems.filter((item) => item.id !== 'trash'), { id: 'settings', label: '设置', icon: Settings2 }])
const pageTitle = computed(() => ({ overview: '总览', library: '图片库', parse: '视频提取', tags: '智能标签', logs: '访问日志', trash: '回收站', settings: '系统设置' }[activeView.value]))
const r18Mode = computed<boolean>(() => currentUser.value?.r18Mode ?? false)
const timeGreeting = computed(() => {
  if (currentHour.value < 5) return '夜深了'
  if (currentHour.value < 12) return '早上好'
  if (currentHour.value < 14) return '中午好'
  if (currentHour.value < 19) return '下午好'
  return '晚上好'
})
const liveImages = computed(() => images.value.filter((image) => !image.deletedAt))
const filteredImages = computed(() => {
  let result = activeView.value === 'trash' ? images.value.filter((image) => image.deletedAt) : liveImages.value
  const search = query.value.trim().toLowerCase()
  if (search) result = result.filter((image) => image.name.toLowerCase().includes(search) || image.tags.some((tag) => tag.toLowerCase().includes(search)))
  if (selectedTag.value !== '全部标签') result = result.filter((image) => image.tags.includes(selectedTag.value))
  if (sortBy.value === 'views') result = [...result].sort((a, b) => b.views - a.views)
  if (sortBy.value === 'newest') result = [...result].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt))
  if (sortBy.value === 'name') result = [...result].sort((a, b) => a.name.localeCompare(b.name))
  return result
})
const topImages = computed(() => [...liveImages.value].sort((a, b) => b.views - a.views).slice(0, 4))
async function handleUploadCompleted(count: number) { showUpload.value = false; await Promise.all([refreshImages(), refreshTags(), refreshDashboard()]); notify(`已完成上传 ${count} 个文件`) }
const batchCandidates = computed(() => {
  const queryText = batchImageQuery.value.trim().toLowerCase()
  return images.value.filter((image) => !image.deletedAt && !image.tagIds.includes(batchTag.value?.id || '') && (!queryText || image.name.toLowerCase().includes(queryText) || image.tags.some((tag) => tag.toLowerCase().includes(queryText))))
})
function tagRatio(count: number) { return stats.value.imageCount ? `${Math.min(100, (count / stats.value.imageCount) * 100).toFixed(1)}%` : '0%' }

function formatTime(value: string) { const date = new Date(value); if (Number.isNaN(date.valueOf())) return value; const diff = Math.max(0, Date.now() - date.valueOf()); const minutes = Math.floor(diff / 60000); if (minutes < 1) return '刚刚'; if (minutes < 60) return `${minutes} 分钟前`; const hours = Math.floor(minutes / 60); if (hours < 24) return `${hours} 小时前`; return date.toLocaleDateString('zh-CN') }
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
function videoCardReady(image: ImageItem, event: Event) { sizeImageCard(event); handleImageProgress(image) }
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
async function refreshSettings() { if (currentUser.value?.role !== 'ADMIN') return; const data = await api.settings(); settings.value = { ...settings.value, ...data }; selectedTheme.value = String(data.theme || 'Aurora'); isDark.value = true; webglEnabled.value = data.webglEnabled !== false; animationEnabled.value = data.animationEnabled !== false; fluidSpeed.value = 3; if (Array.isArray(data.fluidColors) && data.fluidColors.length >= 3) fluidColors.value = data.fluidColors.slice(0, 3).map(String); applyPreferences() }
async function loadData() { await Promise.all([refreshTags(), refreshDashboard(), refreshImages(), refreshLogs(), refreshSettings()]) }
async function checkSession() { try { currentUser.value = await api.me(); authenticated.value = true; await loadData() } catch { authenticated.value = false } finally { booting.value = false } }
async function handleLogin(username: string, password: string) { authLoading.value = true; authError.value = ''; try { const response = await api.login(username, password); currentUser.value = response.user; authenticated.value = true; await loadData() } catch (error) { authError.value = errorMessage(error) } finally { authLoading.value = false } }
async function handleRegister(username: string, password: string) { authLoading.value = true; authError.value = ''; try { await api.register(username, password); const response = await api.login(username, password); currentUser.value = response.user; authenticated.value = true; await loadData() } catch (error) { authError.value = errorMessage(error) } finally { authLoading.value = false } }
async function logout() { await api.logout().catch(() => undefined); authenticated.value = false; currentUser.value = null; images.value = []; tags.value = []; logs.value = [] }
async function go(view: View) { closePreview(); activeView.value = view; sidebarOpen.value = false; selectedTag.value = '全部标签'; if (view === 'library' || view === 'trash' || view === 'tags') await refreshImages(); if (view === 'logs') await refreshLogs(); if (view === 'settings') await refreshSettings(); nextTick(() => gsap.fromTo('.page-content', { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: .4, ease: 'power2.out' })) }
function handleSearchInput() { if (searchTimer) window.clearTimeout(searchTimer); if (activeView.value !== 'library' && activeView.value !== 'trash') return; searchTimer = window.setTimeout(() => { void refreshImages() }, 280) }
async function submitGlobalSearch() { if (searchTimer) window.clearTimeout(searchTimer); activeView.value = 'library'; selectedTag.value = '全部标签'; sidebarOpen.value = false; await refreshImages(); nextTick(() => gsap.fromTo('.page-content', { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: .4, ease: 'power2.out' })) }
function clearGlobalSearch() { query.value = ''; if (activeView.value === 'library' || activeView.value === 'trash') void refreshImages() }
async function viewTag(tagName: string) { activeView.value = 'library'; selectedTag.value = tagName; sidebarOpen.value = false; await refreshImages(); nextTick(() => gsap.fromTo('.page-content', { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: .4, ease: 'power2.out' })) }
function openContext(image: ImageItem, event: MouseEvent) { contextImage.value = image; contextPosition.value = { x: Math.min(event.clientX, window.innerWidth - 220), y: Math.min(event.clientY, window.innerHeight - 245) } }
function resetPreview() { previewScale.value = 1; previewOffset.value = { x: 0, y: 0 } }
function closePreview() { selectedImage.value = null; videoFullscreen.value = false; resetPreview() }
function handlePreviewWheel(event: WheelEvent) {
  const element = previewImageElement.value
  if (!element || isVideoItem(selectedImage.value)) return
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
async function openImage(image: ImageItem) { try { resetPreview(); previewLoading.value = true; videoFullscreen.value = isVideoItem(image) && isLandscape.value; selectedImage.value = image; const result = await api.viewImage(image.id); image.views = result.views; if (selectedImage.value) selectedImage.value.views = result.views; stats.value.totalViews += 1; if (currentUser.value?.role === 'ADMIN') await refreshLogs() } catch (error) { notify(errorMessage(error)) } }
async function softDelete(image: ImageItem) { try { await api.deleteImage(image.id); contextImage.value = null; selectedImage.value = null; await refreshImages(); await refreshDashboard(); notify('图片已移入回收站') } catch (error) { notify(errorMessage(error)) } }
async function restore(image: ImageItem) { try { await api.restoreImage(image.id); contextImage.value = null; await refreshImages(); notify('图片已恢复') } catch (error) { notify(errorMessage(error)) } }
async function permanentDelete(image: ImageItem) { try { await api.permanentDelete(image.id); contextImage.value = null; await refreshImages(); notify('图片已永久删除') } catch (error) { notify(errorMessage(error)) } }
async function addTag(tagName: string, image = contextImage.value) { if (!image || image.tags.includes(tagName)) return; try { await api.addTag(image.id, tagName); showAddTag.value = false; contextImage.value = null; await refreshTags(); await refreshImages(); notify('标签已添加') } catch (error) { notify(errorMessage(error)) } }
async function removeImageTag(tagId: string, tagName: string, image = contextImage.value) { if (!image || !tagId) return; try { await api.removeTag(image.id, tagId); showRemoveTag.value = false; contextImage.value = null; await refreshTags(); await refreshImages(); notify(`已从「${image.name}」移除标签「${tagName}」`) } catch (error) { notify(errorMessage(error)) } }
function openTagContext(tag: TagItem, event: MouseEvent) { contextTag.value = tag; tagContextPosition.value = { x: Math.min(event.clientX, window.innerWidth - 250), y: Math.min(event.clientY, window.innerHeight - 90) }; contextImage.value = null }
async function openBatchTag(tag: TagItem) { contextTag.value = null; batchTag.value = tag; batchImageQuery.value = ''; selectedBatchImageIds.value = []; showBatchTag.value = true; if (activeView.value !== 'tags') await refreshImages() }
function selectAllBatchImages() { selectedBatchImageIds.value = batchCandidates.value.map((image) => image.id) }
function sizeBatchCard(event: Event) { const image = event.currentTarget as HTMLImageElement; const card = image.closest('.batch-image-option') as HTMLElement | null; const list = card?.parentElement; if (!card || !list) return; card.style.gridRowEnd = 'auto'; requestAnimationFrame(() => { const rowHeight = 8; const rowGap = Number.parseFloat(getComputedStyle(list).rowGap) || 8; card.style.gridRowEnd = `span ${Math.max(1, Math.ceil((card.scrollHeight + rowGap) / (rowHeight + rowGap)))}` }) }
function sizeImageCard(event: Event) { const media = event.target as HTMLElement; if (!media || (media.tagName !== 'IMG' && media.tagName !== 'VIDEO')) return; const card = media.closest('.image-card') as HTMLElement | null; const grid = card?.parentElement; if (!card || !grid) return; card.style.gridRowEnd = 'auto'; requestAnimationFrame(() => { const rowHeight = 8; const rowGap = Number.parseFloat(getComputedStyle(grid).rowGap) || 13; card.style.gridRowEnd = `span ${Math.max(1, Math.ceil((card.scrollHeight + rowGap) / (rowHeight + rowGap)))}` }) }
async function submitBatchTag() { if (!batchTag.value || !selectedBatchImageIds.value.length) return; try { const result = await api.addImagesToTag(batchTag.value.id, selectedBatchImageIds.value); showBatchTag.value = false; await refreshTags(); await refreshImages(); notify(`已将 ${result.added} 张图片添加到「${batchTag.value.name}」`) } catch (error) { notify(errorMessage(error)) } }
async function createTag() { const name = newTag.value.trim(); if (!name) return; const r18 = newTagR18.value; try { await api.createTag(name, '#a78bfa', r18); newTag.value = ''; newTagR18.value = false; showTagDialog.value = false; await refreshTags(); await refreshImages(); notify(r18 ? 'R18 链接标签已创建' : '新标签已创建') } catch (error) { notify(errorMessage(error)) } }
async function toggleTagR18(tag: TagItem) {
  const next = !tag.r18
  try {
    const updated = await api.updateTag(tag.id, { r18: next })
    await refreshTags()
    notify(updated.r18 ? `「${updated.name}」已标记为 R18 链接标签` : `「${updated.name}」已取消 R18 标记`)
  } catch (error) { notify(errorMessage(error)) }
}
async function toggleTagR18FromMenu() { const tag = contextTag.value; if (!tag) return; contextTag.value = null; await toggleTagR18(tag) }
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
async function saveSettings(payload?: Record<string, any>) { try { const next = { ...(payload || settings.value), theme: payload?.theme || selectedTheme.value, darkMode: payload?.darkMode ?? isDark.value, webglEnabled: payload?.webglEnabled ?? webglEnabled.value, animationEnabled: payload?.animationEnabled ?? animationEnabled.value, fluidColors: payload?.fluidColors || fluidColors.value, fluidSpeed: 3 }; settings.value = await api.saveSettings(next); selectedTheme.value = String(settings.value.theme || selectedTheme.value); isDark.value = settings.value.darkMode !== false; webglEnabled.value = settings.value.webglEnabled !== false; animationEnabled.value = settings.value.animationEnabled !== false; if (Array.isArray(settings.value.fluidColors)) fluidColors.value = settings.value.fluidColors.slice(0, 3).map(String); fluidSpeed.value = 3; applyPreferences(); notify('设置已保存') } catch (error) { notify(errorMessage(error)) } }
async function changePassword() { const currentPassword = window.prompt('请输入当前密码'); const newPassword = window.prompt('请输入新密码（至少 8 位）'); if (!currentPassword || !newPassword) return; try { await api.changePassword(currentPassword, newPassword); notify('密码已修改') } catch (error) { notify(errorMessage(error)) } }
function closeContext() { contextImage.value = null; contextTag.value = null }
/** 视频提取面板内跳转（如保存成功后去图片库） */
function onPanelGoto(view: 'library' | 'tags') { void go(view) }
function toggleTheme() { isDark.value = !isDark.value; applyPreferences() }

watch([isDark, animationEnabled, selectedTheme, () => settings.value.siteName], applyPreferences)
onMounted(() => { applyPreferences(); greetingTimer = window.setInterval(() => { currentHour.value = new Date().getHours() }, 60_000); checkSession()
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
onBeforeUnmount(() => { if (greetingTimer) window.clearInterval(greetingTimer); landscapeCleanup?.() })
</script>

<template>
  <SettingsPanel v-if="authenticated && activeView === 'settings'" :settings="settings" :fluid-colors="fluidColors" :fluid-speed="fluidSpeed" :selected-theme="selectedTheme" :is-dark="isDark" :webgl-enabled="webglEnabled" :animation-enabled="animationEnabled" @close="go('overview')" @save="saveSettings" @change-password="changePassword" />
  <LoginView v-if="!booting && !authenticated" :loading="authLoading" :error="authError" @login="handleLogin" @register="handleRegister" />
  <div v-else-if="authenticated" class="app-shell" @click="closeContext">
    <FluidCanvas v-if="webglEnabled" class="app-fluid-background" :palette="fluidColors" :speed="3" :paused="!animationEnabled" :edge-color="fluidColors[0]" :edge-highlight="true" />
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }" @click.stop>
      <div class="brand"><div class="brand-mark"><span></span><span></span><span></span></div><div><strong>fluxframe</strong><small>INTRANET LIBRARY</small></div></div>
      <div class="workspace-switch"><div class="avatar avatar-purple">{{ currentUser?.username?.[0]?.toUpperCase() }}</div><div><strong>创作空间</strong><small>个人媒体库</small></div><ChevronDown :size="15" /></div>
      <nav class="main-nav"><p class="nav-label">工作区</p><button v-for="item in navItems" :key="item.id" :class="['nav-item', { active: activeView === item.id }]" @click="go(item.id)"><component :is="item.icon" :size="18" /><span>{{ item.label }}</span><em v-if="item.id === 'library'">{{ stats.imageCount }}</em><em v-if="item.id === 'trash'">{{ images.filter((image) => image.deletedAt).length }}</em></button><p class="nav-label nav-label-spaced">管理</p><button :class="['nav-item', { active: activeView === 'settings' }]" @click="go('settings')"><Settings2 :size="18" /><span>系统设置</span></button></nav>
      <div class="sidebar-bottom"><div class="storage-card"><div class="storage-head"><span>存储空间</span><strong>{{ stats.storage }}</strong></div><div class="progress"><i :style="{ width: `${Number(stats.storagePercent || 0)}%` }"></i></div><small>磁盘容量 {{ stats.storageCapacity || '未知' }} · 已占 {{ Number(stats.storagePercent || 0).toFixed(2) }}%</small></div><div class="profile"><div class="avatar avatar-blue">{{ currentUser?.username?.[0]?.toUpperCase() }}</div><div><strong>{{ currentUser?.username }}</strong><small>{{ currentUser?.role === 'ADMIN' ? '超级管理员' : '普通用户' }}</small></div><button class="icon-button" @click="logout"><MoreHorizontal :size="18" /></button></div></div>
    </aside>
    <main class="main-area">
      <header class="topbar"><button class="mobile-menu" @click.stop="sidebarOpen = !sidebarOpen"><Menu :size="21" /></button><div class="breadcrumb"><span>创作空间</span><b>/</b><strong>{{ pageTitle }}</strong></div><div class="top-actions"><div class="global-search"><Search :size="17" /><input v-model="query" placeholder="搜索图片、标签..." @input="handleSearchInput" @keydown.enter.prevent="submitGlobalSearch" @keydown.esc="clearGlobalSearch" /><button v-if="query" class="global-search-clear" aria-label="清空搜索" @click="clearGlobalSearch"><X :size="13" /></button><kbd>⌘ K</kbd></div><button class="icon-button"><CircleHelp :size="18" /></button><button class="icon-button"><Clock3 :size="18" /></button><button class="user-chip" @click="logout"><span class="avatar avatar-blue">{{ currentUser?.username?.[0]?.toUpperCase() }}</span><span>{{ currentUser?.username }}</span><ChevronDown :size="14" /></button></div></header>
      <section class="page-content">
        <template v-if="activeView === 'overview'"><div class="page-heading"><div><p class="eyebrow"><span class="status-dot"></span> 系统运行正常 · 数据来自 PostgreSQL</p><h1>{{ timeGreeting }}，{{ currentUser?.username }} <span>✦</span></h1><p class="subheading">这是你的媒体库今天的概览。</p></div><button class="primary-button" @click.stop="showUpload = true"><Upload :size="17" />上传媒体</button></div><div class="stats-grid"><div class="stat-card"><div class="stat-top"><span class="stat-icon purple"><Images :size="18" /></span><span class="trend positive">真实数据</span></div><strong>{{ stats.imageCount.toLocaleString() }}</strong><span>图片总数</span></div><div class="stat-card"><div class="stat-top"><span class="stat-icon cyan"><Eye :size="18" /></span><span class="trend positive">实时累计</span></div><strong>{{ stats.totalViews.toLocaleString() }}</strong><span>总观看次数</span></div><div class="stat-card"><div class="stat-top"><span class="stat-icon orange"><Tags :size="18" /></span><span class="trend neutral">可管理</span></div><strong>{{ stats.tagCount }}</strong><span>活跃标签</span></div><div class="stat-card"><div class="stat-top"><span class="stat-icon green"><Zap :size="18" /></span><span class="trend neutral">已连接</span></div><strong>{{ stats.storage }}</strong><span>已使用空间</span></div></div><div class="dashboard-grid"><div class="panel popular-panel"><div class="panel-heading"><div><h2>热门图片</h2><p>按观看次数排序</p></div><button class="text-button" @click="go('library')">查看全部 <span>→</span></button></div><div v-if="topImages.length" class="popular-list"><button v-for="(image, index) in topImages" :key="image.id" class="popular-item" @click="openImage(image)"><span class="rank">0{{ index + 1 }}</span><video v-if="isVideoItem(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline /><img v-else :src="image.thumb" :alt="image.name" decoding="async" /><span class="popular-name"><strong>{{ image.name }}</strong><small>{{ image.tags.join(' · ') || '未分类' }}</small></span><span class="view-count"><Eye :size="15" />{{ image.views.toLocaleString() }}</span></button></div><div v-else class="empty-state">还没有图片，先上传一张吧。</div></div><div class="panel activity-panel"><div class="panel-heading"><div><h2>最近动态</h2><p>系统操作实时记录</p></div></div><div class="activity-list"><div v-for="log in logs.slice(0, 4)" :key="log.id" class="activity-item"><span :class="['activity-dot', log.tone]"></span><div><strong>{{ log.user }} <span>{{ log.action }}</span></strong><small>{{ log.target }}</small></div><time>{{ formatTime(log.time) }}</time></div></div><button class="activity-footer" @click="go('logs')">查看完整日志 <span>→</span></button></div></div><div class="section-heading"><div><h2>最近上传</h2><p>共 {{ liveImages.length }} 张图片</p></div><div class="heading-actions"><button class="filter-button" @click="go('library')"><Filter :size="15" />筛选</button></div></div><div v-if="liveImages.length" class="image-grid compact-grid" @load.capture="sizeImageCard"><button v-for="image in liveImages.slice(0, 4)" :key="image.id" class="image-card" @click="openImage(image)" @contextmenu.prevent.stop="openContext(image, $event)"><div class="image-wrap"><video v-if="isVideoItem(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline @loadeddata="videoCardReady(image, $event)" @error="videoCardReady(image, $event)" /><img v-else :src="image.thumb" :alt="image.name" decoding="async" /><span v-if="isVideoItem(image)" class="video-flag"><Play :size="11" /></span><span class="image-badge"><Eye :size="13" />{{ image.views }}</span><span v-if="image.r18" class="image-r18-badge" title="R18 内容">R18</span><span class="image-more" @click.stop="openContext(image, $event)"><MoreHorizontal :size="17" /></span></div><div class="image-meta"><strong>{{ image.name }}</strong><span>{{ formatTime(image.uploadedAt) }}</span></div></button></div><div v-else class="empty-state"><Images :size="30" /><strong>还没有图片</strong><span>点击右上角上传第一张图片。</span></div></template>
        <template v-else-if="activeView === 'library' || activeView === 'trash'"><div class="page-heading library-heading"><div><p class="eyebrow"><FolderOpen :size="14" /> MEDIA LIBRARY</p><h1>{{ activeView === 'trash' ? '回收站' : '图片库' }}</h1><p class="subheading">{{ activeView === 'trash' ? '已删除的图片会在这里等待处理。' : '浏览、筛选并管理你的全部图片。' }}</p></div><button v-if="activeView === 'library'" class="primary-button" @click.stop="showUpload = true"><Upload :size="17" />上传媒体</button></div><div class="library-toolbar"><div class="filter-pills"><button v-for="tag in ['全部标签', ...tags.map((tag) => tag.name)]" :key="tag" :class="['pill', { active: selectedTag === tag }]" @click="selectedTag = tag">{{ tag }}</button></div><button class="r18-toggle" :class="{ on: r18Mode }" :title="r18Mode ? '关闭 R18 模式' : '开启 R18 模式（显示 R18 内容与链接标签）'" @click="toggleR18Mode"><ShieldCheck :size="14" />R18<i></i></button><div class="sort-select"><SlidersHorizontal :size="15" /><select v-model="sortBy" @change="refreshImages"><option value="views">观看次数</option><option value="newest">最近上传</option><option value="name">名称</option></select><ChevronDown :size="14" /></div></div><transition name="progress-fade"><div v-if="libraryLoading" class="library-progress"><div class="library-progress-meta"><span class="library-progress-label"><Images :size="13" /> 正在加载图片</span><strong>{{ libraryProgress }}%</strong><small>{{ libraryLoaded }} / {{ libraryTotal }} 张</small></div><div class="library-progress-track"><i :style="{ width: `${libraryProgress}%` }"></i></div></div></transition><div v-if="filteredImages.length" class="image-grid" @load.capture="sizeImageCard"><button v-for="image in filteredImages" :key="image.id" class="image-card" :data-image-id="image.id" @click="activeView === 'trash' ? undefined : openImage(image)" @contextmenu.prevent.stop="openContext(image, $event)"><div class="image-wrap"><video v-if="isVideoItem(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline @loadeddata="videoCardReady(image, $event)" @error="videoCardReady(image, $event)" /><img v-else :src="image.thumb" :alt="image.name" decoding="async" @load="handleImageProgress(image)" @error="handleImageProgress(image)" /><span v-if="isVideoItem(image)" class="video-flag"><Play :size="11" /></span><span class="image-badge"><Eye :size="13" />{{ image.views }}</span><span v-if="image.r18" class="image-r18-badge" title="R18 内容">R18</span><span class="image-more" @click.stop="openContext(image, $event)"><MoreHorizontal :size="17" /></span></div><div class="image-meta"><strong>{{ image.name }}</strong><span class="image-time">{{ image.size }} · {{ formatTime(image.uploadedAt) }}</span><div v-if="image.tags.length" class="image-tags"><span v-for="tag in image.tags" :key="tag">#{{ tag }}</span></div><small v-else class="image-tags image-tags-empty">未分类</small></div></button></div><div v-else class="empty-state"><Trash2 :size="30" /><strong>这里还没有图片</strong><span>试试调整标签或搜索条件。</span></div></template>
        <template v-else-if="activeView === 'tags'"><div class="page-heading"><div><p class="eyebrow"><Sparkles :size="14" /> ORGANIZE SMARTER</p><h1>智能标签</h1><p class="subheading">点击标签查看图片，右键标签可批量添加图片。</p></div><button class="primary-button" @click="showTagDialog = true"><Plus :size="17" />新建标签</button></div><div class="tag-summary"><div><span class="stat-icon purple"><Tags :size="18" /></span><strong>{{ tags.length }}</strong><small>自定义标签</small></div><div><span class="stat-icon cyan"><Images :size="18" /></span><strong>{{ tags.reduce((sum, tag) => sum + tag.count, 0) }}</strong><small>标签关联</small></div><div><span class="stat-icon orange"><Zap :size="18" /></span><strong>{{ stats.imageCount ? '已连接' : '等待图片' }}</strong><small>数据状态</small></div></div><div class="tag-list panel"><div class="panel-heading"><div><h2>全部标签</h2><p>左键筛选图片，右键批量关联多张图片。</p></div><div class="tag-head-actions"><button class="filter-button"><Filter :size="15" />排序</button><div class="tag-layout-switch" title="每排显示数量"><button v-for="n in [1, 2, 3]" :key="n" :class="{ active: tagColumns === n }" @click="setTagColumns(n)">{{ n }}</button></div></div></div><div class="tag-rows" :style="{ '--tag-columns': tagColumns }"><div v-for="tag in tags" :key="tag.id" class="tag-row clickable" @click="viewTag(tag.name)" @contextmenu.prevent.stop="openTagContext(tag, $event)"><span class="tag-color" :style="{ background: tag.r18 ? '#ef4444' : tag.color }"></span><strong><span class="tag-name">{{ tag.name }}</span><em v-if="tag.r18 && tag.name !== 'R-18'" class="r18-chip">R18</em></strong><span class="tag-count">{{ tag.count }} 张图片 · {{ tagRatio(tag.count) }}</span><div class="tag-bar"><i :style="{ width: tagRatio(tag.count), background: tag.r18 ? '#ef4444' : tag.color }"></i></div><div class="tag-row-actions"><button v-if="tag.name !== 'R-18'" class="icon-button small r18-mark" :class="{ on: tag.r18 }" :title="tag.r18 ? '取消 R18 链接标记' : '标记为 R18 链接标签（仅 R18 模式可见）'" @click.stop="toggleTagR18(tag)"><ShieldCheck :size="15" /></button><button v-if="currentUser?.role === 'ADMIN'" class="icon-button small" @click.stop="removeTag(tag)"><Trash2 :size="15" /></button></div></div></div></div></template>
        <template v-else-if="activeView === 'logs'"><div class="page-heading"><div><p class="eyebrow"><ShieldCheck :size="14" /> AUDIT TRAIL</p><h1>访问日志</h1><p class="subheading">记录每一次访问和内容变更。</p></div><button class="filter-button" @click="refreshLogs"><Clock3 :size="15" />刷新日志</button></div><div class="log-stats"><div><strong>{{ logs.length }}</strong><span>当前记录</span></div><div><strong>{{ stats.userCount }}</strong><span>用户数量</span></div><div><strong>审计中</strong><span>日志状态</span></div></div><div class="panel log-panel"><div class="log-head"><span>操作</span><span>目标</span><span>用户</span><span>来源 IP</span><span>时间</span></div><div v-for="log in logs" :key="log.id" class="log-row"><span><i :class="['log-icon', log.tone]"><Eye v-if="log.action === '查看图片'" :size="14" /><Tags v-else-if="log.action.includes('标签')" :size="14" /><Trash2 v-else-if="log.action.includes('删除')" :size="14" /><UserRound v-else :size="14" /></i><strong>{{ log.action }}</strong></span><span>{{ log.target }}</span><span class="log-user"><span class="avatar avatar-blue">{{ log.user?.[0]?.toUpperCase() }}</span>{{ log.user }}</span><span><code>{{ log.ip }}</code><em :class="log.scope === '内网' ? 'internal' : 'external'">{{ log.scope }}</em></span><time>{{ formatTime(log.time) }}</time></div></div></template>
        <ParsePanel v-else-if="activeView === 'parse'" @goto="onPanelGoto" />
        <template v-else-if="activeView === 'settings'"><div class="page-heading"><div><p class="eyebrow"><Settings2 :size="14" /> CONTROL CENTER</p><h1>系统设置</h1><p class="subheading">调整工作区、视觉风格和服务配置。</p></div><button class="primary-button" @click="saveSettings"><Check :size="17" />保存设置</button></div><div class="settings-layout"><div class="settings-nav panel"><button class="active"><SlidersHorizontal :size="16" />常规设置</button><button><Sparkles :size="16" />视觉主题</button><button @click="changePassword"><ShieldCheck :size="16" />修改密码</button><button><Zap :size="16" />服务与存储</button></div><div class="settings-content"><div class="settings-card panel"><div class="setting-title"><div><h2>外观偏好</h2><p>定义你的媒体库视觉语言。</p></div><Sparkles :size="20" /></div><div class="setting-row"><div><strong>主题模式</strong><small>选择适合当前环境的显示模式。</small></div><div class="segmented"><button :class="{ active: isDark }" @click="isDark = true">深色</button><button :class="{ active: !isDark }" @click="isDark = false">浅色</button></div></div><div class="setting-row"><div><strong>流体背景</strong><small>使用 WebGL 渲染多色混合流体效果。</small></div><button class="switch on"><i></i></button></div><div class="setting-row"><div><strong>主题色彩</strong><small>选择工作区的流体色彩组合。</small></div><div class="theme-swatches"><button v-for="theme in ['Aurora', 'Ember', 'Mono']" :key="theme" :class="['theme-swatch', theme.toLowerCase(), { active: selectedTheme === theme }]" @click="selectedTheme = theme"><i></i><span>{{ theme }}</span></button></div></div></div><div class="settings-card panel"><div class="setting-title"><div><h2>服务配置</h2><p>管理员配置会写入 PostgreSQL。</p></div><Zap :size="20" /></div><label class="field-label">服务端口<input v-model="settings.port" type="number" /><small>修改端口后需要重启 Node 服务。</small></label><label class="field-label">图片存储目录<input v-model="settings.storageDir" /><small>建议使用本机磁盘或 NAS 映射目录。</small></label></div></div></div></template>
      </section>
    </main>
    <nav v-if="authenticated" class="mobile-tabbar" aria-label="主导航"><button v-for="tab in mobileNavItems" :key="tab.id" :class="{ active: activeView === tab.id }" @click="go(tab.id)"><component :is="tab.icon" :size="20" /><span>{{ tab.label }}</span></button></nav>
    <div v-if="contextTag" class="context-menu tag-context-menu" @click.stop :style="{ left: `${tagContextPosition.x}px`, top: `${tagContextPosition.y}px` }"><div class="context-title">标签：{{ contextTag.name }}</div><button v-if="contextTag.name !== 'R-18'" @click="toggleTagR18FromMenu"><ShieldCheck :size="16" />{{ contextTag.r18 ? '取消 R18 链接' : '标记为 R18 链接标签' }}</button><button @click="openBatchTag(contextTag!)"><Tags :size="16" />批量添加图片</button></div>
    <div v-if="showBatchTag" class="modal-backdrop" @click.self="showBatchTag = false"><div class="modal batch-tag-modal"><button class="modal-close" @click="showBatchTag = false"><X :size="18" /></button><div class="upload-icon"><Tags :size="24" /></div><h2>批量添加图片</h2><p>选择要添加到「{{ batchTag?.name }}」的图片，可多选。</p><input v-model="batchImageQuery" class="modal-input" placeholder="搜索图片名称或标签..." /><div class="batch-toolbar"><button class="text-button" @click="selectAllBatchImages">全选当前结果</button><button class="text-button" @click="selectedBatchImageIds = []">清空</button><span>已选 {{ selectedBatchImageIds.length }} 张</span></div><div class="batch-image-list"><label v-for="image in batchCandidates" :key="image.id" class="batch-image-option"><input v-model="selectedBatchImageIds" :value="image.id" type="checkbox" /><video v-if="isVideoItem(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline @loadeddata="sizeBatchCard" /><img v-else :src="image.thumb" :alt="image.name" decoding="async" @load="sizeBatchCard" /><span><strong>{{ image.name }}</strong><small>{{ image.tags.join(' · ') || '未分类' }}</small></span></label><div v-if="!batchCandidates.length" class="batch-empty">没有可添加的图片</div></div><button class="primary-button full" :disabled="!selectedBatchImageIds.length" @click="submitBatchTag">添加到标签</button></div></div>
    <div v-if="contextImage" class="context-menu" @click.stop :style="{ left: `${contextPosition.x}px`, top: `${contextPosition.y}px` }"><div class="context-title">{{ contextImage.name }}</div><button @click="openImage(contextImage); contextImage = null"><Eye :size="16" />查看图片</button><button @click="showAddTag = true"><Tags :size="16" />添加标签</button><button v-if="contextImage.tags.length" @click="showRemoveTag = true"><Trash2 :size="16" />移除标签</button><button class="danger" @click="activeView === 'trash' ? permanentDelete(contextImage!) : softDelete(contextImage!)"><Trash2 :size="16" />{{ activeView === 'trash' ? '永久删除' : '移入回收站' }}</button><button v-if="activeView === 'trash'" @click="restore(contextImage!)"><Check :size="16" />恢复图片</button></div>
    <UploadReviewModal :show="showUpload" :available-tags="tags" :r18-mode="r18Mode" @close="showUpload = false" @completed="handleUploadCompleted" @toggle-r18="toggleR18Mode" />
    <div v-if="showTagDialog" class="modal-backdrop" @click.self="showTagDialog = false"><div class="modal"><button class="modal-close" @click="showTagDialog = false"><X :size="18" /></button><div class="upload-icon"><Tags :size="24" /></div><h2>新建标签</h2><p>创建一个新的视觉索引。</p><input v-model="newTag" class="modal-input" placeholder="例如：灵感、人物、项目" @keyup.enter="createTag" /><label v-if="r18Mode" class="new-tag-r18"><input v-model="newTagR18" type="checkbox" /><span>标记为 R18 链接标签</span><small>仅 R18 模式开启时可见、可选择</small></label><button class="primary-button full" @click="createTag">创建标签</button></div></div>
    <div v-if="showAddTag" class="modal-backdrop" @click.self="showAddTag = false"><div class="modal"><button class="modal-close" @click="showAddTag = false"><X :size="18" /></button><h2>添加标签</h2><p>选择一个标签添加到「{{ contextImage?.name }}」。</p><div class="tag-picker"><button v-for="tag in tags" :key="tag.id" @click="addTag(tag.name)"><i :style="{ background: tag.color }"></i>{{ tag.name }}<Plus :size="15" /></button></div></div></div>
    <div v-if="showRemoveTag" class="modal-backdrop" @click.self="showRemoveTag = false"><div class="modal" @click.stop><button class="modal-close" @click="showRemoveTag = false"><X :size="18" /></button><h2>移除标签</h2><p>选择要从「{{ contextImage?.name }}」移除的标签。</p><div class="tag-picker"><button v-for="(tag, index) in contextImage?.tags || []" :key="tag" class="danger" @click="removeImageTag(contextImage!.tagIds[index], tag)"><i :style="{ background: tags.find((item) => item.name === tag)?.color || '#fb7185' }"></i>{{ tag }}<Trash2 :size="15" /></button></div></div></div>
    <div v-if="selectedImage" :class="['preview-backdrop', { 'video-fs': videoFullscreen }]" @click.self="videoFullscreen ? undefined : closePreview()" @wheel.prevent="handlePreviewWheel"><div v-if="previewLoading" class="preview-loading" aria-label="正在加载"><i></i></div><button v-if="isVideoItem(selectedImage)" class="preview-fs-toggle" @click="videoFullscreen = !videoFullscreen">{{ videoFullscreen ? '退出全屏' : '全屏播放' }}</button><button class="preview-close" @click="closePreview"><X :size="22" /></button><div ref="previewImageElement" class="preview-image" :style="{ transform: `translate(${previewOffset.x}px, ${previewOffset.y}px) scale(${previewScale})` }"><video v-if="isVideoItem(selectedImage)" :src="selectedImage.url" controls autoplay playsinline @loadeddata="previewLoading = false" @error="previewLoading = false" @abort="previewLoading = false" /><img v-else :src="selectedImage.url" :alt="selectedImage.name" decoding="async" @load="previewLoading = false" @error="previewLoading = false" /><div class="preview-caption"><strong>{{ selectedImage.name }}</strong><span v-if="isVideoItem(selectedImage)">视频 · {{ selectedImage.size }} · {{ selectedImage.views.toLocaleString() }} 次查看</span><span v-else>{{ selectedImage.width }} × {{ selectedImage.height }} · {{ selectedImage.views.toLocaleString() }} 次查看</span></div></div></div>
    <transition name="toast"><div v-if="toast" class="toast-message"><Check :size="16" />{{ toast }}</div></transition>
  </div>
</template>

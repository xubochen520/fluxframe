<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { gsap } from 'gsap'
import { BookmarkPlus, Check, ChevronLeft, ChevronRight, Download, LoaderCircle, Plus, ScanFace, Sparkles, X } from 'lucide-vue-next'
import type { ImageItem, TagItem } from '../types'
import type { SimilarImageItem } from '../api'
import { isVideoItem } from '../views/shared'
import { auroraEase, motionReduced } from '../motion'

const props = defineProps<{
  image: ImageItem
  loading: boolean
  scale: number
  offset: { x: number; y: number }
  videoFullscreen: boolean
  canNav: boolean
  boxMode: boolean
  boxPixels: Array<{ index: number; box: { kind: 'face' | 'head'; manual?: boolean; score: number }; style: Record<string, string> }>
  boxesLoading: boolean
  boxesDirty: boolean
  boxesSaving: boolean
  selectedBoxIndex: number
  dragging: boolean
  personTags: TagItem[]
  refPickerOpen: boolean
  refSaving: boolean
  referenceCounts: Record<string, number>
  similarOpen: boolean
  similarLoading: boolean
  similarAnalyzing: boolean
  similarItems: SimilarImageItem[]
  similarThreshold: number
  /** 打开大图时缩略图卡片的位置（FLIP 放大的起点）；没有就淡入 */
  flipFrom: { left: number; top: number; width: number; height: number } | null
}>()
const emit = defineEmits<{
  close: []
  step: [delta: number]
  toggleFullscreen: []
  wheel: [event: WheelEvent]
  touchStart: [event: TouchEvent]
  touchMove: [event: TouchEvent]
  touchEnd: []
  boxMove: [index: number, event: PointerEvent]
  boxResize: [index: number, event: PointerEvent]
  boxRemove: [index: number]
  detect: []
  addBox: []
  saveBoxes: []
  toggleBoxMode: []
  download: [image: ImageItem]
  toggleRefPicker: []
  confirmReference: [tagId: string]
  openSimilar: [imageId: string]
  closeSimilar: []
  openSimilarImage: [item: SimilarImageItem]
  mediaLoaded: []
}>()

/* 父组件（App.vue）的滚轮缩放要以图片元素的实际位置为基准，故把元素暴露出去 */
const imageEl = ref<HTMLElement | null>(null)
defineExpose({ imageEl })

/* 从缩略图位置 FLIP 放大：起点是点开那张卡片的位置，终点是查看器里的图片。
   必须在图片有实际尺寸之后跑（挂载瞬间 <img> 还没解码、stage 是 0×0），
   所以既在挂载时试一次，也在图片 load 时再来一次；只动 transform / opacity。 */
let flipDone = false
function runFlip() {
  if (flipDone) return
  flipDone = true
  const from = props.flipFrom
  const element = imageEl.value
  if (!from || !element || motionReduced() || props.videoFullscreen) return
  const rect = element.getBoundingClientRect()
  if (!rect.width || !rect.height) { flipDone = false; return }
  const scale = Math.max(0.05, Math.min(1, from.width / rect.width, from.height / rect.height))
  gsap.fromTo(element, {
    x: from.left + from.width / 2 - (rect.left + rect.width / 2),
    y: from.top + from.height / 2 - (rect.top + rect.height / 2),
    scale,
    opacity: 0.25,
  }, { x: 0, y: 0, scale: 1, opacity: 1, duration: 0.42, ease: auroraEase, clearProps: 'transform,opacity' })
}
function onMediaLoaded() {
  emit('mediaLoaded')
  runFlip()
}
onMounted(() => {
  runFlip()
  window.setTimeout(runFlip, 320)
})
</script>

<template>
  <div
    :class="['viewer', { 'video-fs': props.videoFullscreen, 'similar-open': props.similarOpen }]"
    @click.self="!props.videoFullscreen && emit('close')"
    @wheel.prevent="emit('wheel', $event)"
    @touchstart.passive="emit('touchStart', $event)"
    @touchmove.passive="emit('touchMove', $event)"
    @touchend="emit('touchEnd')"
    @touchcancel="emit('touchEnd')"
  >
    <div v-if="props.loading" class="viewer-loading" aria-label="正在加载"><i></i></div>

    <button v-if="isVideoItem(props.image)" class="viewer-fs" @click="emit('toggleFullscreen')">{{ props.videoFullscreen ? '退出全屏' : '全屏播放' }}</button>
    <button v-if="props.canNav" class="viewer-nav prev" title="上一张" @click.stop="emit('step', -1)"><ChevronLeft :size="24" /></button>
    <button v-if="props.canNav" class="viewer-nav next" title="下一张" @click.stop="emit('step', 1)"><ChevronRight :size="24" /></button>
    <button class="viewer-close" title="关闭（Esc）" @click="emit('close')"><X :size="22" /></button>

    <div ref="imageEl" class="viewer-stage" :class="{ 'box-editing': props.boxMode }" :style="{ transform: `translate(${props.offset.x}px, ${props.offset.y}px) scale(${props.scale})` }">
      <video
        v-if="isVideoItem(props.image)"
        :src="props.image.url"
        controls
        autoplay
        playsinline
        @loadeddata="onMediaLoaded"
        @error="onMediaLoaded"
        @abort="onMediaLoaded"
      />
      <img v-else :src="props.image.url" :alt="props.image.name" decoding="async" @load="onMediaLoaded" @error="onMediaLoaded" />

      <div v-if="props.boxMode && props.boxPixels.length" class="box-layer" :class="{ dragging: props.dragging }">
        <div
          v-for="entry in props.boxPixels"
          :key="entry.index"
          class="box-item"
          :class="[entry.box.kind, { manual: entry.box.manual, selected: props.selectedBoxIndex === entry.index }]"
          :style="entry.style"
          @pointerdown="emit('boxMove', entry.index, $event)"
          @click.stop
        >
          <span class="box-label">
            {{ entry.box.kind === 'head' ? '头' : '脸' }}
            <template v-if="entry.box.score"> · {{ entry.box.score.toFixed(2) }}</template>
            <template v-else> · 人工</template>
          </span>
          <button class="box-del" title="删除这个框" @pointerdown.stop @click.stop="emit('boxRemove', entry.index)"><X :size="11" /></button>
          <i class="box-handle" title="拖动缩放" @pointerdown="emit('boxResize', entry.index, $event)"></i>
        </div>
      </div>

      <div v-if="props.boxMode" class="box-toolbar" @click.stop>
        <span class="box-tip">
          <template v-if="props.boxesLoading">正在识别…</template>
          <template v-else-if="props.boxPixels.length">共 {{ props.boxPixels.length }} 个框 · 点选第 {{ props.selectedBoxIndex + 1 }} 个 · 拖动移动 / 右下角缩放</template>
          <template v-else>还没有框，点「自动框选」</template>
        </span>
        <button class="box-btn" :disabled="props.boxesLoading" @click="emit('detect')"><Sparkles :size="13" />{{ props.boxPixels.length ? '重跑自动框选' : '自动框选' }}</button>
        <button class="box-btn" @click="emit('addBox')"><Plus :size="13" />加框</button>
        <button class="box-btn primary" :disabled="!props.boxesDirty || props.boxesSaving" @click="emit('saveBoxes')"><Check :size="13" />{{ props.boxesSaving ? '保存中…' : '保存' }}</button>
      </div>
    </div>

    <div class="viewer-caption">
      <strong>{{ props.image.name }}</strong>
      <span class="viewer-meta">
        <span v-if="isVideoItem(props.image)">视频 · {{ props.image.size }} · {{ props.image.views.toLocaleString() }} 次查看</span>
        <span v-else>{{ props.image.width }} × {{ props.image.height }} · {{ props.image.views.toLocaleString() }} 次查看</span>
        <button v-if="!isVideoItem(props.image)" class="cap-btn" :class="{ on: props.boxMode }" :title="props.boxMode ? '退出框选' : '框选人物（核验识别准确性）'" @click.stop="emit('toggleBoxMode')">
          <ScanFace :size="13" />{{ props.boxMode ? '退出框选' : '框选' }}
        </button>
        <button v-if="!isVideoItem(props.image)" class="cap-btn" title="查看视觉上相似的图片（也可直接上滑）" @click.stop="props.similarOpen ? emit('closeSimilar') : emit('openSimilar', props.image.id)">
          <Sparkles :size="13" />相似图
        </button>
        <button v-if="props.personTags.length" class="cap-btn" title="把这个框加入该角色的参考图库" @click.stop="emit('toggleRefPicker')">
          <BookmarkPlus :size="13" />参考图
        </button>
        <button class="cap-btn" :title="isVideoItem(props.image) ? '下载视频' : '下载图片'" @click.stop="emit('download', props.image)"><Download :size="13" />下载</button>
      </span>
    </div>

    <div v-if="props.refPickerOpen && props.personTags.length" class="ref-picker" @click.stop>
      <p>把当前框加入哪个角色的参考图库？</p>
      <button v-for="tag in props.personTags" :key="tag.id" class="ref-pick-item" :disabled="props.refSaving" @click="emit('confirmReference', tag.id)">
        <i></i>
        <span>{{ tag.name }}</span>
        <small v-if="props.referenceCounts[tag.id]">已有 {{ props.referenceCounts[tag.id] }} 张</small>
        <small v-else>还没有参考</small>
      </button>
      <small class="ref-pick-hint">会取分数最高的框；想换框请先调整后再点。</small>
    </div>

    <transition name="similar-slide">
      <div v-if="props.similarOpen" class="similar-panel open" @click.stop>
        <div class="similar-head">
          <Sparkles :size="14" />
          <strong>相似图片</strong>
          <span class="similar-sub">
            <template v-if="props.similarLoading">正在查找…</template>
            <template v-else-if="props.similarAnalyzing">正在识别这张图，稍候自动刷新</template>
            <template v-else-if="props.similarItems.length">找到 {{ props.similarItems.length }} 张 · 相似度 ≥ {{ Math.round(props.similarThreshold * 100) }}%</template>
            <template v-else>没有足够相似的图片</template>
          </span>
          <button class="similar-close" title="收起（按 Esc 也可）" @click="emit('closeSimilar')"><X :size="14" /></button>
        </div>
        <div class="similar-body">
          <div v-if="props.similarLoading" class="similar-loading"><LoaderCircle :size="15" class="spin" />正在比对视觉指纹…</div>
          <div v-else-if="props.similarAnalyzing" class="similar-loading"><LoaderCircle :size="15" class="spin" />这张图还没有视觉指纹，后台正在算（约 1 秒）…</div>
          <div v-else-if="props.similarItems.length" class="similar-grid">
            <button v-for="item in props.similarItems" :key="item.id" class="similar-card" :title="`${item.name} · 相似度 ${Math.round(item.score * 100)}%`" @click="emit('openSimilarImage', item)">
              <span class="similar-thumb">
                <img :src="item.thumb" :alt="item.name" loading="lazy" decoding="async" />
                <span class="similar-score mono">{{ Math.round(item.score * 100) }}%</span>
              </span>
              <span class="similar-meta">
                <strong>{{ item.name }}</strong>
                <small>{{ item.tags.length ? item.tags.slice(0, 2).join(' · ') : `${item.width} × ${item.height}` }}</small>
              </span>
            </button>
          </div>
          <div v-else class="similar-empty">
            <Sparkles :size="20" />
            <strong>暂时没有相似的图片</strong>
            <span>库里还没有和这张视觉上接近的图。<br />相似度阈值 {{ Math.round(props.similarThreshold * 100) }}%，可在设置里调整。</span>
          </div>
        </div>
        <div class="similar-foot">按视觉指纹（CCIP）比对，不依赖标签 · 点任意一张可直接切过去</div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.viewer { position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; overflow: hidden; background: radial-gradient(120% 100% at 50% 0%, rgba(8, 10, 22, .95), rgba(2, 3, 8, .99)); -webkit-backdrop-filter: blur(18px) saturate(1.2); backdrop-filter: blur(18px) saturate(1.2); animation: fade-in var(--t-mid) var(--ease); }
.viewer.video-fs { background: #000; }
.viewer-loading { position: absolute; z-index: 3; top: 0; left: 0; right: 0; height: 2px; overflow: hidden; background: var(--track); }
.viewer-loading i { display: block; width: 38%; height: 100%; border-radius: 2px; background: var(--aurora-grad); animation: viewer-slide 1.1s var(--ease) infinite; }

.viewer-stage { position: relative; max-width: min(94vw, 1680px); max-height: 88vh; transform-origin: center center; will-change: transform; transition: transform var(--t-fast) var(--ease); }
.viewer-stage img, .viewer-stage video { display: block; max-width: min(94vw, 1680px); max-height: 88vh; border-radius: var(--r-md); box-shadow: 0 40px 90px -40px rgba(0, 0, 0, .9); }
.viewer.video-fs .viewer-stage img, .viewer.video-fs .viewer-stage video { max-width: 100vw; max-height: 100vh; border-radius: 0; }
.viewer-stage.box-editing img { cursor: crosshair; }

.viewer-close { position: fixed; z-index: 5; top: calc(14px + env(safe-area-inset-top)); right: 16px; display: grid; place-items: center; width: 42px; height: 42px; border-radius: 50%; border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(18px); backdrop-filter: blur(18px); color: var(--text-1); box-shadow: var(--edge-hl), var(--shadow-1); transition: transform var(--t-mid) var(--ease), background var(--t-fast) var(--ease); }
.viewer-close:hover { transform: rotate(90deg); background: var(--field-hover); }
.viewer-fs { position: fixed; z-index: 5; top: calc(18px + env(safe-area-inset-top)); right: 70px; height: 36px; padding: 0 15px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(18px); backdrop-filter: blur(18px); color: var(--text-1); font-size: 12.5px; font-weight: 500; }
.viewer-nav { position: fixed; z-index: 5; top: 50%; display: grid; place-items: center; width: 48px; height: 48px; border-radius: 50%; border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(18px); backdrop-filter: blur(18px); color: var(--text-1); box-shadow: var(--edge-hl), var(--shadow-1); transform: translateY(-50%); transition: transform var(--t-mid) var(--ease), background var(--t-fast) var(--ease); }
.viewer-nav.prev { left: 18px; }
.viewer-nav.next { right: 18px; }
.viewer-nav:hover { background: var(--field-hover); }
.viewer-nav.prev:hover { transform: translateY(-50%) translateX(-3px); }
.viewer-nav.next:hover { transform: translateY(-50%) translateX(3px); }

.viewer-caption { position: fixed; z-index: 4; left: 50%; bottom: calc(18px + env(safe-area-inset-bottom)); display: flex; align-items: center; gap: 14px; max-width: min(94vw, 1180px); padding: 9px 10px 9px 18px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(22px) saturate(1.5); backdrop-filter: blur(22px) saturate(1.5); box-shadow: var(--edge-hl), var(--shadow-2); transform: translateX(-50%); }
.viewer-caption > strong { flex: 0 1 auto; min-width: 0; max-width: 26vw; color: var(--text-1); font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.viewer-meta { display: flex; align-items: center; gap: 6px; flex: none; color: var(--text-3); font: 11px var(--font-mono); }
/* 尺寸与观看数一旦换行会把底部胶囊撑成两行，这里一律不换行 */
.viewer-meta > span { white-space: nowrap; }
.cap-btn { display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 12px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 11.5px; font-weight: 500; white-space: nowrap; transition: color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease); }
.cap-btn:hover { color: var(--text-1); background: var(--field-hover); border-color: var(--stroke-strong); }
.cap-btn.on { color: var(--accent-ink); border-color: var(--accent-line); background: var(--accent-soft); }

/* ---- 框选层 ---- */
.box-layer { position: absolute; inset: 0; }
.box-layer.dragging { cursor: grabbing; }
.box-item { position: absolute; border-radius: 8px; border: 1.5px solid color-mix(in srgb, var(--accent-2) 82%, transparent); background: color-mix(in srgb, var(--accent-2) 10%, transparent); box-shadow: 0 0 0 1px rgba(0, 0, 0, .35), 0 0 20px -6px color-mix(in srgb, var(--accent-2) 60%, transparent); cursor: move; }
.box-item.head { border-color: color-mix(in srgb, var(--accent-1) 84%, transparent); background: color-mix(in srgb, var(--accent-1) 10%, transparent); }
.box-item.manual { border-style: dashed; }
.box-item.selected { box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-2) 45%, transparent), 0 0 26px -4px color-mix(in srgb, var(--accent-2) 70%, transparent); }
.box-label { position: absolute; top: -22px; left: -1.5px; padding: 2px 7px; border-radius: 7px; background: var(--chrome-solid); color: var(--text-1); font: 10px var(--font-mono); white-space: nowrap; }
.box-del { position: absolute; top: -10px; right: -10px; display: grid; place-items: center; width: 19px; height: 19px; border-radius: 50%; background: var(--danger); color: #fff; box-shadow: 0 4px 12px -4px rgba(0, 0, 0, .8); }
.box-handle { position: absolute; right: -7px; bottom: -7px; width: 15px; height: 15px; border-radius: 5px; border: 1.5px solid var(--bg-0); background: var(--accent-2); cursor: nwse-resize; }
/* 工具条固定在大图下方、底部操作条之上：挂在 .viewer-stage 上会随缩放位移，也会被 overflow 裁掉 */
.box-toolbar { position: fixed; z-index: 6; left: 50%; bottom: calc(80px + env(safe-area-inset-bottom)); display: flex; align-items: center; gap: 8px; padding: 7px 8px 7px 14px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome-solid); box-shadow: var(--shadow-2); transform: translateX(-50%); white-space: nowrap; }
.box-tip { color: var(--text-2); font-size: 11.5px; }
.box-btn { display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 11.5px; font-weight: 500; }
.box-btn:hover { color: var(--text-1); background: var(--field-hover); }
.box-btn.primary { border-color: transparent; background: var(--accent-grad); color: var(--on-accent); }
.box-btn:disabled { opacity: .5; }

/* ---- 参考图角色选择 ---- */
.ref-picker { position: fixed; z-index: 6; left: 50%; bottom: calc(78px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 7px; width: min(360px, calc(100vw - 32px)); padding: 16px; border-radius: var(--r-lg); border: 1px solid var(--stroke-strong); background: var(--surface-strong); box-shadow: var(--edge-hl), var(--shadow-2); transform: translateX(-50%); }
.ref-picker p { margin: 0 0 4px; color: var(--text-2); font-size: 12.5px; }
.ref-pick-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: var(--r-sm); border: 1px solid var(--stroke); background: var(--field); color: var(--text-1); font-size: 13px; text-align: left; }
.ref-pick-item:hover { border-color: color-mix(in srgb, var(--person) 40%, transparent); background: color-mix(in srgb, var(--person) 10%, transparent); }
.ref-pick-item i { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--person); box-shadow: 0 0 8px color-mix(in srgb, var(--person) 70%, transparent); }
.ref-pick-item span { flex: 1; min-width: 0; }
.ref-pick-item small { color: var(--text-3); font-size: 11px; }
.ref-pick-hint { color: var(--text-3); font-size: 11px; line-height: 1.6; }

/* ---- 相似图面板 ---- */
.similar-panel { position: fixed; z-index: 4; left: 50%; bottom: 0; display: flex; flex-direction: column; width: min(1080px, 100vw); max-height: 58vh; border-radius: var(--r-xl) var(--r-xl) 0 0; border: 1px solid var(--stroke-strong); border-bottom: 0; background: var(--surface-strong); box-shadow: 0 -30px 70px -30px rgba(0, 0, 0, .8); transform: translateX(-50%); }
.similar-head { display: flex; align-items: center; gap: 10px; padding: 14px 16px 12px 20px; border-bottom: 1px solid var(--stroke); }
.similar-head svg { color: var(--accent-ink); }
.similar-head strong { font: 600 14px var(--font-display); }
.similar-sub { flex: 1; min-width: 0; color: var(--text-3); font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.similar-close { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); }
.similar-close:hover { color: var(--text-1); background: var(--field-hover); }
.similar-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 16px 4px; }
.similar-loading { display: flex; align-items: center; justify-content: center; gap: 9px; padding: 34px 0; color: var(--text-3); font-size: 12.5px; }
.similar-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
.similar-card { display: flex; flex-direction: column; gap: 7px; overflow: hidden; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); padding: 6px; text-align: left; transition: transform var(--t-mid) var(--ease), border-color var(--t-fast) var(--ease); }
.similar-card:hover { transform: translateY(-3px); border-color: var(--accent-line); }
.similar-thumb { position: relative; display: block; overflow: hidden; border-radius: var(--r-sm); background: var(--media-bg); aspect-ratio: 1; }
.similar-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.similar-score { position: absolute; right: 6px; bottom: 6px; padding: 2px 7px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(10px); backdrop-filter: blur(10px); color: var(--text-1); font-size: 10px; }
.similar-meta { min-width: 0; padding: 0 2px 2px; }
.similar-meta strong { display: block; color: var(--text-1); font-size: 11.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.similar-meta small { display: block; margin-top: 3px; color: var(--text-3); font-size: 10.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.similar-empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 38px 20px; color: var(--text-3); font-size: 12.5px; text-align: center; }
.similar-empty svg { color: var(--accent-ink); }
.similar-empty strong { color: var(--text-1); font-size: 14px; }
.similar-foot { padding: 10px 20px calc(12px + env(safe-area-inset-bottom)); border-top: 1px solid var(--stroke); color: var(--text-3); font-size: 11px; text-align: center; }
.similar-slide-enter-active, .similar-slide-leave-active { transition: transform var(--t-slow) var(--ease), opacity var(--t-mid) var(--ease); }
.similar-slide-enter-from, .similar-slide-leave-to { transform: translate(-50%, 100%); opacity: .4; }

@keyframes viewer-slide { from { transform: translateX(-60%); } to { transform: translateX(320%); } }
@media (max-width: 720px) {
  .viewer-stage img, .viewer-stage video { max-width: 100vw; max-height: 74vh; border-radius: 0; }
  .viewer-nav { width: 40px; height: 40px; }
  .viewer-nav.prev { left: 8px; }
  .viewer-nav.next { right: 8px; }
  .viewer-caption { left: 10px; right: 10px; bottom: calc(10px + env(safe-area-inset-bottom)); transform: none; max-width: none; border-radius: var(--r-lg); flex-direction: column; align-items: flex-start; gap: 8px; padding: 12px 14px; }
  .viewer-caption > strong { max-width: 100%; }
  .viewer-meta { flex-wrap: wrap; gap: 7px; }
  .similar-panel { max-height: 66vh; }
  .similar-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .box-toolbar { bottom: calc(124px + env(safe-area-inset-bottom)); flex-wrap: wrap; justify-content: center; white-space: normal; max-width: calc(100vw - 24px); }
}
</style>

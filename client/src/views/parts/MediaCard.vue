<script setup lang="ts">
import { ref } from 'vue'
import { Download, Eye, ImageOff, MoreHorizontal, Play } from 'lucide-vue-next'
import type { ImageItem } from '../../types'
import { formatTime, hasPoster, isVideoItem, sizeImageCard } from '../shared'

const props = defineProps<{ image: ImageItem; variant?: 'compact' | 'full'; trash?: boolean; progress?: boolean; personNames?: Set<string> }>()
const emit = defineEmits<{
  open: [image: ImageItem]
  context: [image: ImageItem, event: MouseEvent]
  pressStart: [image: ImageItem, event: TouchEvent]
  pressCancel: []
  pressEnd: [event: TouchEvent]
  download: [image: ImageItem]
  mediaProgress: [image: ImageItem]
}>()

function onImageLoad(event: Event) {
  sizeImageCard(event)
  if (props.progress) emit('mediaProgress', props.image)
}
const broken = ref(false)
function onImageError(event: Event) {
  broken.value = true
  sizeImageCard(event)
  if (props.progress) emit('mediaProgress', props.image)
}
function onVideoReady(event: Event) { sizeImageCard(event); emit('mediaProgress', props.image) }
function onClick() { if (!props.trash) emit('open', props.image) }
</script>

<template>
  <button class="image-card glow-card" :data-image-id="image.id" @click="onClick" @contextmenu.prevent.stop="emit('context', image, $event)" @touchstart.passive="emit('pressStart', image, $event)" @touchmove.passive="emit('pressCancel')" @touchend="emit('pressEnd', $event)" @touchcancel="emit('pressCancel')">
    <div class="image-wrap">
      <video v-if="isVideoItem(image) && !hasPoster(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline @loadeddata="onVideoReady" @error="onVideoReady" />
      <span v-else-if="broken" class="media-fallback"><ImageOff :size="22" /><small>无法加载预览</small></span>
      <img v-else :src="image.thumb" :alt="image.name" loading="lazy" decoding="async" @load="onImageLoad" @error="onImageError" />
      <span class="card-shade"></span>
      <span class="card-badges">
        <span class="glass-badge"><Eye :size="12" />{{ image.views }}</span>
        <span v-if="image.r18" class="glass-badge r18" title="R18 内容">R18</span>
        <span v-if="isVideoItem(image)" class="glass-badge"><Play :size="11" />视频</span>
      </span>
      <span class="image-more" title="更多操作" @click.stop="emit('context', image, $event)"><MoreHorizontal :size="16" /></span>
      <span class="image-download" :title="'下载' + (isVideoItem(image) ? '视频' : '图片')" @click.stop="emit('download', image)"><Download :size="14" /></span>
    </div>
    <div class="image-meta">
      <strong>{{ image.name }}</strong>
      <span v-if="variant === 'full'" class="image-time">{{ image.size }} · {{ formatTime(image.uploadedAt) }}</span>
      <span v-else class="image-time">{{ formatTime(image.uploadedAt) }}</span>
      <template v-if="variant === 'full'">
        <div v-if="image.tags.length" class="image-tags">
          <span v-for="tag in image.tags" :key="tag" :class="['chip', { person: personNames?.has(tag) }]">#{{ tag }}</span>
        </div>
        <small v-else class="image-tags image-tags-empty">未分类</small>
      </template>
    </div>
  </button>
</template>

<style scoped>
.image-card { position: relative; display: block; width: 100%; padding: 6px; border-radius: var(--r-lg); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl), var(--shadow-1); color: inherit; text-align: left; align-self: start; height: max-content; -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; transition: transform var(--t-mid) var(--ease), box-shadow var(--t-mid) var(--ease), border-color var(--t-mid) var(--ease); }
.image-card:hover { transform: translateY(-4px); border-color: var(--stroke-strong); box-shadow: var(--edge-hl), 0 26px 50px -26px rgba(0, 0, 0, .7), 0 20px 44px -28px var(--glow); }
.image-card:active { transform: translateY(-1px) scale(.99); transition-duration: var(--t-fast); }
.image-wrap { position: relative; overflow: hidden; border-radius: 17px; background: var(--media-bg); }
.image-wrap img, .image-wrap video { display: block; width: 100%; height: auto; min-height: 60px; object-fit: contain; transition: transform var(--t-slow) var(--ease); }
.image-card:hover .image-wrap img, .image-card:hover .image-wrap video { transform: scale(1.035); }
.image-wrap::after { content: ''; position: absolute; inset: 0; pointer-events: none; opacity: 0; background: radial-gradient(240px circle at var(--mx, 50%) var(--my, 50%), rgba(255, 255, 255, .2), transparent 60%); transition: opacity var(--t-mid) var(--ease); }
.image-card:hover .image-wrap::after { opacity: 1; }
.media-fallback { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; aspect-ratio: 4 / 3; color: var(--text-3); font-size: 11px; background: radial-gradient(circle at 50% 30%, var(--accent-soft), transparent 70%); }
.card-shade { position: absolute; inset: 0; pointer-events: none; background: linear-gradient(180deg, rgba(4, 5, 12, .42), transparent 34%, transparent 66%, rgba(4, 5, 12, .4)); }
.card-badges { position: absolute; top: 9px; left: 9px; right: 46px; display: flex; flex-wrap: wrap; gap: 5px; }
.glass-badge { display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px; border-radius: var(--r-pill); border: 1px solid rgba(255, 255, 255, .16); background: rgba(8, 10, 20, .5); color: #fff; font: 500 10.5px var(--font-mono); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .14); }
.glass-badge.r18 { border-color: rgba(255, 255, 255, .22); background: rgba(225, 29, 72, .82); font-family: var(--font-display); font-weight: 700; letter-spacing: .04em; }
.image-more, .image-download { position: absolute; right: 8px; display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; border: 1px solid rgba(255, 255, 255, .18); background: rgba(8, 10, 20, .55); color: #fff; box-shadow: inset 0 1px 0 rgba(255, 255, 255, .16); opacity: 0; transform: scale(.86); transition: opacity var(--t-mid) var(--ease), transform var(--t-mid) var(--ease), background var(--t-fast) var(--ease); }
.image-more { top: 8px; }
.image-download { bottom: 8px; }
.image-card:hover .image-more, .image-card:hover .image-download { opacity: 1; transform: none; }
.image-more:hover, .image-download:hover { background: var(--accent-1); color: var(--on-accent); }
.image-meta { display: flex; flex-direction: column; gap: 3px; padding: 10px 8px 6px; }
.image-meta strong { color: var(--text-1); font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.image-time { color: var(--text-3); font: 10.5px var(--font-mono); }
.image-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.image-tags .chip { height: 20px; padding: 0 7px; font-size: 10.5px; }
.image-tags-empty { color: var(--text-3); font-size: 11px; }
@media (hover: none), (max-width: 720px) {
  .image-more, .image-download { opacity: 1; transform: none; width: 28px; height: 28px; }
  .image-card:hover { transform: none; }
  .image-card:active .image-wrap img, .image-card:active .image-wrap video { transform: scale(1.02); }
}
</style>

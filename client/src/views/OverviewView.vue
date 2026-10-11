<script setup lang="ts">
import { computed } from 'vue'
import { Eye, Filter, Images, Tags, Upload, Zap } from 'lucide-vue-next'
import DeepseekBar from '../components/DeepseekBar.vue'
import MediaCard from './parts/MediaCard.vue'
import type { CurrentUser } from '../api'
import type { AuditLog, ImageItem, View } from '../types'
import { formatTime, hasPoster, isVideoItem, type DashboardStats } from './shared'

const props = defineProps<{ user: CurrentUser | null; greeting: string; stats: DashboardStats; topImages: ImageItem[]; logs: AuditLog[]; liveImages: ImageItem[] }>()
const emit = defineEmits<{
  upload: []
  go: [view: View]
  open: [image: ImageItem]
  context: [image: ImageItem, event: MouseEvent]
  pressStart: [image: ImageItem, event: TouchEvent]
  pressCancel: []
  pressEnd: [event: TouchEvent]
  download: [image: ImageItem]
  mediaProgress: [image: ImageItem]
}>()

const statCards = computed(() => [
  { key: 'images', label: '图片总数', chip: '数据存储', icon: Images, tone: 'violet', value: props.stats.imageCount.toLocaleString() },
  { key: 'views', label: '总观看次数', chip: '实时累计', icon: Eye, tone: 'cyan', value: props.stats.totalViews.toLocaleString() },
  { key: 'tags', label: '活跃标签', chip: '可管理', icon: Tags, tone: 'amber', value: String(props.stats.tagCount) },
  { key: 'storage', label: '已使用空间', chip: '已连接', icon: Zap, tone: 'green', value: props.stats.storage },
])
const recent = computed(() => props.liveImages.slice(0, 4))
</script>

<template>
  <div class="overview">
    <section class="bento">
      <article class="hero panel glow-card rise">
        <span class="hero-glow" aria-hidden="true"></span>
        <p class="status-pill"><span class="status-dot"></span>系统运行正常 · v1.1.17 · 数据来自 PostgreSQL</p>
        <h1>{{ greeting }}，<span class="hero-name">{{ user?.username }}</span> <span class="spark">✦</span></h1>
        <p class="subheading">这是你的媒体库今天的概览。</p>
        <div class="hero-actions">
          <button class="primary-button" @click.stop="emit('upload')"><Upload :size="17" />上传媒体</button>
          <button class="filter-button" @click="emit('go', 'library')"><Images :size="15" />浏览图片库</button>
        </div>
      </article>
      <div class="stat-grid">
        <article v-for="(card, index) in statCards" :key="card.key" class="stat-card panel glow-card lift rise" :style="{ '--i': index + 1 }">
          <div class="stat-top">
            <span :class="['stat-icon', card.tone]"><component :is="card.icon" :size="18" /></span>
            <span class="stat-chip">{{ card.chip }}</span>
          </div>
          <strong class="num">{{ card.value }}</strong>
          <span class="stat-label">{{ card.label }}</span>
        </article>
      </div>
    </section>

    <DeepseekBar class="rise" style="--i: 5" :is-admin="user?.role === 'ADMIN'" />

    <section class="duo">
      <article class="panel popular-panel rise" style="--i: 6">
        <header class="panel-heading">
          <div><h2>热门图片</h2><p>按观看次数排序</p></div>
          <button class="text-button" @click="emit('go', 'library')">查看全部 <span>→</span></button>
        </header>
        <div v-if="topImages.length" class="popular-list">
          <button v-for="(image, index) in topImages" :key="image.id" class="popular-item" @click="emit('open', image)" @contextmenu.prevent.stop="emit('context', image, $event)" @touchstart.passive="emit('pressStart', image, $event)" @touchmove.passive="emit('pressCancel')" @touchend="emit('pressEnd', $event)" @touchcancel="emit('pressCancel')">
            <span class="rank num">0{{ index + 1 }}</span>
            <span class="popular-thumb">
              <video v-if="isVideoItem(image) && !hasPoster(image)" :src="image.url + '#t=0.5'" preload="metadata" muted playsinline />
              <img v-else :src="image.thumb" :alt="image.name" loading="lazy" decoding="async" />
            </span>
            <span class="popular-name"><strong>{{ image.name }}</strong><small>{{ image.tags.join(' · ') || '未分类' }}</small></span>
            <span class="view-count"><Eye :size="14" />{{ image.views.toLocaleString() }}</span>
          </button>
        </div>
        <div v-else class="empty-state">还没有图片，先上传一张吧。</div>
      </article>
      <article class="panel activity-panel rise" style="--i: 7">
        <header class="panel-heading"><div><h2>最近动态</h2><p>系统操作实时记录</p></div></header>
        <ol class="timeline">
          <li v-for="log in logs.slice(0, 4)" :key="log.id" class="timeline-item">
            <span :class="['timeline-dot', log.tone]"></span>
            <div class="timeline-text"><strong>{{ log.user }} <span>{{ log.action }}</span></strong><small>{{ log.target }}</small></div>
            <time>{{ formatTime(log.time) }}</time>
          </li>
          <li v-if="!logs.length" class="timeline-empty">暂无动态</li>
        </ol>
        <button class="activity-footer" @click="emit('go', 'logs')">查看完整日志 <span>→</span></button>
      </article>
    </section>

    <section class="recent rise" style="--i: 8">
      <div class="section-heading">
        <div><h2>最近上传</h2><p>共 {{ liveImages.length }} 张图片</p></div>
        <button class="filter-button" @click="emit('go', 'library')"><Filter :size="15" />筛选</button>
      </div>
      <div v-if="liveImages.length" class="image-grid compact-grid">
        <MediaCard v-for="image in recent" :key="image.id" :image="image" variant="compact" @open="emit('open', $event)" @context="(img, e) => emit('context', img, e)" @press-start="(img, e) => emit('pressStart', img, e)" @press-cancel="emit('pressCancel')" @press-end="emit('pressEnd', $event)" @download="emit('download', $event)" @media-progress="emit('mediaProgress', $event)" />
      </div>
      <div v-else class="empty-state"><Images :size="30" /><strong>还没有图片</strong><span>点击右上角上传第一张图片。</span></div>
    </section>
  </div>
</template>

<style scoped>
.overview { display: flex; flex-direction: column; gap: 18px; }
.bento { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(0, 1fr); gap: 18px; }
.hero { display: flex; flex-direction: column; justify-content: flex-end; min-height: 300px; padding: 32px; overflow: hidden; border-radius: var(--r-xl); }
.hero-glow { position: absolute; inset: -40% -20% auto auto; width: 520px; height: 520px; border-radius: 50%; background: radial-gradient(circle, color-mix(in srgb, var(--accent-1) 42%, transparent), color-mix(in srgb, var(--accent-2) 16%, transparent) 45%, transparent 70%); filter: blur(10px); opacity: .75; pointer-events: none; z-index: -1; animation: drift 14s ease-in-out infinite alternate; }
.status-pill { display: inline-flex; align-self: flex-start; align-items: center; gap: 9px; margin: 0 0 auto; padding: 7px 13px 7px 11px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font: 500 10.5px var(--font-mono); letter-spacing: .06em; }
.hero h1 { margin: 34px 0 0; font: 600 clamp(28px, 3vw, 42px)/1.1 var(--font-display); letter-spacing: -.04em; }
.hero-name { background: var(--aurora-grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
.spark { display: inline-block; color: var(--accent-2); font-size: .7em; animation: twinkle 3.2s var(--ease) infinite; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 22px; }
.stat-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.stat-card { display: flex; flex-direction: column; padding: 20px; overflow: hidden; }
.stat-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.stat-icon { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 14px; color: var(--c); background: color-mix(in srgb, var(--c) 15%, transparent); box-shadow: 0 0 26px -6px color-mix(in srgb, var(--c) 60%, transparent), inset 0 1px 0 var(--highlight); }
.stat-icon.violet { --c: var(--accent-1); }
.stat-icon.cyan { --c: var(--accent-2); }
.stat-icon.amber { --c: #f59e0b; }
.stat-icon.green { --c: var(--success); }
.stat-chip { padding: 4px 9px; border-radius: var(--r-pill); border: 1px solid var(--stroke); color: var(--text-3); font: 500 10px var(--font-mono); letter-spacing: .04em; }
.stat-card strong { font-size: clamp(26px, 2.4vw, 36px); font-weight: 600; line-height: 1; }
.stat-label { margin-top: 8px; color: var(--text-2); font-size: 12.5px; }
.duo { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr); gap: 18px; }
.popular-panel, .activity-panel { padding: 22px; }
.popular-list { display: flex; flex-direction: column; gap: 6px; margin-top: 16px; }
.popular-item { display: grid; grid-template-columns: 54px 58px minmax(0, 1fr) auto; align-items: center; gap: 14px; padding: 8px 12px 8px 4px; border-radius: var(--r-md); color: inherit; text-align: left; -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; transition: background var(--t-fast) var(--ease), transform var(--t-mid) var(--ease); }
.popular-item:hover { background: var(--glass-2); transform: translateX(3px); }
.rank { font-size: 30px; font-weight: 700; text-align: center; background: linear-gradient(180deg, var(--text-1), color-mix(in srgb, var(--text-1) 18%, transparent)); -webkit-background-clip: text; background-clip: text; color: transparent; }
.popular-item:first-child .rank { background: var(--aurora-grad); -webkit-background-clip: text; background-clip: text; }
.popular-thumb { display: block; width: 58px; height: 58px; overflow: hidden; border-radius: 15px; background: var(--media-bg); box-shadow: inset 0 0 0 1px var(--stroke); }
.popular-thumb img, .popular-thumb video { width: 100%; height: 100%; object-fit: cover; display: block; }
.popular-name { min-width: 0; }
.popular-name strong { display: block; font-size: 13.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.popular-name small { display: block; margin-top: 4px; color: var(--text-3); font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.view-count { display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border-radius: var(--r-pill); background: var(--field); color: var(--text-2); font: 500 11.5px var(--font-mono); }
.activity-panel { display: flex; flex-direction: column; }
.timeline { position: relative; flex: 1; margin: 18px 0 0; padding: 0; list-style: none; }
.timeline::before { content: ''; position: absolute; left: 6px; top: 8px; bottom: 8px; width: 1px; background: linear-gradient(180deg, var(--accent-line), transparent); }
.timeline-item { position: relative; display: grid; grid-template-columns: 13px minmax(0, 1fr) auto; gap: 14px; padding: 9px 0; }
.timeline-dot { position: relative; top: 5px; width: 13px; height: 13px; border-radius: 50%; border: 3px solid var(--bg-1); background: var(--c, var(--accent-1)); box-shadow: 0 0 12px var(--c, var(--accent-1)); }
.timeline-dot.blue { --c: #60a5fa; }
.timeline-dot.violet { --c: #a78bfa; }
.timeline-dot.orange { --c: #fb923c; }
.timeline-dot.red { --c: var(--danger); }
.timeline-dot.green { --c: var(--success); }
.timeline-text { min-width: 0; }
.timeline-text strong { display: block; font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.timeline-text strong span { color: var(--text-2); font-weight: 400; }
.timeline-text small { display: block; margin-top: 3px; color: var(--text-3); font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.timeline-item time { color: var(--text-3); font: 10.5px var(--font-mono); white-space: nowrap; padding-top: 2px; }
.timeline-empty { padding: 20px 0; color: var(--text-3); font-size: 12.5px; }
.activity-footer { display: flex; align-items: center; justify-content: center; gap: 6px; height: 40px; margin-top: 12px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--accent-ink); font-size: 12.5px; font-weight: 600; transition: background var(--t-fast) var(--ease); }
.activity-footer:hover { background: var(--field-hover); }
.recent { display: flex; flex-direction: column; gap: 16px; margin-top: 8px; }
.image-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); grid-auto-rows: 8px; grid-auto-flow: dense; align-items: start; row-gap: 13px; column-gap: 16px; }
@keyframes drift { to { transform: translate(-60px, 40px) scale(1.12); } }
@keyframes twinkle { 50% { opacity: .45; transform: scale(.82) rotate(20deg); } }
@media (max-width: 1260px) { .bento, .duo { grid-template-columns: 1fr; } .hero { min-height: 250px; } }
@media (max-width: 1050px) { .image-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 720px) {
  .overview { gap: 14px; }
  .hero { min-height: 0; padding: 22px 20px; }
  .hero h1 { margin-top: 24px; font-size: 28px; }
  .hero-actions .primary-button { flex: 1; }
  .stat-grid { gap: 12px; }
  .stat-card { padding: 16px; }
  .stat-top { margin-bottom: 14px; }
  .popular-panel, .activity-panel { padding: 18px 16px; }
  .popular-item { grid-template-columns: 34px 50px minmax(0, 1fr); gap: 11px; }
  .popular-thumb { width: 50px; height: 50px; }
  .rank { font-size: 22px; }
  .view-count { display: none; }
  .image-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 12px; }
}
</style>

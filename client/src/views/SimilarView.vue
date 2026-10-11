<script setup lang="ts">
import { Box, Fingerprint, LayoutGrid, LoaderCircle, Orbit, SlidersHorizontal, Sparkles, Tags } from 'lucide-vue-next'
import SimilarGalaxy from '../components/SimilarGalaxy.vue'
import SimilarGalaxy3D from '../components/SimilarGalaxy3D.vue'
import type { EmbedGraphMode, EmbedGraphPayload } from '../api'
import type { ImageItem } from '../types'

const props = defineProps<{
  mode: EmbedGraphMode
  view: 'galaxy' | 'galaxy3d' | 'list'
  payload: EmbedGraphPayload | null
  loading: boolean
  groups: Array<{ size: number; items: ImageItem[] }>
}>()
const emit = defineEmits<{
  setMode: [mode: EmbedGraphMode]
  setView: [view: 'galaxy' | 'galaxy3d' | 'list']
  refresh: []
  openGraph: [image: ImageItem, showSimilarPanel: boolean]
  goSettings: []
  goLibrary: []
}>()

const percent = (value: number | undefined) => Math.round((value || 0) * 100)
</script>

<template>
  <div class="similar">
    <div class="page-heading rise">
      <div>
        <p class="eyebrow"><Sparkles :size="14" /> VISUAL RELATIONS</p>
        <h1>相似图关系网</h1>
        <p class="subheading">
          {{ props.mode === 'tag'
            ? '按标签自动把被打了同一批标记的图片连起来：图片挂在自己的标签上，围成一团的就是共享那批标签的图。'
            : '按视觉指纹（CCIP）自动把看起来像同一角色/同一张图的图片连起来，和标签无关。' }}上面是星云全貌，下面可以切成按分组看。
        </p>
      </div>
      <div class="similar-actions">
        <div class="segmented" role="radiogroup" aria-label="连线依据">
          <button :class="{ active: props.mode === 'visual' }" title="按 CCIP 视觉指纹连线：长得像的图聚在一起" @click="emit('setMode', 'visual')"><Fingerprint :size="14" />视觉指纹</button>
          <button :class="{ active: props.mode === 'tag' }" title="按标签连线：被打了同一批标记的图聚在一起" @click="emit('setMode', 'tag')"><Tags :size="14" />标签</button>
        </div>
        <div class="segmented" role="radiogroup" aria-label="视图">
          <button :class="{ active: props.view === 'galaxy' }" title="二维总览：一眼看清有多少团、彼此多远" @click="emit('setView', 'galaxy')"><Orbit :size="14" />星系图</button>
          <button :class="{ active: props.view === 'galaxy3d' }" title="三维星系空间：拖动旋转、点图推近" @click="emit('setView', 'galaxy3d')"><Box :size="14" />3D 星系</button>
          <button :class="{ active: props.view === 'list' }" title="按分组逐张看" @click="emit('setView', 'list')"><LayoutGrid :size="14" />分组列表</button>
        </div>
        <button class="filter-button" :disabled="props.loading" @click="emit('refresh')">
          <LoaderCircle v-if="props.loading" :size="15" class="spin" /><Sparkles v-else :size="15" />{{ props.loading ? '计算中…' : '刷新关系网' }}
        </button>
        <button class="filter-button" @click="emit('goSettings')"><SlidersHorizontal :size="15" />阈值设置</button>
      </div>
    </div>

    <div v-if="props.payload" class="hud rise" style="--i: 1">
      <div><strong class="num">{{ props.payload.totalIndexed ?? props.payload.nodes.length }}</strong><span>{{ props.mode === 'tag' ? '有标签' : '已建指纹' }}</span></div>
      <div><strong class="num">{{ props.payload.linked ?? 0 }}</strong><span>连上关系</span></div>
      <div><strong class="num">{{ props.groups.length }}</strong><span>相似分组</span></div>
      <div><strong class="num">{{ props.payload.isolated }}</strong><span>{{ props.mode === 'tag' ? '无共同标签' : '暂无相似图' }}</span></div>
      <div><strong class="num">{{ percent(props.payload.threshold) }}%</strong><span>{{ props.mode === 'tag' ? '标签重合度' : '相似度阈值' }}</span></div>
    </div>

    <div v-if="props.loading && !props.payload" class="stage panel loading-stage"><LoaderCircle :size="20" class="spin" />正在读取关系网…</div>

    <template v-else-if="props.payload && !props.payload.nodes.length">
      <div class="panel empty-stage">
        <Sparkles :size="26" />
        <strong>{{ props.mode === 'tag' ? '还没有给图片打标签' : '还没有建立视觉指纹' }}</strong>
        <p>
          {{ props.mode === 'tag'
            ? '标签模式靠标签连线，库里得先有标签。上传时可以用自动打标，也可以在图片库/标签页里手动补。'
            : '视觉指纹是「找相似图」的依据，每张图算一次（约 0.7 秒）。上传新图会自动算；库里的历史图片需要先在设置里点一次「建立索引」。' }}
        </p>
        <button v-if="props.mode !== 'tag'" class="primary-button" @click="emit('goSettings')">去设置里建立索引</button>
        <button v-else class="primary-button" @click="emit('goLibrary')">去图片库打标签</button>
      </div>
    </template>

    <template v-else-if="props.payload">
      <div class="stage panel">
        <SimilarGalaxy3D
          v-if="props.view === 'galaxy3d'"
          :nodes="props.payload.nodes"
          :edges="props.payload.edges"
          :groups="props.payload.groups"
          :positions="props.payload.positions || {}"
          :characters="props.payload.characters || {}"
          :threshold="props.payload.threshold || 0.35"
          :mode="props.mode"
          :tag-anchors="props.payload.tagAnchors || {}"
          @open="(item: ImageItem) => emit('openGraph', item, false)"
        />
        <SimilarGalaxy
          v-else-if="props.view === 'galaxy'"
          :nodes="props.payload.nodes"
          :edges="props.payload.edges"
          :groups="props.payload.groups"
          :positions="props.payload.positions || {}"
          :characters="props.payload.characters || {}"
          :threshold="props.payload.threshold || 0.35"
          :mode="props.mode"
          :tag-anchors="props.payload.tagAnchors || {}"
          @open="(item: ImageItem) => emit('openGraph', item, true)"
        />
        <template v-else>
          <div v-if="!props.groups.length" class="empty-stage inline">
            <Sparkles :size="26" />
            <strong>暂时没有达到阈值（{{ percent(props.payload.threshold) }}%）的{{ props.mode === 'tag' ? '共同标签' : '相似图' }}</strong>
            <p>
              {{ props.mode === 'tag'
                ? '说明没有哪两张图共享了足够有分量的标签。给图片补上更具体的标签（人物、作品、服装）就能连起来。'
                : '可能是库里图片彼此差异较大。可以在设置里把阈值调低一点再试。' }}
            </p>
            <button v-if="props.mode !== 'tag'" class="primary-button" @click="emit('goSettings')">调整阈值</button>
            <button v-else class="primary-button" @click="emit('goLibrary')">去补标签</button>
          </div>
          <div v-else class="groups">
            <div v-for="(group, index) in props.groups" :key="index" class="group panel">
              <div class="group-head">
                <div>
                  <strong>{{ group.items.length }} 张互相相似</strong>
                  <small>{{ group.items[0]?.name }}{{ group.items.length > 1 ? ' 等' : '' }}</small>
                </div>
                <button class="text-button" @click="emit('openGraph', group.items[0], true)">打开 <span>→</span></button>
              </div>
              <div class="group-grid">
                <button v-for="image in group.items.slice(0, 18)" :key="image.id" class="group-thumb" :title="image.name" @click="emit('openGraph', image, true)">
                  <img :src="image.thumb" :alt="image.name" loading="lazy" decoding="async" />
                  <span v-if="image.tags.length" class="thumb-tag">{{ image.tags[0] }}</span>
                </button>
                <span v-if="group.items.length > 18" class="group-more mono">+{{ group.items.length - 18 }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.similar { display: flex; flex-direction: column; gap: 16px; }
.similar-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; }
.hud { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 1px; overflow: hidden; border-radius: var(--r-lg); border: 1px solid var(--stroke); background: var(--glass-fill); box-shadow: var(--edge-hl), var(--shadow-1); }
.hud div { display: flex; flex-direction: column; gap: 4px; padding: 15px 18px; }
.hud div + div { border-left: 1px solid var(--stroke); }
.hud strong { font-size: clamp(19px, 1.7vw, 25px); font-weight: 600; line-height: 1.1; color: var(--text-1); }
.hud span { color: var(--text-3); font-size: 11.5px; }

.stage { position: relative; padding: 8px; overflow: hidden; border-radius: var(--r-xl); background: radial-gradient(120% 90% at 50% -10%, color-mix(in srgb, var(--accent-1) 16%, transparent), transparent 62%), linear-gradient(180deg, #06070f, #04050b); }
.stage.loading-stage, .empty-stage { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; min-height: 260px; padding: 34px 22px; color: var(--text-2); font-size: 13px; text-align: center; }
.empty-stage strong { color: var(--text-1); font-size: 16px; font-weight: 600; }
.empty-stage p { max-width: 620px; margin: 0; color: var(--text-2); font-size: 12.5px; line-height: 1.75; }
.empty-stage svg { color: var(--accent-ink); }
.empty-stage.inline { min-height: 320px; }

.groups { display: flex; flex-direction: column; gap: 14px; padding: 6px; }
.group { padding: 16px 18px 18px; }
.group-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.group-head strong { display: block; font: 600 14.5px var(--font-display); letter-spacing: -.02em; color: var(--text-1); }
.group-head small { display: block; margin-top: 4px; color: var(--text-3); font-size: 11.5px; }
.group-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: 10px; }
.group-thumb { position: relative; overflow: hidden; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--media-bg); aspect-ratio: 1; transition: transform var(--t-mid) var(--ease), border-color var(--t-fast) var(--ease); }
.group-thumb:hover { transform: translateY(-3px); border-color: var(--accent-line); }
.group-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-tag { position: absolute; left: 6px; bottom: 6px; max-width: calc(100% - 12px); padding: 2px 8px; border-radius: var(--r-pill); border: 1px solid var(--stroke-strong); background: var(--chrome); -webkit-backdrop-filter: blur(10px); backdrop-filter: blur(10px); color: var(--text-1); font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.group-more { display: grid; place-items: center; border-radius: var(--r-md); border: 1px dashed var(--stroke-strong); color: var(--text-3); font-size: 12px; aspect-ratio: 1; }

@media (max-width: 1050px) { .hud { grid-template-columns: repeat(3, minmax(0, 1fr)); } .hud div:nth-child(4) { border-left: 0; } }
@media (max-width: 720px) {
  .hud { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hud div { padding: 12px 14px; }
  .hud div:nth-child(odd) { border-left: 0; }
  .stage { padding: 4px; border-radius: var(--r-lg); }
  .group { padding: 14px; }
  .group-grid { grid-template-columns: repeat(auto-fill, minmax(78px, 1fr)); gap: 8px; }
}
</style>

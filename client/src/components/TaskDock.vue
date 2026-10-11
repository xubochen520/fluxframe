<script setup lang="ts">
import { Check, FolderOpen, Images, LoaderCircle, RotateCcw, X } from 'lucide-vue-next'
import { dismissDownloadTask, markDownloadOpenError, openDownloadedFile, retryDownloadTask, useDownloadTasks } from '../downloadTaskStore'
import { dismissSaveTask, retrySaveTask, useSaveTasks } from '../parseSaveStore'

/* ============================================================
   全局任务坞（左下角）：后台任务进度卡片
   保存到图片库（服务端导入）+ 本机下载（浏览器拉取）
   ============================================================ */
const emit = defineEmits<{ gotoLibrary: [] }>()
defineProps<{ hidden?: boolean }>()

const saveTasks = useSaveTasks()
const downloadTasks = useDownloadTasks()

/** APK 下载完成 → 用系统查看器打开文件验证落盘；失败则卡片转错误态显示原因 */
async function onOpenDownload(id: number) {
  const result = await openDownloadedFile(id)
  if (!result.ok) markDownloadOpenError(id, result.message || '无法打开文件')
}
</script>

<template>
  <div v-if="!hidden && (saveTasks.length || downloadTasks.length)" class="task-dock">
    <!-- 保存到图片库任务 -->
    <div v-for="t in saveTasks" :key="'s' + t.id" :class="['task-card', t.state]">
      <div class="task-head">
        <span class="task-ico">
          <LoaderCircle v-if="t.state === 'working'" class="spin" :size="15" />
          <Check v-else-if="t.state === 'done'" :size="15" />
          <X v-else :size="15" />
        </span>
        <div class="task-tx">
          <b>保存到图片库 · {{ t.opts.name }}</b>
          <small>{{ t.stageText }}</small>
        </div>
        <button class="task-x" :title="t.state === 'working' ? '取消' : '关闭'" @click="dismissSaveTask(t.id)"><X :size="13" /></button>
      </div>
      <div class="task-bar">
        <i v-if="t.state === 'working'" :class="{ indet: t.unknown || !t.pct }"
          :style="{ width: t.unknown || !t.pct ? '34%' : Math.round(t.pct * 100) + '%' }"></i>
        <i v-else :class="[t.state === 'done' ? 'fill-ok' : 'fill-err']"></i>
      </div>
      <div v-if="t.state === 'done'" class="task-acts">
        <button class="task-btn" @click="dismissSaveTask(t.id)">知道了</button>
        <button class="task-btn task-go" @click="emit('gotoLibrary')"><Images :size="12" />去图片库查看</button>
      </div>
      <div v-else-if="t.state === 'error'" class="task-acts">
        <button class="task-btn" @click="dismissSaveTask(t.id)">关闭</button>
        <button class="task-btn task-go" @click="retrySaveTask(t.id)"><RotateCcw :size="12" />重试</button>
      </div>
    </div>

    <!-- 本机下载任务 -->
    <div v-for="t in downloadTasks" :key="'d' + t.id" :class="['task-card', t.state]">
      <div class="task-head">
        <span class="task-ico">
          <LoaderCircle v-if="t.state === 'working'" class="spin" :size="15" />
          <Check v-else-if="t.state === 'done'" :size="15" />
          <X v-else :size="15" />
        </span>
        <div class="task-tx">
          <b>{{ t.opts.filename }}</b>
          <small>{{ t.stageText }}</small>
        </div>
        <button class="task-x" :title="t.state === 'working' ? '取消' : '关闭'" @click="dismissDownloadTask(t.id)"><X :size="13" /></button>
      </div>
      <div class="task-bar">
        <i v-if="t.state === 'working'" :class="{ indet: t.unknown || !t.pct }"
          :style="{ width: t.unknown || !t.pct ? '34%' : Math.round(t.pct * 100) + '%' }"></i>
        <i v-else :class="[t.state === 'done' ? 'fill-ok' : 'fill-err']"></i>
      </div>
      <div v-if="t.state === 'done'" class="task-acts">
        <button v-if="t.fileId" class="task-btn task-go" @click="onOpenDownload(t.id)"><FolderOpen :size="12" />打开文件</button>
        <button class="task-btn task-go" @click="dismissDownloadTask(t.id)">知道了</button>
      </div>
      <div v-else-if="t.state === 'error'" class="task-acts">
        <button class="task-btn" @click="dismissDownloadTask(t.id)">关闭</button>
        <button class="task-btn task-go" @click="retryDownloadTask(t.id)"><RotateCcw :size="12" />重试</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   全局任务坞（左下角）· 极光玻璃
   位置逻辑不变；卡片改为玻璃原语，进度条走极光渐变
   ============================================================ */
.task-dock {
  position: fixed; left: 20px; bottom: 20px; z-index: 26;
  display: flex; flex-direction: column; gap: 9px;
  width: min(360px, calc(100vw - 32px));
  pointer-events: none;
}
.task-card {
  pointer-events: auto;
  position: relative;
  padding: 12px 13px 11px;
  border-radius: var(--r-md);
  border: 1px solid var(--stroke);
  background: var(--glass-fill);
  box-shadow: var(--edge-hl), var(--shadow-1), 0 22px 44px -34px var(--glow);
  -webkit-backdrop-filter: blur(20px) saturate(1.5); backdrop-filter: blur(20px) saturate(1.5);
  animation: rise var(--t-slow) var(--ease) backwards;
}
.task-card.error { border-color: color-mix(in srgb, var(--danger) 34%, transparent); }
.task-card.done { border-color: color-mix(in srgb, var(--success) 30%, transparent); }
.task-head { display: flex; align-items: flex-start; gap: 10px; }
.task-ico { display: inline-flex; align-items: center; justify-content: center; flex: none; width: 27px; height: 27px; border-radius: 10px; background: var(--accent-soft); color: var(--accent-ink); box-shadow: inset 0 1px 0 var(--highlight); }
.task-card.done .task-ico { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
.task-card.error .task-ico { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); }
.task-tx { flex: 1; min-width: 0; }
.task-tx b { display: block; color: var(--text-1); font-size: 12.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-tx small { display: block; margin-top: 3px; color: var(--text-3); font-size: 10.5px; line-height: 1.45; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-card.done .task-tx small { color: color-mix(in oklab, var(--success) 72%, var(--text-1)); }
.task-card.error .task-tx small { color: var(--danger); }
.task-x { display: inline-flex; align-items: center; justify-content: center; flex: none; width: 24px; height: 24px; border-radius: 8px; color: var(--text-3); transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.task-x:hover { background: var(--field-hover); color: var(--text-1); }
.task-bar { height: 5px; margin-top: 11px; overflow: hidden; border-radius: var(--r-pill); background: var(--track); }
.task-bar i { display: block; height: 100%; border-radius: inherit; background: var(--aurora-grad); transition: width var(--t-fast) var(--ease); }
.task-bar i.indet { animation: task-indet 1.15s var(--ease) infinite alternate; }
.task-bar i.fill-ok { width: 100%; background: linear-gradient(90deg, var(--success), color-mix(in oklab, var(--success) 55%, var(--accent-2))); }
.task-bar i.fill-err { width: 100%; background: color-mix(in srgb, var(--danger) 62%, transparent); }
@keyframes task-indet {
  from { transform: translateX(-110%); }
  to { transform: translateX(320%); }
}
.task-acts { display: flex; gap: 8px; margin-top: 11px; }
.task-btn { display: inline-flex; align-items: center; justify-content: center; flex: 1; gap: 6px; height: 30px; padding: 0 10px; border-radius: var(--r-pill); border: 1px solid var(--stroke); background: var(--field); color: var(--text-2); font-size: 11.5px; font-weight: 500; transition: background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.task-btn:hover { background: var(--field-hover); border-color: var(--stroke-strong); color: var(--text-1); }
.task-btn:active { transform: scale(.97); }
.task-btn.task-go { border-color: var(--accent-line); background: var(--accent-soft); color: var(--accent-ink); }
.task-btn.task-go:hover { background: color-mix(in srgb, var(--accent-1) 24%, transparent); }
.spin { animation: task-spin .9s linear infinite; }
@keyframes task-spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) {
  .task-dock { left: 12px; right: 12px; bottom: calc(70px + env(safe-area-inset-bottom)); width: auto; max-width: none; }
  .task-card { padding: 11px 12px 10px; -webkit-backdrop-filter: none; backdrop-filter: none; background: var(--surface-strong); }
}
</style>

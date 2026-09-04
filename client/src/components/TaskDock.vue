<script setup lang="ts">
import { Check, Images, LoaderCircle, RotateCcw, X } from 'lucide-vue-next'
import { dismissDownloadTask, retryDownloadTask, useDownloadTasks } from '../downloadTaskStore'
import { dismissSaveTask, retrySaveTask, useSaveTasks } from '../parseSaveStore'

/* ============================================================
   全局任务坞（左下角）：后台任务进度卡片
   保存到图片库（服务端导入）+ 本机下载（浏览器拉取）
   ============================================================ */
const emit = defineEmits<{ gotoLibrary: [] }>()
defineProps<{ hidden?: boolean }>()

const saveTasks = useSaveTasks()
const downloadTasks = useDownloadTasks()
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
.task-dock {
  position: fixed; left: 20px; bottom: 20px; z-index: 26;
  display: flex; flex-direction: column; gap: 9px;
  width: min(360px, calc(100vw - 32px));
  pointer-events: none;
}
.task-card {
  pointer-events: auto;
  border: 1px solid rgba(157, 171, 210, .2);
  border-radius: 13px;
  background: rgba(16, 22, 40, .97);
  box-shadow: 0 16px 44px rgba(0, 0, 0, .42);
  padding: 12px 13px 11px;
}
.task-card.error { border-color: rgba(248, 113, 113, .32); }
.task-card.done { border-color: rgba(74, 222, 128, .26); }
.task-head { display: flex; align-items: flex-start; gap: 10px; }
.task-ico {
  width: 26px; height: 26px; flex: none; border-radius: 8px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(167, 139, 250, .13); color: #b7a5ff;
}
.task-card.done .task-ico { background: rgba(74, 222, 128, .13); color: #6ee7a2; }
.task-card.error .task-ico { background: rgba(248, 113, 113, .13); color: #fb8294; }
.task-tx { flex: 1; min-width: 0; }
.task-tx b {
  display: block; font-size: 12px; font-weight: 500; color: #dfe4ef;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.task-tx small {
  display: block; margin-top: 3px; font-size: 10.5px; line-height: 1.45;
  color: #8b96ad; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.task-card.done .task-tx small { color: #7ce7a5; }
.task-card.error .task-tx small { color: #fda4af; }
.task-x {
  flex: none; width: 22px; height: 22px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; color: #6d7894;
}
.task-x:hover { background: rgba(255, 255, 255, .07); color: #cfd6e6; }
.task-bar {
  height: 5px; margin-top: 10px; border-radius: 6px;
  background: rgba(255, 255, 255, .07); overflow: hidden;
}
.task-bar i {
  display: block; height: 100%; border-radius: 6px;
  background: linear-gradient(90deg, #22d3ee, #a78bfa, #e15aa6);
  transition: width .18s ease;
}
.task-bar i.indet { animation: task-indet 1.15s ease-in-out infinite alternate; }
.task-bar i.fill-ok { width: 100%; background: linear-gradient(90deg, #34d399, #6ee7a2); }
.task-bar i.fill-err { width: 100%; background: rgba(248, 113, 113, .55); }
@keyframes task-indet {
  from { transform: translateX(-110%); }
  to { transform: translateX(320%); }
}
.task-acts { display: flex; gap: 8px; margin-top: 11px; }
.task-btn {
  flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 7px 10px; border-radius: 8px;
  background: rgba(255, 255, 255, .05); color: #aab3c8; font-size: 11px;
  transition: .16s;
}
.task-btn:hover { background: rgba(255, 255, 255, .1); color: #fff; }
.task-btn.task-go { background: rgba(167, 139, 250, .16); color: #cfc3ff; }
.task-btn.task-go:hover { background: rgba(167, 139, 250, .26); }
.spin { animation: task-spin .9s linear infinite; }
@keyframes task-spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) {
  .task-dock {
    left: 12px; right: 12px; bottom: calc(70px + env(safe-area-inset-bottom));
    width: auto; max-width: none;
  }
  .task-card { padding: 11px 12px 10px; }
}
</style>

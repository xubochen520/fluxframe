/* ============================================================
   保存到图片库 —— 后台导入任务（模块级单例，跨视图切换存活）
   客户端创建服务端导入任务并轮询进度：服务器直连片源流式写盘
   （不占手机流量、无浏览器并发限制），完成后自动入库带标签。
   支持取消（中止服务端下载）。
   ============================================================ */
import { reactive, ref } from 'vue'

export interface SaveFileOpts {
  /** 上游原始地址（解析返回的 media.src / coverSrc，非代理地址） */
  url: string
  /** 防盗链 Referer（媒体.referer） */
  ref?: string
  /** 入库名称 */
  name: string
  kind: 'video' | 'cover'
}

export interface SaveTaskOpts {
  name: string
  video: SaveFileOpts | null
  cover: SaveFileOpts | null
  /** 附带来源标签（如 抖音/哔哩哔哩），null 则不加 */
  platTag: string | null
}

export interface SaveTask {
  id: number
  state: 'working' | 'done' | 'error'
  /** 阶段文案：下载 xx / xx MB、导入中…、已保存 n 个… */
  stageText: string
  /** 当前阶段进度 0-1；unknown=true 时为不确定进度（滚动条） */
  pct: number
  unknown: boolean
  opts: SaveTaskOpts
}

const tasks = ref<SaveTask[]>([])
const controllers = new Map<number, AbortController>()
let nextId = 1

export function useSaveTasks() { return tasks }

/** 关闭/取消：中止服务端导入并从列表移除 */
export function dismissSaveTask(id: number) {
  controllers.get(id)?.abort()
  controllers.delete(id)
  tasks.value = tasks.value.filter((t) => t.id !== id)
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** 创建服务端导入任务 */
async function createImportJob(file: SaveFileOpts, platTag: string | null): Promise<string> {
  const res = await fetch('/api/parse/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: file.url,
      ref: file.ref || undefined,
      name: file.name,
      kind: file.kind,
      platTag: file.kind === 'video' ? platTag || undefined : undefined,
    }),
    credentials: 'include',
  })
  if (!res.ok) {
    let msg = `创建导入任务失败（HTTP ${res.status}）`
    try { const ej = await res.json(); if (ej?.message) msg = String(ej.message) } catch { /* 非 JSON */ }
    throw new Error(msg)
  }
  const json = await res.json()
  if (!json?.id) throw new Error('导入任务创建失败')
  return String(json.id)
}

/** 轮询一次任务状态；返回 null 表示任务消失 */
async function pollImportJob(id: string): Promise<{ status: string; progress: number | null; message: string; items: Array<{ id: string }>; duplicate: boolean } | null> {
  const res = await fetch(`/api/parse/import/${encodeURIComponent(id)}`, { credentials: 'include' })
  if (!res.ok) return null
  return await res.json()
}

/** 启动一个后台导入任务；立即返回任务 id */
export function startSaveTask(opts: SaveTaskOpts): number {
  /* 必须用 reactive 包装后再入数组：直接 push 普通对象并原地修改不会触发
     Vue 重渲染（组件读的是代理，改的是原对象），进度条会卡死 */
  const task = reactive<SaveTask>({
    id: nextId++,
    state: 'working',
    stageText: '准备中…',
    pct: 0,
    unknown: false,
    opts,
  })
  const ac = new AbortController()
  controllers.set(task.id, ac)
  tasks.value.push(task)
  void run(task, ac.signal)
  return task.id
}

async function run(task: SaveTask, signal: AbortSignal) {
  const files = [task.opts.video, task.opts.cover].filter((f): f is SaveFileOpts => !!f)
  let savedCount = 0
  let duplicate = false
  try {
    if (!files.length) throw new Error('没有可保存的内容')
    for (const file of files) {
      if (signal.aborted) return
      task.unknown = true
      task.pct = 0
      task.stageText = file.kind === 'video' ? '正在后台下载无水印视频…' : '正在后台下载封面…'
      const label = file.kind === 'video' ? '视频' : '封面'
      let jobId: string
      try {
        jobId = await createImportJob(file, task.opts.platTag)
      } catch (err: any) {
        throw new Error(`${label}导入启动失败：${err?.message || '网络错误'}`)
      }
      /* 轮询进度（服务端流式下载 → 查重 → 入库） */
      for (;;) {
        if (signal.aborted) {
          fetch(`/api/parse/import/${encodeURIComponent(jobId)}`, { method: 'DELETE', credentials: 'include' }).catch(() => undefined)
          return
        }
        await sleep(800)
        let job: Awaited<ReturnType<typeof pollImportJob>>
        try {
          job = await pollImportJob(jobId)
        } catch {
          continue /* 瞬时网络抖动继续轮询 */
        }
        if (!job) throw new Error(`${label}导入任务丢失，请重试`)
        if (job.status === 'error') throw new Error(`${label}导入失败：${job.message || '未知原因'}`)
        if (job.status === 'done') {
          task.pct = 1
          task.unknown = false
          savedCount += (job.items || []).length
          duplicate = duplicate || !!job.duplicate
          break
        }
        task.pct = typeof job.progress === 'number' ? job.progress : task.pct
        task.unknown = job.progress == null
        task.stageText = job.message || '导入中…'
      }
    }
    task.state = 'done'
    task.pct = 1
    task.unknown = false
    if (duplicate && !savedCount) {
      task.stageText = '已在图片库中（内容相同，自动去重）'
    } else {
      const tagHint = task.opts.platTag ? ` · 已附「${task.opts.platTag}」` : ''
      task.stageText = `已保存 ${savedCount} 个文件到图片库 ✓（视频自动带「视频」标签${tagHint}）`
    }
  } catch (err: any) {
    if (signal.aborted) return
    task.state = 'error'
    task.stageText = err && err.message ? String(err.message) : '保存失败，请重试'
  } finally {
    controllers.delete(task.id)
  }
}

/** 重试：以原参数重新入队（旧任务移除） */
export function retrySaveTask(id: number) {
  const old = tasks.value.find((t) => t.id === id)
  if (!old) return
  dismissSaveTask(id)
  startSaveTask(old.opts)
}

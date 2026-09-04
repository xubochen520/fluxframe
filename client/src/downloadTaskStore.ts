/* ============================================================
   本地下载任务（模块级单例）：左下角任务坞显示真实字节进度
   拉取完成后触发浏览器保存到本机下载目录
   ============================================================ */
import { reactive, ref } from 'vue'

export interface DownloadOpts {
  url: string
  /** 保存文件名（含扩展名） */
  filename: string
  /** 已知大小（可选，无 Content-Length 时估算显示） */
  size?: number
}

export interface DownloadTask {
  id: number
  state: 'working' | 'done' | 'error'
  stageText: string
  pct: number
  unknown: boolean
  opts: DownloadOpts
}

const tasks = ref<DownloadTask[]>([])
const controllers = new Map<number, AbortController>()
let nextId = 1

export function useDownloadTasks() { return tasks }

export function dismissDownloadTask(id: number) {
  controllers.get(id)?.abort()
  controllers.delete(id)
  tasks.value = tasks.value.filter((t) => t.id !== id)
}

function fmtSize(b: number) {
  if (!b || b <= 0) return '0 B'
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1048576 * 1024) return (b / 1048576).toFixed(2) + ' MB'
  return (b / 1073741824).toFixed(2) + ' GB'
}

/** 触发浏览器保存（拉取完成后的本地文件） */
function saveBlobToDisk(blob: Blob, filename: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(a.href), 10_000)
}

/** 启动下载；立即返回任务 id */
export function startDownload(opts: DownloadOpts): number {
  const task = reactive<DownloadTask>({
    id: nextId++,
    state: 'working',
    stageText: '准备下载…',
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

async function run(task: DownloadTask, signal: AbortSignal) {
  try {
    const res = await fetch(task.opts.url, { credentials: 'include', signal })
    if (!res.ok) throw new Error(`下载失败（HTTP ${res.status}）`)
    const total = Number(res.headers.get('content-length')) || task.opts.size || 0
    const body = res.body
    if (!body) throw new Error('内容不可用')
    const reader = body.getReader()
    const chunks: Array<Uint8Array<ArrayBuffer>> = []
    let received = 0
    task.stageText = '下载中…'
    for (;;) {
      if (signal.aborted) return
      const { done, value } = await reader.read()
      if (done) break
      if (value && value.length) {
        chunks.push(value as unknown as Uint8Array<ArrayBuffer>)
        received += value.length
        task.unknown = !total
        task.pct = total ? received / total : 0
        const shown = total || received
        task.stageText = `下载中 ${fmtSize(received)}${total ? ' / ' + fmtSize(shown) : ''}`
      }
    }
    if (signal.aborted) return
    const blob = new Blob(chunks, { type: res.headers.get('content-type') || '' })
    task.state = 'done'
    task.pct = 1
    task.unknown = false
    task.stageText = `已下载「${task.opts.filename}」· 保存到本机下载目录`
    saveBlobToDisk(blob, task.opts.filename)
  } catch (err: any) {
    if (signal.aborted) return
    task.state = 'error'
    task.stageText = err && err.message ? String(err.message) : '下载失败，请重试'
  } finally {
    controllers.delete(task.id)
  }
}

export function retryDownloadTask(id: number) {
  const old = tasks.value.find((t) => t.id === id)
  if (!old) return
  dismissDownloadTask(id)
  startDownload(old.opts)
}

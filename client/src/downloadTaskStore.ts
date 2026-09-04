/* ============================================================
   本地下载任务（模块级单例）：左下角任务坞显示真实字节进度
   · 桌面浏览器：拉取为 blob 后触发浏览器保存到本机下载目录
   · APK 手机端：交给 Android 系统 DownloadManager 内网直连下载，
     直接落盘到手机「下载」文件夹（WebView 无法触发系统保存）
   ============================================================ */
import { reactive, ref } from 'vue'
import { isNativeAndroid, NativeDownloads, prepareNativeDownload } from './nativeDownload'

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
/** 原生任务：任务 id → 系统下载器请求 id */
const nativeIds = new Map<number, string>()
let nextId = 1

export function useDownloadTasks() { return tasks }

export function dismissDownloadTask(id: number) {
  controllers.get(id)?.abort() // 原生任务的取消由 runNative 的 abort 监听器执行
  controllers.delete(id)
  tasks.value = tasks.value.filter((t) => t.id !== id)
}

function fmtSize(b: number) {
  if (!b || b <= 0) return '0 B'
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1048576 * 1024) return (b / 1048576).toFixed(2) + ' MB'
  return (b / 1073741824).toFixed(2) + ' GB'
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

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
  if (isNativeAndroid) {
    await runNative(task, signal)
    return
  }
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

/** APK 原生路径：系统 DownloadManager 直连内网服务器落盘（不经页面内存） */
async function runNative(task: DownloadTask, signal: AbortSignal) {
  let nativeId: string | undefined
  const onAbort = () => {
    if (nativeId) void NativeDownloads.cancel({ id: nativeId }).catch(() => undefined)
  }
  signal.addEventListener('abort', onAbort)
  try {
    task.stageText = '正在建立内网直连…'
    const { url, cookie } = await prepareNativeDownload(task.opts.url)
    if (signal.aborted) return
    const started = await NativeDownloads.start({ url, filename: task.opts.filename, cookie })
    nativeId = started.id
    nativeIds.set(task.id, nativeId)
    if (signal.aborted) { onAbort(); return }
    task.stageText = '系统下载器下载中…'
    for (;;) {
      if (signal.aborted) return
      const p = await NativeDownloads.progress({ id: nativeId })
      if (p.status === 'successful') break
      if (p.status === 'failed') throw new Error(`下载失败：${p.message || '系统错误，请重试'}`)
      if (p.status === 'gone') throw new Error('下载任务已被系统移除，请重试')
      const totalKnown = Number(p.total) > 0
      task.pct = totalKnown ? Math.min(1, Number(p.downloaded) / Number(p.total)) : 0
      task.unknown = !totalKnown
      task.stageText = totalKnown
        ? `下载中 ${fmtSize(Number(p.downloaded))} / ${fmtSize(Number(p.total))}`
        : `下载中 ${fmtSize(Number(p.downloaded))}`
      await sleep(800)
      if (signal.aborted) return
    }
    if (signal.aborted) return
    task.state = 'done'
    task.pct = 1
    task.unknown = false
    task.stageText = '已保存到手机「下载」文件夹'
  } catch (err: any) {
    if (signal.aborted) return
    task.state = 'error'
    task.stageText = err && err.message ? String(err.message) : '下载失败，请重试'
  } finally {
    nativeIds.delete(task.id)
    signal.removeEventListener('abort', onAbort)
    controllers.delete(task.id)
  }
}

export function retryDownloadTask(id: number) {
  const old = tasks.value.find((t) => t.id === id)
  if (!old) return
  dismissDownloadTask(id)
  startDownload(old.opts)
}

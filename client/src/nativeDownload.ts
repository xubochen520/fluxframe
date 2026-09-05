/* ============================================================
   APK 原生下载桥（系统 DownloadManager + MediaStore）
   WebView 里 blob → a.download 不会触发系统保存，无法落盘；
   改为把“内网直链地址 + 会话 Cookie 头”交给原生下载器：
   手机直连电脑服务器拉取（不走页面内存中转），原生侧先申请
   存储权限（Android 9-）或走 MediaStore（Android 10+），
   先由系统 DownloadManager 下载到应用专属临时目录，再由 MediaStore 原子发布到图库；
   只有媒体库回查确认成功后才返回 successful，并发送系统通知。
   桌面浏览器不经过此桥，仍走原 blob 保存逻辑。
   ============================================================ */
import { Capacitor, registerPlugin } from '@capacitor/core'

interface NativeDownloadsApi {
  start(opts: { url: string; filename: string; cookie: string }): Promise<{ id: string }>
  progress(opts: { id: string }): Promise<{ status: string; downloaded: number; total: number; message?: string; path?: string }>
  cancel(opts: { id: string }): Promise<void>
  openFile(opts: { id: string }): Promise<void>
}

interface RemoteNativeDownloadsApi {
  start(url: string, filename: string, cookie: string): string
  progress(id: string): string
  cancel(id: string): string
  openFile(id: string): string
}

declare global {
  interface Window {
    FluxframeNativeDownloads?: RemoteNativeDownloadsApi
  }
}

const capacitorNativeDownloads = registerPlugin<NativeDownloadsApi>('NativeDownloads')

function remoteBridge(): RemoteNativeDownloadsApi | undefined {
  return typeof window !== 'undefined' ? window.FluxframeNativeDownloads : undefined
}

function parseBridgeResult(raw: string): Record<string, any> {
  let result: Record<string, any>
  try {
    result = JSON.parse(raw || '{}') as Record<string, any>
  } catch {
    throw new Error('原生下载桥返回了无效结果，请重启 App')
  }
  if (result.error) throw new Error(String(result.error))
  return result
}

/** 同时兼容 Capacitor 本地页面和 MainActivity 加载的远程电脑端页面。 */
export const NativeDownloads: NativeDownloadsApi = {
  async start(opts) {
    const bridge = remoteBridge()
    if (bridge) {
      const result = parseBridgeResult(bridge.start(opts.url, opts.filename, opts.cookie))
      return { id: String(result.id || '') }
    }
    return capacitorNativeDownloads.start(opts)
  },
  async progress(opts) {
    const bridge = remoteBridge()
    if (bridge) {
      const result = parseBridgeResult(bridge.progress(opts.id))
      return {
        status: String(result.status || 'gone'),
        downloaded: Number(result.downloaded) || 0,
        total: typeof result.total === 'number' ? result.total : Number(result.total) || -1,
        ...(result.message ? { message: String(result.message) } : {}),
        ...(result.path ? { path: String(result.path) } : {}),
      }
    }
    return capacitorNativeDownloads.progress(opts)
  },
  async cancel(opts) {
    const bridge = remoteBridge()
    if (bridge) {
      parseBridgeResult(bridge.cancel(opts.id))
      return
    }
    return capacitorNativeDownloads.cancel(opts)
  },
  async openFile(opts) {
    const bridge = remoteBridge()
    if (bridge) {
      parseBridgeResult(bridge.openFile(opts.id))
      return
    }
    return capacitorNativeDownloads.openFile(opts)
  },
}

/** 是否运行在 APK（Capacitor 原生壳）里 */
export const isNativeAndroid = Capacitor.isNativePlatform() || Boolean(remoteBridge())

let sessionToken = ''
/** 登录态在 httpOnly Cookie 中 JS 读不到 → 向服务端换取一次令牌（每次下载前重取，保证最新） */
async function ensureSessionToken(): Promise<string> {
  if (sessionToken) return sessionToken
  const res = await fetch('/api/download/session', { credentials: 'include' })
  if (!res.ok) throw new Error(`获取下载会话失败（HTTP ${res.status}）`)
  const data = (await res.json()) as { token?: string }
  if (!data.token) throw new Error('登录状态已失效，请在应用内重新登录')
  sessionToken = data.token
  return sessionToken
}

/** 生成系统下载器可用的绝对地址与 Cookie 头（相对路径 → 当前服务器绝对地址） */
export async function prepareNativeDownload(url: string): Promise<{ url: string; cookie: string }> {
  const absUrl = new URL(url, window.location.origin).href
  const token = await ensureSessionToken()
  return { url: absUrl, cookie: `fluxframe_session=${token}` }
}

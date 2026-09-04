/* ============================================================
   APK 原生下载桥（Android 系统 DownloadManager）
   WebView 里 blob → a.download 不会触发系统下载，无法落盘；
   改为把“内网直链地址 + 会话 Cookie 头”交给系统下载器：
   手机直连电脑服务器拉取（不走页面内存中转），落盘到系统
   「下载」文件夹，状态栏有系统级进度/完成通知，速度最快。
   桌面浏览器不经过此桥，仍走原 blob 保存逻辑。
   ============================================================ */
import { Capacitor, registerPlugin } from '@capacitor/core'

interface NativeDownloadsApi {
  start(opts: { url: string; filename: string; cookie: string }): Promise<{ id: string }>
  progress(opts: { id: string }): Promise<{ status: string; downloaded: number; total: number; message?: string }>
  cancel(opts: { id: string }): Promise<void>
}

export const NativeDownloads = registerPlugin<NativeDownloadsApi>('NativeDownloads')

/** 是否运行在 APK（Capacitor 原生壳）里 */
export const isNativeAndroid = Capacitor.isNativePlatform()

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

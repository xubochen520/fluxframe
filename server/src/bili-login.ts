// B站官方扫码登录（passport 接口）：生成二维码 → 用户用 B站 App 扫码 →
// 服务端轮询确认并从响应 Set-Cookie 中捕获 SESSDATA（参考 Mineradio 的
// “官方扫码登录优先、手动粘贴 Cookie 兜底”模式；本项目用于 B站高清保存的登录态）。
import QRCode from 'qrcode'

const UA_PC = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36'
const GENERATE_URL = 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate'
const POLL_URL = 'https://passport.bilibili.com/x/passport-login/web/qrcode/poll'

/** 会话内共享的浏览器 Cookie（warmup 的 buvid3 等），多请求间透传 */
let sessionCookie = ''

function setCookieFrom(res: Response): string {
  let cookie = sessionCookie
  const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : []
  for (const raw of list) {
    const pair = raw.split(';')[0]
    const name = pair.split('=')[0].trim()
    if (!name || /^(expires|path|domain|max-age|samesite|priority)$/i.test(name)) continue
    if (cookie) cookie += '; '
    cookie += pair
  }
  sessionCookie = cookie
  return cookie
}

/** 预热：先访问一次 bilibili.com 拿 buvid3 等基础 Cookie（扫码接口要求） */
async function warmup() {
  if (/buvid3=/.test(sessionCookie)) return
  try {
    const res = await fetch('https://www.bilibili.com/', {
      headers: { 'User-Agent': UA_PC, Accept: '*/*', 'Accept-Language': 'zh-CN,zh;q=0.9' },
      redirect: 'follow',
      signal: AbortSignal.timeout(15000),
    })
    setCookieFrom(res)
  } catch { /* warmup 失败不致命，继续尝试 */ }
}

function baseHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'User-Agent': UA_PC,
    Referer: 'https://www.bilibili.com/',
    Accept: 'application/json, text/plain, */*',
    'Content-Type': 'application/x-www-form-urlencoded',
  }
  if (sessionCookie) headers.Cookie = sessionCookie
  return headers
}

export interface BiliQrCreateResult { ok: boolean; error?: string; qrcodeKey?: string; image?: string }

/** 生成登录二维码（返回前端可直接显示的 PNG dataURL）。注意：passport 的 generate 现为 GET。 */
export async function biliQrCreate(): Promise<BiliQrCreateResult> {
  try {
    await warmup()
    const res = await fetch(GENERATE_URL, {
      method: 'GET',
      headers: baseHeaders(),
      signal: AbortSignal.timeout(15000),
    })
    setCookieFrom(res)
    const json: any = await res.json().catch(() => null)
    if (!json || json.code !== 0 || !json.data?.qrcode_key) {
      return { ok: false, error: json?.message || `二维码接口返回 code=${json?.code ?? '?'}` }
    }
    const qrcodeKey = String(json.data.qrcode_key)
    const url = String(json.data.url || '')
    const image = await QRCode.toDataURL(url, { margin: 1, width: 260, color: { dark: '#10162a', light: '#ffffff' } })
    return { ok: true, qrcodeKey, image }
  } catch (error: any) {
    return { ok: false, error: error?.message || '生成二维码失败' }
  }
}

export interface BiliQrPollResult {
  ok: boolean
  status: 'waiting' | 'scanned' | 'expired' | 'ok' | 'error'
  error?: string
  sessdata?: string
  nickname?: string
}

/** 轮询扫码状态；确认成功后捕获 SESSDATA（可附带昵称用于确认账号）。注意：poll 现为 GET。 */
export async function biliQrPoll(qrcodeKey: string): Promise<BiliQrPollResult> {
  try {
    const res = await fetch(`${POLL_URL}?qrcode_key=${encodeURIComponent(qrcodeKey)}`, {
      method: 'GET',
      headers: baseHeaders(),
      signal: AbortSignal.timeout(15000),
    })
    const cookie = setCookieFrom(res)
    const json: any = await res.json().catch(() => null)
    /* 外层 code=0 只代表请求成功，登录状态在内层 data.code：
       0=成功 86101=未扫码 86090=已扫码待确认 86038=已过期 */
    const code = Number(json?.data?.code ?? -1)
    if (code === 0) {
      /* 成功：从 Set-Cookie 捕获 SESSDATA */
      const m = /(?:^|;\s*)SESSDATA=([^;\s]+)/i.exec(cookie || '')
      if (!m) return { ok: false, status: 'error', error: '登录成功但未捕获到 SESSDATA，请稍后重试或手动粘贴 Cookie' }
      const sessdata = m[1]
      /* 尽力取账号昵称，用于界面确认登录的是哪个账号 */
      let nickname = ''
      try {
        const nav = await fetch('https://api.bilibili.com/x/web-interface/nav', {
          headers: { 'User-Agent': UA_PC, Referer: 'https://www.bilibili.com/', Cookie: `SESSDATA=${sessdata}` },
          signal: AbortSignal.timeout(10000),
        })
        const navJson: any = await nav.json().catch(() => null)
        if (navJson?.code === 0 && navJson.data?.uname) nickname = String(navJson.data.uname)
      } catch { /* 昵称为可选信息 */ }
      return { ok: true, status: 'ok', sessdata, nickname }
    }
    if (code === 86038) return { ok: true, status: 'expired', error: '二维码已过期，请刷新' }
    if (code === 86090) return { ok: true, status: 'scanned' }
    if (code === 86101) return { ok: true, status: 'waiting' }
    return { ok: false, status: 'error', error: json?.message || `登录轮询返回 code=${code}` }
  } catch (error: any) {
    return { ok: false, status: 'error', error: error?.message || '轮询登录状态失败' }
  }
}

/** 从用户粘贴的文本中提取 SESSDATA（支持纯 SESSDATA 值或完整 Cookie 头） */
export function extractSessdata(raw: string): string {
  const input = String(raw || '').trim()
  const m = /(?:^|;\s*)SESSDATA=([^;\s]+)/i.exec(input)
  return m ? m[1].trim() : input
}

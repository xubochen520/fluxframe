/* ============================================================
   视频解析引擎（纯享解析 PureParse）—— 内嵌进 fluxframe Fastify 服务
   ------------------------------------------------------------
   · POST /api/parse    真实解析：服务端直连平台官方接口（绕过浏览器 CORS）
                        B站/b23.tv  → 官方开放 API 全链真实解析
                        抖音        → 网页版官方 API + a_bogus 动态签名直连
                                      （开源签名算法 server/dyab，附 ttwid/uifid 会话，风控自动重试）
                        快手        → H5 分享页详情接口直解（免签名免 Cookie，2025 改版后
                                      手机页已无内嵌数据，页面直链/短链均可）
   · GET  /api/stream   媒体代理流：透传 UA/Referer/Range，供播放与下载（防防盗链+CORS）
   · GET  /api/ping     健康检查（解析页据此自动切换到真实解析模式）
   与图片库同源（4311），前端静态页位于 client/public/parse/（/parse/），
   解析结果可一键「保存到图片库」。
   ============================================================ */
import { Readable } from 'node:stream'
import type { FastifyInstance } from 'fastify'
import { signDouyin, signerReady, signerError, DY_UA } from '../dyab/index.mjs'

const UA_PC = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36'
const UA_MOBILE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'
/* 宿主注入（index.ts）：B站登录 Cookie、ffmpeg 定位，供 B站高清 DASH 解析/保存使用 */
export interface ParseServerDeps {
  getBiliSession?: () => Promise<string>
  findFfmpeg?: () => Promise<string | null>
}
let deps: ParseServerDeps = {}
const jget = async (url: string, headers: Record<string, string> = {}, timeout = 10000) => {
  const res = await fetch(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(timeout) })
  const body = await res.arrayBuffer()
  return { status: res.status, finalUrl: res.url, buf: Buffer.from(body) }
}

/* ============================================================
   A. 平台解析器
   ============================================================ */
function detectPlatform(url: string) {
  try {
    const host = new URL(url).hostname.toLowerCase()
    if (/bilibili\.com|b23\.tv|bilibili\.tv/.test(host)) return 'bilibili'
    if (/douyin\.com|iesdouyin\.com/.test(host)) return 'douyin'
    if (/kuaishou\.com|chenzhongtech\.com|gifshow\.com/.test(host)) return 'kuaishou'
    if (/xiaohongshu\.com|xhslink\.com/.test(host)) return 'xiaohongshu'
    if (/weibo\.(com|cn)/.test(host)) return 'weibo'
    if (/ixigua\.com/.test(host)) return 'xigua'
    return null
  } catch {
    return null
  }
}

const QUALITY_NAME: Record<number, string> = { 127: '8K', 126: '杜比', 125: 'HDR', 120: '4K', 116: '1080P60', 112: '1080P+', 80: '1080P', 74: '720P60', 64: '720P', 32: '480P', 16: '360P', 6: '240P' }
const fail = (msg: string) => ({ ok: false as const, msg })

/* ---------- B站 / b23.tv（官方开放接口，全链真实） ----------
   清晰度说明（实测）：B 站对未登录游客封顶 720P，任何更高档位请求都会被降级；
   且 1080P 及以上只有 DASH 分离流（音视频分开）。因此：
   · 预览/常规保存：mp4 单文件直链（游客 720P，登录后老视频可能拿到更高 mp4 档）
   · 高清保存：登录 Cookie（SESSDATA）存在且本机有 ffmpeg 时，额外取 DASH 最高档
     （普通账号 1080P，大会员 1080P+/4K），由服务端下载双流并用 ffmpeg 无损合并 */
async function resolveBilibili(url: string) {
  const [biliSession, ffmpegExe] = await Promise.all([deps.getBiliSession ? deps.getBiliSession() : Promise.resolve(String(process.env.BILI_SESSDATA || '')), deps.findFfmpeg ? deps.findFfmpeg() : Promise.resolve(null)])
  const sessdata = biliSession.trim()
  const cookieHeaders: Record<string, string> = { 'User-Agent': UA_PC, Referer: 'https://www.bilibili.com/' }
  if (sessdata) cookieHeaders.Cookie = `SESSDATA=${sessdata}`
  const view = await jget('https://api.bilibili.com/x/web-interface/view?bvid=' + (url.match(/BV[0-9A-Za-z]{10}/) || [])[0], { 'User-Agent': UA_PC, Referer: 'https://www.bilibili.com/' })
  let vj: any
  try {
    vj = JSON.parse(view.buf.toString())
  } catch {
    return fail('B站接口响应异常')
  }
  if (!vj || vj.code !== 0) return fail(vj?.message === '请求被拦截' ? 'B站风控拦截（请求过频），稍后再试' : (vj?.message || 'B站视频不存在或不可解析（番剧/需登录内容）'))
  const d = vj.data
  const stat = d.stat || {}

  /* 播放地址（mp4 单文件）：从高到低请求实际可得档位（游客会被服务端降到 720P） */
  let quality = 0, label = '', durl: any = null
  for (const qn of [116, 80, 64, 32, 16]) {
    const pj = await jget(`https://api.bilibili.com/x/player/playurl?bvid=${d.bvid}&cid=${d.cid}&qn=${qn}&fnval=0&fnver=0&fourk=0`, cookieHeaders)
    try {
      const p = JSON.parse(pj.buf.toString())
      if (p.code === 0 && p.data?.durl?.[0]?.url) {
        quality = p.data.quality || qn
        durl = p.data.durl[0]
        break
      }
    } catch {
      /* 下一档 */
    }
  }
  if (!durl) return fail('未获取到可播放的清晰度（该视频可能需登录或为互动视频）')

  /* 高清候选（DASH 分离流）：仅登录 + ffmpeg 就绪时附加，供「保存到图片库」自动使用 */
  let biliHigh: { videoUrl: string; audioUrl: string; quality: number; label: string } | undefined
  if (sessdata && ffmpegExe) {
    try {
      const pj = await jget(`https://api.bilibili.com/x/player/playurl?bvid=${d.bvid}&cid=${d.cid}&qn=116&fnval=16&fnver=0&fourk=1`, cookieHeaders)
      const p = JSON.parse(pj.buf.toString())
      if (p.code === 0 && p.data?.dash && p.data.quality >= 80) {
        const dash = p.data.dash
        const videos = Array.isArray(dash.video) ? dash.video : []
        const audios = Array.isArray(dash.audio) ? dash.audio : []
        const pickUrl = (stream: any) => String(stream?.baseUrl || stream?.backup_url?.[0] || '').replace(/^http:\/\//i, 'https://')
        const video = videos.find((item: any) => Number(item.id) === Number(p.data.quality)) || videos[0]
        const audio = audios[0]
        if (video && audio && pickUrl(video) && pickUrl(audio)) {
          biliHigh = {
            videoUrl: pickUrl(video),
            audioUrl: pickUrl(audio),
            quality: Number(p.data.quality) || 80,
            label: QUALITY_NAME[p.data.quality] || `${p.data.quality}P`,
          }
        }
      }
    } catch { /* 高清候选失败不影响主流程 */ }
  }

  return {
    platform: 'bilibili',
    title: d.title || '未命名视频',
    author: { name: d.owner?.name || '未知UP主', handle: '', verified: false, tag: 'B站 UP 主' },
    stats: { like: stat.like || 0, comment: stat.reply || 0, share: stat.share || 0, view: stat.view || 0 },
    cover: d.pic || '',
    qualityLabel: QUALITY_NAME[quality] || `${quality}P`,
    duration: Math.round(d.duration || 0),
    watermarkFree: true,
    /* high 高清档信息：解析页「保存到图片库」时随任务提交，服务端下载双流 + ffmpeg 合并 */
    high: biliHigh,
    media: [{
      url: durl.url,            // 上游无水印直链（展示用）
      width: 0, height: 0,      // B站流不直接返回宽高
      duration: Math.round(d.duration || 0),
      size: durl.size || 0,
      type: 'mp4',
      referer: 'https://www.bilibili.com/',
    }],
  }
}

/* ---------- 抖音：网页版官方 API + a_bogus 签名直连 ----------
   机制说明（实测于 2025-09，样本 aweme 7681199202382269706）：
   · 官方详情接口 https://www.douyin.com/aweme/v1/web/aweme/detail/
     需 a_bogus 签名（开源重构算法 server/dyab，Apache-2.0，V1.0.1.19-fix.01）
   · 需会话 cookie：ttwid（ttwid.bytedance.com 注册接口真实签发）
     + uifid（设备指纹位，Argus 风控要求存在；取与真实样本同格式的
     384 位 hex 随机值，风控放行概率高）
   · 返回 play_addr.url_list 即官方播放无水印直链（下载版才烧录水印）
   · Argus 风控按请求频率动态收紧 → 每次尝试更换 uifid 并自动重试 */
const DY_REF = 'https://www.douyin.com/'
const DY_DETAIL_API = 'https://www.douyin.com/aweme/v1/web/aweme/detail/'
const DY_TTWID_API = 'https://ttwid.bytedance.com/ttwid/union/register/'
let dySession: { ttwid: string; at: number } = { ttwid: '', at: 0 }

function dyParams(awemeId: string) {
  /* 参数顺序与浏览器请求一致（a_bogus 对查询串逐字签名） */
  return {
    device_platform: 'webapp',
    aid: '6383',
    channel: 'channel_pc_web',
    pc_client_type: '1',
    pc_libra_divert: 'Windows',
    version_code: '170400',
    version_name: '17.4.0',
    cookie_enabled: 'true',
    screen_width: '2560',
    screen_height: '1440',
    browser_language: 'zh-CN',
    browser_platform: 'Win32',
    browser_name: 'Chrome',
    browser_version: '135.0.0.0',
    browser_online: 'true',
    engine_name: 'Blink',
    engine_version: '135.0.0.0',
    os_name: 'Windows',
    os_version: '10',
    cpu_core_num: '20',
    device_memory: '8',
    platform: 'PC',
    downlink: '0.55',
    effective_type: '3g',
    round_trip_time: '500',
    aweme_id: String(awemeId),
  }
}
const dyQs = (p: Record<string, string>) => Object.entries(p).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
const dyUifid = () => [...crypto.getRandomValues(new Uint8Array(192))].map((b) => b.toString(16).padStart(2, '0')).join('')

/** 注册/续期 ttwid（有效约一年，缓存复用） */
async function ensureDyTtwid(force = false) {
  if (!force && dySession.ttwid && Date.now() - dySession.at < 6 * 3600e3) return dySession.ttwid
  const res = await fetch(DY_TTWID_API, {
    method: 'POST',
    headers: {
      'User-Agent': DY_UA,
      'Content-Type': 'application/json',
      Origin: 'https://www.douyin.com',
      Referer: DY_REF,
      Accept: 'application/json, text/plain, */*',
    },
    body: JSON.stringify({ region: 'cn', aid: 1768, needFid: false, service: 'www.ixigua.com', migrate_info: { ticket: '', source: 'node' }, cbUrlProtocol: 'https', union: true }),
    signal: AbortSignal.timeout(10000),
  })
  const cookies = res.headers.getSetCookie ? res.headers.getSetCookie() : []
  const ttwid = cookies.map((c) => c.split(';')[0]).find((c) => c.startsWith('ttwid='))?.slice(6) || ''
  if (!ttwid) throw new Error('ttwid 注册失败')
  dySession = { ttwid, at: Date.now() }
  return ttwid
}

/** 展开分享短链 / 提取 aweme_id */
async function extractDouyinId(url: string) {
  const m = String(url).match(/\/video\/(\d{10,25})/) || String(url).match(/\/share\/video\/(\d{10,25})/) || String(url).match(/\/note\/(\d{10,25})/)
  if (m) return m[1]
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': DY_UA, Accept: '*/*', 'Accept-Language': 'zh-CN,zh;q=0.9' },
      redirect: 'follow',
      signal: AbortSignal.timeout(10000),
    })
    const fin = res.url || ''
    const m2 = fin.match(/\/video\/(\d{10,25})/) || fin.match(/\/share\/video\/(\d{10,25})/) || fin.match(/\/note\/(\d{10,25})/) || fin.match(/aweme_id=(\d{10,25})/)
    return m2 ? m2[1] : ''
  } catch {
    return ''
  }
}

/** 单次签名详情请求；返回 { json, blocked, empty } */
async function dyDetailOnce(awemeId: string, ttwid: string) {
  const q = dyQs(dyParams(awemeId))
  const ab = signDouyin(q)
  if (!ab) return { blocked: true, reason: '签名生成失败' }
  const res = await fetch(`${DY_DETAIL_API}?${q}`, {
    headers: {
      'User-Agent': DY_UA,
      Accept: 'application/json, text/plain, */*',
      Referer: DY_REF,
      'a-bogus': ab,
      Cookie: `ttwid=${ttwid}; uifid=${dyUifid()}`,
    },
    signal: AbortSignal.timeout(12000),
  })
  const text = await res.text()
  if (res.status === 403 || /Argus|Blocked|Uifid|Signature/i.test(text.slice(0, 120))) return { blocked: true, reason: text.slice(0, 100) }
  if (!text.trim()) return { empty: true }
  try {
    return { json: JSON.parse(text) }
  } catch {
    return { empty: true }
  }
}

/** 验证直链可播并取真实总大小 */
async function probeDyMedia(url: string) {
  for (let i = 0; i < 3; i++) {
    try {
      const res = await fetch(url, {
        headers: { 'User-Agent': DY_UA, Referer: DY_REF, Range: 'bytes=0-0', Accept: '*/*' },
        redirect: 'follow',
        signal: AbortSignal.timeout(12000),
      })
      const ct = res.headers.get('content-type') || ''
      const cr = res.headers.get('content-range') || ''
      const cl = res.headers.get('content-length')
      if ((res.status === 200 || res.status === 206) && /video|octet-stream/i.test(ct)) {
        const total = cr ? Number(cr.split('/')[1]) || 0 : Number(cl) || 0
        return { ok: true, type: ct, size: total }
      }
      await new Promise((r) => setTimeout(r, 600))
    } catch {
      await new Promise((r) => setTimeout(r, 600))
    }
  }
  return { ok: false }
}

async function resolveDouyin(url: string) {
  if (!signerReady()) return fail('抖音签名器加载失败：' + (signerError() || '未知错误'))

  const awemeId = await extractDouyinId(url)
  if (!awemeId) return fail('未能识别抖音视频 ID（短链展开失败）')

  /* 签名请求 + 风控重试（每次新 uifid/新签名，间隔抖动） */
  let detail: any = null
  let lastReason = ''
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const ttwid = await ensureDyTtwid(false)
      const r: any = await dyDetailOnce(awemeId, ttwid)
      if (r.json?.aweme_detail) {
        detail = r.json.aweme_detail
        break
      }
      if (r.blocked) lastReason = r.reason || '被风控拦截'
      if (attempt === 1 && r.blocked) {
        try {
          await ensureDyTtwid(true)
        } catch {
          /* 沿用旧会话 */
        }
      }
      if (r.json?.status_code != null) lastReason = `status_code=${r.json.status_code}`
    } catch (e: any) {
      lastReason = e.message || '网络错误'
    }
    if (attempt < 3) await new Promise((r) => setTimeout(r, 800 + Math.random() * 900))
  }
  if (!detail) {
    const why = lastReason || '接口未返回数据'
    return fail(`抖音暂未放行（${why}）——已内置开源 a_bogus 签名并自动重试 3 次，多为平台风控限流，10~30 秒后重试通常可恢复；也可用演示预览体验完整流程`)
  }

  const v = detail.video
  if (!v?.play_addr?.url_list?.length) return fail('该抖音作品为图文/其他类型，不含可提取的视频流')

  /* 播放直链选择：官方按风控动态下发放流档位（play_addr 可能是 480P/1080P 等），
     bit_rate[] 内含全档位（4K/2K/1080P…）。从最高档开始探测可播源，取实际最优档 */
  const urlOf = (u: string) => (/^https?:/i.test(u) ? u : '')
  const gears = (v.bit_rate || []).slice().sort((a: any, b: any) => ((b.play_addr?.height || 0) - (a.play_addr?.height || 0)) || ((b.bit_rate || 0) - (a.bit_rate || 0)))
  const cands: Array<{ url: string; w: number; h: number; size: number; gear: string }> = []
  for (const g of gears) {
    const pa = g?.play_addr
    if (!pa) continue
    for (const u of pa.url_list || []) {
      const s = urlOf(u)
      if (s) cands.push({ url: s, w: pa.width || 0, h: pa.height || 0, size: pa.data_size || 0, gear: g.gear_name || '' })
    }
    if (cands.length >= 6) break // 探测预算：最多 6 条
  }
  for (const u of v.play_addr.url_list || []) {
    const s = urlOf(u)
    if (s) cands.push({ url: s, w: v.play_addr.width || v.width || 0, h: v.play_addr.height || v.height || 0, size: v.play_addr.data_size || 0, gear: 'play_addr' })
  }

  let chosen: any = null
  for (const c of cands) {
    const probe = await probeDyMedia(c.url)
    if (probe.ok) {
      chosen = { ...c, probe }
      break
    }
  }
  if (!chosen) return fail('抖音视频流验证失败（直链不可达，多为临时限流），请稍后重试')

  const author = detail.author || {}
  /* 尺寸/体积以实际选中档位为准（避免母版 4K 与下发 1080P 不一致的误导） */
  const w = Number(chosen.w) || 0
  const h = Number(chosen.h) || 0
  const hh = h
  const qLabel = hh >= 2000 ? '4K' : hh >= 1400 ? '2K' : hh >= 1000 ? '1080P' : hh >= 700 ? '720P' : hh >= 480 ? '480P' : hh ? `${hh}P` : '高清'
  const stats = detail.statistics || {}

  return {
    platform: 'douyin',
    pageUrl: `https://www.douyin.com/video/${awemeId}`,
    title: detail.desc || '抖音视频',
    author: {
      name: author.nickname || '未知作者',
      handle: author.unique_id || author.short_id || '',
      verified: !!(author.verified || (author.custom_verify && !/^$/.test(author.custom_verify))),
      tag: '抖音创作者',
    },
    stats: {
      like: stats.digg_count || 0,
      comment: stats.comment_count || 0,
      share: stats.share_count || 0,
      view: stats.play_count || 0,
    },
    cover: v.origin_cover?.url_list?.[0] || v.cover?.url_list?.[0] || '',
    qualityLabel: qLabel,
    duration: Math.round((Number(v.duration) || 0) / 1000),
    watermarkFree: true,
    media: [{
      url: chosen.url,
      width: w, height: h,
      duration: Math.round((Number(v.duration) || 0) / 1000),
      size: Number(chosen.size) || chosen.probe.size || 0,
      type: 'mp4',
      referer: DY_REF,
    }],
  }
}

/* ---------- 快手：H5 分享页详情接口（2025 改版后手机页为纯客户端渲染，旧版页面内嵌
   __INITIAL_STATE__ 已不存在；改为直接调分享页同源的详情接口：
   POST /rest/wd/ugH5App/photo/simple/info { photoId, isLongVideo }
   免签名、免 Cookie，返回封面/无水印播放直链/作者/数据统计 ---------- */
/** 从快手分享链接/展开后的页面地址中提取作品 ID（字母数字混合或纯数字均可） */
function extractKsPhotoId(target: string) {
  const pathM = target.match(/\/(?:fw\/(?:photo|long-video|concept-photo|share\/photo)|short-video|share\/photo)\/([0-9A-Za-z_-]{4,64})/)
  if (pathM) return pathM[1]
  try {
    const u = new URL(target)
    for (const key of ['photoId', 'photo_id', 'shareObjectId', 'shareId', 'fid', 'id']) {
      const value = u.searchParams.get(key) || ''
      if (/^[0-9A-Za-z_-]{4,64}$/.test(value)) return value
    }
  } catch { /* 非法 URL */ }
  return ''
}
/** 截断长地址用于错误提示（避免刷屏日志） */
function clipUrl(value: string, max = 100) { return value.length > max ? `${value.slice(0, max)}…` : value }

/** 探测快手 CDN 直链可播并取真实总大小（Range 首字节；kwimgs/yximgs/kwaicdn 均支持） */
async function probeKsMedia(url: string) {
  for (let i = 0; i < 3; i++) {
    try {
      const res = await fetch(url, {
        headers: { 'User-Agent': UA_PC, Referer: 'https://www.kuaishou.com/', Range: 'bytes=0-0', Accept: '*/*' },
        redirect: 'follow',
        signal: AbortSignal.timeout(12000),
      })
      const ct = res.headers.get('content-type') || ''
      const cr = res.headers.get('content-range') || ''
      if ((res.status === 200 || res.status === 206) && /video|octet-stream/i.test(ct)) {
        const total = cr ? Number(cr.split('/')[1]) || 0 : 0
        return { ok: true, type: ct, size: total }
      }
      await new Promise((r) => setTimeout(r, 600))
    } catch {
      await new Promise((r) => setTimeout(r, 600))
    }
  }
  return { ok: false }
}

async function resolveKuaishou(url: string) {
  try {
    /* 1. 短链/未知路径先用手机 UA 展开（v.kuaishou.com 等）；直接页面链接无需展开 */
    let target = url
    const looksShort = /^https?:\/\/[^/]*(?:kuaishou|gifshow)\.(?:com|cn|tv)\//i.test(url) && !/\/(?:fw|short-video)\//.test(url)
    if (looksShort) {
      const short = await jget(url, { 'User-Agent': UA_MOBILE }, 8000)
      target = short.finalUrl || url
    }
    /* 2. 提取作品 ID：展开后地址优先，原链接兜底（部分跳转会丢弃 query 参数） */
    let photoId = extractKsPhotoId(target)
    if (!photoId) photoId = extractKsPhotoId(url)
    /* 3. 个别场景手机 UA 被拦（落地 JSON 校验页），换桌面 UA 再展开一次 */
    if (!photoId && looksShort) {
      const short = await jget(url, { 'User-Agent': UA_PC, Referer: 'https://www.kuaishou.com/' }, 8000)
      target = short.finalUrl || url
      photoId = extractKsPhotoId(target)
      if (!photoId) photoId = extractKsPhotoId(url)
    }
    if (!photoId) {
      /* 展开后落到推荐/校验页：多半是链接失效或触发了临时风控，给用户可操作的提示 */
      const gateHint = /new-reco|security|verify|captcha|passport/i.test(target)
        ? '（快手返回了推荐/校验页——链接可能已失效，或该次请求被临时风控，可稍后重试或换一条分享链接）'
        : ''
      return fail(`快手链接展开失败：未能识别作品 ID（已解析地址：${clipUrl(target)}）${gateHint}`)
    }

    /* 2. 详情接口（与 H5 分享页同源；短视频与中长视频同接口，仅 isLongVideo 不同） */
    const isLong = /\/fw\/long-video\//.test(target)
    const res = await fetch('https://m.gifshow.com/rest/wd/ugH5App/photo/simple/info', {
      method: 'POST',
      headers: { 'User-Agent': UA_MOBILE, 'Content-Type': 'application/json', Referer: 'https://m.gifshow.com/' },
      body: JSON.stringify({ photoId, isLongVideo: isLong }),
      signal: AbortSignal.timeout(12000),
    })
    if (!res.ok) return fail(`快手详情接口返回 HTTP ${res.status}`)
    let json: any = null
    try { json = await res.json() } catch { /* 非 JSON */ }
    if (!json || json.result !== 1 || !json.photo) {
      return fail(json?.error_msg ? `快手未返回作品：${json.error_msg}` : '快手未返回作品数据（作品不存在、已删除或账号风控）')
    }
    const photo = json.photo

    /* 3. 播放直链候选：manifest 自适应流（含清晰度/大小/尺寸）优先，mainMvUrls 兜底；
       逐个探测可播性，取首个真实可播的直链（避免 CDN 路由失效的坏链） */
    const reps: any[] = []
    for (const set of Array.isArray(photo.manifest?.adaptationSet) ? photo.manifest.adaptationSet : []) {
      for (const rep of Array.isArray(set?.representation) ? set.representation : []) {
        if (rep && typeof rep.url === 'string' && rep.url && !rep.hidden) reps.push(rep)
      }
    }
    reps.sort((a, b) => (Number(b.height) || 0) - (Number(a.height) || 0) || Number(b.defaultSelect ? 1 : 0) - Number(a.defaultSelect ? 1 : 0))
    const toHttps = (value: string | undefined) => (value || '').replace(/^http:\/\//i, 'https://')
    const candidates: Array<{ url: string; width: number; height: number; size: number; qualityLabel: string }> = []
    for (const rep of reps) {
      candidates.push({ url: toHttps(rep.url), width: Number(rep.width) || 0, height: Number(rep.height) || 0, size: Number(rep.fileSize) || 0, qualityLabel: String(rep.qualityLabel || (rep.height ? `${rep.height}P` : '')) })
    }
    for (const mv of Array.isArray(photo.mainMvUrls) ? photo.mainMvUrls : []) {
      const u = toHttps(mv?.url)
      if (u && !candidates.some((c) => c.url === u)) candidates.push({ url: u, width: 0, height: 0, size: 0, qualityLabel: '' })
    }
    if (!candidates.length) return fail('该快手作品为图文/其他类型，不含可提取的视频流')
    let chosen: (typeof candidates)[0] | null = null
    for (const candidate of candidates) {
      const probe = await probeKsMedia(candidate.url)
      if (probe.ok) {
        chosen = candidate
        if (!chosen.size && probe.size) chosen.size = probe.size
        break
      }
    }
    if (!chosen) return fail('快手视频流验证失败（直链不可达，多为临时限流），请稍后重试')
    const cover = toHttps(photo.coverUrls?.[0]?.url) || toHttps(photo.webpCoverUrls?.[0]?.url) || ''
    const durationMs = Number(photo.duration) || 0
    return {
      platform: 'kuaishou',
      title: String(photo.caption || '快手视频').trim(),
      author: { name: String(photo.userName || '未知作者'), handle: '', verified: !!photo.verified, tag: '快手' },
      stats: {
        like: Number(photo.likeCount) || 0,
        comment: Number(photo.commentCount) || 0,
        share: Number(photo.shareCount) || 0,
        view: Number(photo.viewCount) || 0,
      },
      cover,
      qualityLabel: chosen.qualityLabel || (chosen.height ? `${chosen.height}P` : '原画'),
      duration: Math.round(durationMs / 1000),
      watermarkFree: true,
      media: [{
        url: chosen.url,
        width: chosen.width || Number(photo.width) || 0,
        height: chosen.height || Number(photo.height) || 0,
        duration: Math.round(durationMs / 1000),
        size: chosen.size,
        type: /\.webm($|\?)/i.test(chosen.url) ? 'webm' : 'mp4',
        referer: 'https://www.kuaishou.com/',
      }],
    }
  } catch (e: any) {
    return fail('快手解析请求失败：' + (e.message || '网络错误'))
  }
}

/* ---------- 其余平台 ---------- */
function resolveUnsupported(plat: string) {
  return fail(`${plat} 真实解析接口未内置（登录态/签名要求较高）。可接入自有解析服务或使用演示预览`)
}

const PLATFORM_CN: Record<string, string> = { bilibili: 'B站', douyin: '抖音', kuaishou: '快手', xiaohongshu: '小红书', weibo: '微博', xigua: '西瓜视频' }

async function resolveAll(url: string) {
  const plat = detectPlatform(url)
  if (!plat) return fail('未识别链接平台（支持：B站/抖音/快手/小红书/微博/西瓜）')
  let data: any
  try {
    if (plat === 'bilibili') data = await resolveBilibili(url)
    else if (plat === 'douyin') data = await resolveDouyin(url)
    else if (plat === 'kuaishou') data = await resolveKuaishou(url)
    else data = resolveUnsupported(PLATFORM_CN[plat])
  } catch (e: any) {
    return fail(`${PLATFORM_CN[plat]} 解析异常：${e.message || e}`)
  }
  if (data.ok === false) return data
  return { ok: true, data }
}

/* ============================================================
   B. Fastify 路由（与图片库同源 4311）
   ============================================================ */

/** 按片源域名选择带防盗链头的上游请求头（/api/stream 与 /api/parse/import 共用） */
export function pickUpstreamHeaders(target: string, ref: string): Record<string, string> {
  const headers: Record<string, string> = { 'User-Agent': UA_PC }
  if (ref) headers.Referer = ref
  /* 抖音系 CDN 使用与签名环境一致的浏览器 UA（douyinvod/douyinpic 等防盗链宽松但验 UA） */
  if (/bilibili|bilivideo|hdslb|ixigua/i.test(target)) headers['User-Agent'] = UA_PC
  else if (/douyin|douyinvod|douyinpic|snssdk|amemv|byteimg|bytecdn|toutiao|pstatp/i.test(target) || /douyin\.com/i.test(ref)) headers['User-Agent'] = DY_UA
  return headers
}

export function registerParseApi(app: FastifyInstance, options?: ParseServerDeps) {
  if (options) deps = options
  app.post('/api/parse', async (request, reply) => {
    const input: any = request.body ?? {}
    const out = await resolveAll(input.url || input.share || '')
    /* 媒体直链改写成同源代理流地址（播放/下载不受防盗链与 CORS 限制）；
       src / coverSrc 保留上游原始地址（服务端导入保存用） */
    if (out.ok) {
      const referer = out.data.media?.[0]?.referer || ''
      if (out.data.media?.[0]?.url) {
        out.data.media[0].src = out.data.media[0].url
        out.data.media[0].url = `/api/stream?url=${encodeURIComponent(out.data.media[0].url)}${referer ? '&ref=' + encodeURIComponent(referer) : ''}`
      }
      if (out.data.cover && /^https?:/i.test(out.data.cover)) {
        out.data.coverSrc = out.data.cover
        const coverRef = referer || 'https://www.bilibili.com/'
        out.data.cover = `/api/stream?url=${encodeURIComponent(out.data.cover)}&ref=${encodeURIComponent(coverRef)}&disposition=inline`
      }
    }
    return reply.code(out.ok ? 200 : 400).type('application/json; charset=utf-8').send(out)
  })

  /* 媒体代理流（Range / 防盗链 UA+Referer 透传） */
  app.get('/api/stream', async (request, reply) => {
    const query = request.query as { url?: string; ref?: string; disposition?: string }
    const target = query.url || ''
    const ref = query.ref || ''
    if (!/^https?:\/\//i.test(target)) return reply.code(400).send('bad url')
    const rng = request.headers.range
    const headers = pickUpstreamHeaders(target, ref)
    if (typeof rng === 'string') headers.Range = rng
    /* 超时只作用于“等待响应头”（上游连接/首字节）。正文一旦开始就持续流式转发，
       不再设硬时限 —— 大文件（几十 MB）在慢速 CDN 下需要远超 20s 的下载时间，
       硬超时会在中途掐断导致客户端挂起。空闲看门狗兜底死链。 */
    const controller = new AbortController()
    /* 连接超时放宽到 45s：同一签名 URL 被并发连接时 CDN 会排队（实测约 20s+） */
    const connectTimer = setTimeout(() => controller.abort(), 45_000)
    let upstream: Response
    try {
      upstream = await fetch(target, { headers, redirect: 'follow', signal: controller.signal })
    } catch (e: any) {
      clearTimeout(connectTimer)
      return reply.code(502).type('text/plain; charset=utf-8').send('代理拉流失败：' + (e.message || ''))
    }
    clearTimeout(connectTimer)
    if (!upstream.ok && upstream.status !== 206) {
      upstream.body?.cancel().catch(() => undefined)
      return reply.code(upstream.status).send()
    }
    const outHeaders: Record<string, string> = {
      'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream',
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'public, max-age=600',
    }
    /* undici 可能已解压上游响应；此时原始 Content-Length 不再代表转发正文长度，
       不应把它传给 APK，否则原生端会把完整正文误判为截断。 */
    const upstreamEncoding = upstream.headers.get('content-encoding')
    const safeContentLength = upstreamEncoding ? null : upstream.headers.get('content-length')
    if (query.disposition === 'inline') outHeaders['Content-Disposition'] = 'inline'
    if (rng) {
      const cr = upstream.headers.get('content-range')
      const cl = safeContentLength
      if (cr) outHeaders['Content-Range'] = cr
      if (cl) outHeaders['Content-Length'] = cl
    } else {
      const cl = safeContentLength
      if (cl) outHeaders['Content-Length'] = cl
    }
    reply.code(upstream.status).headers(outHeaders)
    if (request.method === 'HEAD') {
      upstream.body?.cancel().catch(() => undefined)
      return reply.send()
    }
    if (!upstream.body) return reply.send()
    const stream = Readable.fromWeb(upstream.body as import('node:stream/web').ReadableStream)
    let watchdog: ReturnType<typeof setInterval> | undefined
    const stopWatchdog = () => { if (watchdog) clearInterval(watchdog) }
    let lastData = Date.now()
    watchdog = setInterval(() => {
      if (Date.now() - lastData > 60_000) {
        try { stream.destroy(new Error('上游流空闲超时')) } catch { /* 已销毁 */ }
      }
    }, 15_000)
    stream.on('data', () => { lastData = Date.now() })
    stream.on('error', () => {
      stopWatchdog()
      try { reply.raw.destroy() } catch { /* 已断开 */ }
    })
    stream.on('close', stopWatchdog)
    /* 客户端断开时销毁上游流，避免悬挂请求拖住连接池 */
    request.raw.on('close', () => {
      if (!request.raw.readableEnded && !stream.destroyed) stream.destroy()
    })
    return reply.send(stream)
  })

  /* 健康检查（解析页据此切换真实模式） */
  app.get('/api/ping', async (_request, reply) => reply.type('text/plain').send('pong'))
}

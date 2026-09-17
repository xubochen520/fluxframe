// ffmpeg 引擎管理：定位本机 ffmpeg（PATH / FFMPEG_PATH / models\ffmpeg / 常见目录），
// 未找到时可从 gyan.dev 官方构建一键下载（curl 走系统代理、断点续传）到 models\ffmpeg。
// 用途：B 站等平台的高清 DASH 流（音视频分离）保存到图片库前需要 ffmpeg 无损合并。
import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, readdir, rename, rm, stat } from 'node:fs/promises'
import path from 'node:path'

export interface FfmpegProgress { phase: 'idle' | 'downloading' | 'extracting' | 'ready' | 'error'; done: number; total: number; error?: string }

let busy = false
let progress: FfmpegProgress = { phase: 'idle', done: 0, total: 0 }
let cachedVersion: string | null = null

// ---------- 基础工具（与 ai-manager 同款：curl 原生支持代理/重定向/断点续传） ----------

function resolveProjectDir() {
  let dir = process.cwd()
  for (let i = 0; i < 4; i++) {
    if (existsSync(path.join(dir, 'package.json')) && (existsSync(path.join(dir, 'client')) || existsSync(path.join(dir, 'prisma')))) return dir
    dir = path.dirname(dir)
  }
  return process.cwd()
}

/** 平台判断：Windows 用 .exe + PowerShell 解压，Linux 用无后缀可执行文件 + unzip */
const isWin = process.platform === 'win32'
const projectDir = resolveProjectDir()
/** 模型目录可被 MODELS_DIR 覆盖（Docker 部署时挂载到 /data/models，避免容器重建丢文件） */
const modelsRoot = process.env.MODELS_DIR?.trim() ? path.resolve(process.env.MODELS_DIR.trim()) : path.join(projectDir, 'models')
export const ffmpegModelsDir = path.join(modelsRoot, 'ffmpeg')
export const ffmpegDefaultExe = path.join(ffmpegModelsDir, isWin ? 'ffmpeg.exe' : 'ffmpeg')

function getSystemProxy(): string | null {
  const fromEnv = process.env.HTTPS_PROXY || process.env.HTTP_PROXY || process.env.https_proxy || process.env.http_proxy
  if (fromEnv) return fromEnv
  try {
    const key = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'
    const enabled = spawnSync('reg.exe', ['query', key, '/v', 'ProxyEnable'], { encoding: 'utf8', windowsHide: true, timeout: 3000 })
    const proxyInfo = spawnSync('reg.exe', ['query', key, '/v', 'ProxyServer'], { encoding: 'utf8', windowsHide: true, timeout: 3000 })
    if (!/0x1/i.test(enabled.stdout || '')) return null
    const m = (proxyInfo.stdout || '').match(/ProxyServer\s+REG_SZ\s+([^\r\n]+)/i)
    if (!m || !m[1].trim()) return null
    const server = m[1].trim()
    return server.includes('://') ? server : `http://${server}`
  } catch { return null }
}
function curlCommand(): string { return process.platform === 'win32' ? 'curl.exe' : 'curl' }

function curlProbe(url: string): { status: number; size: number } {
  const args = ['-s', '-L', '--max-time', '60', '-r', '0-0', '-o', process.platform === 'win32' ? 'NUL' : '/dev/null', '-D', '-']
  const proxy = getSystemProxy()
  if (proxy) args.push('-x', proxy)
  args.push(url)
  const r = spawnSync(curlCommand(), args, { encoding: 'utf8', windowsHide: true, timeout: 90_000 })
  const headers = String(r.stdout || '')
  const status = Number(headers.match(/^HTTP\/[\d.]+\s+(\d{3})/m)?.[1] || 0)
  const rangeTotal = Number(headers.match(/content-range:\s*bytes\s+\d+-\d+\/(\d+)/i)?.[1] || 0)
  const lenTotal = Number(headers.match(/content-length:\s*(\d+)/i)?.[1] || 0)
  return { status, size: rangeTotal || lenTotal }
}

async function downloadFile(url: string, dest: string, onProgress: (done: number, total: number) => void): Promise<void> {
  const probe = curlProbe(url)
  if (probe.status !== 200 && probe.status !== 206) throw new Error(`下载失败 HTTP ${probe.status}：${url}`)
  const total = probe.size
  const args = ['-sS', '-L', '--retry', '10', '--retry-delay', '3', '--retry-all-errors', '-C', '-', '--max-time', '7200', '-o', dest]
  const proxy = getSystemProxy()
  if (proxy) args.push('-x', proxy)
  args.push(url)
  const proc = spawn(curlCommand(), args, { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] })
  let errText = ''
  proc.stderr?.on('data', (c) => (errText += c))
  const timer = setInterval(() => {
    void stat(dest).then((st) => onProgress(st.size, total)).catch(() => { /* 文件尚未创建 */ })
  }, 800)
  const code = await new Promise<number>((resolve) => proc.on('close', resolve))
  clearInterval(timer)
  if (code !== 0) throw new Error(`下载失败（curl 退出码 ${code}）：${errText.trim().slice(-200) || url}`)
  const finalSize = (await stat(dest).catch(() => null))?.size || 0
  onProgress(finalSize, total)
}

async function extractZip(zipPath: string, destDir: string) {
  await mkdir(destDir, { recursive: true })
  const child = isWin
    ? spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `Expand-Archive -LiteralPath '${zipPath}' -DestinationPath '${destDir}' -Force`], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    : spawn('unzip', ['-o', '-q', zipPath, '-d', destDir], { stdio: ['ignore', 'pipe', 'pipe'] })
  let errOut = ''
  child.stderr?.on('data', (c) => (errOut += c))
  const code = await new Promise<number>((resolve) => child.on('close', resolve))
  if (code !== 0) throw new Error(`解压 ffmpeg 失败：${errOut.slice(0, 200) || `退出码 ${code}`}`)
}

async function findFileRecursive(dir: string, name: string): Promise<string | null> {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => [])
  for (const entry of entries) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) { const found = await findFileRecursive(p, name); if (found) return found }
    else if (entry.name === name) return p
  }
  return null
}

// ---------- 定位 ----------

/** 探测给定 ffmpeg.exe 的版本（首行），不可用返回 null */
export async function probeFfmpegVersion(exePath: string): Promise<string | null> {
  if (cachedVersion) return cachedVersion
  try {
    const r = spawnSync(exePath, ['-version'], { encoding: 'utf8', windowsHide: true, timeout: 10_000 })
    const first = String(r.stdout || '').split(/\r?\n/)[0] || ''
    const m = first.match(/ffmpeg version\s+([^\s]+)/i)
    if (r.status === 0 && m) { cachedVersion = m[1]; return m[1] }
  } catch { /* 不可用 */ }
  return null
}

/** 按优先级定位 ffmpeg：设置里的自定义路径 → FFMPEG_PATH → models/ffmpeg → PATH → 常见安装目录 */
export async function locateFfmpeg(configuredPath?: string): Promise<{ exe: string; version: string } | null> {
  const candidates: string[] = []
  if (configuredPath?.trim()) candidates.push(configuredPath.trim())
  if (process.env.FFMPEG_PATH?.trim()) candidates.push(process.env.FFMPEG_PATH.trim())
  candidates.push(ffmpegDefaultExe)
  if (isWin) {
    const dirs = (process.env.PATH || '').split(';')
    for (const dir of dirs) if (dir.trim()) candidates.push(path.join(dir.trim(), 'ffmpeg.exe'))
    candidates.push('C:\\ffmpeg\\bin\\ffmpeg.exe', path.join(process.env.ProgramFiles || 'C:\\Program Files', 'ffmpeg', 'bin', 'ffmpeg.exe'))
  } else {
    const dirs = (process.env.PATH || '').split(':')
    for (const dir of dirs) if (dir.trim()) candidates.push(path.join(dir.trim(), 'ffmpeg'))
    candidates.push('/usr/bin/ffmpeg', '/usr/local/bin/ffmpeg', '/opt/ffmpeg/bin/ffmpeg', '/snap/bin/ffmpeg')
  }
  for (const exe of candidates) {
    if (!existsSync(exe)) continue
    const version = await probeFfmpegVersion(exe)
    if (version) return { exe, version }
  }
  return null
}

// ---------- 一键下载（gyan.dev 官方 Windows 构建，可 FFMPEG_DOWNLOAD_URL 覆盖） ----------

export function getFfmpegStatus() {
  return { busy, progress: { ...progress } }
}

export async function downloadFfmpeg(): Promise<{ ok: boolean; error?: string }> {
  if (busy) return { ok: false, error: '已有下载任务进行中，请稍候' }
  const existing = await locateFfmpeg()
  if (existing) return { ok: true }
  // Linux：一审下载的是 Windows 构建，容器/服务器请用系统 ffmpeg（镜像里已预装 /usr/bin/ffmpeg）
  if (!isWin) {
    const error = '当前为 Linux 环境：请在系统设置中填写 ffmpeg 路径（默认 /usr/bin/ffmpeg），或用 apt install -y ffmpeg 安装后重试'
    progress = { phase: 'error', done: 0, total: 0, error }
    return { ok: false, error }
  }
  busy = true
  cachedVersion = null
  progress = { phase: 'downloading', done: 0, total: 0 }
  try {
    await mkdir(ffmpegModelsDir, { recursive: true })
    const url = process.env.FFMPEG_DOWNLOAD_URL || 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
    const zipPath = path.join(ffmpegModelsDir, 'ffmpeg.zip')
    await rm(zipPath, { force: true })
    progress = { phase: 'downloading', done: 0, total: 0 }
    await downloadFile(url, zipPath, (done, total) => { progress = { phase: 'downloading', done, total } })
    progress = { phase: 'extracting', done: 0, total: 0 }
    const extractDir = path.join(ffmpegModelsDir, 'extract')
    await rm(extractDir, { recursive: true, force: true })
    await extractZip(zipPath, extractDir)
    const foundExe = await findFileRecursive(extractDir, 'ffmpeg.exe')
    if (!foundExe) throw new Error('压缩包中未找到 ffmpeg.exe')
    await rename(foundExe, path.join(ffmpegModelsDir, 'ffmpeg.exe'))
    const foundProbe = await findFileRecursive(extractDir, 'ffprobe.exe')
    if (foundProbe) await rename(foundProbe, path.join(ffmpegModelsDir, 'ffprobe.exe')).catch(() => undefined)
    await rm(extractDir, { recursive: true, force: true })
    await rm(zipPath, { force: true })
    const version = await probeFfmpegVersion(path.join(ffmpegModelsDir, 'ffmpeg.exe'))
    if (!version) throw new Error('下载完成但 ffmpeg 无法运行（被杀毒软件拦截？）')
    progress = { phase: 'ready', done: 1, total: 1 }
    return { ok: true }
  } catch (error) {
    progress = { phase: 'error', done: 0, total: 0, error: error instanceof Error ? error.message : '下载失败' }
    return { ok: false, error: progress.error }
  } finally {
    busy = false
  }
}

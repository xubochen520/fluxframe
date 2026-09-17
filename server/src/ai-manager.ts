// AI 引擎自动管理：检测本机 llama.cpp、自动下载（GitHub Releases + Hugging Face）、
// 解压、启动 llama-server 子进程并等待就绪。全部离线本地运行，无需 API 密钥。
//
// 网络说明：Node 内置 fetch 不读取 Windows 系统代理，且自建 CONNECT 隧道对部分
// 站点（huggingface.co）不稳定，因此下载统一调用系统自带 curl.exe（原生支持
// 代理、重定向、断点续传），代理地址自动发现；无代理时 curl 直连。
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { createWriteStream, existsSync } from 'node:fs'
import { access, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import path from 'node:path'

export type AiPhase = 'idle' | 'fetching-release' | 'downloading-llama' | 'extracting' | 'downloading-model' | 'starting' | 'ready' | 'error' | 'stopped'
export interface AiProgress {
  phase: AiPhase
  llamaDone: number
  llamaTotal: number
  modelDone: number
  modelTotal: number
  modelName: string
  variant: string
  error?: string
  port?: number
}
export interface AiStatusPayload {
  running: boolean
  port: number | null
  baseUrl: string | null
  detected: { port: number; modelId: string | null } | null
  files: { server: boolean; model: boolean; mmproj: boolean; variant: string | null }
  progress: AiProgress
}

interface Variant { repo: string; model: string; modelLocal: string; mmproj: string; mmprojLocal: string; label: string; modelMB: number; mmprojMB: number }
// 官方 Qwen GGUF 仓库需要登录（gated），这里用公开的 mradermacher 镜像仓库（含 mmproj）
const VARIANTS: Record<string, Variant> = {
  '3b': {
    repo: 'mradermacher/Qwen2.5-VL-3B-Instruct-GGUF',
    model: 'Qwen2.5-VL-3B-Instruct.Q4_K_M.gguf',
    modelLocal: 'qwen2.5-vl-3b-instruct-q4_k_m.gguf',
    mmproj: 'Qwen2.5-VL-3B-Instruct.mmproj-fp16.gguf',
    mmprojLocal: 'qwen2.5-vl-3b-instruct-mmproj-fp16.gguf',
    label: 'Qwen2.5-VL-3B',
    modelMB: 1840,
    mmprojMB: 1276,
  },
  '7b': {
    repo: 'mradermacher/Qwen2.5-VL-7B-Instruct-GGUF',
    model: 'Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf',
    modelLocal: 'qwen2.5-vl-7b-instruct-q4_k_m.gguf',
    mmproj: 'Qwen2.5-VL-7B-Instruct.mmproj-f16.gguf',
    mmprojLocal: 'qwen2.5-vl-7b-instruct-mmproj-f16.gguf',
    label: 'Qwen2.5-VL-7B',
    modelMB: 4466,
    mmprojMB: 1291,
  },
}
const DETECT_PORTS = [8080, 8081, 8000, 11434, 1234]

function resolveProjectDir() {
  // 服务端运行时 cwd 可能是 server/（npm --prefix）或项目根；
  // 项目根的特征是同时包含 package.json 与 client/（或 prisma/），避免误判为 server/
  let dir = process.cwd()
  for (let i = 0; i < 4; i++) {
    if (existsSync(path.join(dir, 'package.json')) && (existsSync(path.join(dir, 'client')) || existsSync(path.join(dir, 'prisma')))) return dir
    dir = path.dirname(dir)
  }
  return process.cwd()
}
const isWin = process.platform === 'win32'
/** llama-server 可执行文件名：Windows 为 llama-server.exe，Linux 无后缀 */
const serverBinName = isWin ? 'llama-server.exe' : 'llama-server'
const projectDir = resolveProjectDir()
/** 模型/日志目录可被环境变量覆盖（Docker 部署挂载到 /data/models、/data/logs，避免容器重建丢文件） */
const modelsDir = process.env.MODELS_DIR?.trim() ? path.resolve(process.env.MODELS_DIR.trim()) : path.join(projectDir, 'models')
const logsDir = process.env.LOGS_DIR?.trim() ? path.resolve(process.env.LOGS_DIR.trim()) : path.join(projectDir, 'logs')

let progress: AiProgress = { phase: 'idle', llamaDone: 0, llamaTotal: 0, modelDone: 0, modelTotal: 0, modelName: '', variant: '7b' }
let busy = false
let serverProc: ChildProcess | null = null
let cachedTag: string | null = null

function setPhase(phase: AiPhase) { progress = { ...progress, phase } }

// ---------- 基础工具 ----------

async function existsFile(file: string) { try { await access(file); return true } catch { return false } }

function hasNvidiaGpu() {
  try {
    const r = spawnSync('nvidia-smi', ['--query-gpu=name', '--format=csv,noheader'], { timeout: 5000, windowsHide: true })
    return r.status === 0 && String(r.stdout).trim().length > 0
  } catch { return false }
}
function getNvidiaDriver(): number | null {
  try {
    const r = spawnSync('nvidia-smi', ['--query-gpu=driver_version', '--format=csv,noheader'], { encoding: 'utf8', timeout: 5000, windowsHide: true })
    const v = parseFloat(String(r.stdout || '').trim())
    return Number.isFinite(v) ? v : null
  } catch { return null }
}

async function tcpFree(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const sock = createServer()
    sock.once('error', () => resolve(false))
    sock.listen(port, '127.0.0.1', () => sock.close(() => resolve(true)))
  })
}
async function findFreePort(start = 8080) {
  for (let p = start; p < start + 20; p++) if (await tcpFree(p)) return p
  throw new Error('未找到空闲端口')
}

// ---------- 系统代理发现与 curl 下载（curl.exe 原生支持代理/重定向/断点续传） ----------

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

// 探测远程文件：返回 { status, size }。跟随重定向，用 Range 0 字节探测并解析 content-range/content-length。
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

function urlExists(url: string): boolean {
  const { status } = curlProbe(url)
  return status === 200 || status === 206
}

// 用 curl 流式下载（自动跟随重定向、断点续传、失败重试），进度按目标文件大小轮询上报。
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

async function fetchUrlText(url: string): Promise<string> {
  const args = ['-s', '-L', '--max-time', '60']
  const proxy = getSystemProxy()
  if (proxy) args.push('-x', proxy)
  args.push(url)
  const r = spawnSync(curlCommand(), args, { encoding: 'utf8', windowsHide: true, timeout: 90_000 })
  if (r.status !== 0) throw new Error(`请求失败（curl 退出码 ${r.status}）：${url}`)
  return String(r.stdout || '')
}

// ---------- llama.cpp 服务探测 ----------

async function queryLlamaCpp(port: number, timeoutMs = 1500): Promise<{ ok: boolean; modelId?: string }> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/v1/models`, { signal: AbortSignal.timeout(timeoutMs) })
    if (!res.ok) return { ok: false }
    const data: any = await res.json()
    const modelId = Array.isArray(data?.data) ? String(data.data[0]?.id || '') : ''
    return { ok: true, modelId }
  } catch { return { ok: false } }
}
async function detectRunning(): Promise<{ port: number; modelId: string | null } | null> {
  for (const port of DETECT_PORTS) {
    const r = await queryLlamaCpp(port)
    if (r.ok) return { port, modelId: r.modelId || null }
  }
  return null
}

// ---------- 下载 ----------

async function getLatestLlamaTag(): Promise<string> {
  if (cachedTag) return cachedTag
  // 1) GitHub API 直连（不经代理，避免共享出口 IP 限流）
  //    注意：仓库的 "latest release" 现在可能是 v0.4.1 这类版本号（只含 nightly-tag.txt），
  //    真正的构建产物发布在 b<数字> 标签上，因此这里扫描最近若干条 release。
  try {
    const res = await fetch('https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=15', {
      headers: { 'User-Agent': 'intranet-image-manager', Accept: 'application/vnd.github+json' },
      signal: AbortSignal.timeout(15_000),
    })
    if (res.ok) {
      const data: any = await res.json()
      const tag = (Array.isArray(data) ? data : [])
        .map((row: any) => String(row?.tag_name || '').trim())
        .find((t: string) => /^b\d+$/.test(t))
      if (tag) { cachedTag = tag; return tag }
    }
  } catch { /* 走 HTML 兜底 */ }
  // 2) GitHub tags 页面（经系统代理）
  try {
    const html = await fetchUrlText('https://github.com/ggml-org/llama.cpp/tags')
    const tags = [...html.matchAll(/releases\/tag\/(b\d+)/g)].map((m) => m[1])
    if (tags.length) { cachedTag = tags[0]; return tags[0] }
  } catch { /* 兜底失败 */ }
  throw new Error(`无法获取 llama.cpp 最新版本号（可手动下载 ${serverBinName} 与模型文件放入 models 目录）`)
}

/** Linux 下若存在 /dev/dri（Intel/AMD 核显/独显）优先尝试 Vulkan 构建 */
function hasVulkanDevice() { return !isWin && existsSync('/dev/dri') }

async function pickReleaseAsset(tag: string): Promise<{ name: string; url: string; cudartName?: string }> {
  const candidates: string[] = []
  if (isWin) {
    if (hasNvidiaGpu()) {
      const driver = getNvidiaDriver()
      if (driver !== null && driver >= 580) candidates.push(`llama-${tag}-bin-win-cuda-13.3-x64.zip`)
      candidates.push(`llama-${tag}-bin-win-cuda-12.4-x64.zip`)
    }
    candidates.push(`llama-${tag}-bin-win-cpu-x64.zip`)
  } else {
    // Linux 官方只提供 cpu / vulkan / rocm / sycl / openvino 等构建（无 CUDA 包）
    if (hasVulkanDevice()) candidates.push(`llama-${tag}-bin-ubuntu-vulkan-x64.tar.gz`)
    candidates.push(`llama-${tag}-bin-ubuntu-x64.tar.gz`)
  }
  for (const name of candidates) {
    const url = `https://github.com/ggml-org/llama.cpp/releases/download/${tag}/${name}`
    if (urlExists(url)) {
      // 新版 llama.cpp 把 CUDA 运行时单独打包（仅 Windows：cudart-llama-bin-win-<cuda>-x64.zip），需一并下载解压到 exe 旁
      const cudartName = isWin && name.includes('cuda') ? `cudart-${name.replace(/^llama-[\w.]+-/, '')}` : undefined
      return { name, url, cudartName: cudartName && urlExists(`https://github.com/ggml-org/llama.cpp/releases/download/${tag}/${cudartName}`) ? cudartName : undefined }
    }
  }
  throw new Error(`未找到可用的 llama.cpp 安装包（平台=${process.platform}，tag=${tag}，可手动下载放入 models 目录）`)
}

async function extractZip(zipPath: string, destDir: string) {
  await mkdir(destDir, { recursive: true })
  const child = isWin
    ? spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `Expand-Archive -LiteralPath '${zipPath}' -DestinationPath '${destDir}' -Force`], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    : spawn('unzip', ['-o', '-q', zipPath, '-d', destDir], { stdio: ['ignore', 'pipe', 'pipe'] })
  let errOut = ''
  child.stderr?.on('data', (c) => (errOut += c))
  const code = await new Promise<number>((resolve) => child.on('close', resolve))
  if (code !== 0) throw new Error(`解压 llama.cpp 失败：${errOut.slice(0, 200) || `退出码 ${code}`}`)
}

/** Linux 安装包为 .tar.gz，用系统 tar 解压 */
async function extractTarGz(tarPath: string, destDir: string) {
  await mkdir(destDir, { recursive: true })
  const child = spawn('tar', ['-xzf', tarPath, '-C', destDir], { stdio: ['ignore', 'pipe', 'pipe'] })
  let errOut = ''
  child.stderr?.on('data', (c) => (errOut += c))
  const code = await new Promise<number>((resolve) => child.on('close', resolve))
  if (code !== 0) throw new Error(`解压 llama.cpp 失败：${errOut.slice(0, 200) || `退出码 ${code}`}`)
}

/** 按扩展名选择解压方式（Windows zip / Linux tar.gz） */
async function extractPackage(file: string, destDir: string) {
  if (/\.tar\.gz$|\.tgz$/i.test(file)) return extractTarGz(file, destDir)
  return extractZip(file, destDir)
}

async function hasCudaRuntimeDlls(): Promise<boolean> {
  const files = await readdir(modelsDir).catch(() => [])
  return files.some((f) => /^(cudart64|cublas64|cublasLt64|ggml-cuda)[\w.]*\.dll$/i.test(f))
}

// 把 cudart 压缩包解压后，将 DLL 等文件全部移动到 models 根目录（与 llama-server.exe 同级）
async function extractCudartToModels(cudartZip: string) {
  const cudartDir = path.join(modelsDir, 'cudart')
  await rm(cudartDir, { recursive: true, force: true })
  await extractZip(cudartZip, cudartDir)
  const entries = await readdir(cudartDir, { withFileTypes: true })
  for (const entry of entries) {
    if (!entry.isDirectory()) await rename(path.join(cudartDir, entry.name), path.join(modelsDir, entry.name)).catch(() => {})
  }
  await rm(cudartDir, { recursive: true, force: true })
  await rm(cudartZip, { force: true })
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

// ---------- 启动 / 停止 ----------

async function detectReadyVariant(): Promise<{ variant: string; model: string; mmproj: string } | null> {
  for (const [key, v] of Object.entries(VARIANTS)) {
    const model = path.join(modelsDir, v.modelLocal)
    const mmproj = path.join(modelsDir, v.mmprojLocal)
    if ((await existsFile(model)) && (await existsFile(mmproj))) return { variant: key, model, mmproj }
  }
  return null
}

async function startAiServer(): Promise<{ ok: boolean; error?: string }> {
  const detected = await detectRunning()
  if (detected) { progress = { ...progress, phase: 'ready', port: detected.port }; return { ok: true } }
  const serverExe = path.join(modelsDir, serverBinName)
  const ready = await detectReadyVariant()
  if (!(await existsFile(serverExe)) || !ready) return { ok: false, error: '文件不完整，请先自动下载' }
  const port = await findFreePort(8080)
  const args = ['-m', ready.model, '--mmproj', ready.mmproj, '-c', '8192', '-fa', 'on', '--host', '127.0.0.1', '--port', String(port)]
  const isCuda = await existsFile(path.join(modelsDir, 'llama-variant-cuda.txt'))
  if (isCuda) args.push('-ngl', '99')
  await mkdir(logsDir, { recursive: true })
  const logStream = createWriteStream(path.join(logsDir, 'llama-server.log'), { flags: 'a' })
  const proc = spawn(serverExe, args, { cwd: modelsDir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  serverProc = proc
  proc.stdout?.pipe(logStream)
  proc.stderr?.pipe(logStream)
  proc.on('exit', () => { if (serverProc === proc) serverProc = null })
  const deadline = Date.now() + 150_000
  try {
    while (Date.now() < deadline) {
      if (proc.exitCode !== null) throw new Error(`llama-server 启动失败（退出码 ${proc.exitCode}），详见 logs/llama-server.log`)
      const r = await queryLlamaCpp(port, 2000)
      if (r.ok) { progress = { ...progress, phase: 'ready', port }; return { ok: true } }
      await new Promise((resolve) => setTimeout(resolve, 2000))
    }
    throw new Error('llama-server 启动超时，详见 logs/llama-server.log')
  } catch (error) {
    try { proc.kill() } catch { /* 已退出 */ }
    throw error
  }
}

async function stopAiServer(): Promise<{ ok: boolean }> {
  if (serverProc && serverProc.exitCode === null) { serverProc.kill(); serverProc = null }
  progress = { ...progress, phase: 'stopped' }
  return { ok: true }
}

// ---------- 下载主流程 ----------

async function downloadAiStack(variantInput: string, mirror?: string): Promise<{ ok: boolean; error?: string }> {
  if (busy) return { ok: false, error: '已有下载任务进行中，请稍候' }
  const variant = VARIANTS[variantInput] || VARIANTS['7b']
  busy = true
  progress = { phase: 'fetching-release', llamaDone: 0, llamaTotal: 0, modelDone: 0, modelTotal: 0, modelName: variant.label, variant: variantInput }
  try {
    await mkdir(modelsDir, { recursive: true })
    const serverExePath = path.join(modelsDir, serverBinName)
    const markerPath = path.join(modelsDir, 'llama-variant-cuda.txt')
    const engineReady = await existsFile(serverExePath)
    const markerTag = await readFile(markerPath, 'utf8').catch(() => '')
    // CUDA 运行时只存在于 Windows 构建；Linux 用自带的 vulkan/cpu 构建，无此步骤
    const runtimeReady = !isWin || !markerTag || (await hasCudaRuntimeDlls())
    // 1. llama.cpp 引擎（GitHub Releases，经系统代理；已就位则跳过）
    if (!engineReady || !runtimeReady) {
      setPhase('fetching-release')
      const tag = engineReady ? markerTag : await getLatestLlamaTag()
      if (!engineReady) {
        const asset = await pickReleaseAsset(tag)
        setPhase('downloading-llama')
        const pkgPath = path.join(modelsDir, asset.name)
        await downloadFile(asset.url, pkgPath, (done, total) => { progress = { ...progress, llamaDone: done, llamaTotal: total } })
        setPhase('extracting')
        const extractDir = path.join(modelsDir, 'llama.cpp')
        await rm(extractDir, { recursive: true, force: true })
        await extractPackage(pkgPath, extractDir)
        // 安装包内可能直接在根目录、也可能多套一层目录，统一按可执行文件所在目录平铺
        const foundBin = await findFileRecursive(extractDir, serverBinName)
        if (!foundBin) throw new Error(`压缩包中未找到 ${serverBinName}`)
        const baseDir = path.dirname(foundBin)
        const entries = await readdir(baseDir, { withFileTypes: true })
        // 整个目录平铺移动到 models 根目录（Windows 的 DLL / Linux 的 .so 必须与可执行文件同级）
        for (const entry of entries) {
          const from = path.join(baseDir, entry.name)
          const to = path.join(modelsDir, entry.name)
          await rename(from, to).catch(async () => {
            await rm(to, { recursive: true, force: true })
            await rename(from, to).catch(() => {})
          })
        }
        await rm(extractDir, { recursive: true, force: true })
        await rm(pkgPath, { force: true })
        await rm(markerPath, { force: true })
        if (isWin && asset.name.includes('cuda')) await writeFile(markerPath, tag)
        // 新版 llama.cpp 的 CUDA 运行时单独打包（cudart zip，仅 Windows），需一并下载解压到 exe 旁
        if (asset.cudartName) {
          setPhase('downloading-llama')
          const cudartZip = path.join(modelsDir, 'cudart.zip')
          await downloadFile(`https://github.com/ggml-org/llama.cpp/releases/download/${tag}/${asset.cudartName}`, cudartZip, (done, total) => { progress = { ...progress, llamaDone: done, llamaTotal: total } })
          await extractCudartToModels(cudartZip)
        }
      } else if (isWin && tag && !runtimeReady) {
        // 引擎已就位但缺 CUDA 运行时（仅 Windows）：按标记的 tag 探测并补下 cudart 包
        const cudartCandidates = ['cudart-llama-bin-win-cuda-13.3-x64.zip', 'cudart-llama-bin-win-cuda-12.4-x64.zip']
        const cudartName = cudartCandidates.find((name) => urlExists(`https://github.com/ggml-org/llama.cpp/releases/download/${tag}/${name}`))
        if (!cudartName) throw new Error('未找到 CUDA 运行时安装包（可手动下载 cudart zip 解压到 models 目录）')
        setPhase('downloading-llama')
        const cudartZip = path.join(modelsDir, 'cudart.zip')
        await downloadFile(`https://github.com/ggml-org/llama.cpp/releases/download/${tag}/${cudartName}`, cudartZip, (done, total) => { progress = { ...progress, llamaDone: done, llamaTotal: total } })
        await extractCudartToModels(cudartZip)
      }
    }
    // 3. 视觉模型（Hugging Face 公开镜像仓库，可指定镜像域名；断点续传）
    setPhase('downloading-model')
    const hfBase = (mirror?.trim() || process.env.AI_DOWNLOAD_MIRROR || 'https://huggingface.co').replace(/\/+$/, '')
    const baseUrl = `${hfBase}/${variant.repo}/resolve/main`
    const modelTotal = (variant.modelMB + variant.mmprojMB) * 1024 * 1024
    progress = { ...progress, modelDone: 0, modelTotal }
    await downloadFile(`${baseUrl}/${variant.model}`, path.join(modelsDir, variant.modelLocal), (done) => { progress = { ...progress, modelDone: done } })
    await downloadFile(`${baseUrl}/${variant.mmproj}`, path.join(modelsDir, variant.mmprojLocal), (done) => { progress = { ...progress, modelDone: progress.modelDone + done } })
    // 4. 启动服务
    setPhase('starting')
    const started = await startAiServer()
    if (!started.ok) throw new Error(started.error || '启动失败')
    setPhase('ready')
    return { ok: true }
  } catch (error) {
    progress = { ...progress, phase: 'error', error: error instanceof Error ? error.message : '下载失败' }
    return { ok: false, error: progress.error }
  } finally {
    busy = false
  }
}

// ---------- 状态 ----------

async function getAiStatus(): Promise<AiStatusPayload> {
  const detected = await detectRunning()
  const ready = await detectReadyVariant()
  return {
    running: Boolean(detected),
    port: detected?.port ?? null,
    baseUrl: detected ? `http://127.0.0.1:${detected.port}/v1` : null,
    detected,
    files: {
      server: await existsFile(path.join(modelsDir, serverBinName)),
      model: Boolean(ready),
      mmproj: Boolean(ready),
      variant: ready?.variant ?? null,
    },
    progress,
  }
}

export { getAiStatus, downloadAiStack, startAiServer, stopAiServer }

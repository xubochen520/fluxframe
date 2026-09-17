// 平台适配冒烟测试：直接加载编译后的两个被改动模块，验证 Windows / Linux 行为
// 用法（仓库根目录）：node deploy/smoke-platform.mjs
// 只做本机探测（ffmpeg -version、本地端口扫描），不连接数据库、不发起外网请求。
import { locateFfmpeg, getFfmpegStatus } from '../server/dist/ffmpeg-manager.js'
import { getAiStatus } from '../server/dist/ai-manager.js'

const platform = process.platform
console.log(`platform = ${platform}`)

const ffmpeg = await locateFfmpeg()
console.log('locateFfmpeg  ->', ffmpeg ? `${ffmpeg.exe} (version ${ffmpeg.version})` : 'null（未找到，符合预期时可忽略）')
console.log('ffmpeg status ->', JSON.stringify(getFfmpegStatus()))

const ai = await getAiStatus()
console.log('ai status     ->', JSON.stringify({ running: ai.running, port: ai.port, files: ai.files, phase: ai.progress.phase }))

const expectedBin = platform === 'win32' ? 'llama-server.exe' : 'llama-server'
console.log(`\n结论：`)
console.log(`- 平台可执行文件名应为 ${expectedBin}（Linux 无 .exe 后缀）`)
console.log(`- ffmpeg 定位：${ffmpeg ? '正常' : '未找到（Linux 下应装系统 ffmpeg 或设置 FFMPEG_PATH）'}`)
console.log(`- AI 状态接口：正常返回（未抛异常）`)

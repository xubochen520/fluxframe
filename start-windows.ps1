[CmdletBinding()]
param(
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

function Fail([string]$message) {
  Write-Host "`n[启动失败] $message" -ForegroundColor Red
  Write-Host '按回车键退出。' -ForegroundColor DarkGray
  [void](Read-Host)
  exit 1
}

function Run-Step([string]$title, [scriptblock]$command) {
  Write-Host "`n[$title]" -ForegroundColor Cyan
  & $command
  if ($LASTEXITCODE -ne 0) { Fail "$title 失败。退出代码：$LASTEXITCODE" }
}

function Stop-ProjectProcesses {
  $names = @('node.exe', 'npm.cmd', 'cmd.exe', 'esbuild.exe')
  $processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessId -ne $PID -and
    $_.Name -in $names -and
    $_.CommandLine -and
    # 项目进程特征：命令行含项目路径（dev），或为发布模式服务（node dist/index.js，无路径）
    ($_.CommandLine.Contains($projectRoot) -or $_.CommandLine -match 'dist/index\.js')
  })
  foreach ($process in $processes) {
    Write-Host "正在停止旧的项目进程：$($process.Name) [$($process.ProcessId)]" -ForegroundColor DarkGray
    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
  }
  if ($processes.Count -gt 0) { Start-Sleep -Seconds 2 }
}

function Is-Admin {
  $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
  return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-LanAddresses {
  $list = @()
  try {
    $list = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
      Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
      Select-Object -ExpandProperty IPAddress)
  } catch { }
  if ($list.Count -eq 0) {
    $parsed = @(ipconfig | Select-String -Pattern 'IPv4' | ForEach-Object { ($_ -split ':')[-1].Trim() } | Where-Object { $_ -notlike '127.*' -and $_ -notlike '169.254.*' })
    $list = $parsed
  }
  return $list
}

Write-Host 'Fluxframe 内网图片管理器' -ForegroundColor Magenta
Write-Host "项目路径：$projectRoot" -ForegroundColor DarkGray

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail '未找到 Node.js LTS。' }
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { Fail '在 PATH 中未找到 npm。' }

if (-not (Test-Path "$projectRoot\.env")) {
  Copy-Item "$projectRoot\.env.example" "$projectRoot\.env"
  Write-Host '已根据 .env.example 创建 .env。' -ForegroundColor Yellow
  Fail '请检查 .env 中的 DATABASE_URL，然后重新运行本脚本。'
}

$postgresServices = @(Get-Service -Name 'postgresql-x64-*' -ErrorAction SilentlyContinue)
if ($postgresServices.Count -eq 0) {
  Fail '未找到 PostgreSQL Windows 服务。请先安装 PostgreSQL 17。'
}

$postgresService = $postgresServices | Select-Object -First 1
if ($postgresService.Status -ne 'Running') {
  Write-Host "正在启动 PostgreSQL 服务：$($postgresService.Name)" -ForegroundColor Cyan
  try { Start-Service -Name $postgresService.Name -ErrorAction Stop } catch { Fail "无法启动 PostgreSQL。请运行：Start-Service $($postgresService.Name)" }
}

$portReady = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
  if (Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -InformationLevel Quiet -WarningAction SilentlyContinue) { $portReady = $true; break }
  Start-Sleep -Seconds 1
}
if (-not $portReady) { Fail '无法访问 5432 端口上的 PostgreSQL。' }

# Prisma 的 Windows 查询引擎是原生 DLL。在替换生成的客户端文件之前，
# 只停止从本项目启动的旧进程。
Stop-ProjectProcesses

if (-not (Test-Path "$projectRoot\node_modules")) { Run-Step '安装根目录依赖' { npm.cmd install } }
if (-not (Test-Path "$projectRoot\client\node_modules")) { Run-Step '安装客户端依赖' { npm.cmd --prefix client install } }
if (-not (Test-Path "$projectRoot\server\node_modules")) { Run-Step '安装服务端依赖' { npm.cmd --prefix server install } }

Run-Step '生成 Prisma Client' { npm.cmd run db:generate }
Run-Step '应用数据库迁移' { npm.cmd run db:migrate -- --name init }

$apiPort = 4311
try {
  $configuredPort = & node --input-type=module -e 'import "dotenv/config"; import { PrismaClient } from "@prisma/client"; const prisma = new PrismaClient(); const row = await prisma.systemSetting.findUnique({ where: { key: "port" } }); if (typeof row?.value === "number") console.log(row.value); await prisma.$disconnect();' 2>$null
  $parsedPort = 0
  if ($configuredPort -and [int]::TryParse(($configuredPort | Select-Object -First 1).ToString(), [ref]$parsedPort) -and $parsedPort -ge 1024 -and $parsedPort -le 65535) { $apiPort = $parsedPort }
} catch { $apiPort = 4311 }
$env:VITE_API_PORT = [string]$apiPort

# 子进程（concurrently → vite / tsx）的输出全部重定向到日志文件，
# 终端只保留本脚本的提示信息，避免被开发日志刷屏。
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$stdoutLog = Join-Path $logDir 'dev.stdout.log'
$stderrLog = Join-Path $logDir 'dev.stderr.log'

Write-Host "`n数据库已就绪，正在启动客户端和 API……" -ForegroundColor Green
$devProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'npm run dev > logs\dev.stdout.log 2> logs\dev.stderr.log') -WorkingDirectory $projectRoot -PassThru -NoNewWindow
Start-Sleep -Seconds 3

if ($devProcess.HasExited) { Fail "开发进程启动失败，请查看日志：$stderrLog" }

if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
Write-Host '客户端：http://localhost:5173' -ForegroundColor Green
Write-Host "API：    http://localhost:$apiPort" -ForegroundColor Green

# ---- 放行防火墙并提示局域网地址（手机可访问） ----
if (Is-Admin) {
  foreach ($rule in @(@{ Name = "Fluxframe API $apiPort"; Port = $apiPort }, @{ Name = 'Fluxframe Vite 5173'; Port = 5173 })) {
    try {
      & netsh.exe advfirewall firewall delete rule name="$($rule.Name)" 2>$null | Out-Null
      & netsh.exe advfirewall firewall add rule name="$($rule.Name)" dir=in action=allow protocol=TCP localport=$($rule.Port) 2>$null | Out-Null
    } catch { }
  }
  Write-Host '防火墙已放行（4311 API + 5173 开发页），手机可访问' -ForegroundColor Green
} else {
  Write-Host '提示：当前不是管理员，未自动放行防火墙。' -ForegroundColor Yellow
  Write-Host '若手机无法连接，请以管理员身份运行一次本脚本（或执行以下命令）：' -ForegroundColor Yellow
  Write-Host "  netsh advfirewall firewall add rule name=`"Fluxframe API $apiPort`" dir=in action=allow protocol=TCP localport=$apiPort" -ForegroundColor DarkGray
  Write-Host '  netsh advfirewall firewall add rule name="Fluxframe Vite 5173" dir=in action=allow protocol=TCP localport=5173' -ForegroundColor DarkGray
}
$lanAddresses = Get-LanAddresses
if ($lanAddresses.Count -gt 0) {
  Write-Host "`n手机同 Wi-Fi 访问：" -ForegroundColor Magenta
  foreach ($address in $lanAddresses) {
    Write-Host "  http://$address`:$apiPort  （App 自动扫描或浏览器访问）" -ForegroundColor Cyan
  }
}

Write-Host "开发日志已写入：$stdoutLog" -ForegroundColor DarkGray
Write-Host '按 Ctrl+C 停止开发服务。' -ForegroundColor DarkGray

Wait-Process -Id $devProcess.Id

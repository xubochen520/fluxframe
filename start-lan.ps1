[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$NoFirewall
)

# ============================================================
# 内网图片管理器 · 局域网一键发布
# 自动构建前端与后端 → 以发布模式启动（单端口 4311，同时提供
# 页面与 API）→ 放行防火墙 → 显示局域网地址。
# 手机端：打开「内网图片管理」App 会自动扫描并连接，无需输入。
# ============================================================

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
    $_.CommandLine.Contains($projectRoot)
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

Write-Host '内网图片管理器 · 局域网发布' -ForegroundColor Magenta
Write-Host "项目路径：$projectRoot" -ForegroundColor DarkGray

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail '未找到 Node.js。' }
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { Fail '在 PATH 中未找到 npm。' }

if (-not (Test-Path "$projectRoot\.env")) {
  Copy-Item "$projectRoot\.env.example" "$projectRoot\.env"
  Write-Host '已根据 .env.example 创建 .env。' -ForegroundColor Yellow
  Fail '请检查 .env 中的 DATABASE_URL，然后重新运行本脚本。'
}

$postgresServices = @(Get-Service -Name 'postgresql-x64-*' -ErrorAction SilentlyContinue)
if ($postgresServices.Count -eq 0) { Fail '未找到 PostgreSQL Windows 服务。' }
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

# 只停止从本项目启动的旧进程（避免误杀其它 Node 服务）
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

# ---- 自动构建前端（源码比 dist 新或产物缺失时） ----
$distHtml = "$projectRoot\client\dist\index.html"
$newestSource = Get-ChildItem "$projectRoot\client\src" -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$needClientBuild = -not (Test-Path $distHtml) -or ($newestSource -and $newestSource.LastWriteTime -gt (Get-Item $distHtml).LastWriteTime)
if (-not $SkipBuild -and $needClientBuild) {
  Run-Step '构建前端页面' { npm.cmd --prefix client run build }
} elseif ($SkipBuild) {
  Write-Host '[跳过构建] 已按参数跳过前端构建。' -ForegroundColor DarkGray
} else {
  Write-Host '[前端产物已是最新，跳过构建]' -ForegroundColor DarkGray
}

# ---- 自动构建后端 ----
$serverOut = "$projectRoot\server\dist\index.js"
$newestServerSource = Get-ChildItem "$projectRoot\server\src" -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$needServerBuild = -not (Test-Path $serverOut) -or ($newestServerSource -and $newestServerSource.LastWriteTime -gt (Get-Item $serverOut).LastWriteTime)
if (-not $SkipBuild -and $needServerBuild) {
  Run-Step '构建后端服务' { npm.cmd --prefix server run build }
} elseif ($SkipBuild) {
  Write-Host '[跳过构建] 已按参数跳过后端构建。' -ForegroundColor DarkGray
} else {
  Write-Host '[后端产物已是最新，跳过构建]' -ForegroundColor DarkGray
}

# ---- 把 .env 注入环境变量（子进程继承），与开发模式行为一致 ----
Get-Content "$projectRoot\.env" | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith('#')) {
    $index = $line.IndexOf('=')
    if ($index -gt 0) {
      $key = $line.Substring(0, $index).Trim()
      $value = $line.Substring($index + 1).Trim().Trim('"').Trim("'")
      Set-Item -Path "env:$key" -Value $value -ErrorAction SilentlyContinue
    }
  }
}
$env:PORT = [string]$apiPort

# ---- 启动发布模式后端（node dist/index.js，日志写入 logs/server-lan.log） ----
$logDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$lanLog = Join-Path $logDir 'server-lan.log'
Write-Host "`n正在启动局域网服务（端口 $apiPort）……" -ForegroundColor Green
$serverProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'node dist/index.js > ..\logs\server-lan.log 2>&1') -WorkingDirectory "$projectRoot\server" -PassThru
Start-Sleep -Seconds 2
if ($serverProcess.HasExited) { Fail "服务进程退出。请查看日志：$lanLog" }

$healthy = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
  if ($serverProcess.HasExited) { Fail "服务进程退出。请查看日志：$lanLog" }
  try {
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:$apiPort/api/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
    if ($health.StatusCode -eq 200) { $healthy = $true; break }
  } catch { }
  Start-Sleep -Seconds 1
}
if (-not $healthy) { Fail "服务未就绪。请查看日志：$lanLog" }
Write-Host '服务已就绪 ✓' -ForegroundColor Green

# ---- 防火墙放行（管理员可直接放行，否则给出提示命令） ----
if (-not $NoFirewall) {
  if (Is-Admin) {
    try {
      & netsh.exe advfirewall firewall delete rule name="Fluxframe API $apiPort" 2>$null | Out-Null
      & netsh.exe advfirewall firewall add rule name="Fluxframe API $apiPort" dir=in action=allow protocol=TCP localport=$apiPort | Out-Null
      Write-Host "防火墙已放行 TCP $apiPort（局域网可访问）" -ForegroundColor Green
    } catch {
      Write-Host '防火墙放行失败，可手动执行：' -ForegroundColor Yellow
      Write-Host "  netsh advfirewall firewall add rule name=`"Fluxframe API $apiPort`" dir=in action=allow protocol=TCP localport=$apiPort" -ForegroundColor DarkGray
    }
  } else {
    Write-Host '提示：当前不是管理员，未自动放行防火墙。' -ForegroundColor Yellow
    Write-Host '若手机无法连接，请以管理员身份运行 PowerShell 执行：' -ForegroundColor Yellow
    Write-Host "  netsh advfirewall firewall add rule name=`"Fluxframe API $apiPort`" dir=in action=allow protocol=TCP localport=$apiPort" -ForegroundColor DarkGray
  }
}

# ---- 输出局域网访问信息 ----
Write-Host "`n========== 局域网访问 ==========" -ForegroundColor Magenta
$lanAddresses = Get-LanAddresses
if ($lanAddresses.Count -eq 0) {
  Write-Host '未检测到局域网 IPv4 地址，请用 ipconfig 查看电脑 IP。' -ForegroundColor Yellow
} else {
  foreach ($address in $lanAddresses) {
    Write-Host "  手机浏览器：http://$address`:$apiPort" -ForegroundColor Cyan
    Write-Host "  手机 App  ：自动扫描连接（或手动输入 $address`:$apiPort）" -ForegroundColor Cyan
  }
}
Write-Host '================================' -ForegroundColor Magenta
Write-Host '服务日志：logs\server-lan.log' -ForegroundColor DarkGray
Write-Host '按 Ctrl+C 停止局域网服务。' -ForegroundColor DarkGray

Wait-Process -Id $serverProcess.Id

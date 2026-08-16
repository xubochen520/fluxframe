[CmdletBinding()]
param(
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

function Fail([string]$message) {
  Write-Host "`n[START FAILED] $message" -ForegroundColor Red
  Write-Host 'Press Enter to exit.' -ForegroundColor DarkGray
  [void](Read-Host)
  exit 1
}

function Run-Step([string]$title, [scriptblock]$command) {
  Write-Host "`n[$title]" -ForegroundColor Cyan
  & $command
  if ($LASTEXITCODE -ne 0) { Fail "$title failed. Exit code: $LASTEXITCODE" }
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
    Write-Host "Stopping previous project process: $($process.Name) [$($process.ProcessId)]" -ForegroundColor DarkGray
    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
  }
  if ($processes.Count -gt 0) { Start-Sleep -Seconds 2 }
}

Write-Host 'Fluxframe Intranet Image Manager' -ForegroundColor Magenta
Write-Host "Project: $projectRoot" -ForegroundColor DarkGray

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'Node.js LTS was not found.' }
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { Fail 'npm was not found in PATH.' }

if (-not (Test-Path "$projectRoot\.env")) {
  Copy-Item "$projectRoot\.env.example" "$projectRoot\.env"
  Write-Host 'Created .env from .env.example.' -ForegroundColor Yellow
  Fail 'Check DATABASE_URL in .env, then run this script again.'
}

$postgresServices = @(Get-Service -Name 'postgresql-x64-*' -ErrorAction SilentlyContinue)
if ($postgresServices.Count -eq 0) {
  Fail 'PostgreSQL Windows service was not found. Install PostgreSQL 17 first.'
}

$postgresService = $postgresServices | Select-Object -First 1
if ($postgresService.Status -ne 'Running') {
  Write-Host "Starting PostgreSQL service: $($postgresService.Name)" -ForegroundColor Cyan
  try { Start-Service -Name $postgresService.Name -ErrorAction Stop } catch { Fail "Cannot start PostgreSQL. Run: Start-Service $($postgresService.Name)" }
}

$portReady = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
  if (Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -InformationLevel Quiet -WarningAction SilentlyContinue) { $portReady = $true; break }
  Start-Sleep -Seconds 1
}
if (-not $portReady) { Fail 'PostgreSQL is not reachable on port 5432.' }

# Prisma's Windows query engine is a native DLL. Stop only old processes
# launched from this project before replacing the generated client files.
Stop-ProjectProcesses

if (-not (Test-Path "$projectRoot\node_modules")) { Run-Step 'Install root dependencies' { npm.cmd install } }
if (-not (Test-Path "$projectRoot\client\node_modules")) { Run-Step 'Install client dependencies' { npm.cmd --prefix client install } }
if (-not (Test-Path "$projectRoot\server\node_modules")) { Run-Step 'Install server dependencies' { npm.cmd --prefix server install } }

Run-Step 'Generate Prisma Client' { npm.cmd run db:generate }
Run-Step 'Apply database migrations' { npm.cmd run db:migrate -- --name init }

$apiPort = 4311
try {
  $configuredPort = & node --input-type=module -e 'import "dotenv/config"; import { PrismaClient } from "@prisma/client"; const prisma = new PrismaClient(); const row = await prisma.systemSetting.findUnique({ where: { key: "port" } }); if (typeof row?.value === "number") console.log(row.value); await prisma.$disconnect();' 2>$null
  $parsedPort = 0
  if ($configuredPort -and [int]::TryParse(($configuredPort | Select-Object -First 1).ToString(), [ref]$parsedPort) -and $parsedPort -ge 1024 -and $parsedPort -le 65535) { $apiPort = $parsedPort }
} catch { $apiPort = 4311 }
$env:VITE_API_PORT = [string]$apiPort

Write-Host "`nDatabase is ready. Starting client and API..." -ForegroundColor Green
$devProcess = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev') -WorkingDirectory $projectRoot -PassThru -NoNewWindow
Start-Sleep -Seconds 3

if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
Write-Host 'Client: http://localhost:5173' -ForegroundColor Green
Write-Host "API:    http://localhost:$apiPort" -ForegroundColor Green
Write-Host 'Press Ctrl+C to stop the development services.' -ForegroundColor DarkGray

Wait-Process -Id $devProcess.Id

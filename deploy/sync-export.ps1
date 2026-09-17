#requires -Version 5.1
<#
  Export local Windows data for syncing to the Linux deployment:
    1) pg_dump of the local image_manager database  -> deploy/out/db.sql
    2) tar of the local storage directory           -> deploy/out/storage.tar
  Usage: powershell -ExecutionPolicy Bypass -File deploy\sync-export.ps1
  ASCII-only on purpose (Windows PowerShell 5.1 parses it regardless of file encoding).
#>
[CmdletBinding()]
param(
  [string]$PgBin = 'C:\Program Files\PostgreSQL\17\bin',
  [string]$StorageDir,
  [string]$Out
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Out) { $Out = Join-Path $root 'deploy\out' }
New-Item -ItemType Directory -Path $Out -Force | Out-Null

# ---- 1. parse DATABASE_URL from the local .env ----
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw ".env not found: $envFile" }
$dbLine = (Get-Content $envFile) | Where-Object { $_ -match '^\s*DATABASE_URL\s*=' } | Select-Object -First 1
if (-not $dbLine) { throw 'DATABASE_URL not found in .env' }
$url = ($dbLine -split '=', 2)[1].Trim().Trim('"')
$m = [regex]::Match($url, '^postgresql://(?<user>[^:]+):(?<pass>[^@]*)@(?<host>[^:/]+):(?<port>\d+)/(?<db>[^?]+)')
if (-not $m.Success) { throw "cannot parse DATABASE_URL: $url" }
$dbUser = $m.Groups['user'].Value
$dbPass = $m.Groups['pass'].Value
$dbHost = $m.Groups['host'].Value
$dbPort = $m.Groups['port'].Value
$dbName = $m.Groups['db'].Value
Write-Host "database : $dbName @ ${dbHost}:${dbPort} (user $dbUser)" -ForegroundColor Cyan

# ---- 2. resolve storage dir (prefer the value saved in the database) ----
$pgDump = Join-Path $PgBin 'pg_dump.exe'
$psql = Join-Path $PgBin 'psql.exe'
if (-not (Test-Path $pgDump)) { throw "pg_dump.exe not found in $PgBin" }

$env:PGPASSWORD = $dbPass
$env:PGCLIENTENCODING = 'UTF8'
if (-not $StorageDir) {
  $sqlFile = Join-Path $env:TEMP ('fluxframe-storage-' + [guid]::NewGuid().ToString('N').Substring(0, 6) + '.sql')
  'SELECT value #>> ''{}'' FROM "SystemSetting" WHERE key = ''storageDir'';' | Set-Content -Path $sqlFile -Encoding ASCII
  $saved = (& $psql -U $dbUser -h $dbHost -p $dbPort -d $dbName -t -A -f $sqlFile) | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1
  Remove-Item $sqlFile -Force -ErrorAction SilentlyContinue
  if ($saved) { $StorageDir = $saved.Trim() } else { $StorageDir = Join-Path $root 'server\storage' }
}
Write-Host "storage  : $StorageDir" -ForegroundColor Cyan
if (-not (Test-Path $StorageDir)) { throw "storage dir not found: $StorageDir" }

# ---- 3. dump database ----
$dumpFile = Join-Path $Out 'db.sql'
if (Test-Path $dumpFile) { Remove-Item $dumpFile -Force }
Write-Host "`n[1/2] pg_dump -> $dumpFile" -ForegroundColor Cyan
& $pgDump -U $dbUser -h $dbHost -p $dbPort -d $dbName `
  --clean --if-exists --no-owner --no-privileges --encoding=UTF8 `
  -f $dumpFile
if ($LASTEXITCODE -ne 0) { throw "pg_dump failed (exit $LASTEXITCODE)" }
Write-Host ("      db dump: {0:N1} MB" -f ((Get-Item $dumpFile).Length / 1MB)) -ForegroundColor Green

# ---- 4. tar storage (images are already compressed: no gzip) ----
$tarFile = Join-Path $Out 'storage.tar'
if (Test-Path $tarFile) { Remove-Item $tarFile -Force }
Write-Host "[2/2] tar storage -> $tarFile" -ForegroundColor Cyan
$subDirs = @()
foreach ($d in @('originals', 'thumbnails', 'trash')) {
  if (Test-Path (Join-Path $StorageDir $d)) { $subDirs += $d }
}
if ($subDirs.Count -eq 0) { throw "no originals/thumbnails/trash under $StorageDir" }
& tar -cf $tarFile -C $StorageDir @subDirs
if ($LASTEXITCODE -ne 0) { throw "tar failed (exit $LASTEXITCODE)" }
Write-Host ("      storage : {0:N1} MB ({1})" -f ((Get-Item $tarFile).Length / 1MB), ($subDirs -join ', ')) -ForegroundColor Green

Write-Host "`nready to upload:" -ForegroundColor Yellow
Write-Host "  $dumpFile"
Write-Host "  $tarFile"

#requires -Version 5.1
<#
  Pack the Linux/Docker deployment bundle for the intranet image manager.
  Includes source + deploy config; excludes node_modules / dist / android / models / logs.
  Output: deploy\out\fluxframe-linux.tar.gz
  Usage: powershell -ExecutionPolicy Bypass -File deploy\pack.ps1
  NOTE: kept ASCII-only so Windows PowerShell 5.1 parses it regardless of file encoding.
#>
[CmdletBinding()]
param(
  [string]$Out
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Out) { $Out = Join-Path $root 'deploy\out\fluxframe-linux.tar.gz' }
$stage = Join-Path $env:TEMP ('fluxframe-pack-' + [guid]::NewGuid().ToString('N').Substring(0, 8))

Write-Host "repo root : $root" -ForegroundColor Cyan
Write-Host "staging   : $stage" -ForegroundColor DarkGray

New-Item -ItemType Directory -Path $stage -Force | Out-Null

function Copy-Tree([string]$rel, [string[]]$xd) {
  $src = Join-Path $root $rel
  $dst = Join-Path $stage $rel
  New-Item -ItemType Directory -Path $dst -Force | Out-Null
  $rcArgs = @($src, $dst, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:1', '/W:1')
  if ($xd.Count -gt 0) { $rcArgs += '/XD'; $rcArgs += $xd }
  & robocopy @rcArgs | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($rel, exit code $LASTEXITCODE)" }
}

foreach ($f in @('package.json', 'package-lock.json', '.dockerignore', 'README.md')) {
  Copy-Item (Join-Path $root $f) (Join-Path $stage $f) -Force
}

Copy-Tree 'client' @((Join-Path $root 'client\node_modules'), (Join-Path $root 'client\dist'), (Join-Path $root 'client\android'), (Join-Path $root 'client\.vite'))
Copy-Tree 'server' @((Join-Path $root 'server\node_modules'), (Join-Path $root 'server\dist'), (Join-Path $root 'server\storage'))
Copy-Tree 'prisma' @()
Copy-Tree 'deploy' @((Join-Path $root 'deploy\data'), (Join-Path $root 'deploy\out'))

$outDir = Split-Path -Parent $Out
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
if (Test-Path $Out) { Remove-Item $Out -Force }
& tar -czf $Out -C $stage .
if ($LASTEXITCODE -ne 0) { throw "tar failed (exit code $LASTEXITCODE)" }

$sizeMb = [math]::Round((Get-Item $Out).Length / 1MB, 2)
Write-Host "bundle ready: $Out ($sizeMb MB)" -ForegroundColor Green

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

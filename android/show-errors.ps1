param([string]$Log = "build-log.txt", [int]$Max = 220)
# 从 Gradle 日志抽取 Kotlin 编译错误。
#
# 前提：日志必须由 cmd 重定向产生（`cmd /c "gradlew.bat ... > build-log.txt 2>&1"`）。
# 如果用 PowerShell 的 `*>`，stderr 会被包装成 NativeCommandError，中文路径与消息
# 都会被拆散，无法可靠解析。
#
# 日志是控制台代码页（本机为 936/GBK）编码，所以按 936 解码。
$base = (Get-Location).Path
$path = if ([System.IO.Path]::IsPathRooted($Log)) { $Log } else { "$base\$Log" }
if (-not (Test-Path $path)) { Write-Output "LOG NOT FOUND: $path"; return }
$enc = [System.Text.Encoding]::GetEncoding(936)
$raw = [System.IO.File]::ReadAllText($path, $enc)
$lines = $raw -split "`r?`n"
$errs = $lines | Where-Object { $_ -match '^e: ' }
if (-not $errs) {
    Write-Output 'NO KOTLIN ERRORS'
    return
}
$errs | ForEach-Object {
    $msg = $_ -replace '^.*app/src/main/java/com/fluxframe/app/', ''
    if ($msg.Length -gt $Max) { $msg = $msg.Substring(0, $Max) }
    Write-Output $msg
}
Write-Output ("--- total: " + $errs.Count + " ---")

# Prepare a headless Android emulator for smoke-testing the native client.
# NOTE: ASCII-only on purpose - Windows PowerShell 5.1 reads .ps1 as GBK and
# non-ASCII text breaks the parser.
$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$tmp = "$env:TEMP\fluxframe-emu"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Step($msg) { Write-Output "[emu] $msg" }

$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$avdmanager = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"

# 1. cmdline-tools
if (-not (Test-Path $sdkmanager)) {
    $clt = "$tmp\clt.zip"
    if (-not (Test-Path $clt)) {
        Step "downloading cmdline-tools"
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $clt -TimeoutSec 900
    }
    Step "extracting cmdline-tools"
    if (Test-Path "$tmp\clt") { Remove-Item "$tmp\clt" -Recurse -Force }
    Expand-Archive -Path $clt -DestinationPath "$tmp\clt" -Force
    New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
    if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
    Move-Item "$tmp\clt\cmdline-tools" "$sdk\cmdline-tools\latest"
}
if (-not (Test-Path $sdkmanager)) { Step "FATAL: sdkmanager missing"; exit 1 }
Step "sdkmanager ok"

# 2. accept licenses
Step "accepting licenses"
$yes = ("y`r`n" * 40)
$yes | & $sdkmanager --sdk_root="$sdk" --licenses 2>&1 | Select-Object -Last 2

# 3. emulator + system image
Step "installing emulator + system image (about 1.4 GB)"
& $sdkmanager --sdk_root="$sdk" "platform-tools" "emulator" "system-images;android-35;google_apis;x86_64" 2>&1 | Select-Object -Last 6

# 4. create AVD
Step "creating AVD"
"no" | & $avdmanager create avd -n fluxframe-test -k "system-images;android-35;google_apis;x86_64" -d pixel_6 --force 2>&1 | Select-Object -Last 4

Step "DONE"

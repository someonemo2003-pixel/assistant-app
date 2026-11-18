# Nova: installs and sets up Termux on the phone over adb, from this laptop.
# Run in PowerShell from this folder:  powershell -ExecutionPolicy Bypass -File .\setup-termux.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

function Step($t) { Write-Host "`n==> $t" -ForegroundColor Cyan }

Step "Checking the phone"
$dev = (adb devices) | Select-String "`tdevice$"
if (-not $dev) { Write-Host "No phone found. Connect it with USB debugging on, then run again." -ForegroundColor Red; exit 1 }

$installed = (adb shell pm list packages com.termux) -match "package:com.termux$"
if (-not $installed) {
    Step "Downloading Termux from GitHub (official)"
    $rel = Invoke-RestMethod "https://api.github.com/repos/termux/termux-app/releases/latest" -Headers @{ "User-Agent" = "nova" }
    $asset = $rel.assets | Where-Object { $_.name -match "arm64-v8a.*\.apk$" } | Select-Object -First 1
    if (-not $asset) { Write-Host "Couldn't find the arm64 APK in the latest release." -ForegroundColor Red; exit 1 }
    $apk = Join-Path $here $asset.name
    Invoke-WebRequest $asset.browser_download_url -OutFile $apk -Headers @{ "User-Agent" = "nova" }
    Step "Installing $($asset.name)"
    adb install -r "$apk"
} else {
    Write-Host "Termux is already installed."
}

Step "Opening Termux once so it prepares itself"
adb shell am start -n com.termux/.app.TermuxActivity | Out-Null
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 3
    $r = adb shell "run-as com.termux ls /data/data/com.termux/files/usr/bin/bash 2>/dev/null"
    if ($r -match "bash") { $ok = $true; break }
}
if (-not $ok) {
    Write-Host "Termux isn't ready, or this Termux build doesn't allow adb access (use the GitHub build, not F-Droid/Play)." -ForegroundColor Red
    exit 1
}

Step "Keeping Termux alive in the background"
adb shell dumpsys deviceidle whitelist +com.termux | Out-Null

Step "Copying the setup script to Termux"
$dest = "/data/data/com.termux/files/home/nova-setup.sh"
$src = Join-Path $here "nova-setup.sh"
cmd /c ('adb exec-in "run-as com.termux sh -c ''cat > ' + $dest + '''" < "' + $src + '"')
$size = adb shell "run-as com.termux wc -c $dest"
if (-not ($size -match "^\s*[1-9]")) { Write-Host "Couldn't copy the setup script." -ForegroundColor Red; exit 1 }

Step "Running the setup (10-20 minutes; answer the sign-in questions at the end)"
adb shell -t "run-as com.termux /data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/nova-setup.sh"

Write-Host "`nAll done. On the phone, ask Nova: run ls in the terminal" -ForegroundColor Green

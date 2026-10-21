# TabDraw_Native — WiFi ADB 連線 + 安裝腳本 (PowerShell)
# 使用方式：
#   .\install_wifi.ps1 -TabletIP 192.168.1.xxx

param(
    [string]$TabletIP = "",
    [int]$AdbPort = 5555,
    [switch]$ConnectOnly
)

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$APK = ".\app\build\outputs\apk\debug\app-debug.apk"
$PKG = "com.tabdraw.native_client"
$ACT = "$PKG/.MainActivity"

function Write-Step($n, $msg) {
    Write-Host "`n[$n] $msg" -ForegroundColor Cyan
}

Write-Host "`n╔══════════════════════════════════════╗" -ForegroundColor Blue
Write-Host "║  TabDraw Native - Install & Launch   ║" -ForegroundColor Blue
Write-Host "╚══════════════════════════════════════╝" -ForegroundColor Blue

# ── Step 1: List devices ────────────────────────────────────────
Write-Step 1 "Connected ADB devices:"
& $ADB devices -l

# ── Step 2: WiFi connect (if IP provided) ──────────────────────
if ($TabletIP -ne "") {
    Write-Step 2 "Connecting to $TabletIP:$AdbPort via WiFi..."
    
    # 先嘗試啟用 tcpip 模式（需要 USB 連線）
    $devices = & $ADB devices | Where-Object { $_ -match "device$" }
    if ($devices) {
        Write-Host "  Enabling tcpip mode on USB device..." -ForegroundColor Yellow
        & $ADB tcpip $AdbPort | Out-Null
        Start-Sleep -Seconds 2
    }
    
    $result = & $ADB connect "${TabletIP}:${AdbPort}" 2>&1
    Write-Host "  $result"
    Start-Sleep -Seconds 1
}

if ($ConnectOnly) {
    Write-Host "`n[Done] Connection only mode. Exiting." -ForegroundColor Green
    exit 0
}

# ── Step 3: Check APK exists ───────────────────────────────────
Write-Step 3 "APK Check:"
if (Test-Path $APK) {
    $size = [math]::Round((Get-Item $APK).Length / 1MB, 2)
    Write-Host "  ✓ $APK ($size MB)" -ForegroundColor Green
} else {
    Write-Host "  ✗ APK not found! Build first with:" -ForegroundColor Red
    Write-Host '    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"' -ForegroundColor Yellow
    Write-Host '    $env:GRADLE_OPTS = "-Djavax.net.ssl.trustStore=wintruststore.jks -Djavax.net.ssl.trustStorePassword=changeit"' -ForegroundColor Yellow
    Write-Host '    .\gradle\bin\gradle.bat assembleDebug --project-dir .' -ForegroundColor Yellow
    exit 1
}

# ── Step 4: Install APK ────────────────────────────────────────
Write-Step 4 "Installing APK..."
$installResult = & $ADB install -r $APK 2>&1
if ($installResult -match "Success") {
    Write-Host "  ✓ Installation successful!" -ForegroundColor Green
} else {
    Write-Host "  ✗ Installation failed:" -ForegroundColor Red
    Write-Host "  $installResult" -ForegroundColor Red
    exit 1
}

# ── Step 5: Launch app ─────────────────────────────────────────
Write-Step 5 "Launching TabDraw Native..."
& $ADB shell am start -n $ACT
Start-Sleep -Seconds 2

# ── Step 6: Show logcat (Ctrl+C to stop) ──────────────────────
Write-Step 6 "Logcat (UdpTransmitter | StylusCanvasView) — Ctrl+C to stop:"
& $ADB logcat -s "UdpTransmitter:D" "StylusCanvasView:D" -v time

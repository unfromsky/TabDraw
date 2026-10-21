@echo off
REM TabDraw_Native — 安裝腳本
REM ===================================================
REM 使用方式：
REM   1. USB 模式：平板插線 → 開啟 USB 除錯 → 執行此腳本
REM   2. WiFi 模式：先 USB 插線執行一次，之後可拔線
REM ===================================================

SET ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
SET APK=app\build\outputs\apk\debug\app-debug.apk

echo.
echo ==========================================
echo  TabDraw Native - ADB Install Script
echo ==========================================
echo.

REM 列出設備
echo [1] Connected devices:
"%ADB%" devices -l
echo.

REM 檢查是否有設備
FOR /F "skip=1 tokens=1" %%D IN ('"%ADB%" devices') DO (
    IF "%%D" NEQ "" (
        echo [2] Installing APK to %%D ...
        "%ADB%" -s %%D install -r "%APK%"
        echo.
        echo [3] Launching TabDraw Native...
        "%ADB%" -s %%D shell am start -n com.tabdraw.native_client/.MainActivity
        echo.
        echo [4] Done! Check your tablet.
        pause
        exit /b 0
    )
)

echo.
echo [!] No device found. Please:
echo     - Connect tablet via USB
echo     - Enable USB Debugging in Developer Options
echo     - Accept RSA fingerprint on tablet
echo.
echo [WiFi ADB - Alternative]:
echo   1. Connect USB once and run:
echo      %ADB% tcpip 5555
echo   2. Find tablet IP in Settings > About > IP address
echo   3. Run: %ADB% connect TABLET_IP:5555
echo   4. Unplug USB and run this script again
echo.
pause

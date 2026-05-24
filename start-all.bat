@echo off
setlocal EnableExtensions EnableDelayedExpansion
title SmartHelp+ One-Click Launcher
echo ============================================
echo   SmartHelp+ One-Click Launcher
echo ============================================
echo.

set "ADB=C:\Users\HP\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "SERVER_DIR=%~dp0server-python"
set "VENV_PYTHON=%SERVER_DIR%\venv\Scripts\python.exe"
set "AGENT_WRAPPER=%~dp0_run-agent.bat"
set "APP_PACKAGE=com.smarthelp.app"

if not exist "%ADB%" (
    where adb >nul 2>&1
    if not errorlevel 1 (
        set "ADB=adb"
    )
)

REM --- Pre-flight checks ---
if not exist "%VENV_PYTHON%" (
    echo [X] Python venv not found:
    echo     %VENV_PYTHON%
    echo     Run setup/install first, then try again.
    pause
    exit /b 1
)
if not exist "%AGENT_WRAPPER%" (
    echo [X] Agent wrapper not found: %AGENT_WRAPPER%
    pause
    exit /b 1
)
echo [OK] Python venv found
echo [OK] Agent wrapper found
echo.

REM --- Auto-detect PC WiFi IP ---
set "PC_IP="
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /C:"IPv4" ^| findstr /V "192.168.56"') do (
    for /f "tokens=1" %%b in ("%%a") do set "PC_IP=%%b"
)
if "%PC_IP%"=="" (
    for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /C:"IPv4"') do (
        for /f "tokens=1" %%b in ("%%a") do set "PC_IP=%%b"
    )
)
echo [*] PC IP: %PC_IP%
echo.

REM --- [1/7] Check connected device ---
echo [1/7] Checking connected devices...
"%ADB%" devices
set "DEVICE="
for /f "skip=1 tokens=1" %%d in ('"%ADB%" devices') do (
    if not "%%d"=="" if not "%%d"=="List" (
        if not defined DEVICE set "DEVICE=%%d"
    )
)
if not defined DEVICE (
    echo [!] No device detected. Connect your phone via USB and enable USB debugging.
    echo     The server will still start, but logcat and app launch will be skipped.
)
echo.

REM --- [2/7] Stop old SmartHelp processes ---
echo [2/7] Stopping old services...
taskkill /FI "WINDOWTITLE eq SmartHelp Token Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq SmartHelp Agent*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq SmartHelp Logcat*" /F >nul 2>&1

set "SMARTHELP_SERVER_DIR=%SERVER_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$dir=$env:SMARTHELP_SERVER_DIR; Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($dir) -and ($_.CommandLine -match 'agent.py dev|token_server.py') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8765" ^| findstr "LISTENING"') do (
    taskkill /PID %%p /F >nul 2>&1
)
echo    [OK] Old processes cleared
echo.

REM --- [3/7] Setup adb reverse + push IP ---
if defined DEVICE (
    echo [3/7] Setting up adb reverse + pushing token endpoint...
    "%ADB%" reverse --remove-all 2>nul
    "%ADB%" reverse tcp:8765 tcp:8765 >nul
    if not "%PC_IP%"=="" (
        echo http://%PC_IP%:8765/token > "%TEMP%\smarthelp_server.txt"
        "%ADB%" shell mkdir -p /sdcard/Android/data/%APP_PACKAGE%/files >nul 2>&1
        "%ADB%" push "%TEMP%\smarthelp_server.txt" /sdcard/Android/data/%APP_PACKAGE%/files/smarthelp_server.txt >nul
        del "%TEMP%\smarthelp_server.txt" >nul 2>&1
        echo    [OK] Endpoint pushed: http://%PC_IP%:8765/token
    )
) else (
    echo [3/7] No device - skipping adb reverse/push
)
echo.

REM --- [4/7] Firewall rule ---
echo [4/7] Checking firewall rule...
netsh advfirewall firewall show rule name="SmartHelp Token Server" >nul 2>&1
if errorlevel 1 (
    netsh advfirewall firewall add rule name="SmartHelp Token Server" dir=in action=allow protocol=tcp localport=8765 >nul 2>&1
    if errorlevel 1 (
        echo    [!] Firewall rule failed. Run this bat as Administrator once.
    ) else (
        echo    [OK] Firewall rule added
    )
) else (
    echo    [OK] Firewall rule already exists
)
echo.

REM --- [5/7] Start Token Server ---
echo [5/7] Starting Token Server on port 8765...
start "SmartHelp Token Server" /D "%SERVER_DIR%" cmd /k ""%VENV_PYTHON%" token_server.py"

REM Wait until /health responds (max 12 seconds)
set "TOKEN_OK="
for /l %%i in (1,1,12) do (
    if not defined TOKEN_OK (
        timeout /t 1 /nobreak >nul
        curl -s -o nul -w "%%{http_code}" http://127.0.0.1:8765/health 2>nul | findstr "200" >nul
        if not errorlevel 1 set "TOKEN_OK=1"
    )
)
if defined TOKEN_OK (
    echo    [OK] Token Server responding
) else (
    echo    [!] Token Server not responding. Check the Token Server window.
)
echo.

REM --- [6/7] Start LiveKit Agent (auto-restart) ---
echo [6/7] Starting LiveKit Agent ^(auto-restart enabled^)...
start "SmartHelp Agent" /D "%SERVER_DIR%" cmd /k call "%AGENT_WRAPPER%" "%VENV_PYTHON%"
echo    [OK] Agent launcher started. It will auto-restart if it exits.
echo.

REM --- [7/7] Launch app + logcat ---
if defined DEVICE (
    echo [7/7] Launching SmartHelp+ app on %DEVICE%...
    "%ADB%" -s %DEVICE% shell monkey -p %APP_PACKAGE% -c android.intent.category.LAUNCHER 1 >nul 2>&1
    if errorlevel 1 (
        echo    [!] Could not launch app. Is the APK installed?
    ) else (
        echo    [OK] App launched
    )

    echo    Starting logcat in a separate window...
    start "SmartHelp Logcat" cmd /k ""%ADB%" -s %DEVICE% logcat -s ServerConnection SmartHelp.Overlay SmartHelp.Accessibility MainActivity smarthelp.agent"
) else (
    echo [7/7] No device - skipping app launch and logcat
)
echo.

echo ============================================
echo   All services started!
echo ============================================
echo   Token Server  : http://127.0.0.1:8765
echo   LiveKit Agent : auto-restart on
echo   App package   : %APP_PACKAGE%
echo   PC IP         : %PC_IP%
echo   Phone endpoint: http://%PC_IP%:8765/token
if defined DEVICE echo   Device        : %DEVICE%
echo ============================================
echo.
echo Phone and PC must be on the same WiFi.
echo To stop everything: run stop-all.bat
echo.
echo This launcher window closes in 5 seconds...
timeout /t 5 /nobreak >nul
exit /b 0

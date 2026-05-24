@echo off
title SmartHelp+ Stop
echo Stopping all SmartHelp+ services...

REM Close visible windows
taskkill /FI "WINDOWTITLE eq SmartHelp Token Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq SmartHelp Agent*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq SmartHelp Logcat*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq SmartHelp+ Launcher*" /F >nul 2>&1

REM Kill any python.exe still running our scripts
set "SERVER_DIR=%~dp0server-python"
set "SMARTHELP_SERVER_DIR=%SERVER_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$dir=$env:SMARTHELP_SERVER_DIR; Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($dir) -and ($_.CommandLine -match 'agent.py dev|token_server.py') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

REM Kill anything on port 8765
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8765" ^| findstr "LISTENING"') do (
    taskkill /PID %%p /F >nul 2>&1
)

echo Done. All services stopped.
timeout /t 2 /nobreak >nul

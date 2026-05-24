@echo off
REM Auto-restart wrapper for SmartHelp+ LiveKit Agent.
REM Called from start-all.bat. Do not run directly.
REM
REM Usage: _run-agent.bat "<full path to venv python.exe>"
REM
REM Restart policy:
REM   - If agent stays alive >= 60s, RESTART_BURST resets to 0.
REM   - If 5 restarts happen back-to-back (each crash within 60s of the previous),
REM     pause for human inspection so a real bug is not silently masked.

setlocal EnableExtensions
title SmartHelp Agent (auto-restart)

if "%~1"=="" (
    echo ERROR: Python path not provided.
    echo This script is meant to be launched from start-all.bat.
    pause
    exit /b 1
)

set "AGENT_PY=%~1"
set "RESTART_TOTAL=0"
set "RESTART_BURST=0"
set "BURST_CAP=5"
set "STABLE_SECONDS=60"

:loop
echo.
echo ============================================
echo   Starting LiveKit Agent  ^(total restarts: %RESTART_TOTAL%, burst: %RESTART_BURST%/%BURST_CAP%^)
echo   %date% %time%
echo ============================================

REM Record the start time so we can detect "stayed alive long enough".
for /f %%t in ('powershell -NoProfile -Command "[int][double]::Parse((Get-Date -UFormat %%s))"') do set "START_EPOCH=%%t"

REM Force unbuffered stdout so logger.info appears in real time.
set PYTHONUNBUFFERED=1

REM Use `start` (single-process) instead of `dev` (forks a job subprocess on
REM Windows whose stdout often does not reach this console). All logs from
REM the entrypoint — Gemini Vision, ReAct decisions, FSM transitions — flow
REM here directly. Hot-reload on file change is lost but that is fine for
REM normal use; relaunch the bat after editing.
"%AGENT_PY%" agent.py start
set "EXIT_CODE=%ERRORLEVEL%"

REM Compute uptime in seconds.
for /f %%t in ('powershell -NoProfile -Command "[int][double]::Parse((Get-Date -UFormat %%s))"') do set "END_EPOCH=%%t"
set /a UPTIME=END_EPOCH-START_EPOCH

set /a RESTART_TOTAL+=1
if %UPTIME% geq %STABLE_SECONDS% (
    echo.
    echo --------------------------------------------
    echo   Agent stayed alive %UPTIME%s ^(>= %STABLE_SECONDS%s^), resetting burst counter.
    set "RESTART_BURST=0"
) else (
    set /a RESTART_BURST+=1
)

echo.
echo --------------------------------------------
echo   [%date% %time%] Agent exited ^(code=%EXIT_CODE%, uptime=%UPTIME%s^)
echo --------------------------------------------

if %RESTART_BURST% geq %BURST_CAP% (
    echo.
    echo ============================================
    echo   FATAL: Agent crashed %BURST_CAP% times in a row, each within %STABLE_SECONDS%s.
    echo   This usually means a real bug ^(import error, missing env var, port in use^).
    echo   Scroll up and read the agent stack trace.
    echo.
    echo   Press any key to RESET burst counter and keep retrying,
    echo   or close this window to give up.
    echo ============================================
    pause >nul
    set "RESTART_BURST=0"
)

echo   Restarting in 3 seconds... ^(Ctrl+C twice to stop^)
timeout /t 3 /nobreak >nul
goto loop

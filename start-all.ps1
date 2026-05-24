# SmartHelp+ PowerShell Launcher
Write-Host "============================================"
Write-Host "  SmartHelp+ Server Launcher"
Write-Host "============================================"
Write-Host ""

$ADB = "C:\Users\HP\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$SERVER_DIR = "$PSScriptRoot\server-python"
$VENV_PYTHON = "$SERVER_DIR\venv\Scripts\python.exe"

# Kill old processes on port 8765
Write-Host "[1/4] Killing old processes on port 8765..."
$procs = Get-NetTCPConnection -LocalPort 8765 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($pid in $procs) {
    if ($pid -and $pid -ne 0) {
        Write-Host "   Killing PID $pid"
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    }
}

# ADB reverse
Write-Host "[2/4] Setting up ADB reverse..."
& $ADB reverse --remove-all 2>$null
& $ADB reverse tcp:8765 tcp:8765

# Start Token Server
Write-Host "[3/4] Starting Token Server..."
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$SERVER_DIR'; & '$VENV_PYTHON' token_server.py"
Start-Sleep -Seconds 3

# Start LiveKit Agent
Write-Host "[4/4] Starting LiveKit Agent..."
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$SERVER_DIR'; & '$VENV_PYTHON' agent.py dev"

Write-Host ""
Write-Host "============================================"
Write-Host "  All services started!"
Write-Host "  - Token Server: http://127.0.0.1:8765"
Write-Host "  - LiveKit Agent: connecting to cloud"
Write-Host "============================================"
Write-Host ""
Write-Host "Press any key to exit this window..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

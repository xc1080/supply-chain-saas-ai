@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [Simlect MCP] Streamable HTTP server...

if not exist ".env" (
    if exist ".env.example" (
        copy /y ".env.example" ".env" >nul
    )
)

if not exist ".venv\Scripts\python.exe" (
    echo [error] run start.bat first to create .venv and install deps
    pause
    exit /b 1
)

".venv\Scripts\python.exe" -c "import mcp" 1>nul 2>nul
if errorlevel 1 (
    echo [Simlect MCP] installing mcp deps...
    call ".venv\Scripts\pip.exe" install -r requirements-runtime.txt -q
    if errorlevel 1 (
        echo [error] pip install failed
        pause
        exit /b 1
    )
)

echo [Simlect MCP] http://0.0.0.0:7060/mcp
echo [Simlect MCP] Ctrl+C to stop
echo.

".venv\Scripts\python.exe" -m app.mcp_server

pause

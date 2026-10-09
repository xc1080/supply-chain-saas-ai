@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [Simlect Agent] preparing...

if not exist ".env" (
    if exist ".env.example" (
        copy /y ".env.example" ".env" >nul
        echo [hint] copied .env.example to .env - fill LLM_API_KEY etc.
    ) else (
        echo [error] missing .env
        pause
        exit /b 1
    )
)

if not exist ".venv\Scripts\python.exe" (
    echo [Simlect Agent] creating venv...
    python -m venv .venv
    if errorlevel 1 (
        echo [error] venv failed - need Python 3.11+
        pause
        exit /b 1
    )
)

if not exist ".venv\Scripts\uvicorn.exe" (
    echo [Simlect Agent] installing deps first time, may be slow...
    call ".venv\Scripts\pip.exe" install -r requirements-runtime.txt -q
    if errorlevel 1 (
        echo [error] pip install failed
        pause
        exit /b 1
    )
)

echo [Simlect Agent] http://0.0.0.0:7050
echo [Simlect Agent] health http://localhost:7050/health
echo [Simlect Agent] Ctrl+C to stop
echo.

".venv\Scripts\uvicorn.exe" app.main:app --host 0.0.0.0 --port 7050 --reload

pause

$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/fusion-agent"
if (-not $env:FUSION_REDIS_URL) { $env:FUSION_REDIS_URL='redis://127.0.0.1:6379/0' }
$env:FUSION_TENANT='studio'
$env:FUSION_DEMO_USER='studio_admin'
$env:FUSION_SESSION_COOKIE='simlect_studio_session'
$env:FUSION_STORE_DB=Join-Path $PSScriptRoot 'runtime/store-studio.sqlite3'
$env:FUSION_ALLOWED_ORIGINS='http://127.0.0.1:6002,http://localhost:6002,http://127.0.0.1:7051'
& "$PSScriptRoot/fusion-agent/.venv/Scripts/python.exe" -m uvicorn main:app --host 127.0.0.1 --port 7051

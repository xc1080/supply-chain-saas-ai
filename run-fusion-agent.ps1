$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/fusion-agent"
if (-not $env:FUSION_REDIS_URL) { $env:FUSION_REDIS_URL='redis://127.0.0.1:6379/0' }
& "$PSScriptRoot/fusion-agent/.venv/Scripts/python.exe" -m uvicorn main:app --host 127.0.0.1 --port 7050

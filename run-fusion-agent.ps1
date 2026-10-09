$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/fusion-agent"
if (-not $env:FUSION_REDIS_URL) { $env:FUSION_REDIS_URL='redis://127.0.0.1:6379/0' }
$commerceCredentials = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime/commerce-service-credentials.json') -Raw | ConvertFrom-Json
$env:FUSION_COMMERCE_USER=$commerceCredentials.demo.username
$env:FUSION_COMMERCE_PASSWORD=$commerceCredentials.demo.password
if (-not $env:FUSION_AI_KEY_PREFIX) { $env:FUSION_AI_KEY_PREFIX='fusion:ai:runtime-v1' }
& "$PSScriptRoot/fusion-agent/.venv/Scripts/python.exe" -m uvicorn main:app --host 127.0.0.1 --port 7050

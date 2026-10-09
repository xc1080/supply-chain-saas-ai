$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/fusion-agent"
if (-not $env:FUSION_REDIS_URL) { $env:FUSION_REDIS_URL='redis://127.0.0.1:6379/0' }
$env:FUSION_TENANT='studio'
$commerceCredentials = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime/commerce-service-credentials.json') -Raw | ConvertFrom-Json
$env:FUSION_COMMERCE_USER=$commerceCredentials.studio.username
$env:FUSION_COMMERCE_PASSWORD=$commerceCredentials.studio.password
$env:FUSION_CUSTOMER_ASSERTION_SECRET=$commerceCredentials.studio.assertionSecret
if (-not $env:FUSION_AI_KEY_PREFIX) { $env:FUSION_AI_KEY_PREFIX='fusion:ai:runtime-v1' }
$env:FUSION_DEMO_USER='studio_admin'
$env:FUSION_SESSION_COOKIE='simlect_studio_session'
$env:FUSION_STORE_DB=Join-Path $PSScriptRoot 'runtime/store-studio.sqlite3'
$env:FUSION_ALLOWED_ORIGINS='http://127.0.0.1:6002,http://localhost:6002,http://127.0.0.1:7051'
& "$PSScriptRoot/fusion-agent/.venv/Scripts/python.exe" -m uvicorn main:app --host 127.0.0.1 --port 7051

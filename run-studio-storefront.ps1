$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot 'Simlect-AI-Mall/Simlect-front/Simlect-web')
$env:VITE_DEV_PORT='6002'
$env:VITE_API_PROXY_TARGET='http://127.0.0.1:7051'
$env:VITE_AGENT_PROXY_TARGET='http://127.0.0.1:7051'
$env:VITE_WS_PROXY_TARGET='ws://127.0.0.1:7051'
& npm.cmd run dev:demo -- --host 127.0.0.1 --port 6002 --strictPort

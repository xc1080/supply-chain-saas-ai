$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot 'Simlect-AI-Mall/Simlect-front/Simlect-web')
if (-not (Test-Path -LiteralPath 'node_modules/vite')) {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'Failed to install storefront dependencies' }
}
& npm.cmd run dev:demo -- --host 127.0.0.1 --port 6001 --strictPort
exit $LASTEXITCODE

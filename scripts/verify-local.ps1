param(
    [switch]$MigrationsOnly,
    [switch]$MixedLoad,
    [string]$Output,
    [string]$Jar,
    [string]$Python,
    [string]$HistoricalRef = 'e6f22cc'
)
$ErrorActionPreference = 'Stop'
$verificationRoot = Split-Path -Parent $PSScriptRoot
$verificationPython = Join-Path $verificationRoot 'fusion-agent/.venv/Scripts/python.exe'
if ($Python) { $verificationPython = $Python }
$verificationArgs = @(
    (Join-Path $PSScriptRoot 'verify_engineering.py'),
    '--mysql', (Join-Path $verificationRoot 'runtime/mysql-8.0.46-winx64/bin/mysql.exe'),
    '--mysql-defaults', (Join-Path $verificationRoot 'runtime/mysql-root.cnf'),
    '--redis-executable', (Join-Path $verificationRoot 'runtime/redis/redis-server.exe'),
    '--historical-ref', $HistoricalRef
)
if ($MigrationsOnly) { $verificationArgs += '--migrations-only' }
if ($MixedLoad) { $verificationArgs += '--mixed-load' }
if ($Output) { $verificationArgs += @('--output', $Output) }
if ($Jar) { $verificationArgs += @('--jar', $Jar) }
& $verificationPython @verificationArgs
if ($LASTEXITCODE -ne 0) { throw 'Isolated engineering verification failed; inspect engineering-result.json' }

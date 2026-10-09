$ErrorActionPreference = 'Stop'
$learningRoot = $PSScriptRoot
$logDirectory = Join-Path $learningRoot 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$shellExecutable = (Get-Process -Id $PID).Path

function Test-DemoPort([int]$Port) {
    $probe = [System.Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1', $Port); return $true }
    catch { return $false }
    finally { $probe.Dispose() }
}

function Start-DemoService([string]$Name, [int]$Port, [string]$Script) {
    if (Test-DemoPort $Port) { Write-Output "$Name already listening on $Port"; return }
    $scriptPath = Join-Path $learningRoot $Script
    if (-not (Test-Path -LiteralPath $scriptPath)) { throw "Missing launcher: $scriptPath" }
    Start-Process -FilePath $shellExecutable -WindowStyle Hidden `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$scriptPath`"") `
        -RedirectStandardOutput (Join-Path $logDirectory "$Name.stdout.log") `
        -RedirectStandardError (Join-Path $logDirectory "$Name.stderr.log") | Out-Null
    $deadline = (Get-Date).AddSeconds(50)
    while (-not (Test-DemoPort $Port)) {
        if ((Get-Date) -gt $deadline) { throw "$Name failed to listen on $Port. Check logs/$Name.stderr.log" }
        Start-Sleep -Milliseconds 500
    }
    Write-Output "$Name listening on $Port"
}

$pythonExecutable = Join-Path $learningRoot 'fusion-agent/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $pythonExecutable)) {
    & python -m venv (Join-Path $learningRoot 'fusion-agent/.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create Python environment' }
    & $pythonExecutable -m pip install -r (Join-Path $learningRoot 'fusion-agent/requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Failed to install Python dependencies' }
}

& $pythonExecutable -c "import multipart, websockets"
if ($LASTEXITCODE -ne 0) {
    & $pythonExecutable -m pip install -r (Join-Path $learningRoot 'fusion-agent/requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Failed to update Python dependencies' }
}

Start-DemoService 'mysql' 3306 'run-mysql.ps1'
Start-DemoService 'redis' 6379 'run-redis.ps1'
& $pythonExecutable (Join-Path $learningRoot 'fusion-agent/bootstrap_tenants.py')
if ($LASTEXITCODE -ne 0) { throw 'Tenant provisioning failed' }
Start-DemoService 'backend' 8035 'run-backend.ps1'
& $pythonExecutable (Join-Path $learningRoot 'fusion-agent/seed_demo.py')
if ($LASTEXITCODE -ne 0) { throw 'Demo data verification failed' }
& $pythonExecutable (Join-Path $learningRoot 'fusion-agent/seed_activities.py')
if ($LASTEXITCODE -ne 0) { throw 'Demo activity initialization failed' }
Start-DemoService 'fusion-agent' 7050 'run-fusion-agent.ps1'
Start-DemoService 'frontend' 5173 'run-frontend.ps1'
Start-DemoService 'storefront' 6001 'run-storefront.ps1'
$previousDemoUser=$env:FUSION_DEMO_USER
try { $env:FUSION_DEMO_USER='studio_admin'; & $pythonExecutable (Join-Path $learningRoot 'fusion-agent/seed_demo.py'); if ($LASTEXITCODE -ne 0) { throw 'Studio seed verification failed' }; & $pythonExecutable (Join-Path $learningRoot 'fusion-agent/seed_activities.py'); if ($LASTEXITCODE -ne 0) { throw 'Studio activity initialization failed' } }
finally { $env:FUSION_DEMO_USER=$previousDemoUser }
Start-DemoService 'studio-agent' 7051 'run-studio-agent.ps1'
Start-DemoService 'studio-storefront' 6002 'run-studio-storefront.ps1'
Write-Output 'Open http://127.0.0.1:6001/ for the Simlect storefront and customer support'
Write-Output 'Open http://127.0.0.1:5173/ai/assistant'

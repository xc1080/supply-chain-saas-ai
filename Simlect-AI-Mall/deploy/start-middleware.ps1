# Simlect 本地中间件一键启动（Windows PowerShell）
$ErrorActionPreference = "Stop"
$ComposeFile = Join-Path $PSScriptRoot "docker-compose.middleware.yml"

Write-Host "==> 可选：提高 ES vm.max_map_count"
try {
  wsl -d docker-desktop -u root -- sysctl -w vm.max_map_count=262144 2>$null | Out-Null
} catch { }

Write-Host "==> 确保 MySQL 先起来（便于初始化 nacos/seata 库）"
docker compose -f $ComposeFile up -d mysql
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  $st = docker inspect -f "{{.State.Health.Status}}" simlect-mysql 2>$null
  if ($st -eq "healthy") { $ok = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $ok) { throw "MySQL 未在超时内 healthy" }

Write-Host "==> 初始化 nacos / seata / undo_log（可重复执行）"
& (Join-Path $PSScriptRoot "init-mysql-meta.ps1")

Write-Host "==> 启动全部中间件（同网络 simlect-net，含 Seata）"
docker compose -f $ComposeFile up -d

Write-Host ""
docker compose -f $ComposeFile ps
Write-Host ""
Write-Host "MySQL 3306 | Redis 6379 | Rabbit 5672/15672 | Nacos 8848 | ES 9200 | Sentinel 8858 | Seata 8091/7091"

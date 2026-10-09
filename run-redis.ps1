$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$PSScriptRoot/runtime/redis/redis-server.exe" --bind 127.0.0.1 --port 6379 --protected-mode yes --dir "$PSScriptRoot/runtime/redis" --appendonly yes --appendfsync everysec --maxmemory-policy noeviction

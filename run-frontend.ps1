$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/ks-inventory-system/ks-vue3"
& npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort

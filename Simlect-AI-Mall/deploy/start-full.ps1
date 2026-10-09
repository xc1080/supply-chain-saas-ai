# Simlect 完整版：中间件全开 + 打印微服务启动清单
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\deploy\start-full.ps1

$ErrorActionPreference = "Stop"
$Deploy = $PSScriptRoot

Write-Host "======== 1/2 中间件（全开）========" -ForegroundColor Cyan
& (Join-Path $Deploy "start-middleware.ps1")

Write-Host ""
Write-Host "======== 2/2 微服务请在 IDE / 另开终端启动（完整版 11 个）========" -ForegroundColor Cyan
Write-Host "推荐机器：16G+；当前按 32G 本机舒适档配置中间件，可放心全开。"
Write-Host ""
Write-Host "顺序 | 入口类                    | 模块路径                         | 端口 | JAVA_TOOL_OPTIONS"
Write-Host "-----|---------------------------|----------------------------------|------|---------------------------"
Write-Host "  1  | GatewayApplication        | Simlect-gateway                  | 8080 | -Xms128m -Xmx256m"
Write-Host "  2  | UserApplication           | Simlect-user/app                 | 8105 | -Xms128m -Xmx320m"
Write-Host "  3  | ProductApplication        | Simlect-product/app              | 8099 | -Xms128m -Xmx320m"
Write-Host "  4  | StockApplication          | Simlect-stock/app                | 8102 | -Xms128m -Xmx256m"
Write-Host "  5  | CartApplication           | Simlect-cart/app                 | 8084 | -Xms128m -Xmx256m"
Write-Host "  6  | CouponApplication         | Simlect-coupon/app               | 8087 | -Xms128m -Xmx256m"
Write-Host "  7  | OrderApplication          | Simlect-order/app                | 8093 | -Xms128m -Xmx384m"
Write-Host "  8  | PayApplication            | Simlect-pay/app                  | 8096 | -Xms128m -Xmx256m"
Write-Host "  9  | SearchApplication         | Simlect-search                   | 8108 | -Xms128m -Xmx384m"
Write-Host " 10  | AdminApplication          | Simlect-admin                    | 8111 | -Xms128m -Xmx256m"
Write-Host " 11  | LogisticsApplication      | Simlect-logitics/app             | 8090 | -Xms128m -Xmx256m"
Write-Host ""
Write-Host "前端: cd Simlect-front\Simlect-web ; npm run dev"
Write-Host "详情: $Deploy\FULL_STACK.md"
Write-Host ""
Write-Host "不要启动: 各服务 api/ 子模块、Simlect-common"

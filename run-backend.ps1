$ErrorActionPreference = 'Stop'
$commerceCredentials = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime/commerce-service-credentials.json') -Raw | ConvertFrom-Json
$env:COMMERCE_PAYMENT_WEBHOOK_SECRET=$commerceCredentials._paymentWebhookSecret
Set-Location "$PSScriptRoot/ks-inventory-system"
$localConfiguration = (Join-Path $PSScriptRoot 'runtime/application-local.yml').Replace('\', '/')
& java '-Dfile.encoding=UTF-8' -jar ruoyi-admin/target/ruoyi-admin.jar "--spring.config.additional-location=file:$localConfiguration" --spring.profiles.active=druid,local

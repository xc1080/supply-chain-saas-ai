$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/ks-inventory-system"
$localConfiguration = (Join-Path $PSScriptRoot 'runtime/application-local.yml').Replace('\', '/')
& java '-Dfile.encoding=UTF-8' -jar ruoyi-admin/target/ruoyi-admin.jar "--spring.config.additional-location=file:$localConfiguration" --spring.profiles.active=druid,local

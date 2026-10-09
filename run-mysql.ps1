$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$PSScriptRoot/runtime/mysql-8.0.46-winx64/bin/mysqld.exe" --no-defaults "--basedir=$PSScriptRoot/runtime/mysql-8.0.46-winx64" "--datadir=$PSScriptRoot/runtime/mysql-data" --bind-address=127.0.0.1 --port=3306 --mysqlx=OFF --console

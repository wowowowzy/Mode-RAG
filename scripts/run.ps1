# 启动 ModeRoute 服务（需先在项目根目录执行 mvn package）。
Set-Location (Split-Path -Parent $PSScriptRoot)
java "-Dfile.encoding=UTF-8" -jar target\moderoute-1.0.0.jar @args

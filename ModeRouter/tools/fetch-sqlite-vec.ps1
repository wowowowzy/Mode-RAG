<#
.SYNOPSIS
    下载 sqlite-vec 的 Windows x64 扩展，解压 vec0.dll 到 lib/native/。
.DESCRIPTION
    仓库已内置 vec0.dll，本脚本用于「误删 / 换机器 / 升级版本」时恢复。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\fetch-sqlite-vec.ps1
#>
[CmdletBinding()]
param(
    [string]$Version = "0.1.9",
    [string]$Sha256  = "fcf98662a7ad9dce394b96a88f91032047823831b951c76636787c312a6476e6"
)

$ErrorActionPreference = "Stop"
$root   = Split-Path -Parent $PSScriptRoot
$dest   = Join-Path $root "lib\native"
$target = Join-Path $dest "vec0.dll"

if (Test-Path $target) {
    $existing = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    Write-Host "已存在 vec0.dll"
    Write-Host "  SHA256: $existing"
    if ($existing -eq $Sha256) {
        Write-Host "与仓库版本一致，无需下载。" -ForegroundColor Green
        return
    }
    Write-Host "与仓库版本不一致，将重新下载。" -ForegroundColor Yellow
}

$url = "https://github.com/asg017/sqlite-vec/releases/download/v$Version/sqlite-vec-$Version-loadable-windows-x86_64.tar.gz"
$tmp = Join-Path $env:TEMP "sqlite-vec-$Version.tar.gz"

Write-Host "下载: $url"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

New-Item -ItemType Directory -Force -Path $dest | Out-Null
tar -xzf $tmp -C $dest vec0.dll
Remove-Item $tmp -Force

if (-not (Test-Path $target)) {
    throw "解压后仍未找到 vec0.dll，请检查压缩包结构"
}

$actual = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
Write-Host "完成。SHA256: $actual"
if ($actual -ne $Sha256) {
    Write-Host "警告：与仓库版本不同（可能是不同构建），请确认服务能正常启动。" -ForegroundColor Yellow
} else {
    Write-Host "校验通过。" -ForegroundColor Green
}

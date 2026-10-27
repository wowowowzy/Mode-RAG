# 下载 sqlite-vec 的 Windows x64 可加载扩展到 lib/native/vec0.dll。
# 换平台时把 $asset 换成对应产物（macos-aarch64 / linux-x86_64 等）。
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "lib\native"
$tmp = Join-Path $root "var\tmp"
$asset = "sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz"
$url = "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/$asset"

New-Item -ItemType Directory -Force -Path $dest, $tmp | Out-Null
$tar = Join-Path $tmp $asset

Write-Host "下载 $url"
curl.exe -s -L --retry 5 --retry-all-errors -o $tar $url
if (-not (Test-Path $tar)) {
    throw "下载失败：$url"
}
tar.exe -xzf $tar -C $dest
Remove-Item $tar -Force -ErrorAction SilentlyContinue

$dll = Join-Path $dest "vec0.dll"
if (-not (Test-Path $dll)) {
    throw "解压后未找到 vec0.dll，请检查产物结构"
}
Write-Host "已就绪：" $dll

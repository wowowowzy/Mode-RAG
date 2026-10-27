# 冒烟：健康检查、库列表、一次真实增强请求。结果写入 var/tmp/smoke-response.json。
Set-Location (Split-Path -Parent $PSScriptRoot)
$base = "http://127.0.0.1:18080"
New-Item -ItemType Directory -Force -Path var\tmp | Out-Null

Write-Host "== /healthz =="
curl.exe -s "$base/healthz"
Write-Host "`n== /v1/cards (count) =="
(Invoke-RestMethod "$base/v1/cards").count

Write-Host "== /v1/enhance =="
curl.exe -s -X POST "$base/v1/enhance" -H "Content-Type: application/json" --data-binary "@tools/smoke-request.json" -o var\tmp\smoke-response.json
$response = Get-Content var\tmp\smoke-response.json -Raw -Encoding utf8 | ConvertFrom-Json
"action={0} card={1} confidence={2} chars={3}" -f $response.meta.action, $response.meta.cardId, $response.meta.confidence, $response.prompt.Length

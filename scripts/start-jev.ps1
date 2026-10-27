# 启动本地精判引擎。模型、依赖、缓存全部位于 D:\Application\Jev-Style。
# 若已把权重放在 D:\Application\Jev-Style\model（离线拷贝），优先用 --model-dir，避免再次联网下载。
$root = "D:\Application\Jev-Style"
$exe = Join-Path $root ".venv\Scripts\jev-style.exe"
if (-not (Test-Path $exe)) {
    Write-Host "未找到 jev-style。请先执行："
    Write-Host "  $root\.venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple `"jev-style[torch]`""
    exit 1
}

$env:HF_HOME = Join-Path $root ".hf"
$env:HUGGINGFACE_HUB_CACHE = Join-Path $root ".hf\hub"
$env:HF_ENDPOINT = "https://hf-mirror.com"
$env:TMP = Join-Path $root ".tmp"
$env:TEMP = Join-Path $root ".tmp"
$env:JEV_STYLE_DEVICE = "cpu"

$args = @("serve", "--release", "0.8b-v3", "--backend", "torch", "--device", "cpu",
          "--host", "127.0.0.1", "--port", "8765")
$modelDir = Join-Path $root "model"
if (Test-Path (Join-Path $modelDir "model.safetensors")) {
    $args += @("--model-dir", $modelDir)
}

& $exe @args

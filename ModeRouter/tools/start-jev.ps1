# 启动本地精判引擎。venv 与模型在与本仓库同级的 Jev-Style 目录下。
# 注意：venv 里的 jev-style.exe 启动器内嵌了旧路径，迁移后不可用，这里直接调用 python.
$root = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "Jev-Style"
$py = Join-Path $root ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
    Write-Host "未找到 venv: $py"
    exit 1
}

$env:HF_HOME = Join-Path $root ".hf"
$env:HUGGINGFACE_HUB_CACHE = Join-Path $root ".hf\hub"
$env:HF_ENDPOINT = "https://hf-mirror.com"
$env:TMP = Join-Path $root ".tmp"
$env:TEMP = Join-Path $root ".tmp"
$env:JEV_STYLE_DEVICE = "cpu"

$serveArgs = @("-c", "import sys;from jev_style.cli import main;sys.exit(main())",
               "serve", "--release", "0.8b-v3", "--backend", "torch", "--device", "cpu",
               "--host", "127.0.0.1", "--port", "8765")
$modelDir = Join-Path $root "model"
if (Test-Path (Join-Path $modelDir "model.safetensors")) {
    $serveArgs += @("--model-dir", $modelDir)
}

& $py @serveArgs
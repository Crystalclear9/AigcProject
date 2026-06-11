param(
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$api = Join-Path $root "services\api"
$venv = Join-Path $api ".venv"

if ($Recreate -and (Test-Path -LiteralPath $venv)) {
    Remove-Item -LiteralPath $venv -Recurse -Force
}
if (-not (Test-Path -LiteralPath $venv)) {
    python -m venv $venv
}

$python = Join-Path $venv "Scripts\python.exe"
& $python -m pip install --upgrade pip
& $python -m pip install -r (Join-Path $api "requirements.txt")
& $python -m pip install pytest==8.3.5

& $python -c "import importlib.metadata as m; assert m.version('langgraph') == '1.2.1'; import langgraph.checkpoint.sqlite.aio; print('Backend runtime ready')"

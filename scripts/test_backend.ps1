$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$api = Join-Path $root "services\api"
$python = Join-Path $api ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Backend virtual environment is missing. Run scripts\setup_backend.ps1 first."
}

$env:PYTEST_DISABLE_PLUGIN_AUTOLOAD = "1"
Push-Location $api
try {
    & $python -m compileall -q app
    if ($LASTEXITCODE -ne 0) { throw "Python compile check failed." }
    & $python -m pytest -q
    if ($LASTEXITCODE -ne 0) { throw "Backend tests failed." }
} finally {
    Pop-Location
}

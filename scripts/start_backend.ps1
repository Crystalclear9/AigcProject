param(
    [string]$HostAddress = "0.0.0.0",
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$api = Join-Path $root "services\api"
$python = Join-Path $api ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Backend virtual environment is missing. Run scripts\setup_backend.ps1 first."
}

Push-Location $api
try {
    & $python -m uvicorn app.main:app --host $HostAddress --port $Port
} finally {
    Pop-Location
}

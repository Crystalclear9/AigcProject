param(
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$script = Join-Path $PSScriptRoot "generate_complex_screenshot_samples.py"

if ($OutputDir) {
    python $script --output-dir $OutputDir
} else {
    python $script --output-dir (Join-Path $root "docs\test-assets\screenshots")
}

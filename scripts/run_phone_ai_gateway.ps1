param(
    [int]$Port = 8000,
    [int]$PollSeconds = 5,
    [string]$CredentialPath = "$env:LOCALAPPDATA\Suishouban\secrets\vivo-api-key.xml"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$apiDirectory = Join-Path $root "services\api"
$python = Join-Path $apiDirectory ".venv\Scripts\python.exe"
$stateDirectory = Join-Path $env:LOCALAPPDATA "Suishouban\gateway"
$logPath = Join-Path $stateDirectory "gateway.log"
$backendOutputPath = Join-Path $stateDirectory "backend.out.log"
$backendErrorPath = Join-Path $stateDirectory "backend.err.log"
$mutex = [Threading.Mutex]::new($false, "Local\SuishoubanPhoneAIGateway")
$hasMutex = $false
$backend = $null

function Write-GatewayLog {
    param([string]$Message)

    if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 2MB) {
        Move-Item -LiteralPath $logPath -Destination "$logPath.previous" -Force
    }
    $line = "{0:o} {1}" -f (Get-Date), $Message
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

function Find-Adb {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Import-ProviderCredential {
    if (-not (Test-Path -LiteralPath $CredentialPath)) {
        throw "Encrypted provider credential is missing: $CredentialPath"
    }
    $secure = Import-Clixml -LiteralPath $CredentialPath
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Test-BackendReady {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
        return [bool]$health.ready
    } catch {
        return $false
    }
}

function Start-Backend {
    if (Test-BackendReady) {
        Write-GatewayLog "Backend is already ready on port $Port."
        return $null
    }

    Remove-Item -LiteralPath $backendOutputPath, $backendErrorPath -Force -ErrorAction SilentlyContinue
    $arguments = @(
        "-m", "uvicorn", "app.main:app",
        "--host", "127.0.0.1",
        "--port", $Port.ToString()
    )
    $process = Start-Process `
        -FilePath $python `
        -ArgumentList $arguments `
        -WorkingDirectory $apiDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendOutputPath `
        -RedirectStandardError $backendErrorPath `
        -PassThru
    Write-GatewayLog "Started backend process $($process.Id)."
    return $process
}

function Update-AdbReverse {
    param([string]$Adb)

    if (-not $Adb) {
        return
    }
    $lines = & $Adb devices 2>$null
    foreach ($line in $lines) {
        if ($line -match "^\s*(\S+)\s+device(?:\s|$)") {
            $serial = $Matches[1]
            $mappings = & $Adb -s $serial reverse --list 2>$null
            $expectedMapping = "tcp:$Port tcp:$Port"
            if ($mappings -match [regex]::Escape($expectedMapping)) {
                continue
            }
            & $Adb -s $serial reverse "tcp:$Port" "tcp:$Port" 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                Write-GatewayLog "Restored ADB reverse for $serial on tcp:$Port."
            }
        }
    }
}

try {
    New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
    $hasMutex = $mutex.WaitOne(0)
    if (-not $hasMutex) {
        exit 0
    }
    if (-not (Test-Path -LiteralPath $python)) {
        throw "Backend runtime is missing. Run scripts\setup_backend.ps1 first."
    }

    $apiKey = Import-ProviderCredential
    $env:LANXIN_API_KEY = $apiKey
    $env:FAST_MODEL_API_KEY = $apiKey
    $env:EXPERT_MODEL_API_KEY = $apiKey
    $env:VIVO_OCR_APP_KEY = $apiKey
    $env:VIVO_IMAGE_GENERATION_API_KEY = $apiKey
    $env:LANXIN_BASE_URL = "https://api-ai.vivo.com.cn/v1"
    $env:FAST_MODEL_BASE_URL = "https://api-ai.vivo.com.cn/v1"
    $env:EXPERT_MODEL_BASE_URL = "https://api-ai.vivo.com.cn/v1"
    $env:VIVO_OCR_URL = "http://api-ai.vivo.com.cn/ocr/general_recognition"
    $env:VIVO_OCR_BUSINESS_PROFILE = "rotatable"
    $env:VIVO_IMAGE_GENERATION_URL = "https://api-ai.vivo.com.cn/api/v1/image_generation"
    $env:ENABLE_PROVIDER_PROBE = "false"
    $apiKey = $null

    $adb = Find-Adb
    if (-not $adb) {
        Write-GatewayLog "ADB was not found. Backend will run without automatic phone mapping."
    }

    Write-GatewayLog "Phone AI gateway supervisor started."
    while ($true) {
        if (-not (Test-BackendReady)) {
            if ($backend -and -not $backend.HasExited) {
                Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
            }
            $backend = Start-Backend
            $deadline = (Get-Date).AddSeconds(20)
            while ((Get-Date) -lt $deadline -and -not (Test-BackendReady)) {
                Start-Sleep -Milliseconds 500
            }
            if (-not (Test-BackendReady)) {
                Write-GatewayLog "Backend did not become ready; retrying after backoff."
                Start-Sleep -Seconds 5
                continue
            }
        }
        Update-AdbReverse -Adb $adb
        Start-Sleep -Seconds ([Math]::Max(2, $PollSeconds))
    }
} catch {
    New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
    Write-GatewayLog "Gateway stopped: $($_.Exception.GetType().Name)"
    throw
} finally {
    if ($backend -and -not $backend.HasExited) {
        Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    }
    if ($hasMutex) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}

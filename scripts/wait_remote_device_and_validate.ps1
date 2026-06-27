param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:36197",
    [string]$WorkflowUrl = "",
    [int]$MaxWaitMinutes = 30,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sdk = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
) | Where-Object { $_ -and (Test-Path -LiteralPath "$_\platform-tools\adb.exe") } |
    Select-Object -First 1

if (-not $sdk) {
    throw "Android platform-tools were not found."
}

$adb = Join-Path $sdk "platform-tools\adb.exe"
$androidDir = Join-Path $env:USERPROFILE ".android"
if (-not (Test-Path -LiteralPath $androidDir)) {
    New-Item -ItemType Directory -Path $androidDir | Out-Null
}
$env:ADB_VENDOR_KEYS = $androidDir

function Get-AdbState {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $connect = (& $adb connect $Device 2>&1) -join " "
    Start-Sleep -Seconds 2
    $state = (& $adb -s $Device get-state 2>&1) -join " "
    $devices = (& $adb devices -l 2>&1) -join " | "
    $ErrorActionPreference = $oldPreference
    return @{
        Connect = $connect
        State = $state.Trim()
        Devices = $devices
    }
}

function Reset-AdbKeys {
    Write-Host "Resetting ADB server and local key pair..."
    & $adb disconnect $Device | Out-Null
    & $adb kill-server | Out-Null
    foreach ($name in @("adbkey", "adbkey.pub")) {
        $path = Join-Path $androidDir $name
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path
            if ($item.DirectoryName -ne $androidDir -or $item.Name -notin @("adbkey", "adbkey.pub")) {
                throw "Refusing to delete unexpected ADB key path: $path"
            }
            Remove-Item -LiteralPath $path -Force
            Write-Host "Deleted $path"
        }
    }
    & $adb start-server | Out-Null
}

$deadline = [DateTimeOffset]::Now.AddMinutes([Math]::Max(1, $MaxWaitMinutes))
$attempt = 0
Write-Host "Waiting for $Device to become state=device. ADB_VENDOR_KEYS=$env:ADB_VENDOR_KEYS"
while ([DateTimeOffset]::Now -lt $deadline) {
    $attempt++
    $result = Get-AdbState
    Write-Host ("[{0}] connect=[{1}] state=[{2}]" -f $attempt, $result.Connect, $result.State)
    if ($result.State -eq "device" -or $result.Devices -match ([regex]::Escape($Device) + "\s+device")) {
        Write-Host "Remote device is authorized. Starting full remote validation..."
        $args = @(
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "validate_remote_complex_screenshots.ps1"),
            "-Device", $Device,
            "-AdbWaitSeconds", "60"
        )
        if ($SkipBuild) { $args += "-SkipBuild" }
        if (-not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
            $args += @("-WorkflowUrl", $WorkflowUrl)
        }
        & powershell @args
        exit $LASTEXITCODE
    }
    if ($result.State -match "unauthorized" -and ($attempt -eq 3 -or $attempt % 12 -eq 0)) {
        Reset-AdbKeys
    } elseif ($result.State -match "offline|not found|closed|failed") {
        & $adb disconnect $Device | Out-Null
    }
    Start-Sleep -Seconds 8
}

$final = Get-AdbState
throw "Remote device never reached state=device. Final connect=[$($final.Connect)] state=[$($final.State)] devices=[$($final.Devices)]"

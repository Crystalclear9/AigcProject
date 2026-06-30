param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:37065",
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

function Quote-ProcessArg {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-AdbRawWithTimeout {
    param(
        [string[]]$Args,
        [int]$TimeoutSeconds = 10
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $psi.Arguments = (($Args | ForEach-Object { Quote-ProcessArg $_ }) -join " ")
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch {}
        return @{ ExitCode = 124; Output = "adb command timed out after ${TimeoutSeconds}s" }
    }
    $output = @($process.StandardOutput.ReadToEnd(), $process.StandardError.ReadToEnd()) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    return @{ ExitCode = $process.ExitCode; Output = ($output -join " ") }
}

function Get-AdbState {
    $connect = (Invoke-AdbRawWithTimeout -Args @("connect", $Device) -TimeoutSeconds 12).Output
    Start-Sleep -Seconds 2
    $stateResult = Invoke-AdbRawWithTimeout -Args @("-s", $Device, "get-state") -TimeoutSeconds 8
    $probeResult = Invoke-AdbRawWithTimeout -Args @("-s", $Device, "shell", "echo", "adb-ready") -TimeoutSeconds 8
    $devices = (Invoke-AdbRawWithTimeout -Args @("devices", "-l") -TimeoutSeconds 8).Output
    return @{
        Connect = $connect
        State = $stateResult.Output.Trim()
        Probe = $probeResult.Output
        ProbeExit = $probeResult.ExitCode
        Devices = $devices
    }
}

function Reset-AdbKeys {
    Write-Host "Resetting ADB server and local key pair..."
    Invoke-AdbRawWithTimeout -Args @("disconnect", $Device) -TimeoutSeconds 8 | Out-Null
    Invoke-AdbRawWithTimeout -Args @("kill-server") -TimeoutSeconds 8 | Out-Null
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
    Invoke-AdbRawWithTimeout -Args @("start-server") -TimeoutSeconds 8 | Out-Null
}

$deadline = [DateTimeOffset]::Now.AddMinutes([Math]::Max(1, $MaxWaitMinutes))
$attempt = 0
Write-Host "Waiting for $Device to become state=device. ADB_VENDOR_KEYS=$env:ADB_VENDOR_KEYS"
while ([DateTimeOffset]::Now -lt $deadline) {
    $attempt++
    $result = Get-AdbState
    Write-Host ("[{0}] connect=[{1}] state=[{2}] probe=[{3}]" -f $attempt, $result.Connect, $result.State, $result.Probe)
    if (
        $result.State -eq "device" -or
        $result.Devices -match ([regex]::Escape($Device) + "\s+device") -or
        ($result.ProbeExit -eq 0 -and $result.Probe -match "adb-ready")
    ) {
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

param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:36197",
    [string]$WorkflowUrl = "",
    [string]$BackendUrl = "",
    [string]$ApkPath = "",
    [int]$AdbWaitSeconds = 300,
    [switch]$SkipBackendCheck,
    [switch]$UseAdbReverse
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

function Initialize-AdbKeyEnvironment {
    $androidDir = Join-Path $env:USERPROFILE ".android"
    if (-not (Test-Path -LiteralPath $androidDir)) {
        New-Item -ItemType Directory -Path $androidDir | Out-Null
    }
    $env:ADB_VENDOR_KEYS = $androidDir
    Write-Host "ADB_VENDOR_KEYS=$env:ADB_VENDOR_KEYS"
}

function Reset-AdbAuthorization {
    Write-Host "Resetting local ADB authorization keys and server..."
    & $adb disconnect $Device | Out-Null
    & $adb kill-server | Out-Null
    $androidDir = Join-Path $env:USERPROFILE ".android"
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
    Initialize-AdbKeyEnvironment
    & $adb start-server | Out-Null
}

function Wait-AdbDevice {
    param([int]$Attempts = 0)
    Initialize-AdbKeyEnvironment
    $deadline = [DateTimeOffset]::Now.AddSeconds([Math]::Max(30, $AdbWaitSeconds))
    if ($Attempts -gt 0) {
        $deadline = [DateTimeOffset]::Now.AddSeconds([Math]::Max(5, $Attempts * 3))
    }
    $attempt = 0
    while ([DateTimeOffset]::Now -lt $deadline) {
        $attempt++
        $connectOutput = & $adb connect $Device 2>&1
        if ($connectOutput) { $connectOutput | Out-Host }
        Start-Sleep -Seconds 2
        $state = (& $adb -s $Device get-state 2>&1) -join ""
        Write-Host "ADB wait attempt $attempt state=[$state]"
        if ($state.Trim() -eq "device") { return }
        $devices = (& $adb devices 2>&1) -join "`n"
        if ($state -match "unauthorized" -or $devices -match ([regex]::Escape($Device) + "\s+unauthorized")) {
            if ($attempt -eq 3 -or $attempt -eq 8 -or $attempt % 20 -eq 0) {
                Reset-AdbAuthorization
            } else {
                & $adb disconnect $Device | Out-Null
            }
        } elseif ($state -match "offline|failed|not found") {
            if ($attempt % 10 -eq 0) {
                & $adb reconnect offline 2>$null | Out-Null
            }
            & $adb disconnect $Device | Out-Null
        }
    }
    $finalState = (& $adb -s $Device get-state 2>&1) -join "`n"
    $finalDevices = (& $adb devices 2>&1) -join "`n"
    throw "Remote device did not reach the device state. get-state=[$finalState] adb devices=[$finalDevices]"
}

function Confirm-VivoInstaller {
    Wait-AdbDevice
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $installed = (& $adb -s $Device shell pm path com.suishouban.app 2>$null) -join ""
        if ($installed -match "package:") { return }
        & $adb -s $Device shell uiautomator dump /sdcard/suishouban-install.xml | Out-Null
        $ui = (& $adb -s $Device shell cat /sdcard/suishouban-install.xml 2>$null) -join ""
        if ($ui -match "继续安装") {
            & $adb -s $Device shell input tap 630 2450
            Start-Sleep -Seconds 1
            & $adb -s $Device shell input tap 630 2620
            Start-Sleep -Seconds 10
            Wait-AdbDevice
            return
        }
        Start-Sleep -Seconds 2
    }
}
if (-not $ApkPath) {
    $ApkPath = Join-Path $root "apps\android\app\build\outputs\apk\debug\app-debug.apk"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK was not found: $ApkPath"
}

if (-not $SkipBackendCheck -and -not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    if (-not $WorkflowUrl.Trim().StartsWith("https://")) {
        throw "WorkflowUrl must be a public HTTPS gateway for remote deployment: $WorkflowUrl"
    }
    $health = Invoke-RestMethod -Uri "$($WorkflowUrl.TrimEnd('/'))/health" -TimeoutSec 5
    if ($health.status -ne "ok") {
        throw "Workflow gateway health check did not return status=ok."
    }
} elseif (-not $SkipBackendCheck -and -not [string]::IsNullOrWhiteSpace($BackendUrl)) {
    $health = Invoke-RestMethod -Uri "$($BackendUrl.TrimEnd('/'))/health" -TimeoutSec 5
    if ($health.status -ne "ok") {
        throw "Backend health check did not return status=ok."
    }
} else {
    Write-Host "No WorkflowUrl provided. Deploying in phone-only fallback mode."
}

Wait-AdbDevice

if ($UseAdbReverse) {
    & $adb -s $Device reverse tcp:8000 tcp:8000
    if ($LASTEXITCODE -ne 0) {
        throw "ADB reverse failed."
    }
}

$installedPath = (& $adb -s $Device shell pm path com.suishouban.app 2>$null)
if ($installedPath) {
    & $adb -s $Device shell pm clear com.suishouban.app | Out-Host
    & $adb -s $Device uninstall com.suishouban.app | Out-Host
}

$remoteApk = "/data/local/tmp/suishouban-debug.apk"
& $adb -s $Device push $ApkPath $remoteApk
if ($LASTEXITCODE -ne 0) {
    throw "APK push failed."
}
$apkSize = (Get-Item -LiteralPath $ApkPath).Length
$createResult = (& $adb -s $Device shell pm install-create -r -t -S $apkSize 2>&1) -join "`n"
if ($createResult -notmatch "\[(\d+)\]") {
    throw "Could not create package installer session: $createResult"
}
$sessionId = $Matches[1]
& $adb -s $Device shell pm install-write -S $apkSize $sessionId base.apk $remoteApk | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Could not stream APK into install session $sessionId."
}
& $adb -s $Device shell cmd package install-commit $sessionId | Out-Host
Confirm-VivoInstaller
$installedPath = (& $adb -s $Device shell pm path com.suishouban.app 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0 -or $installedPath -notmatch "package:") {
    throw "APK installation failed for session $sessionId."
}
& $adb -s $Device shell rm -f $remoteApk

& $adb -s $Device logcat -c
& $adb -s $Device shell am force-stop com.suishouban.app
& $adb -s $Device shell am start -W -n com.suishouban.app/.MainActivity
if ($LASTEXITCODE -ne 0) {
    throw "App launch failed."
}

Start-Sleep -Seconds 5
$packageInfo = & $adb -s $Device shell dumpsys package com.suishouban.app
$fatalLogs = & $adb -s $Device logcat -d -v brief |
    Select-String -Pattern "FATAL EXCEPTION|Process: com\.suishouban\.app|com\.suishouban\.app.*(Exception|Error)"

Write-Host ""
Write-Host "Installed package:"
$packageInfo | Select-String -Pattern "versionCode|versionName|firstInstallTime|lastUpdateTime|targetSdk"
Write-Host ""
if ($UseAdbReverse) {
    Write-Host "Reverse mappings:"
    & $adb -s $Device reverse --list
} else {
    Write-Host "ADB reverse was not enabled. Phone is not coupled to a development host."
}
Write-Host ""
if ($fatalLogs) {
    Write-Warning "Potential runtime failures were found:"
    $fatalLogs | Out-Host
    exit 2
}
Write-Host "No fatal application errors were found in the startup log."

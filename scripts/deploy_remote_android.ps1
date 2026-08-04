param(
    [string]$Device = "",
    [string]$WorkflowUrl = "",
    [string]$BackendUrl = "",
    [string]$ApkPath = "",
    [int]$AdbWaitSeconds = 300,
    [switch]$SkipBackendCheck,
    [switch]$CleanInstall
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

if (-not $Device) {
    $online = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
    })
    if ($online.Count -ne 1) {
        throw "Expected exactly one online ADB device; found $($online.Count). Pass -Device explicitly."
    }
    $Device = $online[0]
}

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
        if ($Device -match ':') {
            $connectOutput = & $adb connect $Device 2>&1
            if ($connectOutput) { $connectOutput | Out-Host }
        }
        Start-Sleep -Seconds 2
        $state = (& $adb -s $Device get-state 2>&1) -join ""
        Write-Host "ADB wait attempt $attempt state=[$state]"
        if ($state.Trim() -eq "device") { return }
        $devices = (& $adb devices 2>&1) -join "`n"
        if ($state -match "unauthorized" -or $devices -match ([regex]::Escape($Device) + "\s+unauthorized")) {
            if ($Device -notmatch ':') {
                throw "USB device $Device is unauthorized. Approve this computer on the phone; local ADB keys were not deleted."
            }
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

function Confirm-VivoInstallerLegacy {
    Wait-AdbDevice
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $installed = (& $adb -s $Device shell pm path com.suishouban.app 2>$null) -join ""
        if ($installed -match "package:") { return }
        & $adb -s $Device shell uiautomator dump /sdcard/suishouban-install.xml | Out-Null
        $ui = (& $adb -s $Device shell cat /sdcard/suishouban-install.xml 2>$null) -join ""
        if ($ui -match "继续安装") {
            throw "Legacy installer flow is disabled because it cannot identify controls safely."
            Start-Sleep -Seconds 1
            throw "Legacy installer flow is disabled because it cannot identify controls safely."
            Start-Sleep -Seconds 10
            Wait-AdbDevice
            return
        }
        Start-Sleep -Seconds 2
    }
}

function Get-InstallerNodeCenter {
    param([string]$Xml, [string]$ResourceId)
    $escaped = [regex]::Escape($ResourceId)
    $node = [regex]::Match(
        $Xml,
        "<node[^>]*resource-id=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"[^>]*/?>"
    )
    if (-not $node.Success) { return $null }
    return @{
        X = [int](([int]$node.Groups[1].Value + [int]$node.Groups[3].Value) / 2)
        Y = [int](([int]$node.Groups[2].Value + [int]$node.Groups[4].Value) / 2)
    }
}

function Get-AppUpdateTime {
    $match = (& $adb -s $Device shell dumpsys package com.suishouban.app 2>$null) |
        Select-String -Pattern 'lastUpdateTime=' | Select-Object -First 1
    if ($match) { return $match.ToString().Trim() }
    return ""
}

function Confirm-VivoInstaller {
    param([string]$PreviousUpdateTime = "")
    Wait-AdbDevice
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $installed = (& $adb -s $Device shell pm path com.suishouban.app 2>$null) -join ""
        $currentUpdateTime = Get-AppUpdateTime
        if ($installed -match "package:" -and
            ([string]::IsNullOrWhiteSpace($PreviousUpdateTime) -or $currentUpdateTime -ne $PreviousUpdateTime)) {
            return
        }
        & $adb -s $Device shell uiautomator dump /sdcard/suishouban-install.xml | Out-Null
        $ui = (& $adb -s $Device shell cat /sdcard/suishouban-install.xml 2>$null) -join ""
        if ($ui -match 'package="com\.android\.systemui"' -and $ui -match 'USB') {
            & $adb -s $Device shell input keyevent BACK | Out-Null
            Start-Sleep -Seconds 1
            continue
        }
        $checkbox = Get-InstallerNodeCenter $ui "com.android.packageinstaller:id/deleted_file_state_cb"
        $button = Get-InstallerNodeCenter $ui "android:id/button1"
        if ($button) {
            if ($checkbox) {
                & $adb -s $Device shell input tap $checkbox.X $checkbox.Y | Out-Null
                Start-Sleep -Seconds 1
            }
            & $adb -s $Device shell input tap $button.X $button.Y | Out-Null
            Start-Sleep -Seconds 10
            Wait-AdbDevice
            continue
        }
        Start-Sleep -Seconds 2
    }
    throw "Vivo installer could not be completed without a uniquely identified confirmation control."
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

$installedPath = (& $adb -s $Device shell pm path com.suishouban.app 2>$null)
$previousUpdateTime = Get-AppUpdateTime
if ($installedPath -and $CleanInstall) {
    Write-Warning "CleanInstall explicitly requested: app data and the installed package will be removed."
    & $adb -s $Device shell pm clear com.suishouban.app | Out-Host
    & $adb -s $Device uninstall com.suishouban.app | Out-Host
} elseif ($installedPath) {
    Write-Host "Existing app detected. Performing an in-place upgrade and preserving user data."
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
$commitProcess = Start-Process -FilePath $adb -ArgumentList @(
    "-s", $Device, "shell", "cmd", "package", "install-commit", $sessionId
) -PassThru -WindowStyle Hidden
Confirm-VivoInstaller -PreviousUpdateTime $previousUpdateTime
if (-not $commitProcess.WaitForExit(30000)) {
    $commitProcess.Kill()
    throw "Package install commit did not finish after installer confirmation."
}
if ($commitProcess.ExitCode -ne 0) {
    throw "Package install commit failed with exit code $($commitProcess.ExitCode)."
}
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
Write-Host "No ADB reverse mapping is used; the installed product is independent of the development host."
Write-Host ""
if ($fatalLogs) {
    Write-Warning "Potential runtime failures were found:"
    $fatalLogs | Out-Host
    exit 2
}
Write-Host "No fatal application errors were found in the startup log."

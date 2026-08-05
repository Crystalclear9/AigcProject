param(
    [string]$Device = "10AF952BSR0024T",
    [int]$Port = 8000,
    [string]$ArtifactDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
    $ArtifactDirectory = Join-Path $root "artifacts\local-phone-gateway"
}
New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null

$adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
$adb = if ($adbCommand) { $adbCommand.Source } else { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path -LiteralPath $adb)) { throw "adb.exe was not found on PATH or the default Android SDK." }
$devices = & $adb devices
$online = @($devices | Select-String "^\S+\s+device$")
if ($online.Count -ne 1 -or $online[0].ToString().Split("`t")[0] -ne $Device) {
    throw "Expected exactly one online device ($Device). Found: $($online -join '; ')"
}

& $adb -s $Device reverse tcp:$Port tcp:$Port | Out-Null
& $adb -s $Device reverse --list | Out-File (Join-Path $ArtifactDirectory "adb-reverse.txt") -Encoding utf8

$health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 5
$ready = Invoke-RestMethod "http://127.0.0.1:$Port/ready" -TimeoutSec 5
$health | ConvertTo-Json -Depth 10 | Out-File (Join-Path $ArtifactDirectory "health.json") -Encoding utf8
$ready | ConvertTo-Json -Depth 10 | Out-File (Join-Path $ArtifactDirectory "ready.json") -Encoding utf8
if (-not $health.ready -or -not $ready.ready) { throw "Workflow gateway is not ready." }

& $adb -s $Device shell getprop ro.build.version.sdk | Out-File (Join-Path $ArtifactDirectory "device-sdk.txt") -Encoding utf8
& $adb -s $Device shell dumpsys package com.suishouban.app | Out-File (Join-Path $ArtifactDirectory "package-dump.txt") -Encoding utf8
& $adb -s $Device shell uiautomator dump /sdcard/window.xml | Out-Null
& $adb -s $Device pull /sdcard/window.xml (Join-Path $ArtifactDirectory "ui.xml") | Out-Null
& $adb -s $Device logcat -d -t 200 | Out-File (Join-Path $ArtifactDirectory "logcat.txt") -Encoding utf8

[pscustomobject]@{ device = $Device; base_url = "http://127.0.0.1:$Port/"; health_ready = $health.ready; ready_ready = $ready.ready; artifacts = $ArtifactDirectory } |
    ConvertTo-Json | Tee-Object -FilePath (Join-Path $ArtifactDirectory "result.json")

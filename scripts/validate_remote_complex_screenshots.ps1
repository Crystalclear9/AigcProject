param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:35109",
    [string]$ApkPath = "",
    [string]$SampleDir = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) {
    $ApkPath = Join-Path $root "apps\android\app\build\outputs\apk\debug\app-debug.apk"
}
if (-not $SampleDir) {
    $SampleDir = Join-Path $root "docs\test-assets\screenshots"
}

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
$remoteSampleDir = "/sdcard/Pictures/SuishoubanSamples"

function Utf8Text {
    param([string]$Base64)
    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Base64))
}

$T = @{
    ContinueInstall = Utf8Text "57un57ut5a6J6KOF"
    NotificationPermission = Utf8Text "6K+35rGC5ZCR5oKo5Y+R6YCB6YCa55+l"
    Allow = Utf8Text "5YWB6K64"
    MediaPermission = Utf8Text "6K+35rGC6K6/6Zeu5oKo55qE54Wn54mH5LiO6KeG6aKR"
    AllowAllMedia = Utf8Text "5YWB6K645a6M5YWo6K6/6Zeu"
    LocalMode = Utf8Text "5pys5py65qih5byP"
    MaybeTodo = Utf8Text "5Y+v6IO95pyJ5b6F5Yqe"
    Generate = Utf8Text "55Sf5oiQ"
    Ignore = Utf8Text "5b+955Wl"
    ConfirmCreate = Utf8Text "56Gu6K6k5Yib5bu65o+Q6YaS5LiO6KGM5Yqo5Y2h"
    Submit = Utf8Text "5o+Q5Lqk"
    ReminderCreated = Utf8Text "5bey5Yib5bu65o+Q6YaS"
    PossibleAction = Utf8Text "5Y+R546w5Y+v6IO96KGM5Yqo5LqL6aG5"
    LabReport = Utf8Text "5a6e6aqM5oql5ZGK"
    EvidenceLabel = Utf8Text "6K+G5Yir5Zy65pmv"
    CourseScenario = Utf8Text "6K++56iLL+S9nOS4mumAmuefpQ=="
    HighConfidence = Utf8Text "6auY5Y+v5L+h"
}

function Normalize-AdbArgs {
    param([object[]]$RawArgs)
    $flat = @()
    foreach ($arg in $RawArgs) {
        if ($arg -is [System.Array]) {
            foreach ($inner in $arg) { $flat += [string]$inner }
        } else {
            $flat += [string]$arg
        }
    }
    if ($flat.Count -eq 1 -and $flat[0] -match "\s") {
        return @($flat[0] -split "\s+")
    }
    return $flat
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $normalizedArgs = Normalize-AdbArgs $Args
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $output = & $adb -s $Device @normalizedArgs 2>&1
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $oldPreference
        if ($exitCode -eq 0) {
            return $output
        }
        $text = $output -join "`n"
        if ($text -match "offline|closed|device .*not found|no devices") {
            Wait-AdbDevice -Attempts 6
            Start-Sleep -Seconds 1
            continue
        }
        throw "adb $($normalizedArgs -join ' ') failed:`n$text"
    }
    throw "adb $($normalizedArgs -join ' ') failed after reconnect attempts."
}

function Invoke-AdbLoose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $normalizedArgs = Normalize-AdbArgs $Args
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $adb -s $Device @normalizedArgs 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    return @{ ExitCode = $exitCode; Output = $output }
}

function Wait-AdbDevice {
    param([int]$Attempts = 12)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $connectOutput = & $adb connect $Device 2>&1
        $connectExit = $LASTEXITCODE
        Start-Sleep -Seconds 2
        $stateResult = Invoke-AdbLoose get-state
        $devicesOutput = & $adb devices 2>&1
        $ErrorActionPreference = $oldPreference
        if ($connectOutput) { $connectOutput | Out-Host }
        $state = ($stateResult.Output -join "").Trim()
        $devicesText = ($devicesOutput -join "`n")
        $devicePattern = [regex]::Escape($Device) + "\s+device"
        if ($stateResult.ExitCode -eq 0 -and $state -eq "device") { return }
        if ($devicesText -match $devicePattern) { return }
        if ($state -match "offline|unauthorized|failed" -or $connectExit -ne 0) {
            & $adb disconnect $Device | Out-Null
        }
    }
    throw "Remote device did not reach state=device."
}

function Get-UiXml {
    param([string]$Name = "suishouban-window.xml")
    Invoke-Adb shell uiautomator dump "/sdcard/$Name" | Out-Null
    return (Invoke-Adb shell cat "/sdcard/$Name") -join "`n"
}

function Get-TextCenter {
    param([string]$Xml, [string]$Text)
    $escaped = [regex]::Escape($Text)
    $match = [regex]::Match($Xml, "<node[^>]*(text|content-desc)=""[^""]*$escaped[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    if (-not $match.Success) { return $null }
    $x1 = [int]$match.Groups[2].Value
    $y1 = [int]$match.Groups[3].Value
    $x2 = [int]$match.Groups[4].Value
    $y2 = [int]$match.Groups[5].Value
    return @{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Tap-Text {
    param([string]$Text, [string]$XmlName = "suishouban-window.xml")
    $xml = Get-UiXml $XmlName
    $center = Get-TextCenter $xml $Text
    if (-not $center) {
        throw "Could not find text on device: $Text"
    }
    Invoke-Adb shell input tap $center.X $center.Y | Out-Null
    Start-Sleep -Seconds 1
}

function Assert-UiContains {
    param([string]$Text, [string]$Message)
    $xml = Get-UiXml "assert-window.xml"
    if ($xml -notmatch [regex]::Escape($Text)) {
        throw $Message
    }
}

function Assert-UiNotContains {
    param([string]$Text, [string]$Message)
    $xml = Get-UiXml "assert-window.xml"
    if ($xml -match [regex]::Escape($Text)) {
        throw $Message
    }
}

function Wait-UiContains {
    param([string]$Text, [string]$Message, [int]$Attempts = 8)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $xml = Get-UiXml "wait-window.xml"
        if ($xml -match [regex]::Escape($Text)) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw $Message
}

function Test-UiContains {
    param([string]$Text)
    $xml = Get-UiXml "test-window.xml"
    return $xml -match [regex]::Escape($Text)
}

function Wait-UiNotContains {
    param([string]$Text, [string]$Message, [int]$Attempts = 5)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $xml = Get-UiXml "wait-window.xml"
        if ($xml -notmatch [regex]::Escape($Text)) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw $Message
}

function Confirm-VivoInstaller {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        Wait-AdbDevice -Attempts 3
        $installedResult = Invoke-AdbLoose shell pm path com.suishouban.app
        $installed = ($installedResult.Output -join "")
        if ($installedResult.ExitCode -eq 0 -and $installed -match "package:") { return }
        Invoke-Adb shell cmd statusbar collapse | Out-Null
        Start-Sleep -Seconds 1
        $xml = ""
        try {
            $xml = Get-UiXml "suishouban-install.xml"
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        if ($xml -match [regex]::Escape($T.ContinueInstall)) {
            Invoke-Adb shell input tap 630 2450 | Out-Null
            Start-Sleep -Seconds 1
            Invoke-Adb shell input tap 630 2620 | Out-Null
            Start-Sleep -Seconds 8
        } else {
            Start-Sleep -Seconds 2
        }
        Wait-AdbDevice
    }
    throw "Vivo installer did not complete."
}

function Install-App {
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "APK was not found: $ApkPath"
    }
    Wait-AdbDevice
    & $adb -s $Device uninstall com.suishouban.app | Out-Host
    $remoteApk = "/data/local/tmp/suishouban-debug.apk"
    & $adb -s $Device push $ApkPath $remoteApk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "APK push failed." }
    $apkSize = (Get-Item -LiteralPath $ApkPath).Length
    $createResult = (& $adb -s $Device shell pm install-create -r -t -S $apkSize 2>&1) -join "`n"
    if ($createResult -notmatch "\[(\d+)\]") {
        throw "Could not create package installer session: $createResult"
    }
    $sessionId = $Matches[1]
    & $adb -s $Device shell pm install-write -S $apkSize $sessionId base.apk $remoteApk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Could not write APK into install session." }
    & $adb -s $Device shell cmd package install-commit $sessionId | Out-Host
    Wait-AdbDevice -Attempts 8
    Confirm-VivoInstaller
    Invoke-Adb @("shell", "rm", "-f", $remoteApk) | Out-Null
    $packagePath = (Invoke-Adb shell pm path com.suishouban.app) -join ""
    if ($packagePath -notmatch "package:") { throw "APK installation did not finish." }
}

function Grant-AppPermissions {
    foreach ($permission in @(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES"
    )) {
        & $adb -s $Device shell pm grant com.suishouban.app $permission 2>$null | Out-Null
    }
}

function Reset-AppData {
    Invoke-Adb shell am force-stop com.suishouban.app | Out-Null
    $clearResult = Invoke-AdbLoose shell pm clear com.suishouban.app
    if ($clearResult.ExitCode -ne 0 -or (($clearResult.Output -join "") -notmatch "Success")) {
        throw "Could not clear app data:`n$($clearResult.Output -join "`n")"
    }
}

function Start-App {
    Invoke-Adb shell am force-stop com.suishouban.app | Out-Null
    Invoke-Adb @("shell", "logcat", "-c") | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.suishouban.app/.MainActivity") | Out-Host
    Start-Sleep -Seconds 4
    $xml = Get-UiXml "startup.xml"
    if ($xml -match [regex]::Escape($T.NotificationPermission)) {
        Tap-Text $T.Allow "startup-permission.xml"
        Start-Sleep -Seconds 1
    }
    $xml = Get-UiXml "startup-media.xml"
    if ($xml -match [regex]::Escape($T.MediaPermission)) {
        if ($xml -match [regex]::Escape($T.AllowAllMedia)) {
            Tap-Text $T.AllowAllMedia "startup-media-permission.xml"
        } else {
            Tap-Text $T.Allow "startup-media-permission.xml"
        }
        Start-Sleep -Seconds 2
    }
    Invoke-Adb shell cmd statusbar collapse | Out-Null
    Start-Sleep -Seconds 1
    Assert-UiContains $T.LocalMode "Home did not show local mode."
}

function Push-Sample {
    param([string]$Name)
    $localPath = Join-Path $SampleDir $Name
    if (-not (Test-Path -LiteralPath $localPath)) {
        throw "Sample image missing: $localPath"
    }
    Invoke-Adb @("shell", "mkdir", "-p", $remoteSampleDir) | Out-Null
    & $adb -s $Device push $localPath "$remoteSampleDir/$Name" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Could not push sample $Name." }
    Invoke-Adb @("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://$remoteSampleDir/$Name") | Out-Null
}

function Open-SampleAndScreenshot {
    param([string]$Name)
    Invoke-Adb shell cmd statusbar collapse | Out-Null
    Push-Sample $Name
    $shotName = "Screenshot_suishouban_validation_$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())_$Name"
    $shotPath = "/sdcard/Pictures/Screenshots/$shotName"
    Invoke-Adb @("shell", "mkdir", "-p", "/sdcard/Pictures/Screenshots") | Out-Null
    Invoke-Adb shell cp "$remoteSampleDir/$Name" $shotPath | Out-Null
    Invoke-Adb @("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://$shotPath") | Out-Null
    Start-Sleep -Seconds 8
}

function Open-Notifications {
    Invoke-Adb shell cmd statusbar expand-notifications | Out-Null
    Start-Sleep -Seconds 1
    return Get-UiXml "notifications.xml"
}

function Assert-NoActionSuggestionNotification {
    $xml = Open-Notifications
    if ($xml -match [regex]::Escape($T.MaybeTodo)) {
        throw "Unexpected action suggestion notification appeared."
    }
    Invoke-Adb shell cmd statusbar collapse | Out-Null
}

function Assert-ActionSuggestionNotification {
    $xml = Open-Notifications
    foreach ($text in @($T.MaybeTodo, $T.Generate, $T.Ignore)) {
        if ($xml -notmatch [regex]::Escape($text)) {
            throw "Expected notification text/action missing: $text"
        }
    }
    return $xml
}

function Test-ActionSuggestionNotification {
    $xml = Open-Notifications
    return $xml -match [regex]::Escape($T.MaybeTodo)
}

function Tap-NotificationAction {
    param([string]$Text)
    $xml = Open-Notifications
    $center = Get-TextCenter $xml $Text
    if (-not $center) {
        throw "Notification action not found: $Text"
    }
    Invoke-Adb shell input tap $center.X $center.Y | Out-Null
    Start-Sleep -Seconds 3
}

function Dismiss-ActionSuggestionWithIgnore {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Tap-NotificationAction $T.Ignore
        if (-not (Test-ActionSuggestionNotification)) {
            Invoke-Adb shell cmd statusbar collapse | Out-Null
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Ignore action did not dismiss the suggestion notification."
}

function Tap-NotificationContentFallback {
    $xml = Open-Notifications
    $center = Get-TextCenter $xml $T.LabReport
    if (-not $center) {
        $center = Get-TextCenter $xml $T.MaybeTodo
    }
    if (-not $center) {
        throw "Could not find notification content fallback."
    }
    Invoke-Adb shell input tap $center.X $center.Y | Out-Null
    Start-Sleep -Seconds 3
}

function Open-GeneratedPreviewFromNotification {
    Tap-NotificationAction $T.Generate
    if (-not (Test-UiContains $T.PossibleAction)) {
        Tap-NotificationContentFallback
    }
    Wait-UiContains $T.PossibleAction "Generate action did not open screenshot preview."
}

function Confirm-Preview {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $xml = Get-UiXml "preview.xml"
        if ($xml -match [regex]::Escape($T.ConfirmCreate)) {
            $center = Get-TextCenter $xml $T.ConfirmCreate
            Invoke-Adb shell input tap $center.X $center.Y | Out-Null
            Start-Sleep -Seconds 4
            return
        }
        Invoke-Adb shell input swipe 650 2350 650 760 500 | Out-Null
        Start-Sleep -Seconds 1
    }
    throw "Could not find confirm button in preview."
}

function Assert-CardAndReminderCreated {
    Invoke-Adb shell input tap 630 2635 | Out-Null
    Start-Sleep -Seconds 2
    Assert-UiContains $T.Submit "Card list did not show the generated action card."
    Assert-UiContains $T.ReminderCreated "Card list did not show reminder creation."
    $jobs = (Invoke-Adb shell dumpsys jobscheduler) -join "`n"
    if ($jobs -notmatch "com\.suishouban\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService") {
        throw "No WorkManager reminder jobs were registered."
    }
}

function Assert-NoBadLogs {
    $logs = (Invoke-Adb @("shell", "logcat", "-d", "-v", "time")) -join "`n"
    $badPattern = "FATAL EXCEPTION|AnalyzeResult\.<init>|cacheStatus|JsonSyntaxException|SQLiteException|NetworkOnMainThread|127\.0\.0\.1|10\.0\.2\.2|IllegalArgumentException: baseUrl"
    if ($logs -match $badPattern) {
        throw "Bad logcat pattern found:`n$($Matches[0])"
    }
}

powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "generate_complex_screenshot_samples.ps1") -OutputDir $SampleDir

if (-not $SkipInstall) {
    Install-App
}
Wait-AdbDevice
Reset-AppData
Grant-AppPermissions
Start-App

Write-Host "Validating non-action noise screenshot..."
Open-SampleAndScreenshot "noise_shopping_promo.png"
Assert-NoActionSuggestionNotification

Write-Host "Validating action screenshot notification and ignore action..."
Open-SampleAndScreenshot "complex_course_notice.png"
Assert-ActionSuggestionNotification | Out-Null
Dismiss-ActionSuggestionWithIgnore
Wait-UiNotContains $T.PossibleAction "Ignore action unexpectedly opened preview."

Write-Host "Validating action screenshot notification, generate action, preview, save, reminder..."
Open-SampleAndScreenshot "complex_course_notice.png"
Assert-ActionSuggestionNotification | Out-Null
Open-GeneratedPreviewFromNotification
Wait-UiContains $T.LabReport "Preview did not contain the expected task title."
Wait-UiContains $T.EvidenceLabel "Preview did not show evidence summary."
Wait-UiContains $T.CourseScenario "Preview did not show course scenario classification."
Wait-UiContains $T.HighConfidence "Preview did not show confidence label."
Confirm-Preview
Assert-CardAndReminderCreated
Assert-NoBadLogs

Write-Host ""
Write-Host "Remote complex screenshot validation passed on $Device."

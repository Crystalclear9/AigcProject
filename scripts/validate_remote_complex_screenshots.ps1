param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:35181",
    [string]$ApkPath = "",
    [string]$SampleDir = "",
    [string]$WorkflowUrl = "",
    [int]$AdbWaitSeconds = 300,
    [switch]$SkipBuild,
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
$artifactDir = Join-Path $root "artifacts"

function Initialize-AdbKeyEnvironment {
    $androidDir = Join-Path $env:USERPROFILE ".android"
    if (-not (Test-Path -LiteralPath $androidDir)) {
        New-Item -ItemType Directory -Path $androidDir | Out-Null
    }
    $env:ADB_VENDOR_KEYS = $androidDir
    Write-Host "ADB_VENDOR_KEYS=$env:ADB_VENDOR_KEYS"
}

function Build-DebugApk {
    if ($SkipBuild) { return }
    Write-Host "Building debug APK for remote validation..."
    powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build_android_debug.ps1") -SkipTests
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "APK build did not produce expected file: $ApkPath"
    }
}

function Assert-ApkHasNoSensitiveMarkers {
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "APK was not found: $ApkPath"
    }
    $markers = New-Object System.Collections.Generic.List[string]
    $serverOnlyEnvNames = @(
        (@("LANXIN", "API", "KEY") -join "_"),
        (@("FAST", "MODEL", "API", "KEY") -join "_"),
        (@("EXPERT", "MODEL", "API", "KEY") -join "_"),
        (@("VIVO", "OCR", "APP", "KEY") -join "_"),
        (@("VIVO", "IMAGE", "GENERATION", "API", "KEY") -join "_")
    )
    foreach ($name in $serverOnlyEnvNames) {
        $markers.Add($name)
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { $markers.Add($value) }
    }
    $providerEndpointMarkers = @(
        "https://api-ai.vivo.com.cn/v1/chat/completions",
        "https://api-ai.vivo.com.cn/api/v1/image_generation",
        "http://api-ai.vivo.com.cn/ocr/general_recognition"
    )
    foreach ($marker in $providerEndpointMarkers) {
        $markers.Add($marker)
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.Length -le 0 -or $entry.Length -gt 80000000) {
                continue
            }
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $text = [System.Text.Encoding]::UTF8.GetString($memory.ToArray())
                foreach ($marker in $markers | Where-Object { $_ }) {
                    if ($text.Contains($marker)) {
                        throw "Sensitive marker was found in APK entry $($entry.FullName): $($marker.Substring(0, [Math]::Min(16, $marker.Length)))..."
                    }
                }
            } finally {
                $stream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
    $hash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    $size = (Get-Item -LiteralPath $ApkPath).Length
    Write-Host "APK safety scan passed. Size=$size SHA256=$hash"
}

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
    GenerateDraft = Utf8Text "55Sf5oiQ6I2J56i/"
    Ignore = Utf8Text "5b+955Wl"
    ConfirmCreate = Utf8Text "56Gu6K6k5Yib5bu6"
    CreateAll = Utf8Text "5YWo6YOo5Yib5bu6"
    CreateSelected = Utf8Text "5Y+q5Yib5bu6"
    Submit = Utf8Text "5o+Q5Lqk"
    PrepareAttachment = Utf8Text "5YeG5aSH6ZmE5Lu2"
    ReminderCreated = Utf8Text "5bey5Yib5bu65o+Q6YaS"
    PossibleAction = Utf8Text "5Y+R546w5Y+v6IO96KGM5Yqo5LqL6aG5"
    LabReport = Utf8Text "5a6e6aqM5oql5ZGK"
    TeamReport = Utf8Text "6L+b5bGV5rGH5oql"
    Registration = Utf8Text "5oql5ZCN"
    EvidenceLabel = Utf8Text "6K+G5Yir5Zy65pmv"
    CourseScenario = Utf8Text "6K++56iLL+S9nOS4mumAmuefpQ=="
    HighConfidence = Utf8Text "6auY5Y+v5L+h"
    Settings = Utf8Text "6K6+572u"
    SaveEndpoint = Utf8Text "5L+d5a2Y5aKe5by656uv54K5"
    TestService = Utf8Text "5rWL6K+V5aKe5by65pyN5Yqh"
    CloudOnline = Utf8Text "5LqR56uv5aKe5by65Zyo57q/"
    WorkflowRuntimeOk = Utf8Text "5bel5L2c5rWB6L+Q6KGM5pe25q2j5bi4"
    WorkflowApiUrl = Utf8Text "V29ya2Zsb3cgQVBJIFVSTA=="
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

function Escape-Xml {
    param([string]$Value)
    return [System.Security.SecurityElement]::Escape($Value)
}

function Escape-AdbInputText {
    param([string]$Value)
    return $Value.Replace("%", "%25").Replace(" ", "%s")
}

function Wait-AdbDevice {
    param([int]$Attempts = 0)
    Initialize-AdbKeyEnvironment
    $deadline = [DateTimeOffset]::Now.AddSeconds([Math]::Max(30, $AdbWaitSeconds))
    $attempt = 0
    if ($Attempts -gt 0) {
        $deadline = [DateTimeOffset]::Now.AddSeconds([Math]::Max(5, $Attempts * 3))
    }
    while ([DateTimeOffset]::Now -lt $deadline) {
        $attempt++
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $connectOutput = & $adb connect $Device 2>&1
        $connectExit = $LASTEXITCODE
        Start-Sleep -Seconds 2
        $stateOutput = & $adb -s $Device get-state 2>&1
        $stateExit = $LASTEXITCODE
        $devicesOutput = & $adb devices 2>&1
        $ErrorActionPreference = $oldPreference
        if ($connectOutput) { $connectOutput | Out-Host }
        $state = ($stateOutput -join "").Trim()
        $devicesText = ($devicesOutput -join "`n")
        Write-Host "ADB wait attempt $attempt state=[$state]"
        $devicePattern = [regex]::Escape($Device) + "\s+device"
        if ($stateExit -eq 0 -and $state -eq "device") { return }
        if ($devicesText -match $devicePattern) { return }
        if ($state -match "unauthorized" -or $devicesText -match ([regex]::Escape($Device) + "\s+unauthorized")) {
            if ($attempt -eq 3 -or $attempt -eq 8 -or $attempt % 20 -eq 0) {
                Reset-AdbAuthorization
            } else {
                & $adb disconnect $Device | Out-Null
            }
        } elseif ($state -match "offline|failed" -or $connectExit -ne 0) {
            if ($attempt % 10 -eq 0) {
                & $adb reconnect offline 2>$null | Out-Null
            }
            & $adb disconnect $Device | Out-Null
        }
    }
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $finalState = (& $adb -s $Device get-state 2>&1) -join "`n"
    $finalDevices = (& $adb devices 2>&1) -join "`n"
    $ErrorActionPreference = $oldPreference
    throw "Remote device did not reach state=device. get-state=[$finalState] adb devices=[$finalDevices]"
}

function Get-UiXml {
    param([string]$Name = "suishouban-window.xml")
    Invoke-Adb shell uiautomator dump "/sdcard/$Name" | Out-Null
    return (Invoke-Adb shell cat "/sdcard/$Name") -join "`n"
}

function Save-RemoteDiagnostics {
    param([string]$Prefix = "remote-validation-failure")
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $base = Join-Path $artifactDir "$Prefix-$stamp"
    try {
        Invoke-Adb shell uiautomator dump "/sdcard/$Prefix.xml" | Out-Null
        (Invoke-Adb shell cat "/sdcard/$Prefix.xml") | Set-Content -LiteralPath "$base-ui.xml" -Encoding UTF8
    } catch {
        "Failed to capture UI dump: $_" | Set-Content -LiteralPath "$base-ui-error.txt" -Encoding UTF8
    }
    try {
        (& $adb -s $Device shell dumpsys window windows 2>&1) |
            Select-String -Pattern "mCurrentFocus|mFocusedApp|ScreenshotPreviewActivity|MainActivity" -Context 0,3 |
            Set-Content -LiteralPath "$base-window.txt" -Encoding UTF8
    } catch {
        "Failed to capture focused window: $_" | Set-Content -LiteralPath "$base-window-error.txt" -Encoding UTF8
    }
    try {
        (& $adb -s $Device shell logcat -d -v time 2>&1) |
            Select-Object -Last 500 |
            Set-Content -LiteralPath "$base-logcat.txt" -Encoding UTF8
    } catch {
        "Failed to capture logcat: $_" | Set-Content -LiteralPath "$base-logcat-error.txt" -Encoding UTF8
    }
    Write-Host "Saved remote diagnostics to $base-*"
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

function Get-TextCenterByPattern {
    param([string]$Xml, [string]$Pattern)
    $nodePattern = '<node[^>]*(text|content-desc)="[^"]*' + $Pattern + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Xml, $nodePattern)
    if (-not $match.Success) { return $null }
    $x1 = [int]$match.Groups[2].Value
    $y1 = [int]$match.Groups[3].Value
    $x2 = [int]$match.Groups[4].Value
    $y2 = [int]$match.Groups[5].Value
    return @{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Get-ResourceCenter {
    param([string]$Xml, [string]$ResourceId)
    $escaped = [regex]::Escape($ResourceId)
    $match = [regex]::Match($Xml, "<node[^>]*resource-id=""$escaped""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    if (-not $match.Success) { return $null }
    $x1 = [int]$match.Groups[1].Value
    $y1 = [int]$match.Groups[2].Value
    $x2 = [int]$match.Groups[3].Value
    $y2 = [int]$match.Groups[4].Value
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

function Configure-WorkflowUrl {
    if ([string]::IsNullOrWhiteSpace($WorkflowUrl)) { return }
    $trimmed = $WorkflowUrl.Trim()
    if (-not $trimmed.StartsWith("https://")) {
        throw "WorkflowUrl must be HTTPS for remote validation: $trimmed"
    }
    $healthUrl = $trimmed.TrimEnd("/") + "/health"
    try {
        Invoke-RestMethod -Uri $healthUrl -TimeoutSec 8 | Out-Null
    } catch {
        throw "WorkflowUrl health check failed: $healthUrl"
    }
    Write-Host "Configuring WorkflowUrl from the phone UI..."
    Tap-Text $T.Settings "settings-nav.xml"
    Wait-UiContains $T.WorkflowApiUrl "Settings screen did not show Workflow API URL field."
    Tap-Text $T.WorkflowApiUrl "workflow-url-field.xml"
    Invoke-Adb shell input keyevent 123 | Out-Null
    for ($i = 0; $i -lt 160; $i++) {
        Invoke-Adb shell input keyevent 67 | Out-Null
    }
    Invoke-Adb shell input text (Escape-AdbInputText $trimmed) | Out-Null
    Tap-Text $T.SaveEndpoint "save-workflow-url.xml"
    Tap-Text $T.TestService "test-workflow-url.xml"
    Wait-UiContains $T.CloudOnline "Phone-side WorkflowUrl connection test did not report cloud online." 12
    Wait-UiContains $T.WorkflowRuntimeOk "Phone-side WorkflowUrl connection test did not report workflow runtime ok." 12
    Write-Host "Configured WorkflowUrl through the phone UI."
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

function Tap-NotificationRootFallback {
    $xml = Open-Notifications
    foreach ($resourceId in @(
        "com.suishouban.app:id/notification_generate",
        "com.suishouban.app:id/notification_action_content",
        "com.suishouban.app:id/notification_action_root"
    )) {
        $center = Get-ResourceCenter $xml $resourceId
        if ($center) {
            Invoke-Adb shell input tap $center.X $center.Y | Out-Null
            Start-Sleep -Seconds 3
            return
        }
    }
    Tap-NotificationContentFallback
}

function Open-GeneratedPreviewFromNotification {
    try {
        Tap-NotificationAction $T.Generate
    } catch {
        Tap-NotificationContentFallback
    }
    Invoke-Adb shell cmd statusbar collapse | Out-Null
    Start-Sleep -Seconds 1
    if (-not (Test-UiContains $T.GenerateDraft)) {
        Tap-NotificationRootFallback
        Invoke-Adb shell cmd statusbar collapse | Out-Null
        Start-Sleep -Seconds 1
    }
    Wait-UiContains $T.GenerateDraft "Generate action did not open screenshot request panel."
    Tap-Text $T.GenerateDraft "generate-draft.xml"
}

function Confirm-Preview {
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $xml = Get-UiXml "preview.xml"
        $center = $null
        foreach ($candidate in @($T.ConfirmCreate, $T.CreateAll, $T.CreateSelected)) {
            if ($xml -match [regex]::Escape($candidate)) {
                $center = Get-TextCenter $xml $candidate
                break
            }
        }
        if (-not $center) {
            $selectedPrefixPattern = [regex]::Escape($T.CreateSelected) + "\s*\d+\s*"
            $center = Get-TextCenterByPattern $xml $selectedPrefixPattern
        }
        if ($center) {
            Invoke-Adb shell input tap $center.X $center.Y | Out-Null
            Start-Sleep -Seconds 4
            return
        }
        Invoke-Adb shell input swipe 650 1850 650 650 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    Save-RemoteDiagnostics "confirm-preview-missing"
    throw "Could not find confirm button in preview."
}

function Assert-CardAndReminderCreated {
    Invoke-Adb shell input tap 630 2635 | Out-Null
    Start-Sleep -Seconds 2
    $sawConcreteCard = $false
    $sawReminder = $false
    foreach ($attempt in 1..4) {
        $xml = Get-UiXml "card-list.xml"
        if ($xml -match [regex]::Escape($T.Submit) -or
            $xml -match [regex]::Escape($T.LabReport) -or
            $xml -match [regex]::Escape($T.PrepareAttachment)) {
            $sawConcreteCard = $true
        }
        if ($xml -match [regex]::Escape($T.ReminderCreated)) {
            $sawReminder = $true
        }
        if ($sawConcreteCard -and $sawReminder) { break }
        Invoke-Adb shell input swipe 650 1900 650 930 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    if (-not $sawConcreteCard) {
        Save-RemoteDiagnostics "card-list-missing-card"
        throw "Card list did not show a concrete generated action card."
    }
    if (-not $sawReminder) {
        Save-RemoteDiagnostics "card-list-missing-reminder"
        throw "Card list did not show reminder creation."
    }
    $jobs = (Invoke-Adb shell dumpsys jobscheduler) -join "`n"
    if ($jobs -notmatch "com\.suishouban\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService") {
        Save-RemoteDiagnostics "workmanager-job-missing"
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

Build-DebugApk
Assert-ApkHasNoSensitiveMarkers

if ([string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    Write-Warning "WorkflowUrl was not provided. This run validates the on-device fallback only; cloud model enhancement is NOT verified."
}

powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "generate_complex_screenshot_samples.ps1") -OutputDir $SampleDir

if (-not $SkipInstall) {
    Install-App
}
Wait-AdbDevice
Reset-AppData
Grant-AppPermissions
Start-App
Configure-WorkflowUrl

Write-Host "Validating non-action noise screenshot..."
Open-SampleAndScreenshot "noise_shopping_promo.png"
Assert-NoActionSuggestionNotification

Write-Host "Validating action screenshot notification and ignore action..."
Open-SampleAndScreenshot "complex_course_notice.png"
Assert-ActionSuggestionNotification | Out-Null
Dismiss-ActionSuggestionWithIgnore
Wait-UiNotContains $T.MaybeTodo "Ignore action unexpectedly opened the request panel."

Write-Host "Validating action screenshot notification, generate action, preview, save, reminder..."
Open-SampleAndScreenshot "complex_course_notice.png"
Assert-ActionSuggestionNotification | Out-Null
Open-GeneratedPreviewFromNotification
Wait-UiContains $T.LabReport "Preview did not contain the expected task title."
Wait-UiContains $T.CourseScenario "Preview did not show course scenario classification."
Wait-UiContains $T.HighConfidence "Preview did not show confidence label."
Confirm-Preview
Assert-CardAndReminderCreated

Write-Host "Validating multi-task screenshot decomposition and selective card surface..."
Open-SampleAndScreenshot "complex_multi_tasks.png"
Assert-ActionSuggestionNotification | Out-Null
Open-GeneratedPreviewFromNotification
Wait-UiContains $T.LabReport "Multi-task preview did not include the lab report task."
Wait-UiContains $T.TeamReport "Multi-task preview did not include the meeting/report task."
Wait-UiContains $T.CreateAll "Multi-task preview did not expose all-create action."
Confirm-Preview
Assert-NoBadLogs
Assert-NoBadLogs

Write-Host ""
Write-Host "Remote complex screenshot validation passed on $Device."

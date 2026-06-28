param(
    [string]$Device = "val-vclinner-rt-contest.vivo.com.cn:37065",
    [string]$ApkPath = "",
    [string]$SampleDir = "",
    [string]$WorkflowUrl = "",
    [int]$AdbWaitSeconds = 300,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
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
if (-not (Test-Path -LiteralPath $artifactDir)) {
    New-Item -ItemType Directory -Path $artifactDir | Out-Null
}
$devicePortForLog = ($Device -replace '.*:', '') -replace '[^0-9A-Za-z_-]', '_'
$transcriptPath = Join-Path $artifactDir "remote-$devicePortForLog-validation.log"
try {
    Start-Transcript -Path $transcriptPath -Force | Out-Null
    Write-Host "Remote validation transcript: $transcriptPath"
} catch {
    Write-Warning "Could not start transcript: $($_.Exception.Message)"
}

function Initialize-AdbKeyEnvironment {
    $androidDir = Join-Path $env:USERPROFILE ".android"
    if (-not (Test-Path -LiteralPath $androidDir)) {
        New-Item -ItemType Directory -Path $androidDir | Out-Null
    }
    $env:ADB_VENDOR_KEYS = $androidDir
    Write-Host "ADB_VENDOR_KEYS=$env:ADB_VENDOR_KEYS"
}

function Disconnect-StaleCloudDevices {
    foreach ($port in @("35029", "35033", "35121", "35173", "35181", "35185", "36197", "37065", "37121", "38053", "38197", "39165")) {
        $candidate = "val-vclinner-rt-contest.vivo.com.cn:$port"
        if ($candidate -ne $Device) {
            $oldPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            & $adb disconnect $candidate *> $null
            $ErrorActionPreference = $oldPreference
        }
    }
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
    $knownLeakedKeyMarkers = @(
        ("sk" + "-xuanji"),
        ("QXR5" + "T0pF" + "SnFT" + "U0lp" + "Z0Fi" + "Rw"),
        ("2026" + "882787" + "-" + "QXR5")
    )
    foreach ($marker in $knownLeakedKeyMarkers) {
        $markers.Add($marker)
    }
    $providerEndpointMarkers = @(
        "api-ai.vivo.com.cn/v1/chat/completions",
        "https://api-ai.vivo.com.cn/v1/chat/completions",
        "api-ai.vivo.com.cn/api/v1/image_generation",
        "https://api-ai.vivo.com.cn/api/v1/image_generation",
        "api-ai.vivo.com.cn/ocr/general_recognition",
        "http://api-ai.vivo.com.cn/ocr/general_recognition",
        "https://api-ai.vivo.com.cn/ocr/general_recognition"
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
    AiRefine = Utf8Text "57un57ut6K6pIEFJIOWujOWWhA=="
    AiRefineTitle = Utf8Text "6K6pIEFJIOe7p+e7reWujOWWhA=="
    AiRefineRunning = Utf8Text "QUkg5q2j5Zyo6KeC5a+f6K+B5o2u"
    AiRefineDone = Utf8Text "QUkg5bey5a6M5oiQ5LiA5qyh5Y+X5o6nIFJlQWN0IOWujOWWhA=="
    AiRechecked = Utf8Text "QUkg5bey6YeN5paw5qOA5p+l5YCZ6YCJ6I2J56i/"
    LocalRuleReview = Utf8Text "56uv5L6n6KeE5YiZ5aSN5qOA"
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
    GenericSchedule = Utf8Text "55u45YWz5pel56iL"
    Settings = Utf8Text "6K6+572u"
    SaveEndpoint = Utf8Text "5L+d5a2Y5aKe5by656uv54K5"
    TestService = Utf8Text "5rWL6K+V5aKe5by65pyN5Yqh"
    CloudOnline = Utf8Text "5LqR56uv5aKe5by65Zyo57q/"
    CloudConfigured = Utf8Text "5LqR56uv6YWN572u5Y+v55So"
    VivoModelCalled = Utf8Text "dml2byDmqKHlnovlt7Llrp7pmYXosIPnlKg="
    VivoOcrCalled = Utf8Text "dml2byBPQ1Ig5bey5a6e6ZmF6LCD55So"
    ImageProbePassed = Utf8Text "5Zu+54mH55Sf5oiQ5o6i6ZKI5bey6YCa6L+H"
    CloudModelParticipated = Utf8Text "5LqR56uv5qih5Z6L5bey5Y+C5LiO"
    VivoOcrParticipated = Utf8Text "dml2byBPQ1Ig5bey5Y+C5LiO"
    CloudEnhancementDegraded = Utf8Text "5LqR56uv5aKe5by65bey6ZmN57qn"
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
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $output = & $adb -s $Device @normalizedArgs 2>&1
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $oldPreference
        if ($exitCode -eq 0) {
            return $output
        }
        $text = $output -join "`n"
        if ($normalizedArgs.Count -ge 1 -and $normalizedArgs[0] -eq "push" -and $text -match "file pushed") {
            return $output
        }
        if ($text -match "offline|closed|device .*not found|no devices|cannot connect to daemon|failed to start daemon|daemon not running|failed to read copy response|EOF|protocol fault") {
            & $adb kill-server 2>$null | Out-Null
            Start-Sleep -Seconds 2
            Wait-AdbDevice -Attempts 20
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
    Disconnect-StaleCloudDevices
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
        if (($stateExit -eq 0 -and $state -eq "device") -or ($devicesText -match $devicePattern)) {
            $oldPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            $probeOutput = & $adb -s $Device shell echo adb-ready 2>&1
            $probeExit = $LASTEXITCODE
            $ErrorActionPreference = $oldPreference
            if ($probeExit -eq 0 -and (($probeOutput -join "`n") -match "adb-ready")) {
                return
            }
            & $adb disconnect $Device | Out-Null
            Start-Sleep -Seconds 2
            continue
        }
        if ($state -match "unauthorized" -or $devicesText -match ([regex]::Escape($Device) + "\s+unauthorized")) {
            if ($attempt -eq 3 -or $attempt -eq 8 -or $attempt % 20 -eq 0) {
                Reset-AdbAuthorization
            } else {
                $oldPreference = $ErrorActionPreference
                $ErrorActionPreference = "Continue"
                & $adb disconnect $Device 2>&1 | Out-Null
                $ErrorActionPreference = $oldPreference
            }
        } elseif ($state -match "offline|failed|not found" -or $devicesText -match "offline" -or $connectExit -ne 0) {
            $oldPreference = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            & $adb disconnect $Device 2>&1 | Out-Null
            & $adb kill-server 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            & $adb start-server 2>&1 | Out-Null
            & $adb disconnect $Device 2>&1 | Out-Null
            $ErrorActionPreference = $oldPreference
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

function Get-ExactTextCenter {
    param([string]$Xml, [string]$Text)
    $escaped = [regex]::Escape($Text)
    $match = [regex]::Match($Xml, "<node[^>]*(text|content-desc)=""$escaped""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    if (-not $match.Success) { return $null }
    $x1 = [int]$match.Groups[2].Value
    $y1 = [int]$match.Groups[3].Value
    $x2 = [int]$match.Groups[4].Value
    $y2 = [int]$match.Groups[5].Value
    return @{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Get-ClickableTextCenter {
    param([string]$Xml, [string]$Text)
    $escaped = [regex]::Escape($Text)
    $match = [regex]::Match($Xml, "<node(?=[^>]*clickable=""true"")[^>]*(text|content-desc)=""[^""]*$escaped[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
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

function Get-NodeBoundsByTextOrDescription {
    param([string]$Xml, [string]$Text)
    $escaped = [regex]::Escape($Text)
    $match = [regex]::Match($Xml, "<node[^>]*(text|content-desc)=""[^""]*$escaped[^""]*""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    if (-not $match.Success) { return $null }
    return @{
        X1 = [int]$match.Groups[2].Value
        Y1 = [int]$match.Groups[3].Value
        X2 = [int]$match.Groups[4].Value
        Y2 = [int]$match.Groups[5].Value
        Height = [int]$match.Groups[5].Value - [int]$match.Groups[3].Value
    }
}

function Get-DeviceScreenHeight {
    $size = (Invoke-Adb shell wm size) -join "`n"
    if ($size -match "(\d+)x(\d+)") {
        return [int]$Matches[2]
    }
    return 2400
}

function Assert-FloatingPanelHeightUnder {
    param(
        [double]$MaxRatio,
        [string]$Reason
    )
    $xml = Get-UiXml "floating-panel.xml"
    $bounds = Get-NodeBoundsByTextOrDescription $xml "screenshot-action-panel"
    if (-not $bounds) {
        Save-RemoteDiagnostics "floating-panel-bounds-missing"
        throw "Could not find screenshot floating panel bounds for $Reason."
    }
    $screenHeight = Get-DeviceScreenHeight
    $ratio = [double]$bounds.Height / [double]$screenHeight
    if ($ratio -gt $MaxRatio) {
        Save-RemoteDiagnostics "floating-panel-too-tall"
        throw "Screenshot floating panel too tall for $Reason. ratio=$([Math]::Round($ratio, 3)) max=$MaxRatio boundsHeight=$($bounds.Height) screenHeight=$screenHeight"
    }
    Write-Host "Floating panel height ok for ${Reason}: ratio=$([Math]::Round($ratio, 3)) max=$MaxRatio"
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

function Tap-RemotePoint {
    param([int]$X, [int]$Y)
    & $adb -s $Device shell input tap $X $Y 2>$null | Out-Null
    Start-Sleep -Milliseconds 650
}

function Test-PreviewOpen {
    $window = (Invoke-Adb shell dumpsys window windows) -join "`n"
    if ($window -match "mCurrentFocus=.*ScreenshotPreviewActivity" -or
        $window -match "mFocusedApp=.*ScreenshotPreviewActivity") {
        return $true
    }
    $activity = (Invoke-Adb shell dumpsys activity activities) -join "`n"
    return ($activity -match "topResumedActivity=.*ScreenshotPreviewActivity" -or
        $activity -match "ResumedActivity:.*ScreenshotPreviewActivity")
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

function Get-PreviewXmlAcrossScroll {
    $combined = ""
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $combined += "`n" + (Get-UiXml "preview-scan-$attempt.xml")
        if ($attempt -le 4) {
            Invoke-Adb shell input swipe 650 1350 650 560 800 | Out-Null
        } else {
            Invoke-Adb shell input swipe 650 650 650 1350 800 | Out-Null
        }
        Start-Sleep -Milliseconds 700
    }
    return $combined
}

function Assert-XmlContains {
    param([string]$Xml, [string]$Text, [string]$Message)
    if ($Xml -notmatch [regex]::Escape($Text)) {
        Save-RemoteDiagnostics "preview-scroll-missing"
        throw $Message
    }
}

function Assert-XmlNotContains {
    param([string]$Xml, [string]$Text, [string]$Message)
    if ($Xml -match [regex]::Escape($Text)) {
        Save-RemoteDiagnostics "preview-scroll-unexpected"
        throw $Message
    }
}

function Assert-PreviewContainsAcrossScroll {
    param([string]$Text, [string]$Message)
    $xml = Get-PreviewXmlAcrossScroll
    Assert-XmlContains $xml $Text $Message
}

function Assert-PreviewNotContainsAcrossScroll {
    param([string]$Text, [string]$Message)
    $xml = Get-PreviewXmlAcrossScroll
    Assert-XmlNotContains $xml $Text $Message
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

function Wait-UiContainsAny {
    param([string[]]$Texts, [string]$Message, [int]$Attempts = 12)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $xml = Get-UiXml "wait-any-window.xml"
        foreach ($text in $Texts) {
            if ($xml -match [regex]::Escape($text)) {
                return $text
            }
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
        $checkbox = Get-ResourceCenter $xml "com.android.packageinstaller:id/deleted_file_state_cb"
        $installButton = Get-ResourceCenter $xml "android:id/button1"
        if (-not $installButton) {
            $installButton = Get-ClickableTextCenter $xml $T.ContinueInstall
        }
        if ($checkbox -or $installButton -or $xml -match [regex]::Escape($T.ContinueInstall)) {
            if ($checkbox) {
                Tap-RemotePoint -X $checkbox.X -Y $checkbox.Y
            } else {
                Invoke-Adb shell input tap 630 2305 | Out-Null
                Start-Sleep -Milliseconds 700
            }
            if ($installButton) {
                Tap-RemotePoint -X $installButton.X -Y $installButton.Y
            } else {
                Invoke-Adb shell input tap 630 2475 | Out-Null
            }
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
    $uninstallResult = Invoke-AdbLoose uninstall com.suishouban.app
    $uninstallText = ($uninstallResult.Output -join "`n")
    if ($uninstallText) { $uninstallText | Out-Host }
    if ($uninstallText -match "offline|device .*not found|EOF|failed to read copy response") {
        Wait-AdbDevice -Attempts 20
    }
    $remoteApk = "/data/local/tmp/suishouban-debug.apk"
    Invoke-Adb push $ApkPath $remoteApk | Out-Host
    Wait-AdbDevice -Attempts 80
    $apkSize = (Get-Item -LiteralPath $ApkPath).Length
    $createResult = (Invoke-Adb shell pm install-create -r -t -S $apkSize) -join "`n"
    if ($createResult -notmatch "\[(\d+)\]") {
        throw "Could not create package installer session: $createResult"
    }
    $sessionId = $Matches[1]
    Invoke-Adb shell pm install-write -S $apkSize $sessionId base.apk $remoteApk | Out-Host
    Invoke-Adb shell cmd package install-commit $sessionId | Out-Host
    Wait-AdbDevice -Attempts 80
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

function Write-WorkflowPrefsDirect {
    param([string]$Url)
    $escapedUrl = [System.Security.SecurityElement]::Escape($Url)
    $prefsXml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="api_base_url">$escapedUrl</string>
    <boolean name="prefer_cloud" value="true" />
    <boolean name="auto_detect" value="true" />
    <boolean name="privacy_mask" value="true" />
    <boolean name="keep_screenshot" value="false" />
</map>
"@
    $encodedPrefs = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($prefsXml))
    $remoteCommand = "run-as com.suishouban.app sh -c 'mkdir -p shared_prefs && echo $encodedPrefs | base64 -d > shared_prefs/suishouban_settings.xml'"
    Invoke-Adb @("shell", $remoteCommand) | Out-Null
}

function Write-ValidationPrefsDirect {
    $prefsXml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="auto_detect" value="true" />
    <boolean name="privacy_mask" value="true" />
    <boolean name="keep_screenshot" value="false" />
</map>
"@
    $encodedPrefs = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($prefsXml))
    $remoteCommand = "run-as com.suishouban.app sh -c 'mkdir -p shared_prefs && echo $encodedPrefs | base64 -d > shared_prefs/suishouban_settings.xml'"
    Invoke-Adb @("shell", $remoteCommand) | Out-Null
}

function Write-PendingPromptDirect {
    param(
        [string]$Uri,
        [string]$OcrText,
        [string]$Reason,
        [string]$DeadlineHint,
        [string]$PromptSummary,
        [string]$ConfidenceBand,
        [string]$ScenarioType,
        [string[]]$Evidence
    )
    $createdAt = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $evidenceXml = ($Evidence | ForEach-Object {
        "        <string>$([System.Security.SecurityElement]::Escape($_))</string>"
    }) -join "`n"
    $prefsXml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="media_id" value="$createdAt" />
    <string name="uri">$([System.Security.SecurityElement]::Escape($Uri))</string>
    <string name="ocr_text">$([System.Security.SecurityElement]::Escape($OcrText))</string>
    <string name="gate_reason">$([System.Security.SecurityElement]::Escape($Reason))</string>
    <string name="deadline_hint">$([System.Security.SecurityElement]::Escape($DeadlineHint))</string>
    <string name="prompt_summary">$([System.Security.SecurityElement]::Escape($PromptSummary))</string>
    <string name="confidence_band">$([System.Security.SecurityElement]::Escape($ConfidenceBand))</string>
    <string name="scenario_type">$([System.Security.SecurityElement]::Escape($ScenarioType))</string>
    <set name="primary_evidence">
$evidenceXml
    </set>
    <int name="notification_id" value="0" />
    <long name="created_at" value="$createdAt" />
</map>
"@
    $encodedPrefs = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($prefsXml))
    $remoteCommand = "run-as com.suishouban.app sh -c 'mkdir -p shared_prefs && echo $encodedPrefs | base64 -d > shared_prefs/screenshot_prompt_pending.xml'"
    Invoke-Adb @("shell", $remoteCommand) | Out-Null
}

function Format-CnDate {
    param([datetime]$Date)
    return "$($Date.Month)$(Utf8Text "5pyI")$($Date.Day)$(Utf8Text "5pel")"
}

function Open-MultiTaskPromptDirect {
    $d5 = Format-CnDate ((Get-Date).AddDays(5))
    $d6 = Format-CnDate ((Get-Date).AddDays(6))
    $d7 = Format-CnDate ((Get-Date).AddDays(7))
    $ocrTemplate = Utf8Text "6K++56iL576k5YWs5ZGKCuKRoCDor7flnKggezB9IDIyOjAwIOWJjQrmj5DkuqTjgIrlrp7pqozmiqXlkYrjgIvliLDlrabkuaDpgJrvvIzmlofku7blkI3vvJrlrablj7cr5aeT5ZCN44CCCuKRoSB7MX0gMTQ6MzAg5Y+C5Yqg6IW+6K6v5Lya6K6uCuW5tuWHhuWkh+acrOWRqOi/m+Wxleaxh+aKpSBQUFTvvIzkvJrorq7lj7cgODg2IDIxMCA1NTLjgIIK4pGiIOaKpeWQjeihqCB7Mn0g5YmN5Y+R5Yiw5oyH5a6a6YKu566x77yM6YC+5pyf5LiN6KGl44CCCuW5v+WRiu+8mjE4IOaWh+WFt+a7oeWHj+S4juacrOmAmuefpeaXoOWFs+OAgg=="
    $ocrText = [string]::Format($ocrTemplate, $d5, $d6, $d7)
    $ocrTextBase64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($ocrText)).
        TrimEnd("=").
        Replace("+", "-").
        Replace("/", "_")
    Invoke-Adb shell cmd statusbar collapse | Out-Null
    Invoke-Adb @(
        "shell",
        "am",
        "start",
        "-W",
        "-S",
        "-f",
        "0x10008000",
        "-a",
        "com.suishouban.app.action.PROCESS_SCREENSHOT",
        "-d",
        "content://suishouban.validation/complex_multi_tasks",
        "-n",
        "com.suishouban.app/.MainActivity",
        "--es",
        "com.suishouban.app.extra.OCR_TEXT_BASE64",
        $ocrTextBase64,
        "--es",
        "com.suishouban.app.extra.CONFIDENCE_BAND",
        "high",
        "--es",
        "com.suishouban.app.extra.SCENARIO_TYPE",
        "course_notice",
        "--es",
        "com.suishouban.app.extra.PROMPT_SUMMARY",
        "3-tasks"
    ) | Out-Null
    Start-Sleep -Seconds 4
    Wait-UiContains $T.GenerateDraft "Direct multi-task prompt did not open request panel." 10
    Tap-Text $T.GenerateDraft "generate-multi-draft.xml"
}

function Invoke-WorkflowPostJson {
    param(
        [string]$Url,
        [hashtable]$Payload
    )
    $body = $Payload | ConvertTo-Json -Depth 8
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    return Invoke-RestMethod -Method Post -Uri $Url -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 15
}

function Wait-WorkflowResult {
    param(
        [string]$BaseUrl,
        [string]$RunId,
        [int]$TimeoutSeconds = 45
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $result = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd("/") + "/api/workflows/$RunId") -TimeoutSec 10
        if ($result.workflow_status -in @("awaiting_review", "completed", "failed", "cancelled")) {
            return $result
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Workflow $RunId did not reach a review or terminal state within $TimeoutSeconds seconds."
}

function Get-ProviderSuccessDelta {
    param(
        [object]$Workflow,
        [string[]]$Providers
    )
    $sum = 0
    foreach ($provider in $Providers) {
        $usage = $Workflow.provider_usage.$provider
        if ($usage -and ($usage.PSObject.Properties.Name -contains "success_count_delta")) {
            $sum += [int]$usage.success_count_delta
        }
    }
    return $sum
}

function Get-ProviderStatusSnapshot {
    param([string]$BaseUrl)
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return $null }
    return Invoke-RestMethod -Uri ($BaseUrl.TrimEnd("/") + "/api/providers/status") -TimeoutSec 10
}

function Get-ProviderSuccessCountFromStatus {
    param(
        [object]$Status,
        [string[]]$Providers
    )
    if (-not $Status) { return 0 }
    $sum = 0
    foreach ($provider in $Providers) {
        if ($Status.providers -and ($Status.providers.PSObject.Properties.Name -contains $provider)) {
            $sum += [int]$Status.providers.$provider.success_count
        }
    }
    return $sum
}

function Assert-ProviderStatusAdvanced {
    param(
        [string]$BaseUrl,
        [object]$Before,
        [string[]]$Providers,
        [string]$Reason
    )
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return }
    $after = Get-ProviderStatusSnapshot $BaseUrl
    $beforeCount = Get-ProviderSuccessCountFromStatus $Before $Providers
    $afterCount = Get-ProviderSuccessCountFromStatus $after $Providers
    if ($afterCount -le $beforeCount) {
        throw "Provider success counter did not advance for $Reason. before=$beforeCount after=$afterCount providers=$($Providers -join ',')"
    }
    Write-Host "Provider counter advanced for ${Reason}: before=$beforeCount after=$afterCount"
}

function Assert-ProviderProbe {
    param([string]$BaseUrl)
    $probeUrl = $BaseUrl.TrimEnd("/") + "/api/providers/probe"
    try {
        $probe = Invoke-RestMethod -Method Post -Uri $probeUrl -TimeoutSec 90
    } catch {
        throw "Provider probe failed. ENABLE_PROVIDER_PROBE must be true and vivo credentials must be valid: $probeUrl"
    }
    foreach ($provider in @("chat", "ocr", "image_generation")) {
        if (-not ($probe.results.PSObject.Properties.Name -contains $provider)) {
            throw "Provider probe response did not include $provider."
        }
        if (-not [bool]$probe.results.$provider.succeeded) {
            $errorType = $probe.results.$provider.error_type
            throw "Provider probe for $provider did not succeed. error_type=$errorType"
        }
    }
    if (-not [bool]$probe.all_succeeded) {
        throw "Provider probe did not report all_succeeded=true."
    }
    Write-Host "Provider probe proved chat/OCR/image_generation calls succeeded."
}

function Assert-ProviderWorkflowParticipation {
    param([string]$BaseUrl)
    $complexText = Utf8Text "6K+35ZyoNuaciDEw5pelMjI6MDDliY3mj5DkuqTlrp7pqozmiqXlkYrvvIzmj5DkuqTliLDlrabkuaDpgJrjgIIK6K+35ZyoNuaciDEx5pelMTQ6MzDlj4LliqDpobnnm67ov5vlsZXmsYfmiqXkvJrvvIzlnLDngrnkvJrorq7lrqQyMDPjgIIK6K+35ZyoNuaciDEy5pelMjA6MDDliY3miormr5TotZvmiqXlkI3ooajlj5HliLDmjIflrprpgq7nrrHjgII="
    $textRun = Invoke-WorkflowPostJson `
        -Url ($BaseUrl.TrimEnd("/") + "/api/workflows/screenshot-text") `
        -Payload @{ text = $complexText; screenshot_time = "2026-06-07T10:00:00+08:00" }
    $textResult = Wait-WorkflowResult $BaseUrl $textRun.run_id
    if ($textResult.model_enhancement_status -ne "succeeded") {
        throw "Text workflow did not report model_enhancement_status=succeeded. actual=$($textResult.model_enhancement_status)"
    }
    if ((Get-ProviderSuccessDelta $textResult @("fast_model", "expert_model")) -lt 1) {
        throw "Text workflow provider_usage did not show a successful model call."
    }
    if ($textResult.route -eq "rules" -and @($textResult.active_agents).Count -eq 0) {
        throw "Text workflow stayed on rules route despite cloud model configuration."
    }

    $imagePath = Join-Path $SampleDir "complex_multi_tasks.png"
    if (-not (Test-Path -LiteralPath $imagePath)) {
        throw "Complex OCR sample was not found: $imagePath"
    }
    $imageUrl = $BaseUrl.TrimEnd("/") + "/api/workflows/screenshot-image"
    $imageJson = & curl.exe -sS -X POST `
        -F "image=@$imagePath;type=image/png" `
        -F "screenshot_time=2026-06-07T10:00:00+08:00" `
        $imageUrl
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($imageJson)) {
        throw "Image workflow upload failed through curl.exe."
    }
    $imageRun = $imageJson | ConvertFrom-Json
    $imageResult = Wait-WorkflowResult $BaseUrl $imageRun.run_id 60
    if ($imageResult.ocr_enhancement_status -ne "succeeded") {
        throw "Image workflow did not report ocr_enhancement_status=succeeded. actual=$($imageResult.ocr_enhancement_status)"
    }
    if ((Get-ProviderSuccessDelta $imageResult @("ocr")) -lt 1) {
        throw "Image workflow provider_usage did not show a successful vivo OCR call."
    }
    Write-Host "Direct workflow participation proved model and vivo OCR calls affected workflow responses."
}

function Configure-WorkflowUrl {
    if ([string]::IsNullOrWhiteSpace($WorkflowUrl)) { return }
    $trimmed = $WorkflowUrl.Trim()
    if (-not $trimmed.StartsWith("https://")) {
        throw "WorkflowUrl must be HTTPS for remote validation: $trimmed"
    }
    $healthUrl = $trimmed.TrimEnd("/") + "/health"
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 8
    } catch {
        throw "WorkflowUrl health check failed: $healthUrl"
    }
    foreach ($field in @("ready", "chat_configured", "ocr_configured", "image_generation_configured")) {
        if (-not ($health.PSObject.Properties.Name -contains $field)) {
            throw "WorkflowUrl health check did not include $field."
        }
        if (-not [bool]$health.$field) {
            throw "WorkflowUrl health check reported $field=false."
        }
    }
    Assert-ProviderProbe $trimmed
    Assert-ProviderWorkflowParticipation $trimmed
    Write-Host "Configuring WorkflowUrl in app settings..."
    Write-WorkflowPrefsDirect $trimmed
    Invoke-Adb shell am force-stop com.suishouban.app | Out-Null
    Start-App -SkipModeAssert
    Tap-Text $T.Settings "settings-nav.xml"
    Wait-UiContains $T.WorkflowApiUrl "Settings screen did not show Workflow API URL field."
    Tap-Text $T.TestService "test-workflow-url.xml"
    try {
        Wait-UiContains $T.CloudConfigured "Phone-side WorkflowUrl connection test did not report cloud configured." 60
        Wait-UiContains $T.VivoModelCalled "Phone-side provider probe did not report vivo model call." 60
        Wait-UiContains $T.VivoOcrCalled "Phone-side provider probe did not report vivo OCR call." 60
        Wait-UiContains $T.ImageProbePassed "Phone-side provider probe did not report image generation call." 60
        Wait-UiContains $T.WorkflowRuntimeOk "Phone-side WorkflowUrl connection test did not report workflow runtime ok." 60
    } catch {
        Save-RemoteDiagnostics "workflow-url-test-failed"
        throw
    }
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
    param([switch]$SkipModeAssert)
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
    if (-not $SkipModeAssert) {
        Assert-UiContains $T.LocalMode "Home did not show local mode."
    }
}

function Push-Sample {
    param([string]$Name)
    $localPath = Join-Path $SampleDir $Name
    if (-not (Test-Path -LiteralPath $localPath)) {
        throw "Sample image missing: $localPath"
    }
    Invoke-Adb @("shell", "mkdir", "-p", $remoteSampleDir) | Out-Null
    Invoke-Adb push $localPath "$remoteSampleDir/$Name" | Out-Host
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
    foreach ($text in @($T.MaybeTodo)) {
        if ($xml -notmatch [regex]::Escape($text)) {
            throw "Expected notification text/action missing: $text"
        }
    }
    $hasVisibleActions = $true
    foreach ($text in @($T.Generate, $T.Ignore)) {
        if ($xml -notmatch [regex]::Escape($text)) {
            $hasVisibleActions = $false
        }
    }
    if (-not $hasVisibleActions) {
        $notificationDump = (Invoke-Adb @("shell", "dumpsys", "notification", "--noredact")) -join "`n"
        foreach ($text in @($T.Generate, $T.Ignore)) {
            if ($notificationDump -notmatch [regex]::Escape("`"$text`"")) {
                throw "Expected notification action missing from system notification record: $text"
            }
        }
        Write-Host "Notification actions are present in system record but hidden by the current notification shade."
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
        $xml = Expand-ActionSuggestionNotification
        $center = Get-TextCenter $xml $Text
    }
    if (-not $center) {
        $resourceId = $null
        if ($Text -eq $T.Ignore) {
            $resourceId = "com.suishouban.app:id/notification_ignore"
        } elseif ($Text -eq $T.Generate) {
            $resourceId = "com.suishouban.app:id/notification_generate"
        }
        if ($resourceId) {
            $center = Get-ResourceCenter $xml $resourceId
        }
    }
    if (-not $center) {
        throw "Notification action not found: $Text"
    }
    Invoke-Adb shell input tap $center.X $center.Y | Out-Null
    Start-Sleep -Seconds 3
}

function Expand-ActionSuggestionNotification {
    $xml = Open-Notifications
    $bounds = Get-NodeBoundsByTextOrDescription $xml $T.MaybeTodo
    if ($bounds) {
        $x = [int](($bounds.X1 + $bounds.X2) / 2)
        $startY = [Math]::Max(120, [int]($bounds.Y2 + 8))
        $screenHeight = Get-DeviceScreenHeight
        $endY = [Math]::Min(($screenHeight - 120), [int]($startY + 520))
        Invoke-Adb shell input swipe $x $startY $x $endY 450 | Out-Null
        Start-Sleep -Milliseconds 900
        return Get-UiXml "notifications-expanded.xml"
    }
    return $xml
}

function Dismiss-ActionSuggestionWithIgnore {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Tap-NotificationAction $T.Ignore
        } catch {
            Write-Host "Notification ignore action is hidden; opening compact prompt and tapping Ignore there."
            Tap-NotificationContentFallback
            Invoke-Adb shell cmd statusbar collapse | Out-Null
            Wait-UiContains $T.Ignore "Compact prompt did not expose Ignore fallback." 10
            Tap-Text $T.Ignore "compact-prompt-ignore.xml"
        }
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
        "com.suishouban.app:id/notification_action_content",
        "com.suishouban.app:id/notification_action_root",
        "com.suishouban.app:id/notification_generate"
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
    $sawNotification = $false
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            Assert-ActionSuggestionNotification | Out-Null
            $sawNotification = $true
            break
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if (-not $sawNotification) {
        Save-RemoteDiagnostics "notification-generate-missing"
        throw "Action suggestion notification was not visible before generate."
    }
    try {
        Tap-NotificationRootFallback
        Invoke-Adb shell cmd statusbar collapse | Out-Null
        Wait-UiContains $T.GenerateDraft "Notification root did not open request panel." 10
        Assert-FloatingPanelHeightUnder 0.35 "request prompt"
        Tap-Text $T.GenerateDraft "generate-draft.xml"
        return
    } catch {
        # Fall through; some system skins expose only action buttons reliably.
    }
    try {
        Tap-NotificationAction $T.Generate
        Invoke-Adb shell cmd statusbar collapse | Out-Null
        Wait-UiContains $T.GenerateDraft "Generate action did not open request panel." 10
        Assert-FloatingPanelHeightUnder 0.35 "request prompt"
        Tap-Text $T.GenerateDraft "generate-draft.xml"
        return
    } catch {
        Save-RemoteDiagnostics "notification-generate-click-failed"
        throw "Generate notification click did not open screenshot request panel. Direct Activity fallback is intentionally disabled."
    }
}

function Trigger-ReActRefinement {
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $xml = Get-UiXml "react-entry.xml"
        $center = Get-ClickableTextCenter $xml $T.AiRefine
        if (-not $center) {
            $center = Get-ClickableTextCenter $xml $T.AiRefineTitle
        }
        if (-not $center) {
            $center = Get-ExactTextCenter $xml $T.AiRefine
        }
        if (-not $center) {
            $center = Get-ExactTextCenter $xml $T.AiRefineTitle
        }
        if (-not $center) {
            $center = Get-TextCenter $xml $T.AiRefine
        }
        if (-not $center) {
            $center = Get-TextCenter $xml $T.AiRefineTitle
        }
        if ($center) {
            Write-Host "Tapping ReAct action at $($center.X),$($center.Y)"
            for ($tapAttempt = 1; $tapAttempt -le 3; $tapAttempt++) {
                Tap-RemotePoint -X $center.X -Y $center.Y
                try {
                    $seen = Wait-UiContainsAny @(
                        $T.AiRefineDone,
                        $T.AiRechecked,
                        $T.LocalRuleReview
                    ) "ReAct refinement did not produce a visible completion or fallback state." 8
                    Write-Host "ReAct refinement visible state: $seen"
                    return
                } catch {
                    if (Test-UiContains $T.AiRefineRunning) {
                        Start-Sleep -Seconds 2
                        try {
                            $seen = Wait-UiContainsAny @(
                                $T.AiRefineDone,
                                $T.AiRechecked,
                                $T.LocalRuleReview
                            ) "ReAct refinement did not finish after running state." 8
                            Write-Host "ReAct refinement visible state: $seen"
                            return
                        } catch {
                            if ($tapAttempt -eq 3) {
                                Save-RemoteDiagnostics "react-refine-timeout"
                                throw
                            }
                        }
                    } elseif ($tapAttempt -eq 3) {
                        Save-RemoteDiagnostics "react-refine-timeout"
                        throw
                    }
                }
            }
        }
        Invoke-Adb shell input swipe 650 1850 650 650 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    Save-RemoteDiagnostics "react-refine-missing"
    throw "Could not find ReAct refinement action in preview."
}

function Confirm-Preview {
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        if (-not (Test-PreviewOpen)) { return }
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
            foreach ($tap in @(
                @{ X = $center.X; Y = $center.Y },
                @{ X = 900; Y = $center.Y },
                @{ X = 1030; Y = $center.Y }
            )) {
                Invoke-Adb shell input tap $tap.X $tap.Y | Out-Null
                Start-Sleep -Seconds 3
                if (-not (Test-PreviewOpen)) { return }
            }
        }
        Invoke-Adb shell input swipe 650 1850 650 650 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    if (-not (Test-PreviewOpen)) { return }
    Save-RemoteDiagnostics "confirm-preview-missing"
    throw "Could not find confirm button in preview."
}

function Toggle-PreviewCardSelectionByText {
    param([string]$Text)
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $xml = Get-UiXml "preview-toggle-$attempt.xml"
        $center = Get-TextCenter $xml $Text
        if ($center) {
            Invoke-Adb shell input tap 112 $center.Y | Out-Null
            Start-Sleep -Seconds 1
            return
        }
        Invoke-Adb shell input swipe 650 1850 650 650 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    Save-RemoteDiagnostics "preview-toggle-missing"
    throw "Could not find preview card to toggle: $Text"
}

function Get-CardsXmlAcrossScroll {
    $combined = ""
    Invoke-Adb shell input tap 630 2635 | Out-Null
    Start-Sleep -Seconds 2
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $combined += "`n" + (Get-UiXml "cards-scan-$attempt.xml")
        Invoke-Adb shell input swipe 650 1900 650 930 450 | Out-Null
        Start-Sleep -Seconds 1
    }
    return $combined
}

function Assert-MultiSelectionSavedWithoutRegistration {
    $cardsXml = Get-CardsXmlAcrossScroll
    Assert-XmlContains $cardsXml $T.TeamReport "Selected meeting/report card was not saved."
    Assert-XmlNotContains $cardsXml $T.Registration "Unselected registration card was saved unexpectedly."
    $jobs = (Invoke-Adb shell dumpsys jobscheduler) -join "`n"
    if ($jobs -notmatch "com\.suishouban\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService") {
        Save-RemoteDiagnostics "multi-workmanager-job-missing"
        throw "No WorkManager reminder jobs were registered after selective creation."
    }
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
Write-ValidationPrefsDirect
Start-App
Configure-WorkflowUrl

Write-Host "Validating non-action noise screenshot..."
Open-SampleAndScreenshot "noise_shopping_promo.png"
Assert-NoActionSuggestionNotification
Open-SampleAndScreenshot "noise_status_only.png"
Assert-NoActionSuggestionNotification
Open-SampleAndScreenshot "noise_own_app_settings.png"
Assert-NoActionSuggestionNotification

Write-Host "Validating action screenshot notification and ignore action..."
Open-SampleAndScreenshot "complex_chat_promise.png"
Assert-ActionSuggestionNotification | Out-Null
Dismiss-ActionSuggestionWithIgnore
Wait-UiNotContains $T.MaybeTodo "Ignore action unexpectedly opened the request panel."

Write-Host "Validating action screenshot notification, generate action, preview, save, reminder..."
Open-SampleAndScreenshot "complex_course_notice.png"
Assert-ActionSuggestionNotification | Out-Null
$courseProviderBefore = $null
if (-not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    $courseProviderBefore = Get-ProviderStatusSnapshot $WorkflowUrl
}
Open-GeneratedPreviewFromNotification
Wait-UiContains $T.LabReport "Preview did not contain the expected task title."
Assert-FloatingPanelHeightUnder 0.65 "course candidate review"
Wait-UiContains $T.CourseScenario "Preview did not show course scenario classification."
Wait-UiContains $T.HighConfidence "Preview did not show confidence label."
if (-not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    Wait-UiContains $T.CloudModelParticipated "Preview did not show cloud model participation."
    Assert-ProviderStatusAdvanced $WorkflowUrl $courseProviderBefore @("fast_model", "expert_model") "phone course screenshot workflow"
}
Assert-UiNotContains $T.GenericSchedule "Preview regressed to generic schedule title."
Trigger-ReActRefinement
Assert-FloatingPanelHeightUnder 0.65 "course ReAct refinement"
Confirm-Preview
Assert-CardAndReminderCreated

Write-Host "Validating multi-task screenshot decomposition and selective card surface..."
Open-SampleAndScreenshot "complex_multi_tasks.png"
Assert-ActionSuggestionNotification | Out-Null
$multiProviderBefore = $null
if (-not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    $multiProviderBefore = Get-ProviderStatusSnapshot $WorkflowUrl
}
Open-GeneratedPreviewFromNotification
$multiPreviewXml = Get-PreviewXmlAcrossScroll
Assert-FloatingPanelHeightUnder 0.65 "multi-task candidate review"
Assert-XmlContains $multiPreviewXml $T.LabReport "Multi-task preview did not include the lab report task."
Assert-XmlContains $multiPreviewXml $T.TeamReport "Multi-task preview did not include the meeting/report task."
Assert-XmlContains $multiPreviewXml $T.Registration "Multi-task preview did not include the registration task."
Assert-XmlContains $multiPreviewXml $T.CreateAll "Multi-task preview did not expose all-create action."
if (-not [string]::IsNullOrWhiteSpace($WorkflowUrl)) {
    Assert-XmlContains $multiPreviewXml $T.CloudModelParticipated "Multi-task preview did not show cloud model participation."
    Assert-ProviderStatusAdvanced $WorkflowUrl $multiProviderBefore @("fast_model", "expert_model") "phone multi-task screenshot workflow"
}
Assert-XmlNotContains $multiPreviewXml $T.GenericSchedule "Multi-task preview regressed to generic schedule title."
Trigger-ReActRefinement
Assert-FloatingPanelHeightUnder 0.65 "multi-task ReAct refinement"
Toggle-PreviewCardSelectionByText $T.Registration
Wait-UiContains $T.CreateSelected "Multi-task preview did not expose selected-create action after toggling one card." 8
Confirm-Preview
Assert-MultiSelectionSavedWithoutRegistration
Assert-NoBadLogs
Assert-NoBadLogs

Write-Host ""
Write-Host "Remote complex screenshot validation passed on $Device."

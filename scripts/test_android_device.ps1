param(
    [string]$Device = "",
    [string]$SdkPath = "",
    [string]$GradlePath = "",
    [int]$InstrumentationTimeoutSeconds = 90,
    [switch]$Online
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$androidProject = Join-Path $root "apps\android"
$artifactRoot = Join-Path $root "artifacts\device-tests"

$sdkCandidates = @(
    $SdkPath,
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
) | Where-Object { $_ } | Select-Object -Unique
$sdk = $sdkCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ "platform-tools\adb.exe") } |
    Select-Object -First 1
if (-not $sdk) {
    throw "Android SDK platform-tools were not found."
}

$adb = Join-Path $sdk "platform-tools\adb.exe"
$serial = $Device
if (-not $serial) {
    $connected = & $adb devices |
        Select-String -Pattern "^\S+\s+device$" |
        ForEach-Object { ($_ -split "\s+")[0] }
    if ($connected.Count -ne 1) {
        throw "Specify -Device when zero or multiple Android devices are connected."
    }
    $serial = $connected[0]
}

$gradle = $GradlePath
if (-not $gradle) {
    $gradle = Get-ChildItem `
        -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-8.9-bin") `
        -Filter "gradle.bat" `
        -Recurse `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradle) {
        $gradle = Join-Path $androidProject "gradlew.bat"
    }
}

$gradleArgs = @("--no-daemon")
if (-not $Online) {
    $gradleArgs += "--offline"
}

function Invoke-DeviceAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb -s $serial @Arguments
}

function Confirm-VivoCrossAppLaunch {
    $remoteXml = "/sdcard/suishouban-device-test-app-jump.xml"
    $localXml = Join-Path $artifactRoot "app-jump-state.xml"
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $null = & $adb -s $serial shell uiautomator dump $remoteXml 2>&1
    $dumpExitCode = $LASTEXITCODE
    $null = & $adb -s $serial pull $remoteXml $localXml 2>&1
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorPreference
    if ($dumpExitCode -ne 0 -or $pullExitCode -ne 0 -or -not (Test-Path $localXml)) {
        return $false
    }

    [xml]$document = Get-Content -Raw -Encoding UTF8 $localXml
    $appFilterRoot = $document.SelectSingleNode("//node[@package='com.vivo.appfilter']")
    $targetText = $document.SelectSingleNode(
        "//node[contains(@text, '$testTargetPackage') or contains(@text, '$testPackage')]"
    )
    $alwaysOpen = $document.SelectSingleNode(
        "//node[@resource-id='android:id/button1' and @enabled='true']"
    )
    if (-not $appFilterRoot -or -not $targetText -or -not $alwaysOpen) {
        return $false
    }
    $center = Get-NodeCenter $alwaysOpen
    if (-not $center) {
        return $false
    }
    Invoke-DeviceAdb shell input tap ([int]$center[0]) ([int]$center[1]) | Out-Null
    return $true
}

function Invoke-InstrumentationClass {
    param(
        [Parameter(Mandatory = $true)][string]$ClassName,
        [Parameter(Mandatory = $true)][string]$Runner
    )
    $safeName = $ClassName.Replace(".", "_")
    $stdoutPath = Join-Path $artifactRoot "$safeName.stdout.log"
    $stderrPath = Join-Path $artifactRoot "$safeName.stderr.log"
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    $arguments = @(
        "-s", $serial,
        "shell", "am", "instrument",
        "-w", "-r", "-e", "class", $ClassName, $Runner
    )
    $process = Start-Process `
        -FilePath $adb `
        -ArgumentList $arguments `
        -PassThru `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($InstrumentationTimeoutSeconds)
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 500
            if (-not $process.HasExited) {
                Confirm-VivoCrossAppLaunch | Out-Null
            }
        }
        if (-not $process.HasExited) {
            $process.Kill()
            Invoke-DeviceAdb shell am force-stop $testPackage | Out-Null
            Invoke-DeviceAdb shell am force-stop $testTargetPackage | Out-Null
            throw "Instrumentation class timed out after $InstrumentationTimeoutSeconds seconds: $ClassName"
        }
        $output = @()
        if (Test-Path -LiteralPath $stdoutPath) {
            $output += Get-Content -LiteralPath $stdoutPath
        }
        if (Test-Path -LiteralPath $stderrPath) {
            $output += Get-Content -LiteralPath $stderrPath
        }
        $output | Out-Host
        $combined = $output -join "`n"
        $reportedSuccess = $combined -match "OK \(\d+ tests?\)"
        $reportedFailure = $combined -match "FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED"
        if ($reportedFailure -or (-not $reportedSuccess -and $process.ExitCode -ne 0)) {
            throw "Instrumentation class failed: $ClassName"
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
        }
    }
}

function Get-NodeCenter {
    param([System.Xml.XmlElement]$Node)
    if (-not $Node) {
        return $null
    }
    $match = [regex]::Match(
        $Node.GetAttribute("bounds"),
        "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$"
    )
    if (-not $match.Success) {
        return $null
    }
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    return @(
        [int](($left + $right) / 2),
        [int](($top + $bottom) / 2)
    )
}

function Confirm-VivoPackageInstall {
    $remoteXml = "/sdcard/suishouban-device-test-install.xml"
    $localXml = Join-Path $artifactRoot "install-state.xml"
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $null = & $adb -s $serial shell uiautomator dump $remoteXml 2>&1
    $dumpExitCode = $LASTEXITCODE
    $null = & $adb -s $serial pull $remoteXml $localXml 2>&1
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorPreference
    if ($dumpExitCode -ne 0 -or $pullExitCode -ne 0) {
        return $false
    }
    if (-not (Test-Path -LiteralPath $localXml)) {
        return $false
    }
    [xml]$document = Get-Content -Raw -Encoding UTF8 $localXml
    $installerRoot = $document.SelectSingleNode("//node[@package='com.android.packageinstaller']")
    $continueNode = $document.SelectSingleNode("//node[@resource-id='android:id/button1']")
    if (-not $installerRoot -or -not $continueNode) {
        return $false
    }

    $checkboxNode = $document.SelectSingleNode(
        "//node[@resource-id='com.android.packageinstaller:id/deleted_file_state_cb']"
    )
    if ($checkboxNode -and $checkboxNode.GetAttribute("checked") -ne "true") {
        $checkbox = Get-NodeCenter $checkboxNode
        if (-not $checkbox) {
            throw "The vivo risk acknowledgement is visible, but its bounds are invalid."
        }
        $checkboxX = [int]$checkbox[0]
        $checkboxY = [int]$checkbox[1]
        Invoke-DeviceAdb shell input tap $checkboxX $checkboxY | Out-Null
        Start-Sleep -Milliseconds 500
    }

    # Read the page again so Continue is never tapped against a stale unchecked state.
    $ErrorActionPreference = "SilentlyContinue"
    $null = & $adb -s $serial shell uiautomator dump $remoteXml 2>&1
    $dumpExitCode = $LASTEXITCODE
    $null = & $adb -s $serial pull $remoteXml $localXml 2>&1
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorPreference
    if ($dumpExitCode -ne 0 -or $pullExitCode -ne 0) {
        return $false
    }
    [xml]$document = Get-Content -Raw -Encoding UTF8 $localXml
    $installerRoot = $document.SelectSingleNode("//node[@package='com.android.packageinstaller']")
    if (-not $installerRoot) {
        # The installer may close between acknowledgement and this refresh because installation
        # already completed. The caller verifies the actual package before deciding success.
        return $true
    }
    $checkboxNode = $document.SelectSingleNode(
        "//node[@resource-id='com.android.packageinstaller:id/deleted_file_state_cb']"
    )
    if ($checkboxNode -and $checkboxNode.GetAttribute("checked") -ne "true") {
        return $false
    }
    $continueNode = $document.SelectSingleNode(
        "//node[@resource-id='android:id/button1' and @enabled='true']"
    )
    if (-not $continueNode) {
        # A vivo installer transition is not an error by itself. Leave it untouched and let the
        # install loop inspect the next stable state.
        return $false
    }
    $continue = Get-NodeCenter $continueNode
    if (-not $continue) {
        return $false
    }
    $continueX = [int]$continue[0]
    $continueY = [int]$continue[1]
    Invoke-DeviceAdb shell input tap $continueX $continueY | Out-Null
    return $true
}

function Install-TestApk {
    param(
        [string]$Apk,
        [string]$Label,
        [string]$PackageName
    )
    $stdout = Join-Path $artifactRoot "$Label-install.stdout.txt"
    $stderr = Join-Path $artifactRoot "$Label-install.stderr.txt"
    $process = Start-Process `
        -FilePath $adb `
        -ArgumentList @("-s", $serial, "install", "--no-streaming", "-r", "-t", "-g", $Apk) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Seconds 2
            if (-not $process.HasExited) {
                Confirm-VivoPackageInstall | Out-Null
            }
        }
        if (-not $process.HasExited) {
            $process.Kill()
            throw "$Label APK installation timed out."
        }
        $process.WaitForExit()
        $packageInstalled = [bool](
            Invoke-DeviceAdb shell pm path $PackageName |
                Select-String -SimpleMatch "package:"
        )
        if ($process.ExitCode -ne 0 -and -not $packageInstalled) {
            $details = (
                @(Get-Content $stdout -ErrorAction SilentlyContinue) +
                @(Get-Content $stderr -ErrorAction SilentlyContinue)
            ) -join "`n"
            throw "$Label APK installation failed: $details"
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
        }
    }
}

$mainPackage = "com.suishouban.app"
$testTargetPackage = "com.suishouban.app.devicetest"
$testPackage = "com.suishouban.app.devicetest.test"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$mainApk = Join-Path $androidProject "app\build\outputs\apk\debug\app-debug.apk"
$targetApk = Join-Path $androidProject "app\build\outputs\apk\deviceTest\app-deviceTest.apk"
$testApk = Join-Path $androidProject "app\build\outputs\apk\androidTest\deviceTest\app-deviceTest-androidTest.apk"
$testClasses = @(
    "com.suishouban.app.data.local.AppDatabaseMigrationTest",
    "com.suishouban.app.data.local.TeamCommandQueueTest",
    "com.suishouban.app.ui.CardsTeamNavigationTest",
    "com.suishouban.app.mascot.MofeiActionRingTest#screenshotActionCapturesOnTheFirstTap",
    "com.suishouban.app.mascot.MofeiActionRingTest#collapsedRingInvokesDismissFromCenterSeal",
    "com.suishouban.app.mascot.MofeiActionRingTest#expandedRingRevealsOneLabelBeforeInvokingAction",
    "com.suishouban.app.mascot.MofeiActionRingTest#expandedRingAutoDismissesFiveSecondsAfterTheLatestInteraction",
    "com.suishouban.app.mascot.MofeiPetSpriteAnimationTest",
    "com.suishouban.app.mascot.MofeiNotificationFirefliesTest",
    "com.suishouban.app.mascot.MofeiOverlayViewTreeOwnersTest"
)

$testFailure = $null
$mainWasInstalled = [bool](
    Invoke-DeviceAdb shell pm path $mainPackage |
        Select-String -SimpleMatch "package:"
)
New-Item -ItemType Directory -Force $artifactRoot | Out-Null
Push-Location $androidProject
try {
    & $gradle assembleDebug assembleDeviceTest assembleDeviceTestAndroidTest @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Android device-test APK build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

try {
    # Package installers reject confirmation while an application overlay is visible. Stop only
    # the running process so Mofei is detached; the installed app and all user data are preserved.
    Invoke-DeviceAdb shell am force-stop $mainPackage | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-DeviceAdb uninstall $testPackage | Out-Null
    Invoke-DeviceAdb uninstall $testTargetPackage | Out-Null
    Install-TestApk $targetApk "target" $testTargetPackage
    Install-TestApk $testApk "instrumentation" $testPackage

    foreach ($className in $testClasses) {
        Invoke-DeviceAdb shell am force-stop $mainPackage | Out-Null
        Invoke-DeviceAdb shell am force-stop $testTargetPackage | Out-Null
        # Foreground through the launcher path. OriginOS treats a direct shell launch of
        # ComponentActivity as a cross-app jump and can cover the test with AppJumpPrompt.
        & $adb -s $serial shell monkey -p $testTargetPackage -c `
            android.intent.category.LAUNCHER 1 | Out-Null
        Start-Sleep -Milliseconds 500
        Invoke-InstrumentationClass -ClassName $className -Runner $runner
    }
} catch {
    $testFailure = $_
} finally {
    Invoke-DeviceAdb shell am force-stop $testPackage | Out-Null
    Invoke-DeviceAdb shell am force-stop $testTargetPackage | Out-Null
    Invoke-DeviceAdb uninstall $testPackage | Out-Null
    Invoke-DeviceAdb uninstall $testTargetPackage | Out-Null

    if (-not (Test-Path -LiteralPath $mainApk)) {
        throw "Device tests finished, but the debug APK could not be restored."
    }
    $mainIsInstalled = [bool](
        Invoke-DeviceAdb shell pm path $mainPackage |
            Select-String -SimpleMatch "package:"
    )
    if (-not $mainIsInstalled) {
        Install-TestApk $mainApk "main-restore" $mainPackage
    } elseif ($mainWasInstalled) {
        Write-Host "Main app remained installed; skipping the risky replacement install."
    }
    Invoke-DeviceAdb shell am force-stop $mainPackage | Out-Null
    & $adb -s $serial shell am start -W -n "$mainPackage/.MainActivity" | Out-Host
}

if ($testFailure) {
    throw "$($testFailure.Exception.Message) The main app was restored and cold-started."
}
Write-Host "Device tests passed. $mainPackage and its data remained isolated from instrumentation."

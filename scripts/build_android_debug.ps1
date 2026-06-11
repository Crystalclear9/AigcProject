param(
    [string]$SdkPath = "",
    [string]$GradlePath = "",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$androidProject = Join-Path $root "apps\android"

$candidates = @(
    $SdkPath,
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
    "C:\Android\Sdk",
    "D:\Android\Sdk",
    "E:\Android\Sdk"
) | Where-Object { $_ } | Select-Object -Unique

$sdk = $candidates |
    Where-Object {
        Test-Path -LiteralPath "$($_.TrimEnd('\'))\platforms\android-35"
    } |
    Select-Object -First 1

if (-not $sdk) {
    throw @"
Android SDK 35 was not found.
Install Android Studio, then install:
  - Android SDK Platform 35
  - Android SDK Build-Tools 35
  - Android SDK Platform-Tools
Run this script again, optionally with:
  .\scripts\build_android_debug.ps1 -SdkPath "C:\path\to\Android\Sdk"
"@
}

$escapedSdk = $sdk.Replace("\", "\\")
Set-Content -LiteralPath (Join-Path $androidProject "local.properties") `
    -Value "sdk.dir=$escapedSdk" `
    -Encoding ASCII

Push-Location $androidProject
try {
    $gradle = $GradlePath
    if (-not $gradle) {
        $cachedGradle = Get-ChildItem `
            -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-8.9-bin") `
            -Filter "gradle.bat" `
            -Recurse `
            -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        $gradle = if ($cachedGradle) { $cachedGradle } else { ".\gradlew.bat" }
    }

    if (-not $SkipTests) {
        & $gradle testDebugUnitTest
        if ($LASTEXITCODE -ne 0) {
            throw "Android unit tests failed with exit code $LASTEXITCODE"
        }
    }

    & $gradle assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "APK build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$apk = Join-Path $androidProject "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    throw "Gradle completed but the expected APK was not found: $apk"
}

$item = Get-Item $apk
Write-Host ""
Write-Host "APK ready:"
Write-Host $item.FullName
Write-Host ("Size: {0:N2} MB" -f ($item.Length / 1MB))
Write-Host ("SHA-256: {0}" -f (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)

param(
    [switch]$Remove,
    [string]$TaskName = "Suishouban Phone AI Gateway",
    [string]$CredentialPath = "$env:LOCALAPPDATA\Suishouban\secrets\vivo-api-key.xml"
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "run_phone_ai_gateway.ps1"

if ($Remove) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "Removed scheduled task: $TaskName"
    exit 0
}

if (-not (Test-Path -LiteralPath $runner)) {
    throw "Gateway runner is missing: $runner"
}
if (-not (Test-Path -LiteralPath $CredentialPath)) {
    $secureKey = Read-Host "Enter the vivo API key (stored with Windows DPAPI)" -AsSecureString
    New-Item -ItemType Directory -Path (Split-Path -Parent $CredentialPath) -Force | Out-Null
    $secureKey | Export-Clixml -LiteralPath $CredentialPath
}

$escapedRunner = $runner.Replace('"', '\"')
$arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$escapedRunner`""
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$principal = New-ScheduledTaskPrincipal `
    -UserId "$env:USERDOMAIN\$env:USERNAME" `
    -LogonType Interactive `
    -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 5 `
    -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Description "Keeps the SuiShouBan local AI gateway running and restores adb reverse for connected phones." `
    -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Output "Installed and started scheduled task: $TaskName"
Write-Output "Credential: $CredentialPath"
Write-Output "Log: $env:LOCALAPPDATA\Suishouban\gateway\gateway.log"

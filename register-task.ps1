<#
.SYNOPSIS
    Registers Forge as a Windows scheduled task.

.DESCRIPTION
    Each invocation drains what it can and exits. Projects blocked by a quota window are left
    SUSPENDED with a retry time and picked up by a later run, so the schedule does not need to
    guess when quota reopens -- it only needs to check often enough.

    Every three hours is a reasonable default: subscription quota windows are measured in hours,
    and a shorter interval mostly starts runs that immediately suspend again.

.PARAMETER Root
    The Forge root, holding wishlist/ and runs/.

.PARAMETER IntervalHours
    How often to run. Default 3.

.EXAMPLE
    .\register-task.ps1
    .\register-task.ps1 -IntervalHours 6
#>
param(
    [string] $Root = $PSScriptRoot,
    [int]    $IntervalHours = 3,
    [string] $TaskName = "Forge"
)

$ErrorActionPreference = "Stop"

$jar = Join-Path $Root "forge\target\forge.jar"
if (-not (Test-Path $jar)) {
    throw "Not built yet: $jar`nRun: mvn -f forge\pom.xml package"
}

$java = (Get-Command java).Source

# -Dstdout.encoding=UTF-8 keeps non-ASCII characters legible in the task's captured output.
$action = New-ScheduledTaskAction -Execute $java `
    -Argument "-Dstdout.encoding=UTF-8 -jar `"$jar`" run --root `"$Root`"" `
    -WorkingDirectory $Root

$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).Date.AddHours(1) `
    -RepetitionInterval (New-TimeSpan -Hours $IntervalHours)

# Runs only while you are logged on, and does not wake the machine. An unattended pipeline that
# spawns two coding agents should not be starting up on a sleeping laptop unnoticed.
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 8)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Settings $settings -Description "Forge: unattended build pipeline" -Force | Out-Null

Write-Host "Registered '$TaskName', every $IntervalHours hour(s), root: $Root"
Write-Host "Run now:     Start-ScheduledTask -TaskName $TaskName"
Write-Host "Inspect:     Get-ScheduledTaskInfo -TaskName $TaskName"
Write-Host "Remove:      Unregister-ScheduledTask -TaskName $TaskName -Confirm:`$false"

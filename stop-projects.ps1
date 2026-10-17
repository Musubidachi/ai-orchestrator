# Stops every program running from a project Forge built: run.bat windows, preview servers, game and
# desktop windows, a project's own Java or Python. Never Forge itself, and never a project Forge is
# working on right now: killing an agent turn, a gate or a security check orphans the turn or fails it.
#
#   stop-projects.bat          list them, then stop them
#   stop-projects.bat /list    only list them
#
# A process counts as a project's when its command line or executable is under runs\<id>\. That finds
# anything launched from a project folder by path, which is how run.bat, npm, vite, Maven and a
# project's own virtual environment all start things. A program started by bare name from inside a
# project folder, with no path to it on its command line, is not found; the list says what was.
#
# What Forge is working on is asked of forge-ui itself (/api/jobs), not read off the process tree:
# on Windows an agent's tool commands outlive the shells that started them, so their parent links
# are cut, and a tree walk alone took a Claude turn's own browser tests for a stray program.
param([switch]$List)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$runs = (Join-Path $root 'runs').TrimEnd('\') + '\'

$all = @(Get-CimInstance Win32_Process)
$byParent = @{}
foreach ($p in $all) {
    if (-not $byParent.ContainsKey($p.ParentProcessId)) { $byParent[$p.ParentProcessId] = @() }
    $byParent[$p.ParentProcessId] += $p
}

# Forge: any Java running a forge jar (the editor, or a scheduled `forge run`), and everything under it.
$forge = @($all | Where-Object { $_.Name -like 'java*.exe' -and $_.CommandLine -match 'forge[^" ]*[.]jar' })
$spared = @{}
$queue = New-Object System.Collections.Queue
foreach ($f in $forge) { $queue.Enqueue($f.ProcessId) }
while ($queue.Count -gt 0) {
    $id = $queue.Dequeue()
    if ($spared.ContainsKey($id)) { continue }
    $spared[$id] = $true
    foreach ($child in @($byParent[$id])) { if ($child) { $queue.Enqueue($child.ProcessId) } }
}
$spared[$PID] = $true
$self = $all | Where-Object { $_.ProcessId -eq $PID }
if ($self) { $spared[$self.ParentProcessId] = $true }

# The projects Forge is working on right now, by the folder their repository is in. An enhancement
# (003.2) works in its parent's repository, so it protects runs\003.
$busy = @{}
$asked = $false
if ($forge.Count -gt 0) {
    try {
        $jobs = Invoke-RestMethod -Uri 'http://localhost:7777/api/jobs' -TimeoutSec 5
        # A list of projects. ($jobs.jobs would not do as a test: PowerShell reads it off every entry.)
        $entries = if ($jobs -is [array]) { $jobs } else { $jobs.jobs }
        foreach ($j in @($entries)) { if ($j.running) { $busy[($j.id -split '\.')[0]] = $j.id } }
        $asked = $true
    } catch {
        Write-Host ('Forge is running but could not say what it is working on (' + $_.Exception.Message + '), so nothing is stopped.')
        Write-Host 'Try again in a minute, or stop the projects from the dashboard.'
        exit 1
    }
}

function Project-Of($p) {
    foreach ($text in @($p.CommandLine, $p.ExecutablePath)) {
        if ($text) {
            $at = $text.IndexOf($runs, [StringComparison]::OrdinalIgnoreCase)
            if ($at -ge 0) { return ($text.Substring($at + $runs.Length) -split '[\\/"]')[0] }
        }
    }
    return $null
}

$found = @()
$protected = @()
foreach ($p in $all) {
    if ($spared.ContainsKey($p.ProcessId)) { continue }
    $project = Project-Of $p
    if (-not $project) { continue }
    if ($busy.ContainsKey(($project -split '\.')[0])) { $protected += $p; continue }
    $found += $p
}
# Only the top of each tree: stopping it stops the rest, and listing every child is noise.
$foundIds = @{}
foreach ($p in $found) { $foundIds[$p.ProcessId] = $true }
$tops = @($found | Where-Object { -not $foundIds.ContainsKey($_.ParentProcessId) })

if ($forge.Count -gt 0) {
    Write-Host ("Forge is running (PID " + ($forge.ProcessId -join ', ') + "); it is left alone.")
}
if ($busy.Count -gt 0) {
    Write-Host ("Forge is working on " + (($busy.Values | Sort-Object) -join ', ') + " right now; " + $protected.Count + " process(es) in those folders are left alone.")
}
if ($tops.Count -eq 0) {
    Write-Host 'No other project programs are running.'
    exit 0
}

Write-Host ''
Write-Host 'Running from project folders:'
foreach ($p in $tops) {
    $line = if ($p.CommandLine) { $p.CommandLine } else { $p.ExecutablePath }
    if ($line.Length -gt 140) { $line = $line.Substring(0, 137) + '...' }
    Write-Host ("  [{0}] PID {1,-6} {2}" -f (Project-Of $p), $p.ProcessId, $line)
}

if ($List) {
    Write-Host ''
    Write-Host 'Listed only (/list). Run without it to stop them.'
    exit 0
}

Write-Host ''
$stopped = 0
foreach ($p in $tops) {
    # /T takes the whole tree: a run.bat window and the server it started. A browser tab it opened is
    # not its child and stays open.
    & taskkill.exe /PID $p.ProcessId /T /F 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $stopped++ } else { Write-Host ("  could not stop PID " + $p.ProcessId + " (already gone)") }
}
Write-Host ("Stopped " + $stopped + " of " + $tops.Count + ".")

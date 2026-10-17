# ---------------------------------------------------------------------------
#  Asks a running Forge to stop without destroying anything, and waits.
#
#  Every rebuild has to free forge.jar, and the only way to do that used to be
#  Stop-Process -Force. That kills whatever agent turn happened to be running,
#  and Windows does not reap its children -- so the CLI carries on writing into
#  a workspace nobody is watching, and the work it had not had committed is
#  orphaned. One project here lost a handoff exactly that way and parked on a
#  complaint about a field the turn never got as far as writing.
#
#  So: ask, wait for the turn to reach its commit, and only then let the build
#  run. If it will not settle, say what is still working and leave it alone --
#  deciding to interrupt an agent is a judgement for the person at the keyboard.
#
#  The editor is the Forge listening on the port, and that is the process this
#  waits for. It used to wait until no Forge of any kind was left, so with a
#  scheduled "forge run" or the browser tests' own editor alive it timed out
#  after the editor had already gone, and the launcher reported an editor that
#  had stopped as one that had been left running.
#
#  Exit codes: 0 stopped (or was not running) and nothing else is using the jar;
#              1 still working, or it did not exit; 2 unreachable;
#              3 the editor is stopped, or was not running, but another Forge
#                process is still running from the jar.
# ---------------------------------------------------------------------------

param(
  [int] $Port = 7777,
  [int] $WaitSeconds = 900
)

$ErrorActionPreference = "Stop"

# Both launchers. restart.ps1 starts the editor with javaw so it has no console
# window, and looking only for java.exe reported a running Forge as absent --
# after which the build went ahead and failed on the very jar it is holding.
function Get-ForgeProcesses {
  @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -like '*forge.jar*' })
}

function Write-Others($others) {
  foreach ($p in $others) {
    Write-Host "           PID $($p.ProcessId): $($p.CommandLine)"
  }
}

$owner = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
  Select-Object -First 1 -ExpandProperty OwningProcess
$editor = if ($owner) { Get-ForgeProcesses | Where-Object { $_.ProcessId -eq $owner } } else { $null }

if (-not $editor) {
  $others = Get-ForgeProcesses
  if ($others.Count -eq 0) {
    Write-Host "[quiesce] Forge is not running."
    exit 0
  }
  Write-Host "[quiesce] No editor is on port $Port, but another Forge is running from the jar:"
  Write-Others $others
  exit 3
}

Write-Host "[quiesce] Asking Forge on port $Port to finish up (up to $WaitSeconds s)..."

try {
  # The server holds this request open while it waits, so the client timeout has
  # to outlast the wait it was asked for rather than the other way round.
  # JSON, because the editor refuses a write that is not: a form or an image tag on some web page
  # must not be able to stop Forge, and only a JSON body cannot be sent across sites unannounced.
  # 127.0.0.1 rather than "localhost", for the reason restart.ps1 gives: Forge binds the IPv4
  # loopback, and "localhost" may try ::1 first.
  $response = Invoke-RestMethod -Method Post -TimeoutSec ($WaitSeconds + 60) `
    -ContentType "application/json" -Body "{}" `
    -Uri "http://127.0.0.1:$Port/api/quit?wait=$WaitSeconds"
} catch {
  Write-Host "[quiesce] Could not reach Forge on port $Port : $($_.Exception.Message)"
  Write-Host "[quiesce] It may be an older build with no /api/quit. Stop it yourself, or"
  Write-Host "          run the build with -Force once you are sure nothing is mid-turn."
  exit 2
}

if (-not $response.stopped) {
  Write-Host "[quiesce] Forge is $($response.detail)."
  Write-Host "[quiesce] Not stopping it. Wait for the turn, or stop that project from the"
  Write-Host "          dashboard first -- killing it now loses whatever it has written."
  exit 1
}

# The process exits a moment after answering, so the port is briefly still held.
$gone = $false
for ($i = 0; $i -lt 60; $i++) {
  if (-not (Get-Process -Id $editor.ProcessId -ErrorAction SilentlyContinue)) {
    $gone = $true
    break
  }
  Start-Sleep -Milliseconds 500
}
if (-not $gone) {
  Write-Host "[quiesce] Forge said it would stop but is still running after 30s (PID $($editor.ProcessId))."
  exit 1
}

$others = Get-ForgeProcesses
if ($others.Count -gt 0) {
  Write-Host "[quiesce] The editor stopped cleanly; $($response.detail)."
  Write-Host "[quiesce] Another Forge is still running from the jar:"
  Write-Others $others
  exit 3
}

Write-Host "[quiesce] Stopped cleanly; $($response.detail)."
exit 0

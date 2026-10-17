# ---------------------------------------------------------------------------
#  Starts the editor again after a build, and checks that it actually came up.
#
#  Separate from the scripts that call it because both verify.bat and
#  redeploy.bat need it and a second copy would drift -- the same argument the
#  port parsing in forge-ui.bat makes about itself.
#
#  The health check is the point. Starting it detached means a failure to boot
#  is silent: no window, no error, and a dashboard that simply is not there the
#  next time you look for it.
# ---------------------------------------------------------------------------

param(
  [int] $Port = 7777,
  [string] $Root = $PSScriptRoot
)

$ErrorActionPreference = "Stop"
$jar = Join-Path $Root "forge\target\forge.jar"

if (-not (Test-Path $jar)) {
  Write-Host "[restart] No jar at $jar; nothing to start."
  exit 1
}

# Never a second copy. If something already answers on the port there is an editor running, or
# something that would stop this one from starting, and either way starting another is wrong: on
# 2026-10-01 a second copy, launched by this script half a minute after someone had opened the
# editor by hand, took the first one's running gate command for a leftover and killed it before
# it found the port taken. Forge no longer does that, and this script should not have tried.
#
# Only a Forge on the port counts as success. Anything else holding it means no editor will start,
# and saying "already listening" with a zero exit let verify.bat report every suite passed while
# the dashboard was not there at all.
$holder = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
  Select-Object -First 1 -ExpandProperty OwningProcess
if ($holder) {
  $process = Get-CimInstance Win32_Process -Filter "ProcessId = $holder" -ErrorAction SilentlyContinue
  if ($process -and $process.CommandLine -like '*forge.jar*') {
    Write-Host "[restart] An editor is already running on port $Port (PID $holder); not starting another."
    Write-Host "          To replace it, stop it first with quiesce.ps1."
    exit 0
  }
  $name = if ($process) { $process.Name } else { "an unknown process" }
  Write-Host "[restart] Port $Port is held by $name (PID $holder), which is not Forge. The editor was not started."
  Write-Host "          Free the port, or start the editor on another one with forge-ui.bat <port>."
  exit 1
}

# javaw when it is there, because the editor has no console to write to and a
# stray window is noise. java otherwise: some JDK layouts ship only the one, and
# failing to start over the choice of launcher would be a silly way to be down.
$launcher = if (Get-Command javaw -ErrorAction SilentlyContinue) { "javaw" } else { "java" }

# Logged to a file, because detached it had nowhere to write. The editor stopped on its own one night
# some hours after finishing a project, with no reboot and one exit path in the code, and there was
# no way to find out why: every line it would have printed went nowhere.
#
# The log never did say, because the editor was not exiting: Forge was killing it. Started detached
# like this, its parent is a launcher that has already gone, and Windows gives that launcher's
# process id to something else. When that something was one of Forge's own commands, the editor
# looked like the command's child and was cleaned up with it. Fixed on 2026-10-01 in ProcessTree
# (forge-core, process), which is where the account of it lives.
#
# Earlier logs are archived under logs\archive, named for when they were last written, and the
# newest twenty of each kind are kept. Keeping only one previous log meant the run that died was
# gone after two rebuilds, and a working session here rebuilds far more often than that.
$logDir = Join-Path $Root "logs"
$archive = Join-Path $logDir "archive"
New-Item -ItemType Directory -Force -Path $archive | Out-Null
$out = Join-Path $logDir "forge-ui.log"
$err = Join-Path $logDir "forge-ui.err.log"
foreach ($file in @($out, $err, "$out.previous", "$err.previous")) {
  if (-not (Test-Path $file)) { continue }
  $item = Get-Item $file
  if ($item.Length -eq 0) { Remove-Item -Force $file; continue }
  $kind = if ($item.Name -like "*.err.log*") { "forge-ui.err" } else { "forge-ui" }
  $stamp = $item.LastWriteTime.ToString("yyyyMMdd-HHmmss")
  Move-Item -Force $file (Join-Path $archive "$kind-$stamp.log")
}
foreach ($kind in @("forge-ui", "forge-ui.err")) {
  Get-ChildItem $archive -Filter "$kind-*.log" | Where-Object { $_.Name -match "^$([regex]::Escape($kind))-\d{8}-\d{6}\.log$" } |
    Sort-Object Name -Descending | Select-Object -Skip 20 | Remove-Item -Force
}

# The editor should behave the same whoever started it. A hardened shell sets this variable, which
# stops cmd running a file from the current folder by its bare name; an editor that inherits it
# passes that on to every gate command and every run file, and a project whose acceptance command
# is "mvnw test" would pass when the editor was opened by a double-click and fail when it was
# restarted from such a shell. Found on 2026-10-02, when Play opened a window that could not find
# the run.bat it was standing next to.
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue

Start-Process -FilePath $launcher -WindowStyle Hidden `
  -RedirectStandardOutput $out -RedirectStandardError $err `
  -ArgumentList "-jar", $jar, "ui", "--port", $Port, "--root", $Root

# A socket rather than Invoke-WebRequest, and 127.0.0.1 rather than "localhost".
#
# Both matter. Invoke-WebRequest inside this script kept timing out against an editor that was
# answering 200 to the identical request from the calling shell -- proxy detection and name
# resolution are the usual suspects and neither is worth diagnosing to answer "is the port open".
# And Forge binds the loopback address explicitly, so "localhost" resolving to ::1 first is a
# failure mode that costs a wait and tells you nothing.
#
# Ninety seconds, because starting is not just binding: it loads the wishlist, reads every run's
# state and picks the queue back up. Twenty seconds looked generous and reported a healthy editor
# as a failed one, which is worse than waiting -- the next thing anyone does about a "failed" start
# is to start a second copy, which cannot have the port and really does fail.
for ($i = 0; $i -lt 90; $i++) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $client.Connect("127.0.0.1", $Port)
    if ($client.Connected) {
      Write-Host "[restart] Editor is up on http://localhost:$Port"
      exit 0
    }
  } catch {
    Start-Sleep -Seconds 1
  } finally {
    $client.Dispose()
  }
}

Write-Host "[restart] The editor did not open port $Port within 90s. Start it with forge-ui.bat"
Write-Host "          to see why -- detached, it has nowhere to print the reason."
exit 1

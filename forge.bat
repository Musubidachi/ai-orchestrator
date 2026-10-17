@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ---------------------------------------------------------------------------
rem  Forge launcher.
rem
rem  Runs Forge from anywhere without needing to remember the jar path, the root
rem  flag, or the encoding switches:
rem
rem      forge                 show help
rem      forge ui              edit the wishlist at localhost:7777
rem      forge run             build every eligible project
rem      forge status          one line per project
rem      forge report          regenerate the HTML reports
rem      forge rank            renumber: projects 1..P, then improvements
rem      forge prune           delete build output from finished runs
rem      forge build           rebuild the jar
rem
rem  The jar is rebuilt automatically whenever a source file is newer than it, so
rem  editing the code and running forge never runs the previous build. Set
rem  FORGE_SKIP_BUILD=1 to turn that off. Tests are not run here -- that is what
rem  verify.bat is for; this only has to produce a jar that matches the sources.
rem
rem  The Forge root defaults to this script's own directory, so the wishlist and
rem  runs are found no matter what directory you launch from. Passing your own
rem  --root still wins, because Forge takes the first occurrence of an option --
rem  which is what lets you keep runs/ on another drive:
rem
rem      forge run --root D:\forge
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "JAR=%ROOT%\forge\target\forge.jar"

where java >nul 2>&1
if errorlevel 1 (
  echo [forge] Java was not found on PATH. Install a JDK 24 or newer.
  exit /b 1
)

if /i "%~1"=="build" (
  call :build || exit /b 1
  echo [forge] Built %JAR%
  exit /b 0
)

rem Only the editor binds a port, so only the editor needs one freed. A previous
rem run left in the background keeps the port and the new one dies on startup,
rem which reads as "the editor is broken" rather than "it is already running".
rem
rem This runs before the build, not after: a running editor holds forge.jar open
rem and the shade plugin cannot replace a locked file, so freeing the port is
rem also what makes rebuilding possible.
if /i "%~1"=="ui" (
  call :readport %*
  call :freeport !PORT!
  if defined PORT_BLOCKED exit /b 1
)

call :buildifneeded || exit /b 1

if "%~1"=="" (
  java -jar "%JAR%"
  exit /b %ERRORLEVEL%
)

rem -Dstdout.encoding keeps non-ASCII output legible in the Windows console,
rem which otherwise mangles it to a codepage that cannot represent it.
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar "%JAR%" %* --root "%ROOT%"
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------
rem  :readport  -- sets PORT from "--port <n>", defaulting to 7777.
rem ---------------------------------------------------------------------------
:readport
set "PORT=7777"
:readport_scan
if "%~1"=="" exit /b 0
if /i "%~1"=="--port" (
  if not "%~2"=="" set "PORT=%~2"
  exit /b 0
)
shift
goto :readport_scan

rem ---------------------------------------------------------------------------
rem  :freeport <port>
rem
rem  Stops a Forge editor still holding the port. Deliberately narrow: it stops
rem  only a process actually running forge.jar. Something else listening on 7777
rem  is far more likely to be an unrelated application than a stray Forge, and
rem  silently killing it would be a poor trade for saving one manual step -- so
rem  that case is reported and the launch stops instead.
rem
rem  A Forge is asked, not killed: see :stopforge.
rem
rem  The test is the command line, not the image name. restart.ps1 launches the
rem  editor with javaw so it has no console window, and a check for java.exe
rem  therefore called a perfectly healthy Forge a stranger and refused to start.
rem  An unrelated Java application on the port is still left alone, which was the
rem  point of being narrow in the first place.
rem ---------------------------------------------------------------------------
:freeport
set "PORT_BLOCKED="
set "TARGET=%~1"
if "%TARGET%"=="" exit /b 0

for /f "tokens=5" %%P in ('netstat -ano ^| findstr /c:":%TARGET% " ^| findstr /i "LISTENING"') do (
  set "HOLDER_PID=%%P"
  set "HOLDER_IMG=unknown"
  for /f "tokens=1 delims=," %%I in ('tasklist /FI "PID eq %%P" /NH /FO CSV 2^>nul') do (
    set "HOLDER_IMG=%%~I"
  )

  call :isforge !HOLDER_PID!
  if "!IS_FORGE!"=="1" (
    call :stopforge !HOLDER_PID! %TARGET% !HOLDER_IMG!
  ) else (
    echo [forge] Port %TARGET% is held by !HOLDER_IMG! ^(PID !HOLDER_PID!^), which is not Forge.
    echo [forge] Leaving it alone. Stop it yourself, or use: forge ui --port ^<other^>
    set "PORT_BLOCKED=1"
  )
)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :stopforge <pid> <port> <image>
rem
rem  Asks the Forge holding the port to finish its agent turn and stop, and
rem  waits for it. If it is still working when the wait runs out it is left
rem  running and nothing new is started.
rem
rem  This used to be taskkill /F. verify.bat, redeploy.bat and the dashboard's
rem  "Finish and stop" had all been moved to asking, because a kill in the middle
rem  of a turn throws that turn away and leaves its CLI running with nobody
rem  watching. This launcher was the one left behind, and it is the one that gets
rem  double-clicked: opening the editor while it was already working on a project
rem  killed the turn it was in the middle of.
rem
rem  FORGE_FORCE_STOP=1 brings the kill back, for a Forge that cannot answer --
rem  an older build, or one whose jar was replaced underneath it. That is a
rem  decision for the person at the keyboard, so it has to be asked for.
rem ---------------------------------------------------------------------------
:stopforge
if defined FORGE_FORCE_STOP (
  echo [forge] Port %~2 held by Forge ^(%~3, PID %~1^) - FORGE_FORCE_STOP is set, killing it.
  taskkill /F /PID %~1 >nul 2>&1
  rem Windows does not release the socket the instant the process dies.
  timeout /t 1 /nobreak >nul 2>&1
  exit /b 0
)
echo [forge] Port %~2 is held by a running Forge ^(%~3, PID %~1^). Asking it to finish and stop.
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\quiesce.ps1" -Port %~2
rem 0: stopped. 3: stopped, but another Forge is running from the jar - so the editor is free to
rem start again, and the jar is not rebuilt underneath the other one (see :buildifneeded).
if not errorlevel 1 goto :stopped
if errorlevel 3 if not errorlevel 4 goto :stopped
set "PORT_BLOCKED=1"
echo [forge] It was left running, and nothing new was started.
echo [forge] To stop it anyway, losing whatever its agent has not had committed:
echo [forge]     set FORGE_FORCE_STOP=1
echo [forge] and run this again.
exit /b 0

:stopped
rem Windows does not release the socket the instant the process dies.
timeout /t 1 /nobreak >nul 2>&1
exit /b 0

rem ---------------------------------------------------------------------------
rem  :forgerunning    sets FORGE_RUNNING=1 when any Forge is running, on any port
rem
rem  The same test quiesce.ps1 uses: both launchers, matched on the command line.
rem ---------------------------------------------------------------------------
:forgerunning
set "FORGE_RUNNING="
for /f "usebackq delims=" %%C in (`powershell -NoProfile -Command "if (Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'java.exe','javaw.exe' -and $_.CommandLine -match 'forge\.jar' }) { 'yes' } else { 'no' }" 2^>nul`) do (
  if /i "%%C"=="yes" set "FORGE_RUNNING=1"
)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :isforge <pid>    sets IS_FORGE=1 when that process is running forge.jar
rem
rem  tasklist gives the image name and nothing else, and the image name cannot
rem  tell a Forge editor from any other Java program. The command line can.
rem ---------------------------------------------------------------------------
:isforge
set "IS_FORGE="
if "%~1"=="" exit /b 0
for /f "usebackq delims=" %%C in (`powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter 'ProcessId = %~1' -ErrorAction SilentlyContinue; if ($p -and $p.CommandLine -match 'forge\.jar') { 'yes' } else { 'no' }" 2^>nul`) do (
  if /i "%%C"=="yes" set "IS_FORGE=1"
)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :buildifneeded
rem
rem  Rebuilds when a source file is newer than the jar, so you never run code you
rem  have already changed. Not unconditionally: "forge run" is on a schedule and
rem  "forge status" should answer instantly, and paying Maven every few hours to
rem  discover nothing changed adds latency and a failure mode -- a transient
rem  Maven or network problem would turn a working unattended run into no run.
rem
rem  Hence the two different failures below. With no jar there is nothing to fall
rem  back to and a build failure is fatal. With a jar already present, a stale one
rem  that works beats no run at all, so the failure is reported and the old jar
rem  runs anyway; the alternative is a scheduler that silently stops building
rem  projects because of a compile error in an unrelated commit.
rem
rem  Set FORGE_SKIP_BUILD=1 to bypass this entirely.
rem ---------------------------------------------------------------------------
:buildifneeded
if defined FORGE_SKIP_BUILD exit /b 0

if not exist "%JAR%" (
  echo [forge] No jar yet - building...
  call :build || exit /b 1
  exit /b 0
)

call :isstale
if not errorlevel 1 exit /b 0

rem Never underneath a running Forge. The editor does not hold the jar in a way
rem that stops it being replaced -- one was, on 2026-10-01, twenty-eight seconds
rem after the editor started -- and a JVM whose jar has changed under it can fail
rem on the next class it loads. "forge ui" has already asked a running editor to
rem stop by this point, so this is for every other command: a "forge status" in
rem another window must not be what breaks the editor.
call :forgerunning
if defined FORGE_RUNNING (
  echo [forge] Sources changed since the jar was built, but a Forge is running from it.
  echo [forge] Not rebuilding underneath it; this uses the existing jar. redeploy.bat rebuilds safely.
  exit /b 0
)

echo [forge] Sources changed since the jar was built - rebuilding...
call :build
if errorlevel 1 (
  echo [forge] Build failed. Running the existing jar, which is now out of date.
  exit /b 0
)
exit /b 0

rem ---------------------------------------------------------------------------
rem  :isstale  -- errorlevel 1 when any module's source, the page, or any pom
rem               is newer than the jar.
rem
rem  Every module, not one folder: Forge is split into forge-core ... forge-app,
rem  and a check that looked only at the old forge/src would never see a change
rem  again and keep running an out-of-date jar. Build output is skipped.
rem
rem  PowerShell rather than batch: comparing timestamps across a directory tree
rem  in cmd means parsing localised date strings, which breaks on a machine with
rem  different regional settings. Paths go through the environment to keep them
rem  clear of both quoting layers. Filtered with Where-Object because
rem  Get-ChildItem -Include is ignored alongside -LiteralPath.
rem ---------------------------------------------------------------------------
:isstale
set "FORGE_JAR=%JAR%"
set "FORGE_DIR=%ROOT%\forge"
powershell -NoProfile -Command ^
  "$jar = (Get-Item -LiteralPath $env:FORGE_JAR).LastWriteTimeUtc;" ^
  "$newest = (Get-ChildItem -LiteralPath $env:FORGE_DIR -Recurse -File -ErrorAction SilentlyContinue |" ^
  "  Where-Object { ($_.Extension -in '.java','.html' -or $_.Name -eq 'pom.xml') -and $_.FullName -notmatch '\\(target|last-good)\\' } |" ^
  "  Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum;" ^
  "if ($newest -gt $jar) { exit 1 } else { exit 0 }"
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------
:build
where mvn >nul 2>&1
if errorlevel 1 (
  echo [forge] Maven was not found on PATH, so the jar cannot be built.
  exit /b 1
)
call mvn -B -q -f "%ROOT%\forge\pom.xml" package -DskipTests
if errorlevel 1 (
  echo [forge] Build failed.
  exit /b 1
)
exit /b 0

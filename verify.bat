@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ---------------------------------------------------------------------------
rem  Everything that can tell you whether a change broke something.
rem
rem  Two suites, because they catch different things and neither subsumes the
rem  other:
rem
rem    Java       - the pipeline's decisions. What is allowed to run, what a
rem                 phase means, what gets deleted, what command line reaches a
rem                 vendor. Includes a node --check parse of the dashboard
rem                 script, which is why a broken page fails here too.
rem    Playwright - what the browser actually does with that page. Reorder
rem                 arrows that move cards, panels that survive a redraw,
rem                 controls that do not overlap. None of it visible to Java.
rem
rem  Neither runs an agent, so this costs no subscription quota and can be run
rem  as often as you like. The tests that do spend quota are tagged "live" and
rem  excluded by default -- run those deliberately:
rem      mvn -f forge/pom.xml test -Dexcluded.groups=
rem
rem  Coverage is written to forge/target/site/jacoco/index.html. It is reported
rem  and not enforced: a threshold would be met by testing whatever is easiest,
rem  which is rarely the code where being wrong costs anything.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "FAILED="

rem The UI server holds forge.jar open, so a build cannot replace it while the
rem editor is running. It is asked to stop rather than killed: a forced stop
rem during an agent turn orphans the CLI -- Windows does not reap it -- and
rem loses whatever it had written but not yet had committed. That happened, and
rem the project parked on a complaint about a handoff the turn never reached.
rem
rem Refuses to build while a turn is in flight. Waiting is the right answer:
rem the moment between two turns is safe to stop in, and it arrives on its own.
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%quiesce.ps1"
rem 3: the editor stopped, but another Forge - a scheduled run, say - is still running from the
rem jar, and a build would replace it underneath that one. The editor is put back, unchanged.
if errorlevel 3 (
  echo.
  echo Nothing was built: another Forge process, listed above, is running from forge.jar,
  echo and a build would replace the jar underneath it. Run this again once it has finished.
  powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%restart.ps1"
  exit /b 1
)
if errorlevel 1 (
  echo.
  echo Nothing was built. Wait for the turn to finish, or stop that project
  echo from the dashboard first.
  exit /b 1
)

rem The jar that was running, kept outside target\ where clean cannot reach it. A
rem build that fails deletes the old jar and produces no new one, and this used to
rem end with "No jar; nothing to start" -- a failing test left the dashboard down.
if exist "%ROOT%forge\target\forge.jar" (
  if not exist "%ROOT%forge\last-good" mkdir "%ROOT%forge\last-good"
  copy /y "%ROOT%forge\target\forge.jar" "%ROOT%forge\last-good\forge.jar" >nul
)

echo.
echo === Java: pipeline behaviour =========================================
call mvn -B -f "%ROOT%forge\pom.xml" clean package
if errorlevel 1 set "FAILED=!FAILED! java"

rem Playwright runs the jar the build just produced, so a build that failed means
rem the browser suite would be testing yesterday's code and reporting it green.
rem That happened: a locked jar failed the clean, and the run below still said
rem "18 passed" about a build that did not contain the change being verified.
if defined FAILED (
  echo.
  echo === Playwright: skipped ==============================================
  echo The jar was not rebuilt, so the browser suite would be testing the old
  echo one. Fix the build first.
  goto :restart
)

echo.
echo === Playwright: the dashboard in a browser ===========================
pushd "%ROOT%ui-tests"
if not exist node_modules (
  echo [verify] Installing test dependencies on first run
  call npm install --no-audit --no-fund
)
call npx playwright test
if errorlevel 1 set "FAILED=!FAILED! playwright"
popd

:restart

rem Put it back however the suites went. A failing test is a reason to read the
rem output, not a reason to be left without a dashboard -- and forgetting this
rem step by hand is how the editor ended up down after half the builds today.
echo.
echo === Restarting the editor ============================================
if not exist "%ROOT%forge\target\forge.jar" if exist "%ROOT%forge\last-good\forge.jar" (
  echo [verify] No new jar was built; restarting the one that was running before.
  if not exist "%ROOT%forge\target" mkdir "%ROOT%forge\target"
  copy /y "%ROOT%forge\last-good\forge.jar" "%ROOT%forge\target\forge.jar" >nul
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%restart.ps1"

echo.
echo === Result ===========================================================
if defined FAILED (
  echo FAILED:!FAILED!
  echo.
  echo Read the failure before changing the test. A test that fails after an
  echo edit is doing its job; the question is whether the behaviour it pins is
  echo still what you want, not how to make the message go away.
  exit /b 1
)

echo All suites passed.
echo Coverage report: forge\target\site\jacoco\index.html
exit /b 0

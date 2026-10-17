@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ---------------------------------------------------------------------------
rem  Rebuild Forge and put it back, without destroying work in flight.
rem
rem  The sequence that matters is the whole point of this file:
rem
rem    1. ask the running editor to stop once its agent turn reaches a commit
rem    2. only then rebuild, because the jar cannot be replaced while it is held
rem    3. start it again
rem
rem  Killing the process to free the jar is what this replaces. Windows does not
rem  reap the agent it was waiting on, so a forced stop leaves a CLI writing into
rem  a workspace nobody is watching and loses whatever it had not had committed.
rem
rem  Use verify.bat instead when you want the tests as well; it does the same
rem  three steps with both suites in the middle.
rem
rem  If a turn is in flight this refuses and says which project. That is the
rem  intended behaviour: wait for it, or stop that project from the dashboard.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "PORT=7777"
if not "%~1"=="" set "PORT=%~1"

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%quiesce.ps1" -Port %PORT%
if errorlevel 3 goto :otherforge
if errorlevel 2 goto :unreachable
if errorlevel 1 goto :busy

echo.
echo === Building =========================================================
rem Kept outside target\, which clean empties; see verify.bat.
if exist "%ROOT%forge\target\forge.jar" (
  if not exist "%ROOT%forge\last-good" mkdir "%ROOT%forge\last-good"
  copy /y "%ROOT%forge\target\forge.jar" "%ROOT%forge\last-good\forge.jar" >nul
)
call mvn -B -f "%ROOT%forge\pom.xml" clean package
if errorlevel 1 (
  echo.
  echo The build failed. Fix it and run this again.
  rem Put back what was running: a failed build is a reason to read the output,
  rem not a reason to be left without Forge.
  if not exist "%ROOT%forge\target\forge.jar" if exist "%ROOT%forge\last-good\forge.jar" (
    echo Restarting the build that was running before.
    if not exist "%ROOT%forge\target" mkdir "%ROOT%forge\target"
    copy /y "%ROOT%forge\last-good\forge.jar" "%ROOT%forge\target\forge.jar" >nul
    powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%restart.ps1" -Port %PORT%
  )
  exit /b 1
)

echo.
echo === Starting =========================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%restart.ps1" -Port %PORT%
exit /b %ERRORLEVEL%

:busy
echo.
echo Nothing was rebuilt. Forge is still running and still working.
exit /b 1

:unreachable
echo.
echo Nothing was rebuilt, because it is not clear what state Forge is in.
exit /b 1

:otherforge
rem The editor stopped, but another Forge is running from the jar. Rebuilding would replace the
rem jar underneath that one, so the editor is started again as it was.
echo.
echo Nothing was rebuilt: another Forge process, listed above, is running from forge.jar.
echo Run this again once it has finished.
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%restart.ps1" -Port %PORT%
exit /b 1

@echo off
setlocal EnableExtensions

rem ---------------------------------------------------------------------------
rem  Opens the wishlist editor and points a browser at it.
rem
rem  Freeing the port is done by forge.bat, not here. It already parses --port
rem  for the ui command, and a second copy of the same logic in this file is the
rem  kind of duplication that quietly drifts out of step -- one gets a fix and
rem  the other does not. This script only adds the browser.
rem
rem  The browser is launched from a detached shell after a short delay, because
rem  the server has to be listening before the page will load, and the call
rem  below blocks until you stop it with Ctrl+C.
rem ---------------------------------------------------------------------------

set "PORT=7777"
if not "%~1"=="" set "PORT=%~1"

rem Only open a browser if the port is actually going to be ours. If something
rem that is not Forge is holding it, forge.bat refuses to start and a browser
rem pointed at that stranger's server would be actively misleading.
rem
rem The test is the command line rather than the image name: the editor is
rem launched with javaw so that it has no console window, and a check for
rem java.exe called a healthy Forge a stranger and refused to open the page to
rem a server that was serving perfectly well.
netstat -ano | findstr /c:":%PORT% " | findstr /i "LISTENING" >nul 2>&1
if not errorlevel 1 (
  for /f "tokens=5" %%P in ('netstat -ano ^| findstr /c:":%PORT% " ^| findstr /i "LISTENING"') do (
    for /f "usebackq delims=" %%C in (`powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter 'ProcessId = %%P' -ErrorAction SilentlyContinue; if ($p -and $p.CommandLine -match 'forge\.jar') { 'yes' } else { 'no' }" 2^>nul`) do (
      if /i not "%%C"=="yes" (
        echo [forge] Port %PORT% is held by something that is not Forge. Not opening a browser.
        call "%~dp0forge.bat" ui --port %PORT%
        exit /b %ERRORLEVEL%
      )
    )
  )
)

start "" /b cmd /c "timeout /t 3 /nobreak >nul & start "" http://localhost:%PORT%"

call "%~dp0forge.bat" ui --port %PORT%

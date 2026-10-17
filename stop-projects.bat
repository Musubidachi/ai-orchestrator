@echo off
rem Stops every program running from a project Forge built (run.bat windows, preview servers, game
rem windows, a project's own Java or Python), never Forge itself or anything Forge started.
rem   stop-projects.bat          list them, then stop them
rem   stop-projects.bat /list    only list them
setlocal
set "LISTONLY="
if /i "%~1"=="/list" set "LISTONLY=-List"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-projects.ps1" %LISTONLY%
echo.
pause

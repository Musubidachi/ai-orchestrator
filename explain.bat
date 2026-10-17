@echo off
rem ---------------------------------------------------------------------------
rem  explain.bat -- reads Forge's own documentation out loud.
rem
rem    explain.bat                 what Forge is: its modules, and what the build refuses
rem    explain.bat jobs.queue      one package, from its package-info
rem    explain.bat ReviewStep      one class, from its javadoc
rem    explain.bat ReviewStep -q   write the transcript and recording, play nothing
rem
rem  Needs no jar, no build and no editor: it reads the source notes directly, which is what makes
rem  it useful on the day Forge will not compile. The transcript and a .wav land in logs\.
rem ---------------------------------------------------------------------------
setlocal
set "ROOT=%~dp0"
set "TOPIC=%~1"
set "QUIET="
if /i "%~2"=="-q" set "QUIET=-NoSpeak"
if /i "%~1"=="-q" (
  set "TOPIC="
  set "QUIET=-NoSpeak"
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%explain.ps1" -Topic "%TOPIC%" %QUIET%
endlocal

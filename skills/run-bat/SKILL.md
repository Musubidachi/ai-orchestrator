---
name: run-bat
description: How to write the project's run.bat - the double-click launcher the person uses to try the program on Windows - for a Node web app or game, a Java/Maven app or tool, or a Python tool, with templates. Use when creating or changing run.bat, or when the gate says run.bat is missing or starts nothing.
---

# Writing run.bat

The contract is in the house rules ("A way to run it"): a `run.bat` in the repository root, which the
person double-clicks, kept out of the acceptance commands. This skill is how to write one.

It has to work from a fresh checkout on Windows with only the stack's own toolchain installed — a
JDK and Maven, Node and npm, or Python:

- Begin with `cd /d "%~dp0"`, so it works whatever directory it was launched from.
- Install and build whatever is missing, and skip what is already there so the second launch is
  quick: `npm ci` when there is no `node_modules`, the Maven build when the jar is not there, a
  virtual environment and `pip install` when there is none.
- Then start the real program, not a demo of it:
  - **A web app or a browser game:** start its server on 127.0.0.1 and open the page in the default
    browser (`start "" http://127.0.0.1:<port>/`). Start the server with `node` and the tool's own
    entry point rather than through an npm script, so that both Ctrl+C and closing the window stop
    it. The window stays open while it runs. Say so in a line of output.
  - **A desktop app:** open it.
  - **A command-line tool or a library:** run a worked example on sample input kept in the
    repository, say where the output went, and open it if it is something to look at.
- Never ask a question. Never close on an error: when a step fails, say which one and `pause`, so
  the window is still there to be read. End a command-line example with `pause` for the same reason.

Pick a port no other project uses by default (not 4173, which every Vite project's tests reach for),
and pass `--strictPort` or its equivalent so a busy port is an error you can read, not a silent
move to another one the browser was not opened on.

## Templates

Starting points taken from run.bat files that work on this machine. Change the names, the port,
the build check and the example; keep the shape: check the tool, build what is missing, start the
real thing, and `pause` on every failure.

### Node: a web app or browser game (Vite)

```bat
@echo off
setlocal
cd /d "%~dp0" || (echo Unable to open the project folder. & pause & exit /b 1)
title My Game

where npm.cmd >nul 2>nul || (echo Node.js was not found. Install it from https://nodejs.org/ and run this again. & pause & exit /b 1)

if not exist "node_modules\vite\bin\vite.js" (
    echo Installing dependencies - the first launch needs the npm registry...
    call npm.cmd ci --no-fund || goto failed
)
if not exist "dist\index.html" (
    echo Building...
    call npm.cmd run build || goto failed
)

echo.
echo My Game: http://127.0.0.1:4175/
echo Press Ctrl+C, or close this window, to stop the game server.
node "node_modules\vite\bin\vite.js" preview --host 127.0.0.1 --port 4175 --strictPort --open || goto failed
exit /b 0

:failed
echo.
echo That step failed; the messages above say why.
pause
exit /b 1
```

Start the server with `node` and the tool's own entry point, never `call npm.cmd run preview`. Every
batch file between the console and the server answers Ctrl+C for itself, with `Terminate batch job
(Y/N)?`, and npm's shim adds two of them: `run.bat` → `npm.cmd` → the npm CLI → `cmd /d /s /c vite
preview` → the server. The prompt is the nearest layer talking, not the server, and the server
underneath it keeps running and keeps the port. That has happened here: a Vite preview still holding
its port long after its window was answered and closed, so the next launch died on `--strictPort`
with nothing on screen to blame. Running `node node_modules\vite\bin\vite.js` makes the server the
batch file's own child, which Ctrl+C reaches and a closed window takes with it. It also means the
flags are exactly the ones written here, rather than these plus whatever the `preview` script in
`package.json` already passes — a `--port` appearing twice is read as the second one.

### Java with Maven: a desktop app, or a tool with a worked example

```bat
@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul || (echo Java was not found. Install a JDK and run this again. & pause & exit /b 1)

if not exist "target\mytool.jar" (
    echo Building - the first launch takes a minute...
    call mvn -q -DskipTests package || goto failed
)

rem A desktop app: open it and let this window close.
rem start "" javaw -jar target\mytool.jar
rem exit /b 0

rem A tool: run the worked example on the sample kept in the repository, and show the result.
if not exist run-example mkdir run-example
java -jar target\mytool.jar --in samples\input.txt --out run-example\report.html || goto failed
echo The report is run-example\report.html - opening it.
start "" "run-example\report.html"
pause
exit /b 0

:failed
echo.
echo That step failed; the messages above say why.
pause
exit /b 1
```

### Python: a tool in its own virtual environment

```bat
@echo off
setlocal
cd /d "%~dp0"

set "PY="
where py >nul 2>&1 && set "PY=py -3"
if not defined PY where python >nul 2>&1 && set "PY=python"
if not defined PY (echo Python 3 was not found. Install it from https://www.python.org/ and run this again. & pause & exit /b 1)

if not exist ".venv\Scripts\python.exe" (
    echo Creating a private Python environment in .venv...
    %PY% -m venv .venv || goto failed
)
if not exist ".venv\installed.flag" (
    echo Installing dependencies - first run only...
    ".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -e . || goto failed
    echo.> ".venv\installed.flag"
)

".venv\Scripts\python.exe" -m mytool samples\input.jpg --out run-example || goto failed
echo The result is in run-example\.
start "" "run-example"
pause
exit /b 0

:failed
echo.
echo That step failed; the messages above say why.
pause
exit /b 1
```

## Before you finish

Keep it out of the acceptance commands: it does not exit, so a gate that ran it would wait for ever.
The gate checks that the file exists and starts something, and nothing more. Whether it works is
found out by the person, so try each step of it before you finish, stop anything it started, and
say in your handoff if there is a step you could not try.

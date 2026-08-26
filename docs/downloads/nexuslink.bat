@echo off
rem NexusLink launcher for Windows (command prompt / double-click).
rem
rem Hands over to nexuslink.ps1, which does the work. Every option is the same:
rem
rem   nexuslink.bat --help       every option
rem   nexuslink.bat              run (downloading on the first run)
rem   nexuslink.bat --update     download this version again
rem   nexuslink.bat --fresh      clear the cache, then download and run
rem   nexuslink.bat --clean      delete every cached build
rem   nexuslink.bat --local      run the build installed in ~/.m2 by dist/publish.sh
rem
rem Configure with NEXUSLINK_REPO_URL, or a %USERPROFILE%\.nexuslink\bootstrap.conf.
setlocal
set "SCRIPT_DIR=%~dp0"
if not exist "%SCRIPT_DIR%nexuslink.ps1" (
  echo nexuslink: nexuslink.ps1 is missing - keep it next to this file. 1>&2
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%nexuslink.ps1" %*
exit /b %ERRORLEVEL%

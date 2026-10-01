@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 exit /b 1
call gradlew.bat jar %*
set "MOD_EXIT_CODE=%ERRORLEVEL%"
exit /b %MOD_EXIT_CODE%

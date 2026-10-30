@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if exist "%~dp0config\local-postgres.properties" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0config\local-postgres.properties") do if "%%A"=="PG_BIN" set "PG_BIN=%%B"
)
if not defined PG_BIN exit /b 1
echo Encerre o Branch Control antes de parar o banco exclusivo.
"%PG_BIN%\pg_ctl.exe" -D "%~dp0data\postgres" -m fast -w stop
exit /b %ERRORLEVEL%

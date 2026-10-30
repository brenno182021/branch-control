@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if exist "%~dp0config\local-postgres.properties" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0config\local-postgres.properties") do (
    if "%%A"=="PG_BIN" set "PG_BIN=%%B"
    if "%%A"=="PG_PORT" set "PG_PORT=%%B"
  )
)
if not defined PG_BIN (
  echo Defina PG_BIN com a pasta bin do PostgreSQL. Veja docs/database.md.
  exit /b 1
)
if not defined PG_PORT set "PG_PORT=5433"
if not exist "%~dp0data\postgres\PG_VERSION" (
  echo Instancia exclusiva ainda nao inicializada. Veja docs/database.md.
  exit /b 1
)
"%PG_BIN%\pg_ctl.exe" -D "%~dp0data\postgres" status >nul 2>&1
if not errorlevel 1 exit /b 0
"%PG_BIN%\pg_ctl.exe" -D "%~dp0data\postgres" -l "%~dp0data\postgres-server.log" -o "-h 127.0.0.1 -p %PG_PORT%" -w start
exit /b %ERRORLEVEL%

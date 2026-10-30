@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if not defined PG_BIN set "PG_BIN="
set "PSQL=psql"
if defined PG_BIN set "PSQL=%PG_BIN%\psql.exe"
if not defined PGHOST set "PGHOST=127.0.0.1"
if not defined PGPORT set "PGPORT=5432"
if not defined PGUSER set "PGUSER=postgres"
if not defined DB_NAME set "DB_NAME=branch_control"
if not defined DB_USER set "DB_USER=branch_control"
echo Criar banco %DB_NAME% em %PGHOST%:%PGPORT% usando administrador %PGUSER%.
"%PSQL%" -X -W -d postgres -v ON_ERROR_STOP=1 -v "db_name=%DB_NAME%" -v "db_user=%DB_USER%" -f "%~dp0database\create-database.sql"
if errorlevel 1 exit /b 1
echo Criar tabelas no banco %DB_NAME% como usuario %DB_USER%.
"%PSQL%" -X -W -U "%DB_USER%" -d "%DB_NAME%" -v ON_ERROR_STOP=1 -f "%~dp0database\schema.sql"
if errorlevel 1 exit /b 1
if not exist "%~dp0config\application.properties" copy /y "%~dp0config\application.properties.example" "%~dp0config\application.properties" >nul
echo Edite config\application.properties com spring.datasource.url, username e password.
echo Depois execute compilar.bat e iniciar.bat.

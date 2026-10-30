@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
if exist "%~dp0config\local-postgres.properties" (
    call "%~dp0iniciar-postgres-local.bat"
    if errorlevel 1 exit /b 1
)
if defined BRANCH_CONTROL_JAVA_HOME set "JAVA_HOME=%BRANCH_CONTROL_JAVA_HOME%"
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%~dp0data\tmp" mkdir "%~dp0data\tmp"
if not exist "%~dp0backend\target\branch-control.jar" (
    echo JAR ainda nao existe. Compilando o projeto...
    call "%~dp0compilar.bat"
    if errorlevel 1 exit /b 1
)
echo Branch Control: http://127.0.0.1:8087
echo Use Ctrl+C para encerrar. Logs: %~dp0data\logs
"%JAVA_CMD%" "-Djdk.net.unixdomain.tmpdir=%~dp0data\tmp" -Dfile.encoding=UTF-8 -jar "%~dp0backend\target\branch-control.jar"
exit /b %ERRORLEVEL%


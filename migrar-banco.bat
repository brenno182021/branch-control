@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if defined BRANCH_CONTROL_JAVA_HOME set "JAVA_HOME=%BRANCH_CONTROL_JAVA_HOME%"
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%~dp0backend\target\branch-control.jar" (
  echo Compile antes com compilar.bat.
  exit /b 1
)
"%JAVA_CMD%" -Dfile.encoding=UTF-8 -jar "%~dp0backend\target\branch-control.jar" --migrate-only
exit /b %ERRORLEVEL%

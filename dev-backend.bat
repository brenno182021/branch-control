@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
if defined BRANCH_CONTROL_JAVA_HOME set "JAVA_HOME=%BRANCH_CONTROL_JAVA_HOME%"
set "BRANCH_CONTROL_LOG_DIR=%~dp0data\logs"
if not exist "%~dp0data\tmp" mkdir "%~dp0data\tmp"
call mvn -B -ntp -s "%~dp0config\maven-settings.xml" -f "%~dp0backend\pom.xml" "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=%~dp0data\tmp" spring-boot:run
exit /b %ERRORLEVEL%


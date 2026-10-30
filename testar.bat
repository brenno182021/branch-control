@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
if not defined TEST_DB_URL goto :missing_database
if not defined TEST_DB_USER goto :missing_database
if not defined TEST_DB_PASSWORD goto :missing_database
if defined BRANCH_CONTROL_JAVA_HOME set "JAVA_HOME=%BRANCH_CONTROL_JAVA_HOME%"
pushd frontend
if not exist node_modules (
    call npm ci --no-fund --no-audit
    if errorlevel 1 goto :frontend_error
)
call npm test
if errorlevel 1 goto :frontend_error
popd
call mvn -B -ntp -s "%~dp0config\maven-settings.xml" -f "%~dp0backend\pom.xml" test
exit /b %ERRORLEVEL%
:frontend_error
popd
exit /b 1
:missing_database
echo Configure TEST_DB_URL, TEST_DB_USER e TEST_DB_PASSWORD apontando para um banco exclusivo de testes.
exit /b 1


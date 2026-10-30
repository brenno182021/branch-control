@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
if defined BRANCH_CONTROL_JAVA_HOME set "JAVA_HOME=%BRANCH_CONTROL_JAVA_HOME%"
where git >nul 2>&1
if errorlevel 1 goto :missing
where node >nul 2>&1
if errorlevel 1 goto :missing
where mvn >nul 2>&1
if errorlevel 1 goto :missing
pushd "%~dp0frontend"
call npm ci --no-fund --no-audit
if errorlevel 1 goto :frontend_error
call npm test
if errorlevel 1 goto :frontend_error
call npm run build
if errorlevel 1 goto :frontend_error
popd
if not exist "%~dp0backend\src\main\resources\static" mkdir "%~dp0backend\src\main\resources\static"
xcopy "%~dp0frontend\dist\*" "%~dp0backend\src\main\resources\static\" /E /I /Y >nul
if errorlevel 1 exit /b 1
call mvn -B -ntp -s "%~dp0config\maven-settings.xml" -f "%~dp0backend\pom.xml" -DskipTests clean package
if errorlevel 1 exit /b 1
echo.
echo Build concluido. Execute iniciar.bat e abra http://127.0.0.1:8087
exit /b 0
:frontend_error
popd
echo Falha no frontend.
exit /b 1
:missing
echo Confira os requisitos no README: Git, Java 17 ou superior, Maven e Node 18 ou superior no PATH.
exit /b 1


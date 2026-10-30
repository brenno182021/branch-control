@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0frontend"
if not exist node_modules (
    call npm ci --no-fund --no-audit
    if errorlevel 1 exit /b 1
)
call npm run dev
exit /b %ERRORLEVEL%


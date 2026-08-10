@echo off
setlocal
set SCRIPT_DIR=%~dp0
set LOCAL_MAVEN=%SCRIPT_DIR%.tools\maven\apache-maven-3.9.9\bin\mvn.cmd
if exist "%LOCAL_MAVEN%" (
  call "%LOCAL_MAVEN%" %*
  exit /b %ERRORLEVEL%
)
where mvn >nul 2>nul
if not errorlevel 1 (
  mvn %*
  exit /b %ERRORLEVEL%
)
echo Maven was not found. Install Maven or place it under .tools\maven\apache-maven-3.9.9\bin\mvn.cmd.
exit /b 1

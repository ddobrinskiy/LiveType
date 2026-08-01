@echo off
setlocal
set GRADLE_VERSION=8.9
if "%GRADLE_USER_HOME%"=="" (
  set GRADLE_ROOT=%USERPROFILE%\.gradle\livetype-bootstrap
) else (
  set GRADLE_ROOT=%GRADLE_USER_HOME%\livetype-bootstrap
)
set GRADLE_DIR=%GRADLE_ROOT%\gradle-%GRADLE_VERSION%
set ARCHIVE=%GRADLE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  if not exist "%GRADLE_ROOT%" mkdir "%GRADLE_ROOT%"
  if not exist "%ARCHIVE%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ARCHIVE%'"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ARCHIVE%' -DestinationPath '%GRADLE_ROOT%' -Force"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_DIR%\bin\gradle.bat" %*
endlocal


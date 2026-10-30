@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "PATH=%LOCALAPPDATA%\agy\bin;%PATH%"
echo.
echo CAMPUSDESK: OFFICIAL AGY CLI SETUP
echo This is a separate Google Antigravity account sign-in.
echo The Java app will run AGY in sandbox mode, NEVER --dangerously-skip-permissions.
echo.
where agy >nul 2>&1
if errorlevel 1 (
  echo AGY CLI is not currently installed.
  choice /C YN /M "Download and run the official Windows AGY installer"
  if errorlevel 2 goto end
  powershell -NoProfile -Command "Invoke-WebRequest 'https://antigravity.google/cli/install.cmd' -OutFile '%TEMP%\campusdesk-agy-install.cmd' -UseBasicParsing"
  if errorlevel 1 (echo Download failed. & goto end)
  call "%TEMP%\campusdesk-agy-install.cmd"
  del "%TEMP%\campusdesk-agy-install.cmd" >nul 2>&1
)
set "PATH=%LOCALAPPDATA%\agy\bin;%PATH%"
where agy >nul 2>&1
if errorlevel 1 (
 echo AGY CLI is still not available. Verify installation at https://antigravity.google/docs/cli/install/
 goto end
)
call agy --version
echo Launching AGY to sign in. Close/exit AGY to return to CampusDesk.
call agy
:end
endlocal

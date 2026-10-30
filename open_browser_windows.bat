@echo off
REM Open the browser only after the Java web endpoint responds (including college Wi-Fi lock page).
for /l %%I in (1,1,25) do (
  powershell -NoProfile -Command "try { $response=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8000/' -TimeoutSec 2; if ($response.StatusCode -eq 200) {exit 0}; exit 1 } catch { exit 1 }" >nul 2>&1
  if not errorlevel 1 (
    start "" "http://127.0.0.1:8000"
    exit /b 0
  )
  timeout /t 1 /nobreak >nul
)
exit /b 1

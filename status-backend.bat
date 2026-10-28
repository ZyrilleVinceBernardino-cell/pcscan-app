@echo off
setlocal

echo.
echo ============================================================
echo   PCScan Backend Status Check
echo ============================================================
echo.

echo [1] Is port 8000 LISTENING?
netstat -ano | findstr ":8000 " | findstr LISTENING >nul 2>&1
if %errorlevel% equ 0 (
    echo     YES - Server is running.
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8000 " ^| findstr LISTENING') do (
        echo     PID (process ID): %%p
    )
) else (
    echo     NO - Server is NOT running.
    echo     Start it with: start-backend.bat
)

echo.
echo [2] HTTP health check on http://localhost:8000/api/health ...
powershell -NoProfile -Command ^
  "$ProgressPreference='SilentlyContinue'; try { $r = Invoke-RestMethod -Uri 'http://localhost:8000/api/health' -UseBasicParsing -TimeoutSec 6; Write-Host '   Status  : ' -NoNewline; Write-Host $r.status; Write-Host '   OpenAI  : ' -NoNewline; Write-Host $r.openai_enabled; Write-Host '   Gemini  : ' -NoNewline; Write-Host $r.gemini_enabled; exit 0 } catch { Write-Host '   FAILED - cannot reach server ( '$_.Exception.Message' )'; exit 1 }"

echo.
echo [3] YOUR URLs:
echo     Local (browser on this PC):  http://localhost:8000
echo     Emulator inside Android Std: http://10.0.2.2:8000
echo     Other devices SAME WIFI:     http://192.168.100.51:8000
echo.
echo     Swagger / API test page:     http://localhost:8000/docs
echo.
echo ============================================================
echo   To create a FREE PUBLIC URL (anywhere, kahit data lang):
echo   1) install-ngrok.bat    (once lang)
echo   2) start-public-ngrok.bat
echo ============================================================
echo.
pause
endlocal

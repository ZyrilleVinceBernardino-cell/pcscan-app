@echo off
REM =================================================================
REM PCSCAN BACKEND - PERMANENT WINDOWS SERVICE INSTALLER
REM Uses NSSM (Non-Sucking Service Manager) so backend runs at BOOT,
REM auto-restarts on crash, works even with no user logged in.
REM NO NGROK NEEDED - Pair this with DDNS + Router Port Forwarding.
REM =================================================================
REM HOW TO USE:
REM 1. Run CMD / PowerShell as ADMINISTRATOR (right-click, Run as admin)
REM 2. cd to this folder:  cd "c:\AndroidProjects\cap main\pcscan\pcscan\backend"
REM 3. Run: install_windows_service.bat
REM =================================================================
chcp 65001 >nul
setlocal enabledelayedexpansion

cd /d "%~dp0"
set "BACKEND_DIR=%cd%"
set "VENV_DIR=%BACKEND_DIR%\venv"
set "SERVICE_NAME=PcScanBackend"
set "DISPLAY_NAME=PC Scan Backend (FastAPI)"
set "DESCRIPTION=PC Scan App Backend - always-on build recommendation, search, and OCR API server."

echo ================================================================
echo   PC SCAN BACKEND - WINDOWS SERVICE INSTALLER
echo ================================================================
echo.
echo [1/5] Checking admin privileges...
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo   ERROR: NOT ADMIN. Close this window, RIGHT-CLICK CMD -^> Run as administrator, then run again.
    pause
    exit /b 1
)
echo   OK - Running as Administrator.

echo.
echo [2/5] Looking for venv or python.exe with uvicorn...
set "PYEXE="
if exist "%VENV_DIR%\Scripts\python.exe" (
    set "PYEXE=%VENV_DIR%\Scripts\python.exe"
    echo   Found venv python: %PYEXE%
) else (
    for /f "delims=" %%i in ('where python 2^>nul') do (
        set "PYEXE=%%i"
        goto :pyfound
    )
    :pyfound
    if not defined PYEXE (
        echo   ERROR: Python not installed. Install Python 3.11 first.
        pause
        exit /b 2
    )
    echo   Found system python: %PYEXE%
)

echo.
echo [3/5] Downloading NSSM (service wrapper) if missing...
set "NSSM_EXE=%BACKEND_DIR%\nssm.exe"
if not exist "%NSSM_EXE%" (
    echo   NSSM not present. Downloading from official mirror...
    powershell -NoProfile -Command "try { Invoke-WebRequest -Uri 'https://nssm.cc/release/nssm-2.24.zip' -OutFile '%BACKEND_DIR%\nssm.zip' -UseBasicParsing ; Expand-Archive -Path '%BACKEND_DIR%\nssm.zip' -DestinationPath '%BACKEND_DIR%\nssm_extract' -Force ; Copy-Item -Path '%BACKEND_DIR%\nssm_extract\nssm-2.24\win64\nssm.exe' -Destination '%NSSM_EXE%' -Force ; Remove-Item -Path '%BACKEND_DIR%\nssm.zip','%BACKEND_DIR%\nssm_extract' -Recurse -Force -ErrorAction SilentlyContinue ; echo   OK } catch { Write-Host '   FAILED to auto-download NSSM. Please manually download NSSM from https://nssm.cc/download, extract win64/nssm.exe into this backend folder, then re-run.' ; exit 1 }"
    if errorlevel 1 ( pause; exit /b 3 )
)
if exist "%NSSM_EXE%" ( echo   NSSM OK: %NSSM_EXE% ) else ( echo   ERROR: nssm.exe missing. Manual install required. & pause & exit /b 3 )

echo.
echo [4/5] Removing OLD service (if exists) to avoid conflicts...
"%NSSM_EXE%" stop %SERVICE_NAME% >nul 2>&1
"%NSSM_EXE%" remove %SERVICE_NAME% confirm >nul 2>&1

echo   Installing NEW %SERVICE_NAME% service...
"%NSSM_EXE%" install %SERVICE_NAME% "%PYEXE%" >nul 2>&1
if errorlevel 1 ( echo   ERROR: Service install FAILED. & pause & exit /b 4 )

echo   Setting service parameters (working dir, args, auto-restart, logging)...
"%NSSM_EXE%" set %SERVICE_NAME% AppDirectory "%BACKEND_DIR%" >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppParameters "-m uvicorn main:app --host 0.0.0.0 --port 8000 --log-level warning" >nul
"%NSSM_EXE%" set %SERVICE_NAME% DisplayName "%DISPLAY_NAME%" >nul
"%NSSM_EXE%" set %SERVICE_NAME% Description "%DESCRIPTION%" >nul
"%NSSM_EXE%" set %SERVICE_NAME% Start SERVICE_AUTO_START >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppExit Default Restart >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppRestartDelay 3000 >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppStdout "%BACKEND_DIR%\service_stdout.log" >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppStderr "%BACKEND_DIR%\service_stderr.log" >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppStdoutCreationDisposition 4 >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppStderrCreationDisposition 4 >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppRotateFiles 1 >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppRotateOnline 1 >nul
"%NSSM_EXE%" set %SERVICE_NAME% AppRotateBytes 5242880 >nul

echo.
echo [5/5] Starting service...
"%NSSM_EXE%" start %SERVICE_NAME%
if errorlevel 1 (
    echo   WARNING: Service did not start immediately. Check logs:
    echo     %BACKEND_DIR%\service_stderr.log
)

timeout /t 4 /nobreak >nul

echo.
echo ================================================================
echo   STATUS CHECK
echo ================================================================
sc query %SERVICE_NAME% | findstr /i "STATE"
echo.
echo   Trying /api/health (localhost:8000)...
powershell -NoProfile -Command "try { $r = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/api/health' -TimeoutSec 8 ; Write-Host ('   OK ^| status=' + $r.status + ' ^| openai_enabled=' + $r.openai_enabled + ' ^| gemini_enabled=' + $r.gemini_enabled) } catch { Write-Host '   Not reachable yet (booting or bad config). Wait 30s then try: curl http://127.0.0.1:8000/api/health' }"
echo.
echo ================================================================
echo   DONE ^!
echo ================================================================
echo.
echo HOW TO ACCESS FROM ANY PHONE ON INTERNET (WITHOUT NGROK):
echo.
echo   STEP 1 - PORT FORWARD your Router (login 192.168.100.1 or 192.168.1.1):
echo     External port: 8000   Internal port: 8000   Protocol: TCP
echo     Internal IP: YOUR LOCAL PC's LAN IP (find via: ipconfig ^| findstr IPv4)
echo.
echo   STEP 2 - GET FREE DDNS DOMAIN (so kahit magbago public IP niyo, walang palit URL sa app):
echo     Go to https://www.noip.com/free  -^> Signup free -^> Create hostname:
echo       Example: pcscan-team.ddns.net
echo     Install NO-IP DUC (Dynamic Update Client) on this PC so it syncs IP changes.
echo.
echo   STEP 3 - TEST PUBLIC URL from MOBILE DATA (not same WiFi):
echo     Open on phone browser:  http://pcscan-team.ddns.net:8000/api/health
echo     Must show JSON {"status":"ok", ...}
echo.
echo   STEP 4 - FINAL: Paste your DDNS URL into PcScanApiService.kt ^> BASE_URL_PUBLIC:
echo       private const val BASE_URL_PUBLIC = "http://pcscan-team.ddns.net:8000"
echo     Then rebuild APK:  gradlew.bat clean --no-daemon ; gradlew.bat assembleDebug --no-daemon
echo.
echo USEFUL NSSM COMMANDS (run as admin in this folder):
echo   .\nssm.exe stop   PcScanBackend    ^<-- stop backend service
echo   .\nssm.exe start  PcScanBackend    ^<-- start backend service
echo   .\nssm.exe restart PcScanBackend   ^<-- restart (after code edits)
echo   .\nssm.exe edit   PcScanBackend    ^<-- open GUI config
echo   .\nssm.exe remove PcScanBackend confirm   ^<-- uninstall service
echo.
pause

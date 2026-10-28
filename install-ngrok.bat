@echo off
setlocal

echo.
echo ============================================================
echo   INSTALL NGROK (One-time setup only!)
echo   Creates FREE public URL tunnel
echo ============================================================
echo.

REM --- 1. Make tools folder if missing ----------------------
cd /d "%~dp0"
if not exist "tools" mkdir tools
cd tools

REM --- 2. Download ngrok (Windows 64-bit zip) ---------------
echo [1/3] Download ngrok-v3 ...
powershell -NoProfile -Command ^
  "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri 'https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip' -OutFile 'ngrok.zip'"

if not exist "ngrok.zip" (
    echo [ERROR] Download failed.
    echo.
    echo Try manually: open browser at
    echo   https://ngrok.com/download
    echo Download "Windows (64-bit)", extract ngrok.exe to tools\ folder
    echo.
    pause
    exit /b 1
)

REM --- 3. Extract -----------------------------------------
echo [2/3] Extract ngrok.exe ...
powershell -NoProfile -Command "Expand-Archive -Force ngrok.zip ."
del /q ngrok.zip 2>nul

if not exist "ngrok.exe" (
    echo [ERROR] Extract failed. ngrok.exe missing.
    pause
    exit /b 1
)

REM --- 4. Ask for auth token --------------------------------
echo.
echo [3/3] Ngrok needs a FREE AUTH TOKEN (1-time signup):
echo.
echo   1. Open browser:
echo      https://dashboard.ngrok.com/signup
echo      (Sign up FREE using Google/GitHub, 10 seconds)
echo.
echo   2. After login, go to:
echo      https://dashboard.ngrok.com/get-started/your-authtoken
echo.
echo   3. Copy your token (looks like: 2AbCdEfGhIjKl... short string)
echo      then PASTE it here:
echo.
set /p TOKEN="Paste Ngrok Auth Token then press ENTER: "

if "%TOKEN%"=="" (
    echo [WARN] No token entered.
    echo You can set it later by running:
    echo   tools\ngrok.exe config add-authtoken YOUR_TOKEN_HERE
) else (
    echo.
    echo Saving auth token ...
    ngrok.exe config add-authtoken %TOKEN%
    echo DONE.
)

echo.
echo ============================================================
echo   INSTALL COMPLETE!
echo ============================================================
echo.
echo Every day usage (2 clicks):
echo   1) Double-click start-backend.bat
echo   2) Double-click start-public-ngrok.bat
echo   3) Copy the https://*.ngrok-free.app URL from the new
echo      window, paste to:
echo      pcscan3/app/src/main/java/.../PcScanApiService.kt
echo      at BASE_URL_PUBLIC line, then rebuild Android app.
echo.
pause
endlocal

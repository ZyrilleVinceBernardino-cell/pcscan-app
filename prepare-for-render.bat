@echo off
setlocal

echo.
echo ============================================================
echo   PCScan - Prepare for PERMANENT Render.com deploy (FREE)
echo   Run this ONCE if you want 24/7 server, even if PC is OFF.
echo ============================================================
echo.

REM --- 1. Git installed? -----------------------------------
git --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Git NOT installed.
    echo.
    echo Download from:  https://git-scm.com/download/win
    echo Click "64-bit Git for Windows Setup" -> default install options.
    echo Restart your PC, then re-run this file.
    echo.
    pause
    exit /b 1
)
git --version

echo.
REM --- 2. Init repo if not yet --------------------------
if exist ".git" (
    echo [INFO] Git repo already initialized, OK.
) else (
    echo [1/5] Initialize git repo ...
    git init -q
    if %errorlevel% neq 0 ( echo FAIL git init; pause; exit /b 2 )
    echo       OK.
)

echo.
REM --- 3. Create root .gitignore (safe defaults) --------
echo [2/5] Root .gitignore file ...
if not exist ".gitignore" (
(
echo # --- Android / Gradle
echo pcscan3/.gradle/
echo pcscan3/build/
echo pcscan3/app/build/
echo pcscan3/app/release/
echo pcscan3/local.properties
echo pcscan3/**/*.iml
echo.
echo # --- Node
echo pcscan3/pc-scan/node_modules/
echo.
echo # --- Backend
echo backend/venv/
echo backend/venv_*/
echo backend/.env
echo backend/__pycache__/
echo backend/**/__pycache__/
echo.
echo # --- IDE
echo .idea/
echo .vscode/
echo pcscan3/.idea/
echo pcscan3/.vscode/
echo pcscan3/.kotlin/
echo.
echo # --- Tools / temp
echo Thumbs.db
echo desktop.ini
echo .DS_Store
echo tools/ngrok.exe
echo tools/ngrok.yml
echo *.log
) > .gitignore
    echo       Created.
) else (
    echo       Already exists, skipped.
)

echo.
REM --- 4. Add + Commit -----------------------------------
echo [3/5] Add all files ...
git add -A
echo [4/5] First commit ...
git -c user.name="pcscan-dev" -c user.email="pcscan@local.dev" commit -q -m "initial: pcscan backend with openai/gemini + android app"

echo.
echo ============================================================
echo   DEPLOYMENT STEPS (manual 5 minutes):
echo ============================================================
echo.
echo (1) Open browser at:  https://github.com/new
echo     Repository name   : pcscan   (any name OK)
echo     Set to            : PRIVATE  (recommended)
echo     UNCHECK: Add README / Add .gitignore / Choose license
echo     Click: Create repository
echo.
echo (2) After clicking Create, copy the 3 lines that say:
echo       "...or push an existing repository from the command line"
echo     Paste + run them in PowerShell from folder: d:\App Capstone\pcscan
echo     Example (change USERNAME/pcscan to yours):
echo       git remote add origin https://github.com/USERNAME/pcscan.git
echo       git branch -M main
echo       git push -u origin main
echo.
echo (3) Open:  https://dashboard.render.com/
echo     Sign-up using GitHub (FREE, no credit card)
echo     Click "New +"  ->  "Blueprint Instance"
echo     Pick your pcscan repo.
echo     Root Directory: backend
echo     Click "Apply"
echo.
echo (4) Last step - in Render dashboard, open Environment tab,
echo     ADD these 3 KEYS using the values from backend/.env:
echo       MOCK_MODE        = false
echo       OPENAI_API_KEY   = (copy from backend/.env line with OPENAI_API_KEY)
echo       GOOGLE_API_KEY   = (copy from backend/.env line with GOOGLE_API_KEY)
echo     Wait 3 minutes for it to rebuild and go LIVE.
echo.
echo (5) Copy URL from Render top-bar (e.g. https://pcscan-backend-xyz.onrender.com)
echo     Paste into PcScanApiService.kt line 40 (BASE_URL_PUBLIC), rebuild app.
echo ============================================================
echo.
pause
endlocal

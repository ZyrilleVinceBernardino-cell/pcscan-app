@echo off
REM =========================================================================
REM  NUCLEAR SAFETY WRAPPER - Window will NEVER close automatically.
REM  Re-launches self inside "cmd /k" if not already wrapped.
REM  Uses GOTO style (no paren blocks) - avoids parse bugs when paths
REM  contain parentheses (e.g. "...\pcscan (2)\pcscan\...").
REM  "cmd /k" = run command then KEEP the console open - regardless of errors.
REM =========================================================================
if /i "%__PCSCAN_CMDK_WRAPPED__%"=="1" goto WRAPPER_DONE
set "__PCSCAN_CMDK_WRAPPED__=1"
start "PCScan Backend - DO NOT CLOSE THIS WINDOW" /WAIT /D "%~dp0" cmd.exe /e:on /k call "%~f0" %*
exit /b
:WRAPPER_DONE

setlocal EnableDelayedExpansion

REM ============================================================
REM  PCScan Backend Server - ONE-CLICK LAUNCHER (v2, no-bug)
REM  Watchdog loop: if server crashes, auto-restart
REM ============================================================

REM --- Step 0: Go to project backend folder -----------------
set "PROJECT_DIR=%~dp0"
set "BACKEND_DIR=%PROJECT_DIR%backend"
set "PYTHON_EXE=%BACKEND_DIR%\venv\Scripts\python.exe"

echo.
echo ============================================================
echo   PCScan Backend Server - Starting
echo ============================================================
echo   [DEBUG] Project dir : %PROJECT_DIR%
echo   [DEBUG] Backend dir : %BACKEND_DIR%
echo   [DEBUG] Python path : %PYTHON_EXE%
echo   ----------------------------------------------------------
echo   Local URL : http://localhost:8000
echo   Emulator  : http://10.0.2.2:8000
echo   LAN/WIFI  : http://192.168.100.51:8000
echo   Swagger   : http://localhost:8000/docs
echo   ----------------------------------------------------------
echo   Stop server: press CTRL+C twice
echo ============================================================
echo.

if exist "%BACKEND_DIR%" goto BACKEND_EXISTS_OK
    echo [FATAL] Backend folder NOT FOUND at: %BACKEND_DIR%
    echo         Make sure this .bat file sits beside the 'backend' folder.
    echo.
    pause
    exit /b 1
:BACKEND_EXISTS_OK
cd /d "%BACKEND_DIR%"

REM --- Step 1: Check if already running on port 8000 ---------
echo [1/4] Check if port 8000 is already in use...
netstat -ano 2>nul | findstr ":8000 " | findstr LISTENING >nul 2>&1
if %errorlevel% equ 0 (
    echo.
    echo [INFO] Port 8000 ALREADY has a LISTENING process.
    echo        Backend is probably ALREADY running.
    echo        To restart it, first run: stop-backend.bat
    echo.
    pause
    exit /b 0
)
echo       OK - port 8000 is free.

REM --- Step 2: Check venv + python.exe -----------------------
echo.
echo [2/4] Check Python virtual environment...
if exist "%PYTHON_EXE%" goto PYTHON_EXE_OK
    echo [FATAL] Python not found at:
    echo         %PYTHON_EXE%
    echo.
    echo SOLUTION - Run these ONCE in PowerShell from
    echo            d:\App Capstone\pcscan\backend  folder:
    echo.
    echo   python -m venv venv
    echo   venv\Scripts\activate
    echo   pip install -r requirements.txt
    echo.
    pause
    exit /b 1
:PYTHON_EXE_OK
"%PYTHON_EXE%" --version
echo       OK - venv\python.exe found.

REM --- Step 3: Check main.py + dotenv ------------------------
echo.
echo [3/4] Check backend files...
if exist "main.py" goto MAIN_PY_OK
    echo [FATAL] main.py NOT FOUND in: %BACKEND_DIR%
    pause
    exit /b 1
:MAIN_PY_OK
if exist ".env" goto DOTENV_FOUND
    echo [WARN] .env not found - will use environment variables or defaults.
    goto DOTENV_CHECK_DONE
:DOTENV_FOUND
    echo       .env file found: OK.
:DOTENV_CHECK_DONE
echo       OK - files in place.

REM --- Step 4: Launch uvicorn DIRECTLY + watchdog ------------
echo.
echo [4/4] Starting PCScan FastAPI server (watchdog enabled)...
echo.
set MOCK_MODE=false
set /a CRASH_COUNT=0
set MAX_CRASHES=5

:RESTART
"venv\Scripts\python.exe" -m uvicorn main:app --host 0.0.0.0 --port 8000 --log-level info

set EXIT_CODE=%errorlevel%
echo.
if %EXIT_CODE% equ 0 (
    echo [INFO] Server exited normally (code 0).
    goto CLEAN_EXIT
)

set /a CRASH_COUNT+=1
if %CRASH_COUNT% GEQ %MAX_CRASHES% (
    echo.
    echo [FATAL] Crashed %CRASH_COUNT% times in a row - stopping watchdog.
    echo         Last exit code: %EXIT_CODE%
    echo.
    echo TROUBLESHOOTING:
    echo   - Check the error messages printed ABOVE (scroll up if needed)
    echo   - Most common fix: run these ONCE from backend\ folder:
    echo       venv\Scripts\activate
    echo       pip install -r requirements.txt
    echo   - If there is a Python ImportError, the line above will say which module
    echo.
    pause
    goto :EOF
)

echo [!] CRASH #%CRASH_COUNT% (exit %EXIT_CODE%) - restarting in 3s...
echo     (Press CTRL+C NOW to stop. Last chance before restart)
timeout /t 3 /nobreak
echo     Re-starting server...
echo.
goto RESTART

:CLEAN_EXIT
echo.
echo Server stopped.
echo.
pause
endlocal

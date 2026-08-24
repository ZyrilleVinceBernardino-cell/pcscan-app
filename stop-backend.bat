@echo off
setlocal

echo.
echo ============================================================
echo   Stop PCScan Backend Server
echo ============================================================
echo.

echo [1] Find and kill processes listening on port 8000...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8000 " ^| findstr LISTENING') do (
    echo     Killing PID %%p
    taskkill /PID %%p /F /T >nul 2>&1
)

echo.
echo [2] Find and kill any backend uvicorn / python venv processes...
tasklist /FI "IMAGENAME eq python.exe" /V /FO LIST 2>nul | findstr /i "uvicorn pcscan main.py" >nul
if %errorlevel% equ 0 (
    wmic process where "commandline like '%%uvicorn%%' or commandline like '%%main:app%%'" call terminate >nul 2>&1
)

REM --- Second pass: kill python that's in backend venv path if still any --------------
timeout /t 2 /nobreak >nul
for /f "tokens=2 skip=3" %%k in ('tasklist /FI "IMAGENAME eq python.exe" /FO TABLE 2^>nul') do (
    if not "%%k"=="" (
        wmic process where "processid=%%k and (commandline like '%%backend%%' or commandline like '%%port 8000%%')" call terminate >nul 2>&1
    )
)

echo.
echo ============================================================
echo   DONE. Port 8000 should now be free.
echo   Verify with:  status-backend.bat
echo ============================================================
echo.
timeout /t 3 /nobreak >nul
endlocal

@echo off
setlocal

echo.
echo ============================================================
echo   Add PCScan Backend to Windows STARTUP (login auto-run)
echo   Runs: start-backend.bat minimized on EVERY Windows login.
REM  ---------------------------------------------------------
REM   To REMOVE auto-run, use: uninstall-autostart.bat
REM ============================================================
echo.
set "BAT=%~dp0start-backend.bat"
set "SHORTCUT_NAME=PCScan Backend.lnk"

echo This script places a shortcut to start-backend.bat
echo into your Windows Start-up folder.
echo   Start-up folder = shell:startup
echo.
set /p CONFIRM="Proceed? Type Y then ENTER to continue, N to cancel: "
if /I NOT "%CONFIRM%"=="Y" (
    echo Canceled.
    pause
    exit /b 0
)

for %%f in ("%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup") do set "STARTUP=%%~ff"

REM --- Create shortcut via powershell ---------------------
powershell -NoProfile -Command ^
  "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%STARTUP%\%SHORTCUT_NAME%');" ^
  "$s.TargetPath='%BAT%';" ^
  "$s.WorkingDirectory='%~dp0backend';" ^
  "$s.WindowStyle=7;" ^
  "$s.Save()"

if exist "%STARTUP%\%SHORTCUT_NAME%" (
    echo.
    echo SUCCESS:
    echo   Shortcut created at: %STARTUP%\%SHORTCUT_NAME%
    echo.
    echo Next time you restart the PC, the backend will start
    echo automatically (minimized window).
) else (
    echo FAILED creating shortcut. Maybe startup folder is missing?
)

echo.
pause
endlocal

@echo off
setlocal

echo.
echo ============================================================
echo   REMOVE PCScan Backend from Windows STARTUP
echo   (Reverses what install-autostart.bat did)
echo ============================================================
echo.
set "SHORTCUT_NAME=PCScan Backend.lnk"
for %%f in ("%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup") do set "STARTUP=%%~ff"

set /p CONFIRM="Type Y then ENTER to DELETE startup shortcut, N to cancel: "
if /I NOT "%CONFIRM%"=="Y" (
    echo Canceled.
    pause
    exit /b 0
)

if exist "%STARTUP%\%SHORTCUT_NAME%" (
    del "%STARTUP%\%SHORTCUT_NAME%"
    echo OK - Deleted "%STARTUP%\%SHORTCUT_NAME%"
    echo On next login, backend will NO LONGER start automatically.
) else (
    echo Nothing to delete - PCScan Backend shortcut was not in startup.
)

echo.
pause
endlocal

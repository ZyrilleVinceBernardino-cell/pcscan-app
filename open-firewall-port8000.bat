@echo off
setlocal

REM ============================================================
REM  OPEN FIREWALL PORT 8000 (Run this file as ADMINISTRATOR!)
REM  Right-click the file -> Run as administrator
REM ============================================================

echo.
echo ============================================================
echo   Allow incoming connections to port 8000 on Windows Firewall
REM  -----------------------------------------------------------
REM   NEEDS ADMIN RIGHTS. Right-click file -> Run as admin.
REM ============================================================
echo.

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] NOT running as ADMINISTRATOR.
    echo.
    echo Right-click this file and select:
    echo   "Run as administrator"
    echo.
    pause
    exit /b 1
)

echo [1] Adding firewall rule "PCScan Port 8000" ...
netsh advfirewall firewall delete rule name="PCScan Port 8000" >nul 2>&1
netsh advfirewall firewall add rule name="PCScan Port 8000" dir=in action=allow protocol=TCP localport=8000

if %errorlevel% equ 0 (
    echo.
    echo SUCCESS! Firewall rule added.
    echo.
    echo From now on, phones/devices on YOUR SAME WIFI network can
    echo reach the backend via: http://192.168.100.51:8000
    echo.
    echo (Optional - for public access anywhere in the world with
    echo  mobile data: run install-ngrok.bat then start-public-ngrok.bat)
) else (
    echo FAILED - unknown error.
)

echo.
pause
endlocal

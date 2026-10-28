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
start "PCScan Ngrok - DO NOT CLOSE THIS WINDOW" /WAIT /D "%~dp0" cmd.exe /e:on /k call "%~f0" %*
exit /b
:WRAPPER_DONE

REM  ----------------------------------------------------------
REM   PCScan - Ngrok Public URL launcher
REM   ALL logic moved to _start_ngrok_public.ps1 (PowerShell)
REM   This .bat exists ONLY so double-clicking works reliably.
REM  ----------------------------------------------------------
setlocal
echo.
echo ============================================================
echo   PCScan - Ngrok Public URL (Starting)
echo   Window will NOT auto-close. Press ENTER at the end.
echo ============================================================
echo.
set "SCRIPT=%~dp0_start_ngrok_public.ps1"
if exist "%SCRIPT%" goto SCRIPT_EXISTS_OK
    echo [FATAL] Script missing: %SCRIPT%
    echo.
    pause
    exit /b 1
:SCRIPT_EXISTS_OK

REM --- Detect if powershell is available --------------------------
where powershell >nul 2>&1
if errorlevel 1 (
    echo [FATAL] powershell.exe not found in PATH
    echo.
    pause
    exit /b 2
)

REM --- Run the PS script - capture exit code, NEVER skip pause ----
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
set RC=%errorlevel%

echo.
echo ============================================================
echo   Ngrok launcher finished (rc=%RC%).
echo   (If rc=0, ngrok ran and you pressed CTRL+C to stop it.)
echo   (If rc=1..99, look at the error messages ABOVE.)
echo ============================================================
echo.
pause
endlocal

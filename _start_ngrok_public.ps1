# PCScan - Start Ngrok Public URL (full logic, called from .bat wrapper)
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Continue'

# ── Safety wrapper: catch EVERYTHING so the window never closes silently ──
trap {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Red
    Write-Host "  [FATAL] Script crashed with an unhandled error:" -ForegroundColor Red
    Write-Host "  $($_.Exception.GetType().Name): $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "============================================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "  TROUBLESHOOTING:"
    Write-Host "  - If this is a OneDrive/File-lock error, close Android Studio first."
    Write-Host "  - If ExecutionPolicy is blocked, run in PowerShell (Admin):"
    Write-Host "      Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"
    Write-Host ""
    Read-Host "Press ENTER to close"
    exit 99
}

$PROJECT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$TOOLS_DIR   = Join-Path $PROJECT_DIR "tools"
$NGROK_EXE   = Join-Path $TOOLS_DIR "ngrok.exe"

Write-Host ""
Write-Host "============================================================"
Write-Host "  PCScan - PUBLIC URL via NGROK"
Write-Host "  Remember: Run start-backend.bat FIRST before this one."
Write-Host "============================================================"
Write-Host ("  Project  : " + $PROJECT_DIR)
Write-Host ("  Tools    : " + $TOOLS_DIR)
Write-Host ("  ngrok.exe: " + $NGROK_EXE)
Write-Host "============================================================"
Write-Host ""

# --- Step 0: tools folder exists -------------------------------------------
if (-not (Test-Path $TOOLS_DIR)) {
    Write-Host "[FATAL] tools folder NOT FOUND at: $TOOLS_DIR"
    Write-Host "        Run install-ngrok.bat first (one-time setup)."
    Write-Host ""
    Read-Host "Press ENTER to close"
    exit 1
}

# --- Step 1: ngrok.exe exists ----------------------------------------------
if (-not (Test-Path $NGROK_EXE)) {
    Write-Host "[FATAL] ngrok.exe NOT FOUND at: $NGROK_EXE"
    Write-Host ""
    Write-Host "SOLUTION: Double-click install-ngrok.bat first."
    Write-Host ""
    Read-Host "Press ENTER to close"
    exit 2
}
& $NGROK_EXE version
Write-Host "       OK - ngrok.exe found."

# --- Step 2: ngrok auth token saved ---------------------------------------
Write-Host ""
Write-Host "[1/3] Check ngrok auth token saved ..."
$tokenOK = $false
try {
    $check = & $NGROK_EXE config check 2>&1
    if ($LASTEXITCODE -eq 0) {
        $configPath = Join-Path $env:LOCALAPPDATA "ngrok\ngrok.yml"
        if (Test-Path $configPath) {
            $cfgContent = Get-Content $configPath -Raw -ErrorAction SilentlyContinue
            if ($cfgContent -match 'authtoken\s*:\s*\S+') {
                $tokenOK = $true
            }
        }
    }
} catch {}
if (-not $tokenOK) {
    Write-Host "[FAIL] No auth token configured."
    Write-Host ""
    Write-Host "HOW TO FIX - choose ONE:"
    Write-Host "  A) Run install-ngrok.bat again and paste token at prompt."
    Write-Host "  B) Or run this command manually with YOUR token:"
    Write-Host "       tools\ngrok.exe config add-authtoken YOUR_TOKEN"
    Write-Host ""
    Write-Host "  Get a FREE token from:"
    Write-Host "    https://dashboard.ngrok.com/get-started/your-authtoken"
    Write-Host ""
    Read-Host "Press ENTER to close"
    exit 3
}
Write-Host "       OK - auth token found."

# --- Step 3: backend health check on localhost:8000 -----------------------
Write-Host ""
Write-Host "[2/3] Check backend running at http://localhost:8000 ..."
try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8000/api/health' -UseBasicParsing -TimeoutSec 6
    if ($r.status -eq 'ok') {
        Write-Host ("       OK - backend alive.  OpenAI=" + $r.openai_enabled + "  Gemini=" + $r.gemini_enabled)
    } else {
        throw ("status=" + $r.status)
    }
} catch {
    Write-Host ("[FAIL] Backend not reachable -> " + $_.Exception.Message)
    Write-Host ""
    Write-Host "  Make sure start-backend.bat is running first."
    Write-Host "  Wait until you see the line:"
    Write-Host "    'Uvicorn running on http://0.0.0.0:8000'"
    Write-Host "  Then re-run this file."
    Write-Host ""
    Read-Host "Press ENTER to close"
    exit 4
}

# --- Step 4: instructions then run ngrok tunnel ---------------------------
Write-Host ""
Write-Host "[3/3] Starting ngrok tunnel (region AP, https only) ..."
Write-Host ""
Write-Host "============================================================"
Write-Host "  DO THIS NOW:"
Write-Host "  - Find the line below that says:"
Write-Host "    Forwarding   https://XXXXXXXX.ngrok-free.app  ->  localhost:8000"
Write-Host "  - COPY the https://*.ngrok-free.app URL."
Write-Host "  - Open PcScanApiService.kt, line 40, paste URL:"
Write-Host '      private const val BASE_URL_PUBLIC = "https://....ngrok-free.app"'
Write-Host "  - Rebuild Android app in Android Studio."
Write-Host ""
Write-Host "  To STOP the public tunnel: press CTRL + C in this window."
Write-Host "============================================================"
Write-Host ""

Push-Location $TOOLS_DIR
try {
    & $NGROK_EXE http 8000 --region ap --scheme https
} finally {
    Pop-Location
}

Write-Host ""
Write-Host ("ngrok stopped. (exit code " + $LASTEXITCODE + ")")
Write-Host ""
Read-Host "Press ENTER to close"

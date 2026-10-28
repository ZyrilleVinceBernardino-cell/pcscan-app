$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Stop'
try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8000/api/health' -UseBasicParsing -TimeoutSec 5
    if ($r.status -eq 'ok') {
        Write-Host "    OK - backend alive.  OpenAI=$($r.openai_enabled)  Gemini=$($r.gemini_enabled)"
        exit 0
    } else {
        Write-Host "    NOT READY - status=$($r.status)"
        exit 1
    }
} catch {
    Write-Host "    FAIL - cannot connect to localhost:8000  ($($_.Exception.Message))"
    exit 2
}

# Build helper script for pcscan3 Android project
Write-Host "=== pcscan3 Build Helper ===" -ForegroundColor Cyan
Write-Host "Step 1: Closing Android Studio..." -ForegroundColor Yellow

# Kill all Android Studio-related processes
Get-Process | Where-Object {
    $_.ProcessName -in @("studio64", "java", "adb", "gradle") -or
    $_.MainWindowTitle -like "*Android*"
} | Stop-Process -Force -ErrorAction SilentlyContinue

Start-Sleep -Seconds 4
Write-Host "Step 2: Cleaning up old builds..." -ForegroundColor Yellow

# Delete build directories
$dirs = @("app\build", ".gradle", "build")
foreach ($dir in $dirs) {
    if (Test-Path $dir) {
        try {
            [System.IO.Directory]::Delete($dir, $true)
            Write-Host "  - Deleted $dir" -ForegroundColor Green
        } catch {
            Write-Host "  - Could not delete $dir : $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

Write-Host "Step 3: Building project..." -ForegroundColor Yellow
Write-Host "(This may take a minute)..." -ForegroundColor Gray

# Stop any Gradle daemons first
.\gradlew.bat --stop
Start-Sleep -Seconds 2

# Build the project
.\gradlew.bat assembleDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n=== BUILD SUCCESSFUL ===" -ForegroundColor Green
    Write-Host "APK is at: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Cyan
} else {
    Write-Host "`n=== BUILD FAILED ===" -ForegroundColor Red
}

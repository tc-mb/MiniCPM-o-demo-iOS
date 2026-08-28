$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$output = & $gradleWrapper `
    --offline `
    --dry-run `
    :app:connectedDebugAndroidTest 2>&1 | Out-String
$exitCode = $LASTEXITCODE

if ($exitCode -eq 0) {
    throw "Unsafe connected-device instrumentation task was not blocked."
}
if ($output -notmatch "CONNECTED_DEVICE_TEST_BLOCKED") {
    throw "The task failed without the expected connected-device safety marker.`n$output"
}

Write-Output "Connected-device instrumentation guard is active."

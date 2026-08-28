param(
    [Parameter(Mandatory = $true)]
    [string]$TestClass,

    [ValidateRange(10, 900)]
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$targetPackage = "com.example.minicpm_v_demo"
$testRunner = "$targetPackage.test/androidx.test.runner.AndroidJUnitRunner"
$bootstrapActivity = "$targetPackage/.CheckpointTestHostActivity"
$standardOutput = [System.IO.Path]::GetTempFileName()
$standardError = [System.IO.Path]::GetTempFileName()
$instrumentationProcess = $null

try {
    $connectedDevices = @(
        adb devices |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "\sdevice$" }
    )
    if ($connectedDevices.Count -ne 1) {
        throw "Expected exactly one connected Android device, found $($connectedDevices.Count)."
    }

    $arguments = @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $TestClass,
        $testRunner
    )
    $instrumentationProcess = Start-Process `
        -FilePath "adb" `
        -ArgumentList $arguments `
        -RedirectStandardOutput $standardOutput `
        -RedirectStandardError $standardError `
        -WindowStyle Hidden `
        -PassThru

    $launchDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $hostStarted = $false
    while ([DateTime]::UtcNow -lt $launchDeadline -and -not $instrumentationProcess.HasExited) {
        $targetPid = ((adb shell pidof $targetPackage 2>$null) | Out-String).Trim()
        if ($targetPid) {
            # Some vivo builds freeze an instrumentation process before its test can
            # launch an Activity. This debug-only host requires android.permission.DUMP,
            # so ADB can bootstrap it without exposing it to ordinary applications.
            adb shell am start -W -n $bootstrapActivity | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to start the foreground instrumentation bootstrap."
            }
            $hostStarted = $true
            break
        }
        Start-Sleep -Milliseconds 200
        $instrumentationProcess.Refresh()
    }
    if (-not $hostStarted) {
        throw "Instrumentation did not create the target process within 15 seconds."
    }

    $testDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $testDeadline -and -not $instrumentationProcess.HasExited) {
        Start-Sleep -Milliseconds 500
        $instrumentationProcess.Refresh()
        $targetPid = ((adb shell pidof $targetPackage 2>$null) | Out-String).Trim()
        if ($targetPid) {
            $waitChannel = ((
                adb shell "run-as $targetPackage cat /proc/$targetPid/wchan 2>/dev/null"
            ) | Out-String).Trim()
            if ($waitChannel -eq "do_freezer_trap") {
                adb shell am start -W -n $bootstrapActivity | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    throw "Failed to recover the instrumentation process from the OEM freezer."
                }
            }
        }
    }
    if (-not $instrumentationProcess.HasExited) {
        Stop-Process -Id $instrumentationProcess.Id -Force
        adb shell am force-stop $targetPackage
        throw "Instrumentation exceeded the $TimeoutSeconds second timeout."
    }
    $instrumentationProcess.WaitForExit()

    $output = Get-Content -Raw -ErrorAction SilentlyContinue $standardOutput
    $errorOutput = Get-Content -Raw -ErrorAction SilentlyContinue $standardError
    if ($output) {
        Write-Output $output
    }
    if ($errorOutput) {
        Write-Warning $errorOutput
    }
    if ($output -notmatch "OK \(") {
        throw "Instrumentation did not report a successful JUnit result."
    }
} finally {
    if ($instrumentationProcess -and -not $instrumentationProcess.HasExited) {
        Stop-Process -Id $instrumentationProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $standardOutput, $standardError -Force -ErrorAction SilentlyContinue
}

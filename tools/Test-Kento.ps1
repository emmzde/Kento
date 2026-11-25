<#
.SYNOPSIS
  Runs Kento's tests quickly.

.EXAMPLE
  .\tools\Test-Kento.ps1                       # JVM tests, then every instrumented test on the running emulator
.EXAMPLE
  .\tools\Test-Kento.ps1 -Unit                 # JVM tests only (seconds)
.EXAMPLE
  .\tools\Test-Kento.ps1 -Class MediaJourneyTest
.EXAMPLE
  .\tools\Test-Kento.ps1 -Class MediaJourneyTest#exifDialogAndToolRoundTrip

  Native code (whisper.cpp) is built and packaged only for the ABI of the connected device, the way Android Studio
  does it, so the qa APK is smaller and installs faster. Optional suites keep their switches:
  -Extra showcase=true, publicApis=true, camera=true, airplane=true.
#>
param(
    [string]$Class = '',
    [switch]$Unit,
    [switch]$NoUnit,
    [string[]]$Extra = @()
)
# Native tools (adb, Gradle) report progress on stderr; Windows PowerShell must not treat that as failure.
$ErrorActionPreference = 'Continue'
$projectDir = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectDir 'gradlew.bat'
$started = Get-Date
$tasks = @()
if (-not $NoUnit) { $tasks += ':data:testDebugUnitTest' }
$arguments = @('-p', $projectDir, '--console=plain')

if (-not $Unit) {
    $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\sdk') |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools\adb.exe')) } | Select-Object -First 1
    if (-not $sdk) { throw 'Android SDK platform-tools not found.' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $serial = @(& $adb devices) | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
    if (-not $serial) { throw 'Start the emulator first (Kento.cmd), or pass -Unit for JVM tests only.' }
    $abi = ((& $adb -s $serial shell getprop ro.product.cpu.abi) -join '').Trim()
    $env:ANDROID_SERIAL = $serial
    # UI tests need their window on top: wake the screen and leave any open app (Kento preview included).
    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $serial shell wm dismiss-keyguard | Out-Null
    & $adb -s $serial shell input keyevent KEYCODE_HOME | Out-Null
    # Speech tests need the Whisper model, which is no longer inside the APK. A copy in /data/local/tmp survives the
    # reinstall of the qa app before every run; the tests download it once themselves when neither copy exists.
    $model = 'ggml-small-q5_1.bin'
    if (((& $adb -s $serial shell "ls /data/local/tmp/$model 2>/dev/null") -join '').Trim() -eq '') {
        $local = @((Join-Path $projectDir "data\build\media-downloads\$model"), (Join-Path $env:LOCALAPPDATA "Kento\$model")) |
            Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if ($local) { & $adb -s $serial push $local "/data/local/tmp/$model" | Out-Null }
    }
    $tasks += ':app:connectedQaAndroidTest'
    $arguments += "-Pandroid.injected.build.abi=$abi"
    if ($Class) {
        $target = if ($Class.Contains('.')) { $Class } else { "dev.kento.app.$Class" }
        $arguments += "-Pandroid.testInstrumentationRunnerArguments.class=$target"
    }
    foreach ($pair in $Extra) {
        $name, $value = $pair -split '=', 2
        $arguments += "-Pandroid.testInstrumentationRunnerArguments.$name=$value"
    }
    Write-Host "Device $serial ($abi)"
}

& $gradle @tasks @arguments
$code = $LASTEXITCODE
$span = (Get-Date) - $started
$minutes = [int][math]::Floor($span.TotalMinutes)
Write-Host ("Done in {0}:{1:00}, exit {2}. Reports: app\build\reports\androidTests\connected, data\build\reports\tests" -f $minutes, $span.Seconds, $code)
exit $code

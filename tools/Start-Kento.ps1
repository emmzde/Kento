$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\sdk') |
    Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'emulator\emulator.exe')) } | Select-Object -First 1
if (-not $sdk) { throw 'Install Android Emulator from Android Studio SDK Manager, then run Kento again.' }
$env:ANDROID_HOME = $sdk
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avds = @(& $emulator -list-avds)
$avd = @('Kento_Voice_API_35', 'Kento_API_35') | Where-Object { $avds -contains $_ } | Select-Object -First 1
if (-not $avd) { $avd = $avds | Select-Object -First 1 }
if (-not $avd) { throw 'Create a virtual phone in Android Studio Device Manager first.' }

function Find-KentoEmulator {
    foreach ($item in (& $adb devices)) {
        if ($item -match '^(emulator-\d+)\s+device$') {
            $deviceSerial = $Matches[1]
            $name = @(& $adb -s $deviceSerial emu avd name 2>$null)[0]
            if ($name -and $name.Trim() -eq $avd.Trim()) { return $deviceSerial }
        }
    }
}
$serial = Find-KentoEmulator
if (-not $serial) {
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $avd, '-memory', '2048', '-no-boot-anim') -WindowStyle Normal
}
Write-Host "Starting Kento on $avd..."
$deadline = (Get-Date).AddMinutes(4)
do {
    $serial = Find-KentoEmulator
    if ($serial -and ((& $adb -s $serial shell getprop sys.boot_completed) -join '').Trim() -eq '1') { break }
    if ((Get-Date) -gt $deadline) { throw 'The virtual phone did not finish starting. Check its window.' }
    Start-Sleep -Seconds 2
} while ($true)
$apk = Join-Path $projectDir 'app\build\outputs\apk\preview\app-preview.apk'
if (-not (Test-Path -LiteralPath $apk)) {
    & (Join-Path $projectDir 'gradlew.bat') -p $projectDir :app:assemblePreview
    if ($LASTEXITCODE) { throw 'Android build failed.' }
}
& $adb -s $serial install -r $apk
if ($LASTEXITCODE) { throw 'Could not install Kento.' }
& $adb -s $serial shell am start -n dev.kento.app/.MainActivity
if ($LASTEXITCODE) { throw 'Could not open Kento.' }


param(
    [ValidateSet('Off','On')][string]$VoiceMode = 'Off',
    [ValidateSet('App','Home','Navi')][string]$Target = 'Home',
    [string]$NaviComponent,
    [string]$Serial,
    [switch]$StageProbe,
    [ValidateRange(30,3600)][int]$DurationSeconds = 180,
    [string]$Adb = 'C:\Users\SSAFY\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$package = 'ai.pleos.playground.sample.vehicle.diagnostic'
$component = "$package/ai.pleos.playground.sample.vehicle.MainActivity"
if ($Target -eq 'Navi' -and !$NaviComponent) { throw 'Supply -NaviComponent package/activity (confirmed from the device).' }
if ($StageProbe -and ($VoiceMode -ne 'On' -or $DurationSeconds -lt 330)) { throw 'StageProbe requires -VoiceMode On and -DurationSeconds 330 or longer.' }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
function Invoke-Adb {
    # Windows PowerShell 5.1 can turn even a blank native stderr line into a
    # terminating NativeCommandError. Keep stderr separate; check the exit code.
    $commandArgs = @($args)
    $stderrFile = [IO.Path]::GetTempFileName()
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $Adb @deviceArgs @commandArgs 2> $stderrFile)
        $exitCode = $LASTEXITCODE
        $stderrText = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
    } finally {
        $ErrorActionPreference = $savedPreference
        Remove-Item -LiteralPath $stderrFile -ErrorAction SilentlyContinue
    }
    if ($exitCode -ne 0) {
        # pidof returns 1 when the process no longer exists: this is evidence.
        if ($exitCode -eq 1 -and $commandArgs -contains 'pidof' -and [string]::IsNullOrWhiteSpace($stderrText)) { return }
        throw ("ADB failed (exit {0}): {1}`n{2}`n{3}" -f $exitCode, ($commandArgs -join ' '), ($result -join "`n"), $stderrText)
    }
    if (![string]::IsNullOrWhiteSpace($stderrText)) { Write-Verbose $stderrText }
    $result
}
$null = Invoke-Adb get-state
$userId = (Invoke-Adb shell am get-current-user).Trim()
$out = Join-Path $root ("captures-background/{0}-{1}-{2}" -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), $Target, $VoiceMode)
$null = New-Item -ItemType Directory -Path $out -Force
function Save-Snapshot([string]$label) {
    Invoke-Adb shell dumpsys activity processes | Set-Content -Encoding UTF8 (Join-Path $out "$label-processes.txt")
    Invoke-Adb shell dumpsys activity services $package | Set-Content -Encoding UTF8 (Join-Path $out "$label-services.txt")
    Invoke-Adb shell pidof $package | Set-Content -Encoding UTF8 (Join-Path $out "$label-pid.txt")
}
@{ target=$Target; voiceMode=$VoiceMode; durationSeconds=$DurationSeconds; user=$userId;
    serial=$Serial; naviComponent=$NaviComponent; stageProbe=[bool]$StageProbe; started=(Get-Date).ToString('o') } |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $out 'metadata.json')
Invoke-Adb shell getprop ro.build.fingerprint | Set-Content -Encoding UTF8 (Join-Path $out 'build.txt')
Invoke-Adb shell getprop ro.build.version.sdk | Add-Content -Encoding UTF8 (Join-Path $out 'build.txt')
Invoke-Adb shell device_config get activity_manager_native_boot use_freezer | Set-Content -Encoding UTF8 (Join-Path $out 'freezer-config.txt')
Invoke-Adb shell dumpsys package $package | Set-Content -Encoding UTF8 (Join-Path $out 'package.txt')
$logArgs = @($deviceArgs) + @('logcat','-v','threadtime','-T','1','BgProbe:I','BgStage:I','VoiceTest:I','VehicleControl:I','DriveProbe:I','Condition:I','Intervention:I','ActivityManager:I','AndroidRuntime:E','*:S')
$logger = Start-Process -FilePath $Adb -ArgumentList $logArgs -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $out 'logcat.txt') -RedirectStandardError (Join-Path $out 'logcat-errors.txt')
try {
    $prepare = if ($VoiceMode -eq 'On') { 'true' } else { 'false' }
    $stages = if ($StageProbe) { 'true' } else { 'false' }
    # FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_CLEAR_TASK (0x00008000).
    # am does not accept a --activity-new-task option on this emulator.
    Invoke-Adb shell am start -W --user $userId -f 0x10008000 -n $component --ez bg_probe_voice $prepare --ez bg_probe_stages $stages |
        Set-Content -Encoding UTF8 (Join-Path $out 'launch.txt')
    Start-Sleep -Seconds 8
    $service = (Invoke-Adb shell dumpsys activity services $package) -join "`n"
    $service | Set-Content -Encoding UTF8 (Join-Path $out 'setup-services.txt')
    $foreground = $service -match 'isForeground=true'
    if (($VoiceMode -eq 'On') -ne $foreground) {
        throw 'Service state does not match requested mode. Grant microphone permission in the app, then rerun. No observation performed.'
    }
    if ($StageProbe) {
        Invoke-Adb emu avd hostmicon | Set-Content -Encoding UTF8 (Join-Path $out 'host-microphone.txt')
        Write-Host 'Stage probe armed: synthetic driving inputs; real emulator HVAC and voice. Keep actual vehicle PARK / speed zero.'
    }
    Save-Snapshot 'before'
    $uptime = ((Invoke-Adb shell cat /proc/uptime) -split ' ')[0]
    $startMs = [long]([double]::Parse($uptime, [Globalization.CultureInfo]::InvariantCulture) * 1000)
    $startMs | Set-Content -Encoding UTF8 (Join-Path $out 'observation-start-ms.txt')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    if ($Target -eq 'Home') { $null = Invoke-Adb shell input keyevent KEYCODE_HOME }
    if ($Target -eq 'Navi') {
        Invoke-Adb shell am start -W --user $userId -n $NaviComponent | Set-Content -Encoding UTF8 (Join-Path $out 'navi-launch.txt')
    }
    # No intents, broadcasts, app restarts, or UI queries to the target during observation.
    $checkpoints = @(0,10,20,30,60,120,$DurationSeconds) | Where-Object { $_ -le $DurationSeconds } | Sort-Object -Unique
    foreach ($checkpoint in $checkpoints) {
        while ($watch.Elapsed.TotalSeconds -lt $checkpoint) { Start-Sleep -Milliseconds 500 }
        # pidof exits nonzero on termination: keep observing OS state and record absence.
        Invoke-Adb shell dumpsys activity processes | Set-Content -Encoding UTF8 (Join-Path $out "$checkpoint-processes.txt")
        Invoke-Adb shell dumpsys activity services $package | Set-Content -Encoding UTF8 (Join-Path $out "$checkpoint-services.txt")
        Invoke-Adb shell pidof $package | Set-Content -Encoding UTF8 (Join-Path $out "$checkpoint-pid.txt")
        Write-Host ("{0}s / {1}s collected -> {2}" -f $checkpoint, $DurationSeconds, $out)
    }
    # Read buffers before returning to the app; post-resume bursts are not continuity evidence.
    $uptime = ((Invoke-Adb shell cat /proc/uptime) -split ' ')[0]
    [long]([double]::Parse($uptime, [Globalization.CultureInfo]::InvariantCulture) * 1000) |
        Set-Content -Encoding UTF8 (Join-Path $out 'observation-end-ms.txt')
    Invoke-Adb logcat -d -v threadtime -s 'BgProbe:I' '*:S' | Set-Content -Encoding UTF8 (Join-Path $out 'probe-before-resume.txt')
    & (Join-Path $PSScriptRoot 'Read-BackgroundContinuity.ps1') -EvidenceDirectory $out
    if ($StageProbe) {
        Invoke-Adb logcat -d -v threadtime -s 'BgStage:I' '*:S' | Set-Content -Encoding UTF8 (Join-Path $out 'stages-before-resume.txt')
        & (Join-Path $PSScriptRoot 'Read-BackgroundStages.ps1') -EvidenceDirectory $out
    }
    'Observation finished. No automatic PASS: inspect gaps, counters, process state and OS freeze evidence.' |
        Set-Content -Encoding UTF8 (Join-Path $out 'observation.txt')
} finally {
    if (!$logger.HasExited) { Stop-Process -Id $logger.Id }
    Write-Host "Evidence directory: $out"
}

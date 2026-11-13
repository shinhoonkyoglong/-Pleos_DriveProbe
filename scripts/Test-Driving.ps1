#requires -Version 5.1
<#
.SYNOPSIS
Pleos 에뮬레이터의 주행 시작/조향 판단/자동 개입/종료 요약을 시험한다.
.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Smoke
.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\scripts\Test-Driving.ps1 -Scenario Danger -RequireInterventions
#>
[CmdletBinding()]
param(
    [ValidateSet('Smoke', 'Normal', 'Fatigue', 'Danger')][string]$Scenario = 'Fatigue',
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateRange(1, 120)][int]$AdbTimeoutSeconds = 15,
    [int]$Seed = 42,
    [ValidateRange(0, 120)][Nullable[double]]$NormalMinutes,
    [switch]$RequireInterventions,
    [switch]$CheckOnly,
    [switch]$DryRun,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/driving-tests' }
Add-Type -AssemblyName System.Net.Http
Import-Module (Join-Path $PSScriptRoot 'lib/DrivingTest.psm1') -Force
if ($RequireInterventions -and $Scenario -in @('Smoke', 'Normal')) {
    throw '-RequireInterventions는 Fatigue 또는 Danger 시나리오에서 사용해 주세요.'
}
$plan = Get-DrivingScenario $Scenario
if ($PSBoundParameters.ContainsKey('NormalMinutes')) { $plan.Normal = [double]$NormalMinutes }
if (($plan.Normal + $plan.Drowsy + $plan.Severe) -le 0) { throw '총 주행 시간은 0분보다 커야 합니다.' }
Write-Host "주행 시험: $Scenario / 정상 $($plan.Normal)분 → 졸림 $($plan.Drowsy)분 → 심한 졸림 $($plan.Severe)분 → 정차·P"
Write-Host "속도 80 km/h, seed=$Seed. 주행 화면과 종료 요약 화면은 직접 확인해 주세요."
if ($RequireInterventions) { Write-Host '공조 성공·CAUTION 음성 응답·(Danger) 안내 동의까지 필수 판정합니다. 출발 전 주행 도움을 켜 주세요.' }
else { Write-Host '자동 판정: 주행 신호·피로 단계·요약 저장. 공조/음성/내비 개입 결과는 관측값으로 기록합니다.' }
if ($DryRun) { return }

$packageName = 'ai.pleos.playground.sample.vehicle.diagnostic'
$component = "$packageName/ai.pleos.playground.sample.vehicle.MainActivity"
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(3)
$forwardPort = $null
$logger = $null
$loggerStarted = $false
$logStream = $null
$errorStream = $null
$logTask = $null
$errorTask = $null
$runError = ''
$parked = $false
$runPath = $null
$logPath = $null
$capturePath = $null
$appPid = ''
$runMarker = 'DRIVING_TEST_' + [guid]::NewGuid().ToString('N')
$assessment = $null
$completed = $false
$consoleUri = $null
function Adb([string[]]$Arguments) {
    # All arguments here are fixed command tokens, validated numbers, or the fixed
    # app component. No shell command strings or user-controlled paths are joined.
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $AdbPath
    $info.Arguments = "-s $Serial " + ($Arguments -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($AdbTimeoutSeconds * 1000)) {
            $process.Kill()
            $null = $process.WaitForExit(2000)
            throw "adb timed out after ${AdbTimeoutSeconds}s: $($Arguments -join ' '). 에뮬레이터 응답을 확인해 주세요."
        }
        if (-not $stdout.Wait(2000) -or -not $stderr.Wait(2000)) { throw 'adb 출력 수집 시간 초과' }
        $output = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "adb $($Arguments -join ' '): $output $errorText" }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Read-RunLog {
    param([switch]$Raw)
    if (-not $capturePath -or -not (Test-Path -LiteralPath $capturePath)) { return '' }
    $stream = [IO.File]::Open($capturePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
    try {
        $text = $reader.ReadToEnd()
        if ($Raw) { return $text }
        return Select-DrivingRunLog $text $appPid $runMarker
    } finally { $reader.Dispose() }
}
function ConsoleState { Invoke-DrivingConsole $http $consoleUri GET 'vehicle/state' }
function Save-Json([string]$name, $value) {
    [IO.File]::WriteAllText((Join-Path $runPath $name), ($value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
try {
    if (-not $CheckOnly) {
        $runPath = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
        $null = New-Item -ItemType Directory -Path $runPath -Force
        $logPath = Join-Path $runPath 'app.log'
        $capturePath = Join-Path $runPath 'capture.log'
        Write-Host "결과 폴더: $runPath"
        Save-Json 'run.json' ([ordered]@{ scenario = $Scenario; serial = $Serial; seed = $Seed
            startedAt = [DateTimeOffset]::Now.ToString('o'); plan = $plan; requireInterventions = [bool]$RequireInterventions })
    }
    if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb 없음: $AdbPath (-AdbPath로 지정 가능)" }
    if ((Adb @('get-state')) -ne 'device') { throw "$Serial 에뮬레이터 연결을 확인해 주세요." }
    $vehicleUser = Adb @('shell', 'am', 'get-current-user')
    if ($vehicleUser -notmatch '^\d+$') { throw '현재 AAOS 사용자 확인 실패' }
    $installed = Adb @('shell', 'pm', 'path', '--user', $vehicleUser, $packageName)
    if ($installed -notmatch 'package:') { throw "사용자 $vehicleUser 에 Debug 앱을 설치해 주세요." }
    $null = Adb @('shell', 'run-as', $packageName, '--user', $vehicleUser, 'id')
    $forwardPort = Adb @('forward', 'tcp:0', 'tcp:8088')
    if ($forwardPort -notmatch '^\d+$') { throw 'Virtual Console 포트 연결 실패' }
    $consoleUri = [uri]"http://127.0.0.1:$forwardPort/"
    $before = ConsoleState
    if ($before.gear.actual.value -ne 'Park' -or $before.controls.speed_kph -ne 0) {
        throw '현재 Virtual Console을 P·속도 0으로 놓은 뒤 실행해 주세요.'
    }
    Write-Host "연결 확인: $Serial / AAOS 사용자 $vehicleUser / Debug 앱 / Virtual Console"
    if ($CheckOnly) {
        Write-Host '연결 확인 완료. 주행·모드 변경은 실행하지 않았습니다. 실제 신호 전달은 Smoke로 확인해 주세요.'
        return
    }
    Save-Json 'console-before.json' $before
    $null = Adb @('shell', 'am', 'start', '--user', $vehicleUser, '-W', '-n', $component)
    $appPid = Adb @('shell', 'pidof', $packageName)
    if ($appPid -notmatch '^\d+$') { throw '앱 단일 프로세스 확인 실패' }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $AdbPath
    $startInfo.Arguments = "-s $Serial logcat -v threadtime -T 1 DrivingTest:I Condition:I Intervention:I Trip:I VehicleControl:I VoiceTest:I AndroidRuntime:E *:S"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $logger = [Diagnostics.Process]::new()
    $logger.StartInfo = $startInfo
    $null = $logger.Start()
    $loggerStarted = $true
    $logStream = [IO.FileStream]::new($capturePath, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1)
    $errorStream = [IO.FileStream]::new((Join-Path $runPath 'logcat-errors.log'), [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1)
    $logTask = $logger.StandardOutput.BaseStream.CopyToAsync($logStream)
    $errorTask = $logger.StandardError.BaseStream.CopyToAsync($errorStream)
    # Wait for a unique shell marker before changing the engine. This avoids losing
    # the first app log while logcat attaches, without clearing the device's buffer.
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $null = Adb @('shell', 'log', '-p', 'i', '-t', 'DrivingTest', $runMarker)
        Start-Sleep -Milliseconds 200
        $captureReady = (Read-RunLog -Raw).Contains($runMarker)
        if ($logger.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw '로그 수집 시작 확인 실패' }
    } until ($captureReady)
    $null = Adb @('shell', 'am', 'broadcast', '--user', $vehicleUser, '-p', $packageName,
        '-a', 'ai.pleos.condition.MODE', '--es', 'mode', 'REAL')
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ((Read-RunLog) -notmatch 'engine started mode=REAL isReplay=false') {
        if ($logger.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'REAL 모드 전환 로그 없음. Debug 앱과 logcat-errors.log를 확인해 주세요.' }
        Start-Sleep -Milliseconds 200
    }
    $progress = {
        param($phase, $elapsed)
        if ($logger.HasExited) { throw 'logcat 수집이 중단됐습니다.' }
        $text = Read-RunLog
        if ($text -match 'FATAL EXCEPTION|trip aggregation/save failed') { throw '앱 실행 오류가 발생했습니다. app.log를 확인해 주세요.' }
        $latest = @($text -split '\r?\n' | Select-String 'level=\w+ score=')
        if ($latest.Count -gt 0) { Write-Host "  $($latest[-1].Line.Trim())" }
    }
    & (Join-Path $PSScriptRoot 'drive-sim.ps1') -Normal $plan.Normal -Drowsy $plan.Drowsy -Severe $plan.Severe `
        -Serial $Serial -AdbPath $AdbPath -ConsoleUri $consoleUri -Seed $Seed -OnProgress $progress
    Write-Host 'P 전환 완료. 주행 저장·요약 로그를 기다립니다.'
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ((Read-RunLog) -notmatch '\bsummary:') {
        if ($logger.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw '주행 종료 요약 로그를 받지 못했습니다.' }
        Start-Sleep -Milliseconds 250
    }
    $completed = $true
} catch {
    $runError = $_.Exception.Message
    Write-Warning $runError
} finally {
    if ($runPath -and -not $completed -and -not $runError) { $runError = '시나리오 완료 전에 실행이 중단됐습니다.' }
    if ($runPath -and $consoleUri) {
        try {
            $after = ConsoleState
            Save-Json 'console-after.json' $after
            $parked = $after.gear.actual.value -eq 'Park' -and $after.controls.speed_kph -eq 0 -and $after.controls.steering_deg -eq 0
        } catch { $runError += " 정차 상태 재조회 실패: $($_.Exception.Message)" }
    }
    if ($null -ne $logger) {
        if ($loggerStarted) {
            try {
                if (-not $logger.HasExited) { $logger.Kill() }
                if (-not $logger.WaitForExit(5000)) { throw 'logcat 종료 시간 초과' }
                foreach ($task in @($logTask, $errorTask)) {
                    if ($null -ne $task) {
                        if (-not $task.Wait(5000)) { throw 'logcat 저장 시간 초과' }
                        $null = $task.GetAwaiter().GetResult()
                    }
                }
            } catch { $runError += " 로그 수집 정리 실패: $($_.Exception.Message)" }
        }
        $logger.Dispose()
    }
    if ($logStream) { $logStream.Dispose() }
    if ($errorStream) { $errorStream.Dispose() }
    if ($forwardPort -match '^\d+$') {
        try { $null = Adb @('forward', '--remove', "tcp:$forwardPort") }
        catch { $runError += " 포트 정리 실패: $($_.Exception.Message)" }
    }
    if ($runPath) {
        $capturedLog = Read-RunLog
        [IO.File]::WriteAllText($logPath, $capturedLog, [Text.UTF8Encoding]::new($false))
        $assessment = Get-DrivingAssessment -LogText $capturedLog -Scenario $Scenario -Parked $parked -RunError $runError -RequireInterventions:$RequireInterventions
        Save-Json 'result.json' $assessment
        $verdict = if ($assessment.passed) { 'PASS' } else { 'FAIL' }
        $rows = @(
            "# Driving test: $Scenario"
            ''
            "Result: $verdict"
            ''
            '| Check | Result | Evidence |'
            '| --- | --- | --- |'
        )
        foreach ($check in $assessment.checks) {
            $status = if ($check.passed) { 'PASS' } else { 'FAIL' }
            Write-Host "[$status] $($check.name): $($check.detail)"
            $detail = $check.detail.Replace('|', '/').Replace("`r", ' ').Replace("`n", ' ')
            $rows += "| $($check.name) | $status | $detail |"
        }
        $rows += @('', '## Observed interventions (same trip only)', '')
        foreach ($item in $assessment.interventions) { $rows += "- $($item.level): $($item.outcome) (history=$($item.historyId))" }
        $rows += @('', '## Manual checks', '') + @($assessment.manualChecks | ForEach-Object { '- [ ] ' + $_ })
        [IO.File]::WriteAllLines((Join-Path $runPath 'result.md'), [string[]]$rows, [Text.UTF8Encoding]::new($false))
        Write-Host "결과: $runPath\result.md"
    }
    $http.Dispose()
}
if ($runError -or ($assessment -and -not $assessment.passed)) { throw "주행 시험 실패. $runError 결과 폴더를 확인해 주세요: $runPath" }

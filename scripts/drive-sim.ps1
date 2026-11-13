#requires -Version 5.1
<#
운전 신호 주입기. 전체 준비/판정/로그 저장은 .\scripts\Test-Driving.ps1 사용.
REAL 모드 앱과 Virtual Console REST가 필요하다. 피로 점수/임계값을 조작하지 않는다.
powershell -ExecutionPolicy Bypass -File .\scripts\drive-sim.ps1 -Normal 3 -Drowsy 3 -Severe 3
종료/예외/Ctrl+C 때 속도 0, 조향 0, P 전환을 각각 시도한다. 강제 프로세스 종료는 정리를 보장하지 않는다.
#>
[CmdletBinding()]
param(
    [ValidateRange(0, 120)][double]$Normal = 3,
    [ValidateRange(0, 120)][double]$Drowsy = 3,
    [ValidateRange(0, 120)][double]$Severe = 0,
    [ValidateRange(0, 240)][int]$Speed = 80,
    [ValidateRange(100, 500)][int]$TickMs = 200,
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [uri]$ConsoleUri,
    [int]$Seed = 42,
    [switch]$NoPark,
    [scriptblock]$OnProgress
)
$ErrorActionPreference = 'Stop'
if (($Normal + $Drowsy + $Severe) -le 0) { throw '주행 시간을 하나 이상 지정해 주세요.' }
Add-Type -AssemblyName System.Net.Http
Import-Module (Join-Path $PSScriptRoot 'lib/DrivingTest.psm1') -Force
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(2)
$forwardPort = $null
$primaryError = $null
$started = $false
$clientId = 'drive-test-' + [guid]::NewGuid().ToString('N')
$rand = [System.Random]::new($Seed)
$script:cur = 0
$script:sign = 1
function Send([string]$method, [string]$path) { $null = Invoke-DrivingConsole $http $ConsoleUri $method $path }
function Heartbeat { Send POST "browser/session?client=$clientId" }
function MoveHold([int]$target, [int]$step, [double]$seconds) {
    $values = [System.Collections.Generic.List[int]]::new()
    $count = [Math]::Max(1, [int][Math]::Round($seconds * 1000 / $TickMs))
    for ($i = 0; $i -lt $count; $i++) {
        if ($script:cur -lt $target) { $script:cur = [Math]::Min($target, $script:cur + $step) }
        elseif ($script:cur -gt $target) { $script:cur = [Math]::Max($target, $script:cur - $step) }
        $values.Add($script:cur)
    }
    return ,$values
}
function Chunk([string]$phase) {
    if ($phase -eq 'Normal') {
        $script:sign = -$script:sign
        $amp = if ($rand.NextDouble() -lt 0.02) { 3 } else { $rand.Next(1, 3) }
        return MoveHold ($script:sign * $amp) 1 (1.6 + $rand.NextDouble() * 1.6)
    }
    $values = [System.Collections.Generic.List[int]]::new()
    $hold = if ($phase -eq 'Severe') { 6.0 } else { 4.0 }
    $values.AddRange((MoveHold 0 1 $hold))
    $script:sign = -$script:sign
    $step = [Math]::Max(1, [int][Math]::Round(15 * $TickMs / 1000.0))
    $values.AddRange((MoveHold ($script:sign * 9) $step 1.0))
    if ($phase -eq 'Severe') { $values.AddRange((MoveHold 0 $step 0.8)) }
    else {
        $values.AddRange((MoveHold (-$script:sign * 2) $step 1.0))
        $values.AddRange((MoveHold 0 1 1.0))
        for ($i = 0; $i -lt 3; $i++) {
            $script:sign = -$script:sign
            $values.AddRange((MoveHold $script:sign 1 (3.5 + $rand.NextDouble() * 1.5)))
        }
    }
    return ,$values
}
try {
    if ($null -eq $ConsoleUri) {
        if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb 없음: $AdbPath" }
        $forwardPort = (& $AdbPath -s $Serial forward tcp:0 tcp:8088 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $forwardPort -notmatch '^\d+$') { throw 'adb 포트 연결 실패' }
        $ConsoleUri = [uri]"http://127.0.0.1:$forwardPort/"
    }
    $started = $true
    Heartbeat
    Send PUT 'vehicle/turn-signal?mode=Off'
    Send PUT 'vehicle/controls?speed=0&steering=0'
    Send PUT 'vehicle/gear?gear=Park'
    Start-Sleep -Milliseconds 750
    Heartbeat
    Send PUT 'vehicle/gear?gear=Drive'
    Send PUT "vehicle/controls?speed=$Speed"
    Write-Host "주행 시작: $Speed km/h, seed=$Seed. 유효 판단에는 최소 60초짜리 창이 필요합니다. Ctrl+C로 종료."
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $next = 0.0
    $lastBeat = 0.0
    $lastSpeed = 0.0
    foreach ($phase in @(@{ Name = 'Normal'; Minutes = $Normal }, @{ Name = 'Drowsy'; Minutes = $Drowsy }, @{ Name = 'Severe'; Minutes = $Severe })) {
        if ($phase.Minutes -le 0) { continue }
        Write-Host "구간: $($phase.Name) $($phase.Minutes * 60)초"
        $phaseStart = $watch.Elapsed.TotalSeconds
        $lastPrint = -10.0
        $queue = [System.Collections.Generic.Queue[int]]::new()
        while (($watch.Elapsed.TotalSeconds - $phaseStart) -lt $phase.Minutes * 60) {
            if ($queue.Count -eq 0) { foreach ($value in (Chunk $phase.Name)) { $queue.Enqueue($value) } }
            $angle = $queue.Dequeue()
            Send PUT "vehicle/controls?steering=$angle"
            $now = $watch.Elapsed.TotalSeconds
            if ($now - $lastBeat -ge 1) { Heartbeat; $lastBeat = $now }
            if ($now - $lastSpeed -ge 1) { Send PUT "vehicle/controls?speed=$Speed"; $lastSpeed = $now }
            if ($now - $phaseStart - $lastPrint -ge 10) {
                $lastPrint = $now - $phaseStart
                Write-Host ('[{0:mm\:ss}] {1} 조향={2}°' -f [TimeSpan]::FromSeconds($now), $phase.Name, $angle)
                if ($OnProgress) { & $OnProgress $phase.Name $now }
            }
            $next += $TickMs
            $wait = $next - $watch.Elapsed.TotalMilliseconds
            if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) }
            else { $next = $watch.Elapsed.TotalMilliseconds }
        }
    }
} catch { $primaryError = $_; throw }
finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    if ($started) {
        foreach ($operation in @(@('POST', "browser/session?client=$clientId"), @('PUT', 'vehicle/controls?steering=0'), @('PUT', 'vehicle/controls?speed=0'))) {
            try { Send $operation[0] $operation[1] } catch { $cleanupErrors.Add($_.Exception.Message) }
        }
        if (-not $NoPark) {
            Start-Sleep -Milliseconds 1500
            try { Send PUT 'vehicle/gear?gear=Park' } catch { $cleanupErrors.Add($_.Exception.Message) }
        }
    }
    $http.Dispose()
    if ($forwardPort) { & $AdbPath -s $Serial forward --remove "tcp:$forwardPort" | Out-Null }
    if ($cleanupErrors.Count -gt 0) {
        $message = '정차 정리 중 오류: ' + ($cleanupErrors -join ' / ')
        if ($null -eq $primaryError) { throw $message }
        Write-Warning $message
    }
}

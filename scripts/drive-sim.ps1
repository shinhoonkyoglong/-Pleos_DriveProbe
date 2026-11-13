# 운전 시뮬레이터: Virtual Console REST로 조향·속도·기어를 계속 넣어 "운전하는 사람"을 흉내 낸다.
# 앱(REAL 모드)이 에뮬레이터에 떠 있는 상태에서 PC PowerShell로 실행한다.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\drive-sim.ps1                    # 정상 3분 → 졸림 3분 → P
#   powershell -ExecutionPolicy Bypass -File .\scripts\drive-sim.ps1 -Normal 2 -Drowsy 4
#   powershell -ExecutionPolicy Bypass -File .\scripts\drive-sim.ps1 -Normal 5 -Drowsy 0 # 정상만
#   powershell -ExecutionPolicy Bypass -File .\scripts\drive-sim.ps1 -Normal 1.5 -Drowsy 2 -Severe 2  # DANGER까지
#   Ctrl+C로 멈추면 조향 0 · 속도 0 · 기어 P로 정리하고 끝낸다 (P = 주행 종료 → 주행 요약 생성)
#
# 조향 패턴 (컨디션 판단 core 규칙 기준)
#   정상: 1.6~3.2초마다 좌우를 1~2°씩 오감 → 미세 반전(1°) 분당 약 25회, 가끔 3° → 큰 반전(5°) 분당 약 1회
#   졸림: 4초 고정(무조향) → 0.6초 안에 9° 꺾기(급조향) → 되돌림 → 천천히 1°씩 흔들기, 약 19초 주기
#         → 미세 반전 분당 약 16회(감소), 큰 반전 약 3회(증가), 급조향 60초 창당 약 3회
#   심한 졸림: 6초 완전 고정 → 9° 꺾기 → 바로 0°로 복귀, 약 8초 주기 (작은 흔들기 없음)
#         → 미세·큰 반전 분당 약 7회, 급조향 창당 약 7회 → DANGER 목표
# 주의: D로 바꾼 뒤 약 80초 지나야 첫 유효 창이 나온다. 콘솔 조향은 정수만 받는다.

param(
  [double]$Normal = 3,      # 정상 운전 시간(분)
  [double]$Drowsy = 3,      # 졸린 운전 시간(분)
  [double]$Severe = 0,      # 심하게 조는 운전 시간(분), 졸림 다음에 이어짐
  [int]$Speed = 80,         # km/h (유효 창은 60 이상)
  [int]$TickMs = 200,       # 조향 전송 간격
  [switch]$NoPark           # 끝나도 P로 바꾸지 않음
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$base = "http://127.0.0.1:8088"

& $adb forward tcp:8088 tcp:8088 | Out-Null

Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(3)

function Send([string]$method, [string]$path) {
  $body = New-Object System.Net.Http.StringContent("")
  try {
    if ($method -eq "POST") { $r = $http.PostAsync("$base/$path", $body).Result }
    else { $r = $http.PutAsync("$base/$path", $body).Result }
    $text = $r.Content.ReadAsStringAsync().Result
    if ($text -match '"error"') { Write-Host "  콘솔 에러 ($path): $text" -ForegroundColor Yellow }
  } catch {
    Write-Host "  전송 실패 ($path): $($_.Exception.InnerException.Message)" -ForegroundColor Red
  }
}
function Heartbeat { Send "POST" "browser/session?client=cli" }
function Steer([int]$deg) { Send "PUT" "vehicle/controls?steering=$deg" }

# ── 조향 계획 만들기: 틱(TickMs)마다 넣을 값 목록 ─────────────────────────
$rand = New-Object System.Random
$script:cur = 0
$script:sign = 1
function Ticks([double]$sec) { [Math]::Max(1, [int][Math]::Round($sec * 1000 / $TickMs)) }

# 현재 값에서 target까지 한 틱에 step°씩 움직인 뒤, 남은 시간은 유지
function MoveHold([int]$target, [int]$step, [double]$totalSec) {
  $out = New-Object System.Collections.Generic.List[int]
  $n = Ticks $totalSec
  for ($i = 0; $i -lt $n; $i++) {
    if ($script:cur -lt $target) { $script:cur = [Math]::Min($target, $script:cur + $step) }
    elseif ($script:cur -gt $target) { $script:cur = [Math]::Max($target, $script:cur - $step) }
    $out.Add($script:cur)
  }
  return ,$out
}

function NormalChunk {
  # 반 주기: 반대쪽으로 1~2°(가끔 3°) 이동 후 유지
  $script:sign = -$script:sign
  $amp = if ($rand.NextDouble() -lt 0.02) { 3 } else { $rand.Next(1, 3) }
  return MoveHold ($script:sign * $amp) 1 (1.6 + $rand.NextDouble() * 1.6)
}

function DrowsyChunk {
  $out = New-Object System.Collections.Generic.List[int]
  $out.AddRange((MoveHold 0 1 4.0))                           # 무조향 4초
  $script:sign = -$script:sign
  $out.AddRange((MoveHold ($script:sign * 9) 3 1.0))          # 0.6초 안에 9°, 0.4초 유지 (급조향)
  $out.AddRange((MoveHold (-$script:sign * 2) 3 1.0))         # 크게 되돌림 (반대쪽 2°까지 넘어감)
  $out.AddRange((MoveHold 0 1 1.0))
  for ($k = 0; $k -lt 3; $k++) {                              # 천천히 1°씩 흔들기 (작은 수정 적음)
    $script:sign = -$script:sign
    $out.AddRange((MoveHold $script:sign 1 (3.5 + $rand.NextDouble() * 1.5)))
  }
  return ,$out
}

function SevereChunk {
  $out = New-Object System.Collections.Generic.List[int]
  $out.AddRange((MoveHold 0 3 6.0))                           # 완전 고정 6초
  $script:sign = -$script:sign
  $out.AddRange((MoveHold ($script:sign * 9) 3 1.0))          # 급조향
  $out.AddRange((MoveHold 0 3 0.8))                           # 바로 복귀 (넘어가지 않음)
  return ,$out
}

# ── 실행 ───────────────────────────────────────────────────────────────
$phases = @()
if ($Normal -gt 0) { $phases += @{ Name = "정상"; Sec = $Normal * 60; Gen = { NormalChunk } } }
if ($Drowsy -gt 0) { $phases += @{ Name = "졸림"; Sec = $Drowsy * 60; Gen = { DrowsyChunk } } }
if ($Severe -gt 0) { $phases += @{ Name = "심한졸림"; Sec = $Severe * 60; Gen = { SevereChunk } } }

Write-Host "준비: 하트비트 → 방향지시등 끔 → 기어 P → D → 속도 $Speed km/h"
Heartbeat
Send "PUT" "vehicle/turn-signal?mode=Off"
Steer 0
Send "PUT" "vehicle/gear?gear=Park"
Start-Sleep -Milliseconds 500
Send "PUT" "vehicle/gear?gear=Drive"
Send "PUT" "vehicle/controls?speed=$Speed"
Write-Host "주행 시작. 첫 점수는 약 80초 뒤. 멈추려면 Ctrl+C" -ForegroundColor Cyan

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$next = 0.0
$lastBeat = 0.0
$lastSpeed = 0.0
try {
  foreach ($ph in $phases) {
    $phStart = $sw.Elapsed.TotalSeconds
    $lastPrint = -999.0
    $queue = New-Object System.Collections.Generic.Queue[int]
    while (($sw.Elapsed.TotalSeconds - $phStart) -lt $ph.Sec) {
      if ($queue.Count -eq 0) { foreach ($v in (& $ph.Gen)) { $queue.Enqueue($v) } }
      $deg = $queue.Dequeue()
      Steer $deg

      $now = $sw.Elapsed.TotalSeconds
      if ($now - $lastBeat -ge 1.0) { Heartbeat; $lastBeat = $now }               # 콘솔 세션 유지 (3초 제한)
      if ($now - $lastSpeed -ge 5.0) { Send "PUT" "vehicle/controls?speed=$Speed"; $lastSpeed = $now }
      $e = $now - $phStart
      if ($e - $lastPrint -ge 10) {
        $lastPrint = $e
        $t = [TimeSpan]::FromSeconds($now)
        Write-Host ("[{0:mm\:ss}] {1} {2:0}/{3:0}초  조향 {4,3}°  속도 {5}" -f $t, $ph.Name, $e, $ph.Sec, $deg, $Speed)
      }

      $next += $TickMs
      $wait = $next - $sw.Elapsed.TotalMilliseconds
      if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) } else { $next = $sw.Elapsed.TotalMilliseconds }
    }
  }
}
finally {
  Write-Host "정리: 조향 0 → 속도 0" -ForegroundColor Cyan
  Heartbeat
  Steer 0
  Send "PUT" "vehicle/controls?speed=0"
  if (-not $NoPark) {
    Start-Sleep -Milliseconds 1500
    Send "PUT" "vehicle/gear?gear=Park"
    Write-Host "기어 P → 주행 종료 (주행 요약이 만들어짐)"
  }
  $http.Dispose()
}

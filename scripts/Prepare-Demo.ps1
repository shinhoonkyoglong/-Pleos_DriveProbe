#requires -Version 5.1
<#
시연영상 촬영 준비·주행. debug 빌드에서만 동작한다(시연용 브로드캐스트·계측 도구가 debug 전용).
시나리오는 docs/demo-video.md 참고.

  # 촬영 전 준비(매 테이크): 앱 초기화 → 라이트 테마·속도 권한·평소 기록 → 8주 시연 기록 → 내비 목적지 → 음성 답변 자동 입력
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Demo.ps1

  # 코드를 바꿨다면 빌드·설치까지
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Demo.ps1 -Build

  # 시연 주행(약 3분 30초: 정상 1분 → 졸음 2분 30초 → P). 시작 직후 내비를 앞으로 띄운다
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Demo.ps1 -Drive

주의: 준비 단계는 에뮬레이터 앱의 모든 기록과 마이크 권한·설정을 지우고, 내비 목적지를 바꾼다.
Virtual Console은 P·속도 0이어야 한다. 에뮬레이터는 -allow-host-audio 없이도 된다(음성 답변은 자동 입력).
#>
[CmdletBinding()]
param(
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [switch]$Build,
    [switch]$Drive,
    [switch]$React,
    [switch]$TapStart,
    [switch]$Dark,
    [ValidateRange(0.5, 10)][double]$NormalMinutes = 1,
    [ValidateRange(1, 10)][double]$DrowsyMinutes = 2.5
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw 'adb.exe를 찾지 못했습니다.' }
$pkg = 'ai.pleos.playground.sample.vehicle.diagnostic'
$activity = "$pkg/ai.pleos.playground.sample.vehicle.MainActivity"
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"

function Adb { & $adb -s $Serial @args; if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') 실패" } }

if ((& $adb -s $Serial shell getprop sys.boot_completed).Trim() -ne '1') { throw "$Serial 에뮬레이터가 켜져 있지 않습니다." }
$user = (& $adb -s $Serial shell am get-current-user).Trim()

if ($React) {
    # 반응 체크 자동 응답: '출발 전 컨디션 체크' 화면에서 [시작하기]를 누른 뒤 실행(또는 -TapStart로 버튼까지 누름).
    # 앱이 원을 초록으로 바꿀 때 남기는 로그를 보고 Virtual Console 브레이크를 밟았다 뗀다(최대 30초)
    Add-Type -AssemblyName System.Net.Http
    $http = [System.Net.Http.HttpClient]::new(); $http.Timeout = [TimeSpan]::FromSeconds(2)
    $port = (& $adb -s $Serial forward tcp:0 tcp:8088 | Out-String).Trim()
    $base = "http://127.0.0.1:$port/"
    function Console-Put([string]$path) { $null = $http.PutAsync($base + $path, $null).Result }
    function Console-Beat { $null = $http.PostAsync($base + 'browser/session?client=demo-react', $null).Result }
    & $adb -s $Serial logcat -c
    $psi = [System.Diagnostics.ProcessStartInfo]::new($adb, "-s $Serial logcat -s KdReaction:I")
    $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    try {
        Console-Beat; Console-Put 'vehicle/controls?brake=0'
        if ($TapStart) { & $adb -s $Serial shell input tap 1693 1208 }
        Write-Host '반응 체크 자동 응답 대기 중(초록 원이 뜨면 브레이크)'
        $watch = [Diagnostics.Stopwatch]::StartNew(); $presses = 0
        $read = $proc.StandardOutput.ReadLineAsync()
        while ($watch.Elapsed.TotalSeconds -lt 30 -and $presses -lt 5) {
            if ($read.Wait(1000)) {
                if ($read.Result -match 'demo stimulus shown') {
                    Start-Sleep -Milliseconds (Get-Random -Minimum 280 -Maximum 380)
                    Console-Put 'vehicle/controls?brake=100'; Start-Sleep -Milliseconds 250; Console-Put 'vehicle/controls?brake=0'
                    $presses++; Write-Host "브레이크 $presses/5"
                }
                $read = $proc.StandardOutput.ReadLineAsync()
            }
            Console-Beat
        }
    } finally {
        try { Console-Put 'vehicle/controls?brake=0' } catch {}
        if (!$proc.HasExited) { $proc.Kill() }
        $http.Dispose(); & $adb -s $Serial forward --remove "tcp:$port" | Out-Null
    }
    return
}

if ($Drive) {
    # 주행이 시작되면 계속운전 화면은 시스템이 가리므로 하단 내비 아이콘을 눌러 내비를 앞으로 띄운다
    $tap = Start-Job -ScriptBlock { param($a, $s) Start-Sleep -Seconds 4; & $a -s $s shell input tap 1155 1370 } -ArgumentList $adb, $Serial
    try {
        & (Join-Path $PSScriptRoot 'drive-sim.ps1') -Normal $NormalMinutes -Drowsy $DrowsyMinutes -Severe 0 -Serial $Serial -AdbPath $adb
    } finally {
        Remove-Job $tap -Force -ErrorAction SilentlyContinue
    }
    # 정차(P) 후 계속운전 아이콘을 눌러 앱을 앞으로 → 내비 때문에 막혔던 주행 요약이 뜬다
    Start-Sleep -Seconds 2
    & $adb -s $Serial shell input tap 1520 1370
    Write-Host '주행 종료(P). 주행 요약 화면을 확인하세요.'
    return
}

if ($Build) {
    Push-Location -LiteralPath $projectPath
    try { & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -q; if ($LASTEXITCODE -ne 0) { throw 'debug 빌드 실패' } }
    finally { Pop-Location }
    Adb install -r --user $user (Join-Path $projectPath 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
    Adb install -r -t --user $user (Join-Path $projectPath 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Out-Null
    Write-Host 'debug APK·계측 APK 설치 완료'
}

# 1) 초기화 + 차량 속도 권한(없으면 실제 주행에서 피로 판단이 되지 않는다) + 화면 밝기(기본 라이트)
Adb shell pm clear --user $user $pkg | Out-Null
Adb shell pm grant --user $user $pkg android.car.permission.CAR_SPEED | Out-Null
$light = if ($Dark) { 'false' } else { 'true' }
"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=`"light_theme`" value=`"$light`" /></map>" |
    & $adb -s $Serial shell "run-as $pkg --user $user sh -c 'mkdir -p shared_prefs && cat > shared_prefs/kd_ui.xml'"
Write-Host '앱 데이터 초기화 완료(차량 속도 권한 허용)'

# 2) '평소 기록' 기준선: 첫 주행부터 피로를 판단하게 한다
Get-Content -LiteralPath (Join-Path $projectPath 'app\src\main\assets\condition\baseline_seed.json') -Raw |
    & $adb -s $Serial shell "run-as $pkg --user $user sh -c 'mkdir -p files/condition && cat > files/condition/baseline.json'"
Write-Host '평소 기록 기준선 입력 완료'

# 3) 내비 목적지: 안성휴게소(부산방향) — 다음 휴게소가 기흥휴게소(부산방향)인 경로(계측 실행 후 앱 프로세스는 다시 시작된다)
$route = & $adb -s $Serial shell am instrument -w --user $user -e demoRoute true -e class ai.pleos.playground.sample.vehicle.navigation.DemoRouteSetupTest $runner | Out-String
if ($route -notmatch 'OK \(1 test\)') { throw "내비 목적지 설정 실패. 계측 APK 설치(-Build)와 P·속도 0을 확인하세요.`n$route" }
Write-Host '내비 목적지 설정 완료: 안성휴게소(부산방향)'

# 4) 앱 실행 → 8주 시연 기록 → 음성 답변 자동 입력 켜기
Adb shell am start --user $user -n $activity | Out-Null
Start-Sleep -Seconds 6
Adb logcat -c
Adb shell am broadcast --user $user -p $pkg -a ai.pleos.trip.SEED_HISTORY | Out-Null
Adb shell am broadcast --user $user -p $pkg -a ai.pleos.voice.DEMO_ANSWERS --ez enabled true | Out-Null
Start-Sleep -Seconds 3
$seeded = & $adb -s $Serial logcat -d -s Trip:I | Select-String 'demo history seeded' | Select-Object -Last 1
if ($seeded) { Write-Host "8주 시연 기록 입력 완료 ($($seeded.Line -replace '.*seeded: ',''))" }
else { Write-Warning '시연 기록 입력 로그를 찾지 못했습니다. 홈 "이번 주"에 "시연 기록" 꼬리표가 있는지 확인하세요.' }
if (& $adb -s $Serial logcat -d -s VoiceTest:I | Select-String 'demo scripted answers enabled=true') { Write-Host '음성 답변 자동 입력 켜짐' }
else { Write-Warning '음성 답변 자동 입력이 켜졌는지 확인하지 못했습니다.' }
Write-Host '준비 완료. 앱에서 운전 조건 → 반응 체크 → 주행 도움 → 휴게소 확인 순서로 진행하세요.'

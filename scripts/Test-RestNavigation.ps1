param(
    [string]$Serial = 'emulator-5554',
    [switch]$WriteWaypoint,
    [switch]$PreviewProposal,
    [switch]$WriteReviewedServiceArea,
    [double]$Latitude,
    [double]$Longitude,
    [string]$PlaceName = '명시적 SDK 경유지 시험'
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adbPath)) { throw 'Android SDK platform-tools의 adb.exe를 찾지 못했습니다.' }
if ($Serial -notlike 'emulator-*') { throw '이 스크립트는 에뮬레이터에서만 실행합니다.' }
if (@($WriteWaypoint, $PreviewProposal, $WriteReviewedServiceArea).Where({$_}).Count -gt 1) { throw '시험 모드는 하나만 지정하세요.' }
if ($WriteWaypoint -and (!$PSBoundParameters.ContainsKey('Latitude') -or !$PSBoundParameters.ContainsKey('Longitude'))) {
    throw '-WriteWaypoint에는 -Latitude와 -Longitude를 명시해야 합니다.'
}
if ($WriteWaypoint -and ([double]::IsNaN($Latitude) -or [double]::IsInfinity($Latitude) -or
    [double]::IsNaN($Longitude) -or [double]::IsInfinity($Longitude) -or
    $Latitude -lt -90 -or $Latitude -gt 90 -or $Longitude -lt -180 -or $Longitude -gt 180 -or
    ($Latitude -eq 0 -and $Longitude -eq 0))) { throw '경유지 좌표가 유효하지 않습니다.' }

$probeRunId = [Guid]::NewGuid().ToString('N')
$savedProbeConsoleEncoding = [Console]::OutputEncoding
$savedProbeOutputEncoding = $OutputEncoding
Push-Location -LiteralPath $projectPath
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    & .\gradlew.bat :departure-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw '테스트 또는 APK 빌드 실패' }
    & $adbPath -s $Serial install --user 10 -r .\app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw '앱 APK 설치 실패' }
    & $adbPath -s $Serial install --user 10 -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw '시험 APK 설치 실패' }
    $probeClass = 'ai.pleos.playground.sample.vehicle.navigation.NaviWaypointProbeTest'
    if ($PreviewProposal -or $WriteReviewedServiceArea) { $probeClass = 'ai.pleos.playground.sample.vehicle.navigation.ReviewedServiceAreaProbeTest' }
    $probeArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '--user', '10', '-w', '-e', 'class', $probeClass)
    $probeArgs += @('-e', 'probeRunId', $probeRunId)
    if ($PreviewProposal) { $probeArgs += @('-e', 'reviewedMode', 'preview') }
    if ($WriteReviewedServiceArea) {
        Write-Host '현재 경로에서 확인 등록한 실제 휴게소를 추가하고 검증 후 시험 경유지를 삭제합니다. 음성 동의 시험은 별도입니다.'
        $probeArgs += @('-e', 'reviewedMode', 'write')
    }
    if ($WriteWaypoint) {
        Write-Host '기존 목적지에 지정한 경유지를 추가하고 재조회 후 삭제합니다. P·속도 0·경유지 없는 실제 안내를 준비하세요.'
        $encodedName = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($PlaceName))
        $probeArgs += @('-e', 'writeWaypoint', 'true', '-e', 'latitude', $Latitude.ToString([Globalization.CultureInfo]::InvariantCulture),
            '-e', 'longitude', $Longitude.ToString([Globalization.CultureInfo]::InvariantCulture), '-e', 'placeNameBase64', $encodedName)
    }
    $probeArgs += 'ai.pleos.playground.sample.vehicle.diagnostic.test/androidx.test.runner.AndroidJUnitRunner'
    $probeOutput = & $adbPath @probeArgs 2>&1
    $probeExit = $LASTEXITCODE
    $probeOutput | Write-Output
    $probeLogs = & $adbPath -s $Serial logcat -d -v time -s 'NaviWaypointProbe:I' 'NaviHelperRepository:I' '*:S'
    if ($PreviewProposal -or $WriteReviewedServiceArea) {
        $probeLogs | Where-Object { $_.Contains("runId=$probeRunId ") } | Write-Output
    } else { $probeLogs | Write-Output }
    if ($probeExit -ne 0 -or ($probeOutput -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw '시험 실행 실패 — 위 REVIEWED_PREPARE_FAILED 진단과 앱 시설 확인 저장 상태를 확인하세요.' }
} finally {
    [Console]::OutputEncoding = $savedProbeConsoleEncoding
    $OutputEncoding = $savedProbeOutputEncoding
    Pop-Location
}

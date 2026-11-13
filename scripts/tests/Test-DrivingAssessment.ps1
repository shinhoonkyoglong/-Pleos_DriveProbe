#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Import-Module (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib/DrivingTest.psm1') -Force
$assertions = 0
function Assert([string]$name, [bool]$condition) {
    if (-not $condition) { throw "FAIL: $name" }
    $script:assertions++
}
function Fixture([string]$level = 'CAUTION', [bool]$demo = $false, [int]$judged = 120000) {
    @(
        'Condition: engine started mode=REAL isReplay=false'
        'Trip: trip started demo=false'
        "Condition: level=$level score=0.75 valid=true calibrating=true"
        'Intervention: history=1 session=trip-1 level=MILD outcome=VERIFIED simulation=false details=ok'
        'Intervention: history=2 session=trip-1 level=CAUTION outcome=ANSWERED simulation=false details=ok'
        'Intervention: history=3 session=trip-1 level=DANGER outcome=ACCEPTED simulation=false details=consent only'
        "Trip: trip finished (gear P) TripRecordEntity(tripId=trip-1, maxLevel=$level, judgedMs=$judged, isDemo=$($demo.ToString().ToLowerInvariant()), isSynced=false)"
        'Trip: summary: saved'
    ) -join "`n"
}
$text = Fixture
$raw = @(
    '11-13 10:00:00.000 100 101 I Condition: engine started mode=DEMO isReplay=true'
    '11-13 10:00:01.000 200 201 I DrivingTest: RUN_MARKER'
    '11-13 10:00:01.010 100 101 I Condition: engine started mode=REAL isReplay=false'
    '11-13 10:00:01.020 999 998 E AndroidRuntime: FATAL EXCEPTION in another app'
) -join "`n"
$selected = Select-DrivingRunLog $raw '100' 'RUN_MARKER'
Assert 'session marker excludes buffered logs' ($selected -notmatch 'DEMO')
Assert 'PID filter excludes another app crash' ($selected -notmatch 'FATAL EXCEPTION')
Assert 'fresh app engine retained' ($selected -match 'mode=REAL')
Assert 'missing collector marker yields no evidence' ([string]::IsNullOrEmpty((Select-DrivingRunLog $raw '100' 'MISSING_MARKER')))
$result = Get-DrivingAssessment $text Fatigue $true -RequireInterventions
Assert 'valid production trip passes' $result.passed
Assert 'only matching session interventions' ($result.interventions.Count -eq 3)
Assert 'trip ID preserved' ($result.tripId -eq 'trip-1')
Assert 'navigation is a manual check even with consent' ($result.manualChecks[1] -match 'waypoint')
Assert 'danger target can pass' (Get-DrivingAssessment (Fixture DANGER) Danger $true -RequireInterventions).passed
Assert 'missing target fails' (-not (Get-DrivingAssessment (Fixture MILD) Fatigue $true).passed)
Assert 'danger target not implied by CAUTION' (-not (Get-DrivingAssessment $text Danger $true).passed)
Assert 'normal stays normal' (Get-DrivingAssessment (Fixture NORMAL) Normal $true).passed
Assert 'normal warning fails' (-not (Get-DrivingAssessment $text Normal $true).passed)
Assert 'invalid windows cannot meet target' (-not (Get-DrivingAssessment ($text.Replace('valid=true', 'valid=false')) Fatigue $true).passed)
Assert 'summary with no judged data fails fatigue' (-not (Get-DrivingAssessment (Fixture CAUTION $false 0) Fatigue $true).passed)
Assert 'short smoke accepts no judgment' (Get-DrivingAssessment ((Fixture NORMAL $false 0).Replace('valid=true', 'valid=false')) Smoke $true).passed
Assert 'demo saved record excluded' (-not (Get-DrivingAssessment (Fixture CAUTION $true) Fatigue $true).passed)
Assert 'unconfirmed parking fails' (-not (Get-DrivingAssessment $text Fatigue $false).passed)
Assert 'transport failure never passes' (-not (Get-DrivingAssessment $text Fatigue $true -RunError 'HTTP 500').passed)
Assert 'crash after otherwise successful evidence fails' (-not (Get-DrivingAssessment ($text + "`nAndroidRuntime: FATAL EXCEPTION") Fatigue $true).passed)
Assert 'engine replacement invalidates run' (-not (Get-DrivingAssessment ($text + "`nCondition: engine started mode=REAL isReplay=false") Fatigue $true).passed)
Assert 'old log buffer without new engine cannot pass' (-not (Get-DrivingAssessment ($text.Replace('engine started mode=REAL isReplay=false', 'old logs')) Fatigue $true).passed)
$before = 'Trip: trip finished (gear P) TripRecordEntity(tripId=old, isDemo=false)' + "`nTrip: summary: old`n"
Assert 'old evidence before fresh engine ignored' (Get-DrivingAssessment ($before + $text) Fatigue $true).passed
$wrongSession = $text.Replace('session=trip-1', 'session=other-trip')
Assert 'other trip outcome cannot satisfy required interventions' (-not (Get-DrivingAssessment $wrongSession Fatigue $true -RequireInterventions).passed)
$simulated = $text.Replace('simulation=false', 'simulation=true')
Assert 'simulated outcomes cannot satisfy required interventions' (-not (Get-DrivingAssessment $simulated Fatigue $true -RequireInterventions).passed)
$failed = $text.Replace('outcome=VERIFIED', 'outcome=FAILED')
Assert 'failed MILD not reported as control success' (-not (Get-DrivingAssessment $failed Fatigue $true -RequireInterventions).passed)
Assert 'observed interventions do not change detection-only verdict' (Get-DrivingAssessment $failed Fatigue $true).passed
Assert 'missing summary fails' (-not (Get-DrivingAssessment ($text.Replace('Trip: summary: saved', '')) Fatigue $true).passed)
Write-Output "PASS: $assertions driving assessment checks"

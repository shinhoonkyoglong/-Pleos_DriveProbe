# Shared by the simulator, the driving runner, and its offline regression checks.
Set-StrictMode -Version Latest

function Invoke-DrivingConsole {
    param([System.Net.Http.HttpClient]$Client, [uri]$BaseUri,
        [ValidateSet('GET', 'POST', 'PUT')][string]$Method, [string]$Path)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), [uri]::new($BaseUri, $Path))
    $response = $null
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Console HTTP $([int]$response.StatusCode) ($Path): $body" }
        if ($body.TrimStart().StartsWith('{')) {
            $data = $body | ConvertFrom-Json
            if ($data.PSObject.Properties['error'] -and $data.error) { throw "Console error ($Path): $($data.error)" }
            return $data
        }
        return $body
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Select-DrivingRunLog {
    param([string]$Text, [string]$ProcessId, [string]$Marker)
    if (-not $Marker -or $ProcessId -notmatch '^\d+$') { return '' }
    $start = $Text.LastIndexOf($Marker, [StringComparison]::Ordinal)
    if ($start -lt 0) { return '' }
    $pidPattern = '^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+' + [regex]::Escape($ProcessId) + '\s+\d+\s+[VDIWEF]\s'
    return (@($Text.Substring($start) -split '\r?\n' | Where-Object { $_ -match $pidPattern }) -join "`n")
}

function Get-DrivingScenario {
    param([ValidateSet('Smoke', 'Normal', 'Fatigue', 'Danger')][string]$Name)
    switch ($Name) {
        'Smoke'   { return @{ Normal = 0.25; Drowsy = 0; Severe = 0; Target = $null } }
        'Normal'  { return @{ Normal = 3; Drowsy = 0; Severe = 0; Target = 'NORMAL' } }
        'Fatigue' { return @{ Normal = 3; Drowsy = 3; Severe = 0; Target = 'CAUTION' } }
        'Danger'  { return @{ Normal = 3; Drowsy = 3; Severe = 3; Target = 'DANGER' } }
    }
}

function Get-DrivingAssessment {
    param([string]$LogText, [ValidateSet('Smoke', 'Normal', 'Fatigue', 'Danger')][string]$Scenario,
        [bool]$Parked, [string]$RunError = '', [switch]$RequireInterventions)
    $checks = [System.Collections.Generic.List[object]]::new()
    function Check([string]$name, [bool]$passed, [string]$detail) {
        $checks.Add([pscustomobject]@{ name = $name; passed = $passed; detail = $detail })
    }
    # Accept evidence only after this runner's fresh REAL engine start.
    $lines = @($LogText -split '\r?\n')
    $engineLines = @($lines | Select-String 'engine started mode=\w+ isReplay=\w+')
    $real = $engineLines.Count -eq 1 -and $engineLines[0].Line -match 'mode=REAL isReplay=false'
    Check 'realEngine' $real 'REAL engine started once; engine replacement invalidates this run'
    if ($real) { $lines = @($lines | Select-Object -Skip $engineLines[0].LineNumber) } else { $lines = @() }
    $executionDetail = if ($RunError) { $RunError } else { 'No setup/transport failure' }
    Check 'execution' ([string]::IsNullOrEmpty($RunError)) $executionDetail
    Check 'tripStarted' (@($lines | Select-String 'trip started demo=false').Count -eq 1) 'One new production trip'
    Check 'parked' $Parked 'Console readback: Park, speed 0, steering 0'
    $finished = @($lines | Select-String 'trip finished \(gear P\).*tripId=([^,]+),.*isDemo=false')
    $tripId = $null
    if ($finished.Count -eq 1) { $tripId = [regex]::Match($finished[0].Line, 'tripId=([^,]+),').Groups[1].Value }
    Check 'tripSaved' ($null -ne $tripId) 'One non-demo record saved on gear P'
    Check 'summary' (@($lines | Select-String '\bsummary:').Count -eq 1) 'Saved trip summary emitted (screen appearance is a manual check)'
    Check 'noRuntimeFailure' (@($lines | Select-String 'FATAL EXCEPTION|trip aggregation/save failed|history save failed').Count -eq 0) 'No runtime/aggregation/storage failure in captured logs'
    $windows = @($lines | Select-String 'level=(NORMAL|MILD|CAUTION|DANGER) score=\S+ valid=true')
    $levels = @($windows | ForEach-Object { [regex]::Match($_.Line, 'level=(\w+) score=').Groups[1].Value })
    if ($Scenario -ne 'Smoke') {
        Check 'validWindow' ($windows.Count -gt 0) 'At least one valid fatigue window'
        $judged = $finished.Count -eq 1 -and $finished[0].Line -match 'judgedMs=([1-9]\d*)'
        Check 'judgementSaved' $judged 'Saved record contains judgedMs > 0'
        if ($Scenario -eq 'Normal') {
            Check 'normalStability' ($levels.Count -gt 0 -and @($levels | Where-Object { $_ -ne 'NORMAL' }).Count -eq 0) 'Every observed valid window stayed NORMAL'
        } else {
            $target = (Get-DrivingScenario $Scenario).Target
            $reached = $levels -contains $target -or ($target -eq 'CAUTION' -and $levels -contains 'DANGER')
            Check 'targetLevel' $reached "Observed $target or above in a valid window; thresholds are not changed"
        }
    }
    $interventions = @()
    if ($tripId) {
        $pattern = 'history=(\d+) session=' + [regex]::Escape($tripId) + ' level=(\w+) outcome=(\w+) simulation=false'
        $interventions = @($lines | ForEach-Object {
            $match = [regex]::Match($_, $pattern)
            if ($match.Success) {
                [pscustomobject]@{ historyId = $match.Groups[1].Value; level = $match.Groups[2].Value
                    outcome = $match.Groups[3].Value; evidence = $_ }
            }
        })
    }
    if ($RequireInterventions) {
        foreach ($expected in @(@('MILD', 'VERIFIED'), @('CAUTION', 'ANSWERED'))) {
            $found = @($interventions | Where-Object { $_.level -eq $expected[0] -and $_.outcome -eq $expected[1] })
            Check "intervention$($expected[0])" ($found.Count -gt 0) "$($expected[0]): expected $($expected[1]) in this trip"
        }
        if ($Scenario -eq 'Danger') {
            Check 'dangerConsent' (@($interventions | Where-Object { $_.level -eq 'DANGER' -and $_.outcome -eq 'ACCEPTED' }).Count -gt 0) 'DANGER: voice consent ACCEPTED; navigation application needs manual verification'
        }
    }
    [pscustomobject]@{
        passed = @($checks | Where-Object { -not $_.passed }).Count -eq 0
        scenario = $Scenario; tripId = $tripId; validWindowLogCount = $windows.Count
        observedLevels = @($levels | Select-Object -Unique)
        interventionsAsserted = [bool]$RequireInterventions; interventions = $interventions
        checks = @($checks.ToArray())
        manualChecks = @('Driving screen switches on start; summary screen appears on finish',
            'If DANGER consent was given, verify the rest stop waypoint and original destination in navigation')
    }
}

Export-ModuleMember -Function Invoke-DrivingConsole, Select-DrivingRunLog, Get-DrivingScenario, Get-DrivingAssessment

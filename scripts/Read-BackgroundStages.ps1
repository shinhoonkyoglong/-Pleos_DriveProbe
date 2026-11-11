param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$lines = @(Get-Content -Encoding UTF8 (Join-Path $EvidenceDirectory 'stages-before-resume.txt'))
# The log buffer can include earlier tests: inspect only the most recently armed run.
$capture = @(Get-Content -Encoding UTF8 (Join-Path $EvidenceDirectory 'logcat.txt'))
$armed = @($capture | Where-Object { $_ -match 'ARMED run=background-probe-' } | Select-Object -Last 1)
if ($armed.Count -eq 0) { throw 'No ARMED marker: this APK may not contain the stage probe. Build/install the new Debug APK.' }
$null = $armed[0] -match 'run=(\S+)'
$runId = $Matches[1]
$current = @($lines | Where-Object { $_ -match [regex]::Escape("run=$runId") })
$stages = @('MILD','CAUTION','DANGER') | ForEach-Object {
    $stage = $_
    $level = @($current | Where-Object { $_ -match "LEVEL .* level=$stage(?:\s|$)" })
    $results = @($current | Where-Object { $_ -match "ACTION_RESULT .* level=$stage(?:\s|$)" })
    $saved = @($current | Where-Object { $_ -match "SAVED .* level=$stage(?:\s|$)" })
    $outcome = $null
    if ($results.Count -gt 0 -and $results[-1] -match 'outcome=(\S+)') { $outcome = $Matches[1] }
    if ($null -eq $outcome -and $saved.Count -gt 0 -and $saved[-1] -match 'outcome=(\S+)') { $outcome = $Matches[1] }
    [PSCustomObject]@{ level=$stage; computed=($level.Count -gt 0); actionResult=$outcome; saved=($saved.Count -gt 0) }
}
$failed = @($current | Where-Object { $_ -match 'FAILED run=|CANCELLED run=' })
$finished = @($current | Where-Object { $_ -match 'FINISHED run=' }).Count -gt 0
$status = if ($failed.Count -gt 0) { 'PROBE_ABORTED' } elseif (!$finished) { 'PROBE_INCOMPLETE' } else { 'PROBE_FINISHED_REVIEW_OUTCOMES' }
[PSCustomObject]@{ status=$status; runId=$runId; stages=@($stages); failures=$failed;
    limitation='Synthetic driving inputs and seeded context. Effects are real emulator API calls. Review outcomes, audio and readback separately; not a live-driving validation.' } |
    ConvertTo-Json -Depth 5 | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'stages-summary.json')

param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$start = [long](Get-Content (Join-Path $EvidenceDirectory 'observation-start-ms.txt'))
$end = [long](Get-Content (Join-Path $EvidenceDirectory 'observation-end-ms.txt'))
$rows = @(Get-Content (Join-Path $EvidenceDirectory 'probe-before-resume.txt') | ForEach-Object {
    if ($_ -match 'SAMPLE elapsedMs=(\d+)') {
        $time = [long]$Matches[1]
        if ($time -ge $start -and $time -le $end) {
            $fields = @{}
            foreach ($match in [regex]::Matches($_, '(\w+)=(\S+)')) { $fields[$match.Groups[1].Value] = $match.Groups[2].Value }
            [PSCustomObject]$fields
        }
    }
} | Sort-Object { [long]$_.elapsedMs })
$maxGap = 0L
$last = $start
foreach ($row in $rows) {
    $gap = [long]$row.elapsedMs - $last
    if ($gap -gt $maxGap) { $maxGap = $gap }
    $last = [long]$row.elapsedMs
}
$tail = $end - $last
if ($tail -gt $maxGap) { $maxGap = $tail }
$pids = @($rows | Select-Object -ExpandProperty pid -Unique)
$engines = @($rows | Select-Object -ExpandProperty engine -Unique)
$progress = $false
if ($rows.Count -ge 2 -and $engines.Count -eq 1 -and $pids.Count -eq 1) {
    $progress = [long]$rows[-1].ticks -gt [long]$rows[0].ticks
}
$status = if ($rows.Count -eq 0) { 'NO_SAMPLES' } elseif ($pids.Count -gt 1) { 'PROCESS_CHANGED' } `
    elseif ($engines.Count -gt 1) { 'ENGINE_CHANGED' } elseif ($maxGap -gt 5000) { 'EXECUTION_GAP' } `
    elseif (!$progress) { 'ENGINE_NOT_PROGRESSING' } else { 'CONTINUITY_OBSERVED' }
[PSCustomObject]@{ status=$status; sampleCount=$rows.Count; maxGapMs=$maxGap; tailGapMs=$tail;
    pids=$pids; engines=$engines; tickProgress=$progress;
    limitation='Does not prove live signal delivery, automatic intervention, audio, or vehicle control. Freeze requires OS evidence.' } |
    ConvertTo-Json | Tee-Object -FilePath (Join-Path $EvidenceDirectory 'summary.json')

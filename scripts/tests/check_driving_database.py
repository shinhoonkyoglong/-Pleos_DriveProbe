"""Verify one completed driving run against read-only copies of its Room databases.

python scripts/tests/check_driving_database.py artifacts/driving-tests/<run> \
    --expect MILD:VERIFIED --expect CAUTION:ANSWERED
Only matching trip records are exported. Device databases are never modified.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--expect', action='append', default=[], metavar='LEVEL:OUTCOME')
    parser.add_argument('--adb', default=str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'))
    args = parser.parse_args()
    directory = args.run.resolve()
    result = json.loads((directory / 'result.json').read_text(encoding='utf-8-sig'))
    run = json.loads((directory / 'run.json').read_text(encoding='utf-8-sig'))
    if not result.get('tripId'):
        raise SystemExit('No saved trip ID in this run; database verification cannot pass.')
    serial = run['serial']
    if not re.fullmatch(r'emulator-\d+', serial):
        raise SystemExit('An emulator serial is required.')

    def adb(*arguments):
        return subprocess.run([args.adb, '-s', serial, *arguments], capture_output=True, check=True, timeout=10).stdout

    user = adb('shell', 'am', 'get-current-user').decode().strip()
    if not user.isdigit():
        raise SystemExit('Invalid AAOS user.')
    package = 'ai.pleos.playground.sample.vehicle.diagnostic'
    checks = []

    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})

    with tempfile.TemporaryDirectory(prefix='sqlite-snapshot-', dir=directory) as temporary:
        snapshot = Path(temporary).resolve()
        assert snapshot.parent == directory  # Cleanup stays inside this run's generated directory.
        for name in ('trip_local.db', 'drive_probe_local.db'):
            for suffix in ('', '-wal'):
                remote = 'databases/' + name + suffix
                data = adb('exec-out', 'run-as', package, '--user', user, 'cat', remote)
                (snapshot / (name + suffix)).write_bytes(data)

        def query(name, sql, parameters):
            database = sqlite3.connect(snapshot / name)
            database.row_factory = sqlite3.Row
            try:
                return [dict(row) for row in database.execute(sql, parameters)]
            finally:
                database.close()

        trips = query('trip_local.db', 'SELECT * FROM trip_records WHERE tripId = ?', (result['tripId'],))
        histories = query('drive_probe_local.db',
                          'SELECT * FROM intervention_histories WHERE sessionId = ? ORDER BY id', (result['tripId'],))

    check('oneSavedTrip', len(trips) == 1)
    if not trips:
        raise SystemExit('Saved trip was not found in Room.')
    trip = trips[0]
    check('productionTrip', trip['isDemo'] == 0)
    check('nonzeroDuration', trip['durationMs'] > 0)
    if run['scenario'] != 'Smoke':
        check('judgedDataPersisted', trip['judgedMs'] > 0)
    levels = ['NORMAL', 'MILD', 'CAUTION', 'DANGER']
    target = run['plan'].get('Target')
    if target:
        check('targetLevelPersisted', levels.index(trip['maxLevel']) >= levels.index(target))
    observations = []
    for row in histories:
        details = json.loads(row['actionDetails'])
        check(f"history{row['id']}Production", details.get('simulation') is False)
        observations.append({'id': row['id'], 'level': levels[row['interventionLevel']],
                             'outcome': details.get('outcome'), 'response': row['userResponse'],
                             'navigationStatus': details.get('navigationStatus'),
                             'details': details.get('details')})
    # Current production contract counts DANGER rest recommendations only.
    rest = [row for row in observations if row['level'] == 'DANGER']
    check('restAdviceCountMatches', trip['restAdviceCount'] == len(rest))
    check('restRespondedCountMatches', trip['restRespondedCount'] == sum(bool((row['response'] or '').strip()) for row in rest))
    for expected in args.expect:
        level, outcome = expected.split(':', 1)
        check(f'expected{level}{outcome}', any(row['level'] == level and row['outcome'] == outcome for row in observations))
    evidence = {'passed': all(item['passed'] for item in checks), 'checks': checks,
                'trip': trip, 'interventions': observations}
    (directory / 'database-check.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': evidence['passed'], 'tripId': result['tripId'],
                      'maxLevel': trip['maxLevel'],
                      'outcomes': [f"{item['level']}:{item['outcome']}" for item in observations]}))
    raise SystemExit(0 if evidence['passed'] else 1)


if __name__ == '__main__':
    main()

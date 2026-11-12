# task13 — 1번 저장소 NaviHelperQuerySmokeTest 에뮬레이터 실행 로그 발췌

- 환경: emulator-5554, Pleos Connect v2.1.0(`-gpu swiftshader_indirect`), 내비 앱 `ai.umos.maps.android.navigation.app`, 에뮬레이터 시각 2026-11-10 15:34~15:36 KST, 주차(P)
- 대상: Pleos_DriveProbe `feat/departure-core`, `app/src/androidTest/.../departure/NaviHelperQuerySmokeTest.kt`
- 수집: `adb logcat -d -v time -s NaviQuerySmoke:I NaviHelperRepository:*` (줄 길이 900자에서 자름)

| 파일 | 내용 |
|---|---|
| `run1-no-route-search-permission.txt` | debug 매니페스트에 `NAVI_ROUTE_SEARCH` 선언 전. requestRoute 가 `SecurityException`, 이후 2·3번 항목 실패(목적지 없음 상태) — 테스트 설정 문제이며 조회 구현 문제 아님 |
| `run2-pass.txt` | 권한 선언 후 1회차. 전 항목 PASS(실패 0건) |
| `run3-pass.txt` | 2회차. 전 항목 PASS(실패 0건) |

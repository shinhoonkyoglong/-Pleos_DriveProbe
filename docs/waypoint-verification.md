# 실제 경유지 추가 검증 결과

> 아래는 수정 전 실패 재현 기록이다. 수정 내용과 재시험 결과는 [DANGER 경유 E2E 수정](rest-stop-e2e-fix.md)을 확인한다.

2026-11-13, `codex/driving-test-runner`, 실제 Pleos 에뮬레이터·NaviHelper 3.0.5.

**결과: 실제 경유지 항목 생성과 목적지 보존은 확인했지만, 앱의 완료 검증은 `REQUEST_UNCONFIRMED`로 실패했다.**

## 시험 준비

응답하지 않던 같은 AVD `Pleos_Connect_v2.1.0`을 콜드 부팅했다. 현재 사용자 10에 앱·시험 APK를 빌드·갱신 설치했고 P·속도 0 상태에서 수행했다. 재부팅 후 실제 안내는 없는 상태였다.

앱에 포함된 공식 시설 좌표로 안성휴게소(부산방향)를 **시험 목적지**로 안내를 시작했다. 실제 TBT에 `판교IC - 부산 방면`, 다음 시설 `기흥휴게소`가 있었고, 공식 목록의 `기흥휴게소(부산방향)`과 일치했다. 같은 프로세스에서 현재 routeId·목적지·시설 순서에 묶인 방향 확인을 저장한 뒤 생산 `RestNavigation.prepareProposal()`과 `acceptForProbe()`를 호출했다. 모의 내비·가짜 POI ID를 사용하지 않았다.

## 관측한 값

| 항목 | 실제 결과 |
| --- | --- |
| 제안 시설 | 기흥휴게소(부산방향), 경부선 |
| 요청 좌표 | 37.235125, 127.104595 — 포함된 공식 CSV 자료 |
| 실제 SDK ETA 조회 | 1,570초 |
| 추가 전 | 안성휴게소(부산방향) 안내, RECOMMENDED, 경유지 0개 |
| 실제 요청 | `requestIssued=true` |
| 추가 후 목적지 | 안성휴게소(부산방향), 좌표·옵션·실제 안내 상태 유지 |
| 추가 후 경유지 | 1개, 이름 `기흥휴게소(부산방향)` |
| SDK 경유지 기본 좌표 | `latitude=0`, `longitude=0` |
| SDK 경유지 표시 좌표 | 둘 다 `null` |
| SDK 경유지 식별 정보 | `poiId`, `poiSubId`, `poiProvider` 모두 빈 문자열 |
| 완료 판정 | `REQUEST_UNCONFIRMED`, 연속 조회 21회 모두 상태 일치 실패 |

내비 화면에도 기흥휴게소 경유 항목과 새 경로가 표시됐다. 다만 이름·항목 생성만으로 의도한 시설의 동일성까지 입증하지 않는다. 현재 `waypointMatches()`는 유효한 좌표나 정확한 native POI 식별 근거를 요구하며, 이번 반환값은 어느 쪽도 충족하지 못했다. 따라서 이 결과를 `VERIFIED_WAYPOINT` 성공으로 바꾸지 않았다. **생산 판정 조건은 변경하지 않았다.**

이는 공식 좌표만 가진 시설의 실제 쓰기·재조회에서 확인한 한계다. native POI가 있는 다른 입력도 모두 실패한다고 일반화하지 않는다. 이번 쓰기 검사는 정차 중 명시적 시험 호출이며, DANGER의 실제 음성 동의 후 추가 전체 흐름을 통과한 결과는 아니다.

## 정리 확인

생산 정리 함수도 시설 좌표·POI 식별 불가로 자동 삭제를 거부했다. 추가 요청의 시험 영수증, 같은 목적지·옵션·한 개의 동일 경유 항목을 대조한 **별도 명시적 시험 정리**로 FIRST 경유지만 제거했다. 경로 ID 갱신은 시험 운영자가 별도로 확인한 경우에만 허용했다.

정리 시험의 첫 판정은 재탐색 중 일시 상태를 너무 일찍 읽어 실패했다. 이후 독립적인 읽기 전용 재조회에서 다음을 확인했다.

- 목적지: 시험 목적지 안성휴게소(부산방향), 좌표 유지
- 안내 상태: ROUTE_GUIDING
- 경로 옵션: RECOMMENDED
- 경유지: 0개

시험 정리 코드는 경유지 0개뿐 아니라 목적지·안내 상태·옵션까지 복원될 때까지 기다리도록 보완했다. 시험 목적지는 추가 전 상태로 유지했다.

## 증거와 재현 코드

원본은 Git 제외 경로 `artifacts/driving-tests/waypoint-verification/`에 보존했다.

- `prepare.json`: 실제 TBT·공식 시설 후보·방향 근거
- `write.json`, `write.log`, `write.txt`: 실제 요청, 21회 재조회, 실패 판정
- `inspect.json`: 빈 좌표·표시 좌표·native POI 필드 재확인
- `unconfirmed-waypoint.png`: 내비의 실제 추가 후 화면
- `cleanup-write.json`, `post-cleanup-inspect.json`: 정리 과정과 최종 경유지 0개 확인

`app/src/androidTest/java/ai/pleos/playground/sample/vehicle/navigation/WaypointE2ETest.kt`에 실제 SDK 검사를 추가했다. `waypointMode=prepare/write/inspect/cleanup`와 고유 `probeRunId`를 명시해 실행하며, 인수가 없으면 일반 계측 실행에서는 건너뛴다. 쓰기에는 운영자가 실제 TBT 방향을 확인한 `directionBase64`도 필요하다. 정리 모드는 이번 시험의 캐시 영수증과 현재 상태를 다시 검사한다.

현재 공식 좌표 입력으로 `write`를 실행하면 완료 판정 실패를 그대로 재현한다. 좌표나 POI가 빠진 이름만으로 통과시키는 테스트가 아니다.

# API 키 없는 휴게소 자동 시설 연결

**후속 구현:** 사용자가 제공한 공식 CSV 2개를 앱에 포함하고 졸음쉼터도 추천 대상으로 추가했다. 이제 [공식 파일 기반 확보](public-rest-stop-catalog.md)가 우선 경로이며, 아래 내비 즐겨찾기·최근 목적지는 보조 경로다.

TBT 수신 순서로 다음 휴게소를 고르는 기능과 실제 경유지에 필요한 좌표·POI 확보는 별개다. NaviHelper 3.0.5의 TBT에는 type/distance/description만 있으며 일반 장소 검색 결과 조회 API는 없다. SDK 소스의 `NaviHelper`, `TBTInfo`, `RecentDestinationInfo`, `BookmarkInfo`를 확인했다.

이번 구현은 **전국 시설 검색이 아니다.** API 키 없이 내비의 실제 즐겨찾기·최근 목적지에서 시설을 확보한다. TBT에 `용인휴게소(강릉방향)`처럼 명시적 진행 방향이 있고 해당 이름·방향의 실제 시설이 유일하면, 별도 수동 등록 없이 실제 ETA 조회와 기존 DANGER 제안·동의·경유지 추가 흐름으로 연결한다. 이름만 `용인휴게소`이면 후보가 한 개여도 방향을 추정하지 않는다. 방향 없는 TBT, 중복 POI, 좌표/식별 정보 누락 시 기존 수동 확인을 유지한다. 졸음쉼터 제외 정책은 변경하지 않았다.

- `automaticServiceAreaPlaces`: 기존 SDK 인스턴스·콜백·Mutex로 즐겨찾기와 최근 목적지를 읽는다. 한 조회가 실패하면 다른 실제 조회 결과를 사용할 수 있지만, 둘 다 실패하면 빈 성공으로 바꾸지 않는다.
- `AutomaticServiceAreaResolver`: 방향/방면의 명시적 표기만 비교한다. 실제 POI·sub ID·provider·좌표가 동일한 중복 기록만 합치며, 다른 시설은 자동 선택하지 않는다.
- 생산 `PleosTbtServiceAreaSource`: TBT 완전성·현재 routeId 검사 후 자동 일치 또는 기존 수동 등록을 사용한다. 실제 ETA와 경로 옵션 검사를 유지한다.
- 일반 앱의 휴게소 설정: 자동 연결을 먼저 시도하고, 불가하면 직접 시설을 선택·확인하는 흐름을 유지한다.
- 자동 확보는 동의를 대신하지 않는다. 동의 전 경유지 쓰기는 없고, 동의 후 경로·시설이 바뀌면 기존 제안으로 추가하지 않는다.

출처는 `PLEOS_TBT_AUTOMATIC_NATIVE_FACILITY`, 확인 근거는 `NATIVE_TBT_EXPLICIT_DIRECTION`으로 별도 기록한다. 자동 확보와 수동 확인은 구분한다. 수동 확인은 [동일 경로 재탐색 조건](rest-stop-e2e-fix.md)을 충족할 때 새 routeId에서도 유지한다.

전국 시설을 새로 찾거나 방향 없는 TBT에서 자동 연결하려면 추가 시설 데이터와 현재 경로·진행 방향을 연결할 근거가 필요하다. 팀에는 현재 데이터/API 키가 없음을 확인했다. 한국도로공사 위치 API도 인증키가 필요하며 도로공사 시설코드를 내비 POI로 간주할 수 없다.

참고: [Pleos NaviHelper](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/intro), [도로공사 공식 OpenAPI 가이드](https://data.ex.co.kr/guidedown/openoasis_guide.pdf).

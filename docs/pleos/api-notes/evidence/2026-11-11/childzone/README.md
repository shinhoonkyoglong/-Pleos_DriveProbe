# "어린이 보호구역 우회"만 켰을 때 routeOption (2026-11-11 14:20~14:22, 에뮬레이터 시계)

- 환경: Pleos_Connect_v2.1.0 에뮬레이터(-gpu swiftshader_indirect), 기어 P, 1번 앱 feat/departure-home(26ca7fd) + androidTest `TollAvoidanceToggleCheckTest`(태그 `TollToggleCheck`)
- 조작(adb): 내비 최근 목록 서울역 → 목적지로 → 미리보기 필터 → 경로 설정 시트(두 토글 꺼짐, s1) → "어린이 보호구역 우회"만 swipe로 켬(s2) → 적용 → 후보 3개 모두 "어린이 보호구역 우회" 배지, 1번 추천 23km·**700원**(s3) → 안내 시작
- 관측(run1): `getRouteStateInfo` = `ROUTE_GUIDING`, **`routeOption=FREE`**, tollFare=null(이 프로세스가 onRouteStarted 를 받지 않음). 2번 `ConditionRouteUseCase` = **`Satisfied`** + `TOLL_FARE_NOT_OBSERVED`
- 결론 [실행 확인]: `routeOption == FREE`는 "유료도로 우회 켬"과 1:1이 아니다. 어린이 보호구역 우회만 켜도 FREE가 오고, 그 경로는 통행료 700원(유료도로 포함)이다 → 현재 판정은 이 경우를 **무료도로 충족으로 잘못 본다**
- 미확인: 두 토글을 함께 켰을 때(미리보기 자동 시작 때문에 adb 조작이 반복 실패해 중단), 어린이 보호구역 우회만 켰을 때의 `onRouteStarted` tollFare(700 예상)
- 확인 뒤 내비 안내 종료(원상태)

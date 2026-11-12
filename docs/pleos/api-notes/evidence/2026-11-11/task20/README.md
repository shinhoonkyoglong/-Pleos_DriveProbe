# #20 조건 설정 화면 — Activity 재생성 시 입력 유지 확인 (2026-11-11)

- 환경: Pleos_Connect_v2.1.0 에뮬레이터(-gpu swiftshader_indirect, 기본 GPU는 "OpenGL Core Profile not supported"로 부팅 멈춤), 1번 앱 `feat/departure-core` 4cbc0ba debug, 기어 P
- 차량 화면은 가로 고정이라 회전 대신 `settings put system font_scale 1.15` → `1.0`으로 설정 변경을 일으켰다(MainActivity에 configChanges 없음 → 재생성)
  - `cmd uimode night yes`는 앱 테마가 바뀌지 않았고 재생성 여부도 확인되지 않아 근거에서 뺐다
- 재생성 판정: `dumpsys window windows`의 MainActivity Window ID 변경 5758819 → 855311e → 33171e7

| 단계 | 고속도로 회피 | 허용 반경 | 저장 상태 | 캡처 |
|---|---|---|---|---|
| 기본값에서 편집(저장 안 함) | 켜짐 | 55 km | 아직 저장 안 됨 | 1-edited-before-recreate.jpg |
| 재생성 2회 후 | 켜짐(유지) | 55 km(유지) | 아직 저장 안 됨 | 3-after-recreate.jpg |
| [돌아가기] → 다시 진입 | 꺼짐(기본값) | 50 km(기본값) | 아직 저장 안 됨 | 4-reenter-reloaded.jpg |

결론: 재생성 때는 저장 전 입력이 유지되고, 새로 들어오면 다시 채운다(의도대로). 프로세스 종료 후 복원 경로는 확인하지 않음.

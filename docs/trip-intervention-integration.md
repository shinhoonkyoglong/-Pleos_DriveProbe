# 주행 개입 → 종료 요약·리포트 연동

2026-11-12: 외부 HTTP 전송 없이 앱 내부 Room 이력을 기존 `RestAdviceSource` 계약으로 전달한다.

## 연결 경로

1. `TripRecorder`가 주행 시작/체크포인트 복구 때 생성·복구한 `tripId`를 `InterventionModule.bindReportTrip(engine, id)`에 전달한다.
2. 같은 엔진에 연결된 자동 개입만 실행하고, `InterventionHistoryEntity.sessionId`에 이 `tripId`를 그대로 저장한다. 주행 ID를 받기 전에는 자동 개입을 시작하지 않는다.
3. 종료 시 `finishReportTrip(id)`가 연결을 해제하고 해당 주행의 진행 중 개입을 취소한다. 음성·내비 정리 및 마지막 이력 저장이 끝날 때까지 기다린다. 모드 변경으로 폐기되는 주행도 연결을 해제한다.
4. `InterventionRestAdviceSource`가 해당 ID의 이력을 읽어 `RestAdvice(count, respondedCount, REAL)`을 반환한다.
5. 기존 `TripRecordEntity.restAdviceCount`, `restRespondedCount`, `restAdviceSource`에 저장되어 종료 요약·기간 통계·가족 요약·운전 리포트에서 사용된다.

별도 엔드포인트·토큰·DB 스키마 변경은 필요하지 않다. 가족 공유와 서버 동기화는 기존 기능의 상태를 유지하며, 이 작업으로 외부 전송이 추가되지는 않는다.

## 집계 기준

- `sessionId == tripId`, 단계 2(CAUTION)·3(DANGER)인 자동 개입 이력 건수. 엔진 단계 진입 횟수나 시간 범위로 추정하지 않는다.
- JSON의 `simulation` 값이 실제 boolean `false`인 기록만 포함한다. 시연, 음성 시험, DANGER 경로 시험, 백그라운드 시험, 출처가 불명확한 예전 기록을 제외한다. 시험 출처는 `triggerReason`과 JSON `source` 둘 다 확인한다.
- 응답은 공백이 아닌 `userResponse` 인식 문장이 있는 경우에만 1회로 센다. 거절·불명확한 동의도 음성 응답이다. 무응답을 동의·성공 결과만으로 보완하지 않는다.
- DANGER에서 음성 응답 후 경로 변경이 실패·취소되어도 음성 응답은 유지한다. 동의(`consent`), 경로 결과(`navigationStatus`/`navigation`), 반응 시간은 원본 이력에 별도로 남는다.
- 실패·취소·음성 사용 불가인 자동 개입 시도도 이력이 있으면 개입 횟수에 포함된다. 이 횟수는 실제 휴식·휴게소 도착·경로 변경 성공 횟수가 아니다.
- MILD 공조는 원본 이력에 저장하지만 현재 리포트의 휴식 권고 횟수에는 포함하지 않는다.
- 개입이 없으면 `0/0/REAL`. Replay 주행은 `0/0/MOCK`으로 표시하고 실제 집계에 섞지 않는다.

## 저장과 복구

- 마지막 개입의 저장 시각이 주행 종료 시각보다 늦어도 동일 ID이므로 포함된다.
- 복구하는 주행은 체크포인트의 ID를 그대로 사용한다.
- 개입 이력 저장 실패가 감지되거나 집계/리포트 저장이 실패하면 성공한 요약을 알리지 않고 마지막 체크포인트를 남긴다. 프로세스 종료 전 저장되지 못한 개입의 본문을 복원하는 별도 영속 큐는 없다.
- 기존에 저장된 Mock 요약이나 서로 다른 UUID로 저장된 과거 개입은 시간만으로 소급 연결하지 않는다. 새 연결 이후 주행부터 적용된다.

## 확인

- `InterventionControllerTest`: 공통 ID·새 주행 쿨다운, 초기화 이후 지연 저장 대기, 저장 실패 전달.
- `TripInterventionReportTest`: 메모리 Room DB·가짜 실행 함수로 실제 DAO/어댑터/Recorder 경로 확인. 시험 제외, 응답과 동의 분리, 종료 후 지연 저장, 복구 ID 유지, 실패 시 요약 미발행.
- `DrivingHistoryIsolationTest`: 실제 주행 목록·동기화 대상에 시험 이력이 섞이지 않는지 확인.

테스트는 차량·음성·내비 SDK에 쓰지 않고, 사용자의 기존 주행·경로·설정을 수정하지 않는다.

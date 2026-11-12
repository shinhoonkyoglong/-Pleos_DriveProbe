# docs/pleos — 2번 '출발 전' 조사·계약 문서

| 경로 | 내용 |
|---|---|
| `api-notes/` | Pleos SDK·Playground 조사와 에뮬레이터 실측 근거(내비 콜백, 차량 상태, TTS/STT, 일몰 기준 등). `evidence/`는 캡처·로그 |
| `contracts/` | 서버 계약(운전자 조건 저장 API) — 1번 서버 골격이 나오면 이대로 구현 |
| `integration-guide.md` | 2번 포트 ↔ 1번 Repository·SDK 매핑 근거와 core 버전 이력. 실제 연결 방법은 `docs/departure/README.md` |

코드 쪽 이식성 검사: 저장소 루트에서 `bash scripts/check-departure.sh`

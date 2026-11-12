# 운전자 조건 API 계약 (v1 초안)

- 담당: 2번 '출발 전' (작업 #6) / 작성일: 2026-11-10 / 상태: **초안. 1번 서버 골격·인증 확정 전**
- 범위: 서버에 저장하는 "운전자 조건(조건부 면허 조건)" 하나를 **조회·저장**하는 계약. 서버 구현은 하지 않는다.
- 기준 도메인 모델: `departure-core/src/main/kotlin/com/pleos/departure/model/DriverCondition.kt`
  (`DriverCondition`, `TimeConstraint`, `RouteConstraint`, `RoutePreference`), `Place.kt`(`GeoPoint`), `DataResult.kt`(`UnavailableReason`)
- 앱 쪽 포트: `port/CommonRepositoryContract.kt`의 `DriverConditionRepository`
  (`getCondition()`, `saveCondition(condition)`, `observeCondition()`)
- Pleos SDK와는 관계가 없다(리뷰 task-01 MINOR-1). api-notes 근거가 필요한 항목은 없다.

> `TODO(1번-연동)`: 서버 기술 스택, 기본 경로(base path), 인증 방식, 운전자 식별 방식은 1번이 정한다.
> 이 문서는 그 부분을 **자리만 표시**하고 가정하지 않는다. 확정되면 이 문서의 해당 절과 "8. 1번과 맞출 결정 목록"을 갱신한다.
> "HTTP/JSON REST" 자체도 1번 서버 스택에 대한 **가정**이다(D-1). 1번이 REST가 아닌 방식(예: BaaS SDK 직접 사용)을 택하면
> 2절·2.1·5.2·5.3의 HTTP 상태·경로·헤더 열은 바뀐다. 그래도 **4절 스키마와 "결과 → `UnavailableReason`" 의미(5.3 마지막 두 열)는 유지**한다.

표기: **[본선 이후]** 가 붙은 항목은 예선 MVP에서 1번이 구현하지 않아도 되는 선택 사항이다.
예선 필수는 GET/PUT, HTTP 200/400/401/404/5xx, 오류 본문의 `code`·`message`, 4절 스키마다.

---

## 1. 전체 구조

```
[2번 유스케이스] → DriverConditionRepository (포트, core)
                     └ 1번 구현: 로컬 DB(원본 캐시) + 서버 동기화  ← 이 문서의 HTTP API를 호출
```

- 앱 유스케이스는 HTTP를 직접 부르지 않는다. 서버 호출·로컬 DB·재시도는 1번 Repository 구현 몫이다.
- 이 문서는 (1) 서버 HTTP 계약과 (2) **HTTP 결과 → `DataResult` 변환 규칙**(1번 구현이 지켜야 할 것)을 함께 정한다.

## 2. 리소스와 엔드포인트

리소스: 운전자 1명당 운전자 조건 **1개**(단일 리소스, 목록 아님).

| 동작 | 메서드·경로 | 앱 포트 대응 | 설명 |
|---|---|---|---|
| 조회 | `GET /v1/drivers/me/driver-condition` | `getCondition()`, `observeCondition()`의 초기값·갱신(내보내기 규칙은 5.4) | 인증된 운전자 본인의 조건 |
| 저장 | `PUT /v1/drivers/me/driver-condition` | `saveCondition(condition)` | **전체 교체**(멱등). 처음 저장도 PUT |

- 경로의 `/v1`은 계약 주 버전이다(6절). 그 앞의 base path(예: 호스트, `/api`)는 `TODO(1번-연동)`.
- `me`는 "인증 정보로 식별된 운전자 본인"을 뜻한다. 경로에 운전자 ID를 넣는 방식(`/v1/drivers/{driverId}/...`)을 쓸지는 1번 인증과 맞춘다(8절 D-3).
- 부분 수정(PATCH)·삭제(DELETE)는 v1에 **없다**. 앱 포트에 대응 메서드가 없고, "조건 해제"는 4개 필드를 **명시적 빈 값**으로 PUT 한다(4.3).
- `observeCondition()`(Flow)은 서버 푸시가 아니라 1번 구현이 로컬 DB 변경을 내보내는 것으로 본다. 서버 변경을 언제 다시 가져올지(앱 시작 시, 화면 진입 시 등)는 1번 동기화 정책이다(8절 D-6).

### 2.1 공통 헤더

| 헤더 | 요청/응답 | 값 | 비고 |
|---|---|---|---|
| `<인증 헤더>` | 요청 | `TODO(1번-연동)` | 이름·형식(예: Authorization 여부)을 가정하지 않는다. 1번 인증 방식에 따른다 |
| `Content-Type` | 요청(PUT)·응답 | `application/json; charset=utf-8` | |
| `Accept` | 요청 | `application/json` | |

- 비밀값(토큰 등)은 앱 코드·설정에 하드코딩하지 않는다. 발급·보관은 1번 몫이다.

## 3. 단위·형식 규칙

| 항목 | 규칙 |
|---|---|
| 인코딩 | UTF-8 JSON |
| 필드 이름 | camelCase. **Kotlin 도메인 모델 프로퍼티 이름과 같게** 둔다(어댑터 변환을 1:1로 유지) |
| 좌표 | **WGS84, 소수 도(decimal degrees)**. JSON number. 위도 먼저 쓰지 않고 항상 `latitude`/`longitude` 이름으로 구분(순서에 의존하지 않음, `Place.kt` KDoc의 NaviHelper 인자 순서 변경 이력 참고) |
| 좌표 정밀도 | **[본선 이후]** 서버는 받은 값을 소수 7자리(약 1cm) 이상 정밀도로 보존해 그대로 돌려준다 |
| 거리 | **미터(m)**, JSON number(소수 허용). km 아님 |
| 시각 | **ISO-8601 확장 형식, UTC 오프셋 포함**(예: `2026-11-10T06:30:00Z`). 서버는 UTC(`Z`)로 내보낸다. 오프셋 없는 로컬 시각은 쓰지 않는다 |
| enum | 대문자 문자열, 도메인 enum 상수 이름과 같다(`NO_NIGHT_DRIVING` 등) |
| 집합(Set) | JSON 배열. **순서는 의미 없음**. 중복은 서버가 하나로 합쳐 저장한다. 응답 배열은 enum 이름 오름차순 |
| null·생략 | PUT은 전체 교체다. 그래서 v1의 **최상위 필드 4개는 PUT에서 모두 필수**(키가 반드시 있어야 함)다. 생략하면 `VALIDATION_FAILED`/`REQUIRED`로 거부한다(V-0). 값으로 `null`을 허용하는 것은 `radiusCenter`·`allowedRadiusMeters`뿐이다. 직렬화 버그나 빈 본문 `{}` 하나로 야간 금지 등 모든 제한이 조용히 지워지는 것을 막기 위해서다(리뷰 task-06 MINOR-3). 초판이므로 6절 "선택→필수 = 호환 깨짐"에는 해당하지 않는다 |
| 숫자 | `NaN`·`Infinity`는 JSON에 없으므로 받지 않는다. 문자열 숫자(`"10000"`)는 거부한다 |

## 4. 데이터 형식

### 4.1 요청 본문 (PUT) = `DriverCondition`

```json
{
  "timeConstraints": ["NO_NIGHT_DRIVING"],
  "routePreference": {
    "constraints": ["AVOID_HIGHWAY", "PREFER_TOLL_FREE"]
  },
  "radiusCenter": {
    "latitude": 37.5563,
    "longitude": 126.9236
  },
  "allowedRadiusMeters": 10000.0
}
```

### 4.2 필드 표

PUT 기준 "필수" = 키가 있어야 함, "null 허용" = 값으로 `null`을 받음. 응답은 항상 4개 키를 모두 채워 보낸다. "모델 기본값"은 도메인 모델 생성자 기본값으로 빈 값의 의미를 설명할 뿐, 생략을 허용한다는 뜻이 아니다.

| JSON 필드 | 타입 | 단위 | 필수(PUT) | null 허용 | 허용 범위·값 | 모델 기본값(= 빈 값의 의미) | 도메인 모델 매핑 |
|---|---|---|---|---|---|---|---|
| `timeConstraints` | array of string | - | **필수** | 아니오 | `NO_NIGHT_DRIVING` | `[]` | `DriverCondition.timeConstraints: Set<TimeConstraint>` |
| `routePreference` | object | - | **필수** | 아니오 | 아래 하위 필드 | `{"constraints": []}` | `DriverCondition.routePreference: RoutePreference` (기본 `RoutePreference.NONE`) |
| `routePreference.constraints` | array of string | - | **필수** | 아니오 | `PREFER_TOLL_FREE`, `AVOID_HIGHWAY` | `[]` | `RoutePreference.constraints: Set<RouteConstraint>` |
| `radiusCenter` | object \| null | WGS84 | **필수** | 예 | 아래 하위 필드 | `null` (반경 확인 "확인 불가") | `DriverCondition.radiusCenter: GeoPoint?` |
| `radiusCenter.latitude` | number | 소수 도 | `radiusCenter`가 객체면 **필수** | 아니오 | -90.0 ~ 90.0 (양끝 포함) | - | `GeoPoint.latitude: Double` |
| `radiusCenter.longitude` | number | 소수 도 | `radiusCenter`가 객체면 **필수** | 아니오 | -180.0 ~ 180.0 (양끝 포함) | - | `GeoPoint.longitude: Double` |
| `allowedRadiusMeters` | number \| null | m | **필수** | 예 | `> 0` (0 제외), 상한 없음(9절 차이-3) | `null` (앱이 `DepartureConfig.defaultAllowedRadiusMeters` 10,000m 사용) | `DriverCondition.allowedRadiusMeters: Double?` |

응답 전용(요청에 넣으면 무시):

| JSON 필드 | 타입 | 단위 | 설명 | 도메인 모델 매핑 |
|---|---|---|---|---|
| `updatedAt` | string | ISO-8601 UTC | 서버가 마지막으로 저장한 시각 | **없음** (9절 차이-1) |

직렬화하지 않는 모델 멤버: `DriverCondition.noNightDriving`(파생 getter), `effectiveRadiusMeters()`(함수), `RoutePreference.isEmpty`(파생). 이 값들은 JSON에 넣지 않는다.

enum 값 의미(모델 KDoc 요약):

| 값 | 도메인 상수 | 의미 | 앱 동작 |
|---|---|---|---|
| `NO_NIGHT_DRIVING` | `TimeConstraint.NO_NIGHT_DRIVING` | 일몰 이후 운전 금지 | 출발 마감 계산(일몰 기준, `DepartureConfig.nightStartReference`) |
| `PREFER_TOLL_FREE` | `RouteConstraint.PREFER_TOLL_FREE` | 무료도로 선호(유료도로 회피) | 경로 옵션 `FREE` |
| `AVOID_HIGHWAY` | `RouteConstraint.AVOID_HIGHWAY` | 고속도로 회피 | SDK에 회피 옵션이 없어 근사 적용(`Guarantee.APPROXIMATE`). 서버는 의미 해석 없이 저장만 한다 |

### 4.3 예시

조회 성공 `200 OK`:
```json
{
  "condition": {
    "timeConstraints": ["NO_NIGHT_DRIVING"],
    "routePreference": { "constraints": ["PREFER_TOLL_FREE"] },
    "radiusCenter": { "latitude": 37.5563, "longitude": 126.9236 },
    "allowedRadiusMeters": 10000.0
  },
  "updatedAt": "2026-11-10T06:30:00Z"
}
```
- 응답은 `condition`(= 도메인 모델 그대로)과 메타데이터(`updatedAt`)를 분리한다. 1번 어댑터는 `condition`만 `DriverCondition`으로 바꾸면 된다.
- 응답의 `condition`은 **기본값까지 채운 완전한 형태**로 내보낸다(생략 없음, `radiusCenter`·`allowedRadiusMeters`는 없으면 `null`).

저장 성공 `200 OK`: 본문은 조회 응답과 같은 형식으로 **저장된 값**을 돌려준다(포트 `saveCondition`이 "저장된 값"을 반환하므로). 처음 저장도 `200`(201 구분 안 함, 8절 D-7).

조건 해제(모든 조건 없음) 저장 요청:
```json
{ "timeConstraints": [], "routePreference": { "constraints": [] }, "radiusCenter": null, "allowedRadiusMeters": null }
```
해제는 **이 형태(4개 키 모두 명시)로만** 한다. `{}`나 키를 빠뜨린 본문은 V-0으로 거부된다. 해제는 "미설정(404)"과 다르다. 해제 후 조회하면 `200`과 빈 조건이 온다.

반경 값만 있고 중심이 없는 조건(`radiusCenter: null`, `allowedRadiusMeters: 5000`)은 **허용**한다. 모델이 허용하기 때문이다. 앱은 이때 반경 확인을 "확인 불가"로 표시한다(9절 차이-4).

## 5. 검증 규칙과 오류

### 5.1 서버 검증 (PUT)

| # | 규칙 | 실패 시 `code` | `fieldErrors[].field` 예 |
|---|---|---|---|
| V-0 | 최상위 4개 키(`timeConstraints`, `routePreference`, `radiusCenter`, `allowedRadiusMeters`)와 `routePreference.constraints`가 모두 있음. `timeConstraints`·`routePreference`·`constraints`는 null 아님 | `VALIDATION_FAILED` / reason `REQUIRED` | `timeConstraints` |
| V-1 | 본문이 JSON 객체 | `MALFORMED_JSON` | - |
| V-2 | 필드 타입이 4.2 표와 일치(배열·객체·숫자) | `VALIDATION_FAILED` / reason `TYPE_MISMATCH` | `allowedRadiusMeters` |
| V-3 | enum 문자열이 알려진 값 | `VALIDATION_FAILED` / reason `UNKNOWN_ENUM` | `timeConstraints[0]` |
| V-4 | `radiusCenter`가 객체면 `latitude`, `longitude` 둘 다 있음 | `VALIDATION_FAILED` / reason `REQUIRED` | `radiusCenter.longitude` |
| V-5 | 위도 -90~90, 경도 -180~180 | `VALIDATION_FAILED` / reason `OUT_OF_RANGE` | `radiusCenter.latitude` |
| V-6 | `allowedRadiusMeters` > 0 | `VALIDATION_FAILED` / reason `OUT_OF_RANGE` | `allowedRadiusMeters` |
| V-7 | 알 수 없는 최상위 필드 | **오류 아님. 무시**(6절 호환 규칙) | - |

앱 쪽에서는 V-5·V-6을 도메인 모델 `init`(`GeoPoint`, `DriverCondition`)이, V-3·V-4를 Kotlin 타입(enum, non-null 생성자 인자)이, V-0을 4개 키를 항상 채우는 DTO 직렬화가 보장한다. 앱은 모델 생성·직렬화 단계에서 이미 걸러지므로 정상 앱에서는 서버 검증 실패가 나오지 않아야 한다. 나오면 앱·서버 버전 불일치 신호로 본다.

### 5.2 오류 응답 형식

모든 4xx/5xx는 같은 형식이다.
```json
{
  "code": "VALIDATION_FAILED",
  "message": "allowedRadiusMeters must be > 0",
  "fieldErrors": [
    { "field": "allowedRadiusMeters", "reason": "OUT_OF_RANGE" }
  ],
  "traceId": "c0ffee-1234"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `code` | string | 필수 | 아래 표의 기계용 코드. 앱 분기는 HTTP 상태가 아니라 이 값으로 한다(상태는 보조) |
| `message` | string | 필수 | 개발자용 설명. **화면 문구로 쓰지 않는다**(`DataResult.Unavailable.detail`로만 전달) |
| `fieldErrors` | array | 선택 | 검증 실패 시 필드별 사유. `field`는 JSON 경로(점·대괄호 표기), `reason`은 `REQUIRED`/`TYPE_MISMATCH`/`UNKNOWN_ENUM`/`OUT_OF_RANGE` |
| `traceId` | string | 선택 | **[본선 이후]** 서버 로그 추적용. 형식은 1번 서버 골격에 따름 |

### 5.3 오류 코드와 앱 처리 (`DataResult` 매핑)

1번 Repository 구현은 아래처럼 `DataResult.Unavailable(reason, detail)`로 바꾼다. 예외로 던지지 않는다(CommonRepositoryContract.kt 공통 규칙, 코루틴 취소만 예외 전파).

| 상황 | HTTP | `code` | `UnavailableReason` | 재시도 | 앱(2번) 처리 |
|---|---|---|---|---|---|
| 저장된 조건 없음(미설정) — **HTTP 404이면서 본문 `code == CONDITION_NOT_SET`일 때만** | 404 | `CONDITION_NOT_SET` | `NO_DATA` | 안 함 | "조건 미설정" 안내. 제한 없음으로 간주하지 않고 출발 마감은 계산하지 않음(8절 D-9) |
| 그 밖의 404(본문 없음, 해석 불가, `code`가 없거나 다른 값 — base path 오류·게이트웨이 기본 404 등) | 404 | 없음/기타 | `SERVICE_ERROR` | 가능 | 조회 실패로 처리: 로컬 값 유지(D-9 조회 실패 경로) |
| 알 수 없는 `code`(이 표에 없는 값, HTTP 상태 무관) | 4xx/5xx | 기타 | `SERVICE_ERROR` | 안 함 | 조회 실패로 처리. 로그 |
| 응답 `condition`에 모르는 enum 값이 있음(6절) | 200 | - | `SERVICE_ERROR` | 안 함 | 모르는 값을 **건너뛰지 않는다**. 조회 실패로 처리 → 마지막 로컬 값, 없으면 야간 제한 있음으로 계산 + "조건 확인 불가" 표시(D-9). 이 응답은 로컬 DB에 쓰지 않는다 |
| 요청 본문 오류(검증 실패) | 400 | `VALIDATION_FAILED`, `MALFORMED_JSON` | `INVALID_REQUEST` | 안 함 | "저장하지 못했어요" 표시, 입력값 유지. `fieldErrors`는 로그로만 |
| **[본선 이후]** 지원하지 않는 Content-Type | 415 | `UNSUPPORTED_MEDIA_TYPE` | `INVALID_REQUEST` | 안 함 | 위와 같음(앱 버그) |
| 인증 없음·만료 | 401 | `UNAUTHENTICATED` | `PERMISSION_DENIED` | 1번 인증 갱신 후 1회(1번 몫). 그래도 실패하면 그대로 | "다시 로그인이 필요해요" 계열 안내(로그인 화면은 1번) |
| 권한 없음 | 403 | `FORBIDDEN` | `PERMISSION_DENIED` | 안 함 | 위와 같음 |
| **[본선 이후]** 동시 수정 충돌(예약, v1 미사용) | 409 | `CONFLICT` | `SERVICE_ERROR` | 다시 조회 후 | 8절 D-5 확정 시 사용 |
| **[본선 이후]** 요청 과다 | 429 | `RATE_LIMITED` | `SERVICE_ERROR` | `Retry-After` 후 | 로컬 값 유지 |
| 서버 오류 | 5xx | `INTERNAL_ERROR` 등 | `SERVICE_ERROR` | 가능(백오프, 1번 몫) | 로컬 값 유지 |
| 응답 대기 시간 초과 | - | - | `TIMEOUT` | 가능 | 로컬 값 유지(`TIMEOUT` KDoc은 "콜백 또는 서버 응답" — 9절 차이-10 해소) |
| 연결 실패(오프라인, DNS 등) | - | - | `SERVICE_ERROR` | 가능 | 로컬 값 유지(9절 차이-5: 전용 사유 없음) |
| 응답 본문 해석 실패(형식 위반) | 2xx | - | `SERVICE_ERROR` | 안 함 | 로그. 로컬 값 유지 |
| 서버 미연동(1번 미구현, 설정 없음) | - | - | `NOT_SUPPORTED` | 안 함 | Fake 사용 중이면 해당 없음 |

**로컬 DB 우선 규칙(1번 구현에 권장, 8절 D-6에서 확정)**
- `NO_DATA`/`SERVICE_ERROR` 판정은 위 표 그대로 한다. 특히 `NO_DATA`는 **404 + `code == CONDITION_NOT_SET`일 때만**이다. `NO_DATA`는 "조건 미설정 → 마감 계산 안 함"이라 조회 실패보다 **덜 보수적인** 결과이기 때문이다(리뷰 task-06 MAJOR-2).
- `getCondition()`: 로컬 DB에 값이 있으면 네트워크 실패(TIMEOUT/SERVICE_ERROR)여도 **로컬 값을 `Available`로** 돌려준다. 로컬도 없을 때만 위 표의 `Unavailable`을 돌려준다.
- 서버가 **HTTP 404이면서 본문 `code == CONDITION_NOT_SET`**이면 로컬 캐시와 상관없이 `NO_DATA`로 본다(서버가 원본). 그 밖의 404는 이 규칙에 해당하지 않으며 로컬 값을 지우지 않는다. 단, 로컬에 "아직 서버로 못 보낸 저장"이 있으면 그 값을 우선한다.
- `saveCondition()`: 서버 저장 성공 응답의 `condition`을 로컬 DB에 쓰고 그 값을 `Available`로 돌려준다. 오프라인 저장(로컬만 저장, 나중에 동기화)을 허용할지는 D-6.

### 5.4 `observeCondition()` 내보내기 규칙

포트는 `observeCondition(): Flow<DataResult<DriverCondition?>>`다(감독관 결정 11/10, #5 재작업 1회차에서 반영 — 9절 차이-9 해소). 구독하면 현재 상태를 먼저 1회 내보낸다. 1번 구현은 다음을 지킨다.
- `Available(condition)`: 저장된 조건. 해제 저장(4.3) 후에는 `Available(null)`이 아니라 `Available(DriverCondition())`을 내보낸다(해제 ≠ 미설정).
- **`Available(null)`은 "확인된 미설정"일 때만 내보낸다.** 확인된 미설정 = 서버가 404 + `CONDITION_NOT_SET`을 돌려줬고 로컬에 미전송 저장도 없음.
- 조회 실패(TIMEOUT, SERVICE_ERROR, PERMISSION_DENIED, 모르는 enum 등)는 **`Unavailable(reason)`으로 내보낸다.** `Available(null)`로 내보내지 않는다. 로컬 값이 있으면 5.3 "로컬 DB 우선 규칙"대로 로컬 값을 `Available`로 내보내도 된다.
- 화면(#7)은 `Unavailable`이면 D-9 "조회 실패 → 야간 제한 있음으로 계산 + 조건 확인 불가 표시"를 적용한다.

## 6. 버전·호환 규칙

- 주 버전은 경로(`/v1`)로 표시한다. **하위 호환이 깨지는 변경만** 주 버전을 올린다(`/v2`). **[본선 이후]** 서버는 직전 주 버전을 최소 1개 앱 배포 주기 동안 같이 제공한다(기간은 D-8).
- **하위 호환(같은 v1 안에서 허용)**
  - 응답에 선택 필드 추가 → 앱은 **모르는 필드를 무시**해야 한다(1번 JSON 파서 설정: unknown key 무시).
  - 요청에 **새** 필드 추가 → 새 필드는 선택(생략 시 기존 동작과 같은 기본값)으로만 추가한다. v1의 4개 필드는 계속 필수다. 서버는 모르는 요청 필드를 무시한다(V-7).
- **호환 깨짐(주 버전 올림)**: 필드 이름 변경·삭제, 타입 변경, **단위 변경**(m → km 등), **enum 값 추가**, 기존 enum 값의 의미 변경·삭제, 선택 필드를 필수로 변경, 기본값 변경, 오류 `code` 의미 변경.
- **enum 값 추가는 v1 안에서 하지 않는다(감독관 결정 11/10, 리뷰 task-06 MAJOR-1 대안 (a)).** 이유: PUT은 전체 교체라, 구 앱이 모르는 값을 빼고 다시 저장하면 서버 원본에서 **운전 제한이 조용히 지워진다**. 새 `TimeConstraint`·`RouteConstraint`가 필요하면 `/v2`로 낸다. v1 서버는 v1 enum 값만 저장·응답하므로 **v1 안에서는 "구 앱이 모르는 값을 지운 채 PUT" 하는 일이 생기지 않는다.** 서버가 PUT에 없는 값을 보존하는 방식(대안 (b))은 전체 교체 의미가 흐려져 채택하지 않았다.
- 읽기 쪽 방어: 그래도 응답에 모르는 enum 값이 오면(서버 버그·잘못된 배포) 앱은 **건너뛰지 않고** 조회 실패(`SERVICE_ERROR`)로 처리한다(5.3). 결과는 D-9의 조회 실패 경로(마지막 로컬 값, 없으면 야간 제한 가정)다. 이 상태의 조건으로는 PUT 하지 않는다.
- 도메인 모델(`DriverCondition`)을 바꾸면 같은 작업에서 이 문서의 4.2 표와 9절을 갱신한다.

- [본선 이후] v1·v2 병행 운영 시 v1 PUT이 v2 전용 제한을 지우는지(보존 여부)는 /v2 설계 때 정한다(리뷰 task-06 재검토 MINOR-B).

## 7. 앱 쪽 구현 메모 (1번 어댑터 작성자용)

- core 도메인 모델에는 직렬화 애노테이션을 달지 않는다(core는 순수 Kotlin, 외부 의존은 코루틴만). JSON DTO와 DTO ↔ `DriverCondition` 변환은 1번 쪽(또는 그 위 어댑터)에 둔다.
- DTO → 모델 변환 중 `GeoPoint`/`DriverCondition`의 `init`(`require`)이 예외를 낼 수 있다(서버가 검증을 빠뜨린 경우). 이 예외는 잡아서 `Unavailable(SERVICE_ERROR, detail)`로 바꾼다.
- 저장 요청 전 앱 검증은 모델 생성으로 충분하다(별도 검증 코드를 두지 않는다).
- 서버 호출 타임아웃 값은 `DepartureConfig`에 없다. 서버 통신은 1번 공통 설정 몫이다(필요해지면 감독관에게 제안).

## 8. 1번과 맞출 결정 목록 (서버 스택 미정 전제)

| # | 결정 항목 | 이 문서의 잠정안 | 1번 확정 시 바뀌는 곳 |
|---|---|---|---|
| D-1 | 서버 기술 스택·base path·호스트. **REST 여부 포함** | 미정. HTTP/JSON REST는 가정이며(경로는 `/v1/...`만 정함), REST가 아니어도 4절 스키마와 5.3 `UnavailableReason` 의미는 유지 | 2절, 2.1, 5.2, 5.3 HTTP 열 |
| D-2 | 인증 방식·헤더 이름·토큰 갱신 | 미정(헤더 자리만) | 2.1, 5.3 401 행 |
| D-3 | 운전자 식별: `me`(인증에서 식별) vs 경로 `driverId` | `me` | 2절 경로 |
| D-4 | 보호자·가족이 대신 설정하는 경우(다른 운전자 조건 접근 권한) | v1 범위 밖. 본인만 | 403 의미, 경로 |
| D-5 | 동시 수정 제어: last-write-wins vs `ETag`/`If-Match`(409) | v1은 last-write-wins | 5.3 409 행, 응답 헤더 |
| D-6 | 로컬 DB 우선·오프라인 저장·동기화 시점(앱 시작/화면 진입/주기) | 5.3 "로컬 DB 우선 규칙" 권장안 | 5.3, `observeCondition()` 갱신 시점 |
| D-7 | 처음 저장 시 `201 Created` 구분 여부 | 구분 안 함(항상 200) | 4.3 |
| D-8 | **[본선 이후]** 구 버전 API 유지 기간, 앱 최소 버전 강제 방법(헤더 등) | 미정. enum 추가를 `/v2`로 돌렸으므로(6절) v1 안에서는 최소 버전 강제가 필요 없다 | 6절 |
| D-9 | 조건 미설정(`NO_DATA`)·조회 실패 시 2번 정책 | **결정됨(감독관 11/10, plan.md, task-04b #7-4)**: 조건 **미설정(NO_DATA)** → "조건 미설정" 안내(제한 없음으로 간주하지 않음), 마감은 계산하지 않음. **조회 실패** → 마지막 로컬 값 사용, 그것도 없으면 보수적으로 야간 제한 있음으로 계산하고 화면에 "조건 확인 불가 — 야간 제한 기준" 표시. 서버 계약에는 영향 없음. 이 구분 때문에 5.3의 NO_DATA 한정(404 + `CONDITION_NOT_SET`)과 5.4 규칙이 필요하다 | 2번 유스케이스·#7 화면 |
| D-10 | 오류 응답 공통 형식(5.2)을 1번 서버 전체 형식에 맞출지 | 5.2 형식 제안. 1번 공통 형식이 있으면 그쪽을 따르고 `code` 값만 유지 | 5.2 |
| D-11 | `allowedRadiusMeters` 상한(예: 100km) 서버 검증 추가 여부 | 없음(모델과 일치) | 4.2, V-6, 모델 |
| D-12 | 조건의 출처(운전자 직접 입력 vs 면허 정보 연동) | 운전자/앱 입력만 가정 | 데이터 모델 전체 |

## 9. 모델과 차이

`.kt` 파일은 이 작업에서 수정하지 않았다(다른 작업이 core를 동시에 수정 중). 아래는 계약 작성 중 발견한 어긋남이다. 반영 여부는 감독관이 정한다.

- **차이-1 `updatedAt`(서버 메타데이터)에 대응하는 모델 필드 없음.** 응답에만 있고 `DriverCondition`으로 옮겨지지 않는다. 지금 2번 기능에는 필요 없다. 화면에 "마지막 저장 시각"을 보여 주거나 D-5(ETag/버전)를 도입하면 모델 또는 별도 래퍼 타입(예: 조건 + 메타데이터)이 필요하다.
- **차이-2 모르는 enum 값을 담을 수 없다.** `timeConstraints`/`routePreference.constraints`는 Kotlin enum Set이다. 모르는 값을 건너뛰고 다시 PUT 하면 제한이 지워지는 문제가 있었다. **계약으로 해소**: enum 추가는 `/v2`(6절)라 v1 안에서는 생기지 않고, 그래도 오면 앱은 조회 실패로 처리하고 그 값으로 PUT 하지 않는다(5.3). 모델 변경은 필요 없다.
- **차이-3 반경 상한이 없다.** 모델 `init`은 `allowedRadiusMeters > 0`만 검사한다. 1e9 m 같은 값도 통과한다. 서버 검증도 모델과 맞추려고 상한을 두지 않았다(D-11).
- **차이-4 "반경 값은 있는데 중심이 없음"을 모델이 허용한다.** `radiusCenter == null && allowedRadiusMeters != null`. 이 경우 반경 확인은 항상 "확인 불가"라 저장된 반경이 쓸모없다. 계약은 모델을 따라 허용했다. 막으려면 모델 `init`과 서버 검증(V-4 확장)을 같이 바꾼다.
- **차이-5 `UnavailableReason`에 네트워크 연결 실패 전용 사유가 없다.** 오프라인·DNS 실패를 `SERVICE_ERROR`로 묶었다(5.3). "인터넷 연결을 확인하세요" 같은 별도 문구가 필요하면 사유 추가가 필요하다. 마찬가지로 401(재로그인 필요)과 403(권한 없음)이 둘 다 `PERMISSION_DENIED`라 화면에서 구분할 수 없다.
- **차이-6 "로컬에만 저장됨(동기화 대기)"을 표현할 수 없다.** `saveCondition`의 결과는 `Available`/`Unavailable` 둘뿐이다. D-6에서 오프라인 저장을 허용하면 결과 상태가 하나 더 필요할 수 있다.
- **차이-7 `radiusCenter`에 이름이 없다.** `GeoPoint`는 좌표만 있다. 화면에서 "집 기준 10km"처럼 기준점 이름을 보여 주려면 `Place`처럼 `displayName`이 필요하다. 지금은 계약에 넣지 않았다.
- **차이-8 (해소, #5 재작업 1회차)** `DriverConditionRepository.saveCondition` KDoc의 TODO "서버 검증 실패 시 INVALID_REQUEST 로 줄일지"를 정리했다. KDoc에 "서버 검증 실패(400·415) → INVALID_REQUEST(5.3 확정)"를 적었다. integration-guide 매핑표도 "확정(계약 5.3)"이다.
- **차이-9 (해소, #5 재작업 1회차 — 포트를 `Flow<DataResult<DriverCondition?>>`로 바꿈. Fake·테스트·integration-guide 함께 수정, 5.4 갱신. 아래는 당시 기록)** `observeCondition(): Flow<DriverCondition?>`는 조회 실패와 미설정을 구분할 수 없다. 실패 시 `null`을 내보내면 화면이 미설정(NO_DATA)으로 오해해 D-9 "조회 실패 → 야간 제한 가정"을 적용하지 못한다. 지금은 5.4 규칙(`null` = 확인된 미설정만, 실패로는 `null` 금지)으로 막는다. **감독관 결정(11/10)**: #5 완료 후 포트를 `Flow<DataResult<DriverCondition?>>`로 바꾼다(`CommonRepositoryContract.kt`, Fake, integration-guide 함께 수정). 이 작업에서는 `.kt`를 고치지 않았다. 지금 포트 KDoc("없으면 null 1회")과 5.4("실패면 아무것도 내보내지 않음")가 다르므로, 포트 변경 전까지 화면(#7)은 `observeCondition().first()` 대신 `getCondition()`을 쓴다(리뷰 task-06 재검토 MINOR-A).
- **차이-10 (해소, #5 재작업 1회차 — KDoc을 "콜백 또는 서버 응답이 제한 시간 안에 오지 않았다"로 넓힘. 아래는 당시 기록)** `UnavailableReason.TIMEOUT` KDoc이 SDK 콜백 전용으로 쓰여 있다("요청은 보냈지만 콜백이 제한 시간 안에 오지 않았다 (DepartureConfig 의 콜백 타임아웃)"). 이 문서는 서버 응답 대기 초과도 `TIMEOUT`으로 매핑한다(5.3). core 수정이 풀리면 차이-8과 함께 KDoc을 "콜백 또는 서버 응답이 제한 시간 안에 오지 않았다"로 넓힌다(리뷰 task-06 MINOR-6).

package com.pleos.departure.model

/**
 * 외부(내비·차량·저장소)에서 값을 가져온 결과. "값이 있음" 또는 "사용 불가/모름" 둘 중 하나다.
 *
 * 왜 예외가 아니라 결과 타입인가:
 * - Pleos SDK는 차량·환경에 따라 아예 없거나(내비 미탑재), 콜백이 오지 않을 수 있다
 *   (requestTrip "may be delivered" — api-notes/eta-location.md §A). 즉 "모름"은 오류가 아니라 **정상 경로**다.
 * - 백엔드로 치면 Optional + 실패 사유. 호출부는 `when`으로 두 경우를 모두 처리하도록 컴파일러가 강제한다.
 */
sealed interface DataResult<out T> {

    /** 값을 얻었다. */
    data class Available<out T>(val value: T) : DataResult<T>

    /**
     * 값을 얻지 못했다. 유스케이스는 이 경우의 대체 흐름(기본 이동 시간, "확인 불가" 표시 등)을 가진다.
     * @property detail 로그·디버그용 짧은 설명. 화면 문구로 직접 쓰지 않는다.
     */
    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String? = null,
    ) : DataResult<Nothing>

    /** 값이 있으면 값, 없으면 null. 사유가 필요 없을 때만 쓴다. */
    fun getOrNull(): T? = (this as? Available)?.value
}

/** 값을 얻지 못한 이유. UI 문구와 대체 흐름 선택에 쓴다. SDK 오류 코드를 그대로 노출하지 않고 이 정도로 좁힌다. */
enum class UnavailableReason {
    /** 기능 자체가 없다: 내비 미탑재 차량, SDK '준비 중', 1번 미연동 등. 재시도해도 소용없다. */
    NOT_SUPPORTED,

    /**
     * 요청은 보냈지만 콜백 또는 서버 응답이 제한 시간 안에 오지 않았다
     * (SDK 콜백은 DepartureConfig 의 콜백 타임아웃, 서버 응답은 1번 구현의 타임아웃 — contracts/driver-condition-api.md 5.3).
     */
    TIMEOUT,

    /** 응답은 왔지만 유효한 값이 없다: 위치 null, 저장된 운전자 조건 없음 등. */
    NO_DATA,

    /** 상대 쪽 처리 실패: NaviErrorCode.CAUSE_SEARCH_TRIP / CAUSE_SEARCH_ROUTE, 서버 5xx 등. 재시도 가능. */
    SERVICE_ERROR,

    /** 권한이 없어 호출할 수 없다. */
    PERMISSION_DENIED,

    /** 요청 값이 잘못됐다: 좌표 범위 밖, 필수 POI 정보 없음 등. */
    INVALID_REQUEST,
}

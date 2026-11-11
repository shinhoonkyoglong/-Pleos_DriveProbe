package ai.pleos.playground.sample.vehicle.departure

/**
 * 운전자 조건 설정 화면의 기본값과 입력 한도 — 이 값들은 여기 한 곳에만 둔다(2번 core 의 DepartureConfig 는 바꾸지 않는다).
 *
 * - 기본값은 저장된 조건이 없을 때 화면에 **미리 채워 보여 주기만** 한다. 사용자가 [저장]을 누르기 전에는 DB 에 쓰지 않는다
 *   (저장 안 됨 = "조건 미설정", 계약 driver-condition-api.md §8 D-9).
 * - 반경 중심의 기본값은 고정 좌표가 아니라 화면을 열 때 읽은 내비 현재 위치다(읽지 못하면 비워 둔다).
 */
object DriverConditionDefaults {
    /** 야간(일몰 이후) 운전 금지 — 기본 켬 */
    const val NO_NIGHT_DRIVING = true

    /** 무료도로 선호 — 기본 켬 */
    const val PREFER_TOLL_FREE = true

    /** 고속도로 회피 — 기본 끔 */
    const val AVOID_HIGHWAY = false

    /** 허용 반경 기본값(km) */
    const val RADIUS_KM = 50.0

    /** 허용 반경 [+]/[-] 버튼 한 번에 바뀌는 양(km). [-]로는 이 값 아래로 내려가지 않는다 */
    const val RADIUS_STEP_KM = 5.0

    /**
     * 화면에서 받는 허용 반경 상한(km). 계약(§4.2)은 상한이 없지만, 조건부 면허 반경으로 이보다 큰 값은 입력 실수로 본다.
     * [설계 판단] 300 km — 서울 기준 국토 대부분을 덮는 값.
     */
    const val MAX_RADIUS_KM = 300.0
}

package com.pleos.departure.model

/**
 * WGS84 좌표 한 점. 생성 시 범위를 검증한다(위도 -90..90, 경도 -180..180).
 *
 * 생성자는 항상 named argument로 부르는 것을 권장한다.
 * (NaviHelper 3.0.5에서 좌표 인자 순서가 바뀐 이력이 있음 — api-notes/navi-route.md "RouteInfo")
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

/**
 * 목적지/장소. 좌표는 필수, POI 정보는 선택.
 *
 * NaviHelper `RouteInfo`는 poiName/poiId/address/poiSubId 를 non-null로 요구하고,
 * `TripRequest`는 poiId/poiName 필수 + poiSubId nullable이다(api-notes/navi-route.md, eta-location.md §A).
 * 우리 도메인은 좌표만 아는 장소도 다뤄야 하므로 모두 nullable로 두고, null → SDK 값 변환은 1번 어댑터 몫으로 둔다.
 * - `TripRequest`의 빈 문자열은 허용된다 [실행 확인 2026-11-10 — api-notes/navi-runtime-check.md §10.6]. (requestRoute 도 허용되나
 *   #5b 부터 2번은 requestRoute 를 쓰지 않는다)
 * - 내비 목적지(NaviDestination)에서 만든 Place 는 SDK로 설정한 목적지의 name 이 빈 문자열일 수 있다 → displayName 규칙은
 *   NaviDestination.Present KDoc 참고.
 *
 * @property displayName 화면에 보여 줄 이름(예: "딸네 집"). POI 이름과 다를 수 있다.
 */
data class Place(
    val point: GeoPoint,
    val displayName: String,
    val poiId: String? = null,
    val poiName: String? = null,
    val address: String? = null,
    val poiSubId: String? = null,
)

package com.pleos.departure.model

/**
 * 주행 중 화면 제한 상태. 주행 판단은 속도·기어가 아니라 AAOS CarUxRestrictions 를 따른다
 * (api-notes/vehicle-state.md "A. 주행 규제"). 우리 화면은 RESTRICTED 이면 조작을 최소화한다.
 */
enum class DrivingRestriction {
    /** 제한 없음(정차/주차). 출발 전 화면을 그대로 쓴다. */
    UNRESTRICTED,

    /** 주행 규제 중(isRequiresDistractionOptimization = true). */
    RESTRICTED,

    /**
     * 상태를 알 수 없다(미연동·미수신·오류). 화면은 DepartureConfig.treatUnknownDrivingAsRestricted(기본 true)에 따라
     * RESTRICTED처럼 다룬다 — 플랫폼도 unknown 을 전부 제한으로 본다(api-notes/vehicle-state.md §11.2).
     */
    UNKNOWN,
}

/**
 * 음성 출력 결과. **Pleos TTS 준비 중 — 사용 안 함**(사용자 결정 11/09).
 * TtsRepository 계약을 1번과 맞추기 위해서만 존재한다. 유스케이스는 이 결과에 의존하지 않는다.
 */
sealed interface SpeakResult {
    /** 끝까지 말했다(onDone). */
    data object Done : SpeakResult

    /** 중간에 멈췄다(onStop). */
    data object Stopped : SpeakResult

    /** 말하지 못했다(SDK 준비 중, 초기화 실패, onError). */
    data class Unavailable(val reason: UnavailableReason, val detail: String? = null) : SpeakResult
}

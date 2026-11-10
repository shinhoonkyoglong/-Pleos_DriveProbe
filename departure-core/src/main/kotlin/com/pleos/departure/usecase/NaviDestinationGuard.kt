package com.pleos.departure.usecase

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.NaviDestination

/**
 * core 쪽 (0,0) 방어(리뷰 task-05 MINOR-2). 본래 규칙은 어댑터 몫이다(NaviRepository.getDestination KDoc):
 * 내비는 목적지가 없으면 0 값 객체를 주므로 어댑터가 NotSet 으로 바꿔야 한다.
 * 어댑터가 실수로 `Present((0.0, 0.0))`을 넘겨도 "반경 밖" 오경고나 근거 없는 마감이 나오지 않도록 core 에서 한 번 더 거른다.
 * (0,0)은 기니만 바다 위라 국내 목적지로는 나오지 않는다. 세 유스케이스(마감·반경·경로)가 모두 이 함수를 거친다.
 */
internal fun DataResult<NaviDestination>.treatNullIslandAsNotSet(): DataResult<NaviDestination> {
    val present = (this as? DataResult.Available)?.value as? NaviDestination.Present ?: return this
    val p = present.place.point
    return if (p.latitude == 0.0 && p.longitude == 0.0) DataResult.Available(NaviDestination.NotSet) else this
}

package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckAdviceTest {

    private fun advice(
        checked: Boolean = false,
        skipped: Boolean = false,
        hour: Int = 14,
        lastTrip: FatigueLevel? = null,
        warning: Boolean = false,
    ) = checkAdvice(checked, skipped, hour, lastTrip, warning)

    @Test fun 낮이고_피로신호가_없으면_권하지_않는다() =
        assertEquals(CheckAdvice.OPTIONAL, advice())

    @Test fun 밤이면_권한다() {
        assertEquals(CheckAdvice.RECOMMEND_NIGHT, advice(hour = NIGHT_START_HOUR))
        assertEquals(CheckAdvice.RECOMMEND_NIGHT, advice(hour = 0))
        assertEquals(CheckAdvice.RECOMMEND_NIGHT, advice(hour = NIGHT_END_HOUR - 1))
        assertEquals(CheckAdvice.OPTIONAL, advice(hour = NIGHT_END_HOUR))
    }

    @Test fun 지난_주행이_CAUTION_이상이면_권한다() {
        assertEquals(CheckAdvice.RECOMMEND_AFTER_FATIGUE, advice(lastTrip = FatigueLevel.CAUTION))
        assertEquals(CheckAdvice.RECOMMEND_AFTER_FATIGUE, advice(lastTrip = FatigueLevel.DANGER))
        assertEquals(CheckAdvice.OPTIONAL, advice(lastTrip = FatigueLevel.MILD))
    }

    @Test fun 최근_음성확인이나_휴식권고가_있으면_권한다() =
        assertEquals(CheckAdvice.RECOMMEND_AFTER_FATIGUE, advice(warning = true))

    @Test fun 피로신호가_밤보다_우선한다() =
        assertEquals(CheckAdvice.RECOMMEND_AFTER_FATIGUE, advice(hour = 22, warning = true))

    @Test fun 오늘_체크했으면_결과를_보여준다() =
        assertEquals(CheckAdvice.DONE_TODAY, advice(checked = true, skipped = true, hour = 22, warning = true))

    @Test fun 바로_출발을_골랐으면_다시_권하지_않는다() =
        assertEquals(CheckAdvice.SKIPPED_TODAY, advice(skipped = true, hour = 22, warning = true))
}

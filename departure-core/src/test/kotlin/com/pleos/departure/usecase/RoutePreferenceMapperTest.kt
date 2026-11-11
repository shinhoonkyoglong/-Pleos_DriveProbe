package com.pleos.departure.usecase

import com.pleos.departure.DepartureConfig
import com.pleos.departure.model.Guarantee
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RouteOption
import com.pleos.departure.model.RoutePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 리뷰 task-03 #5-2 매핑 규칙 고정. */
class RoutePreferenceMapperTest {

    private val mapper = RoutePreferenceMapper(DepartureConfig())

    @Test
    fun noPreference_usesConfigDefault_recommended() {
        val m = mapper.map(RoutePreference.NONE)
        assertEquals(RouteOption.RECOMMENDED, m.option)
        assertTrue(m.perConstraint.isEmpty())
        assertEquals(Guarantee.EXACT, m.overall)

        val freeDefault = RoutePreferenceMapper(DepartureConfig(defaultRouteOption = RouteOption.FREE)).map(RoutePreference.NONE)
        assertEquals(RouteOption.FREE, freeDefault.option)
    }

    @Test
    fun tollFree_isFreeExact() {
        val m = mapper.map(RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE))
        assertEquals(RouteOption.FREE, m.option)
        assertEquals(Guarantee.EXACT, m.overall)
    }

    @Test
    fun avoidHighway_isFreeForEta_butUnsupported() {
        val m = mapper.map(RoutePreference.of(RouteConstraint.AVOID_HIGHWAY))
        assertEquals(RouteOption.FREE, m.option) // ETA 보정용 보수적 가정
        assertEquals(Guarantee.UNSUPPORTED, m.overall) // 유료도로 우회로도 고속도로가 남음(§10.4)
    }

    @Test
    fun both_isFree_withPerConstraintGuarantee() {
        val m = mapper.map(RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY))
        assertEquals(RouteOption.FREE, m.option)
        assertEquals(Guarantee.EXACT, m.perConstraint[RouteConstraint.PREFER_TOLL_FREE])
        assertEquals(Guarantee.UNSUPPORTED, m.perConstraint[RouteConstraint.AVOID_HIGHWAY])
        assertEquals(Guarantee.UNSUPPORTED, m.overall)
    }
}

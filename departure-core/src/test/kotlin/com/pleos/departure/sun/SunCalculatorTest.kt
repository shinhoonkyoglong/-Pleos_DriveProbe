package com.pleos.departure.sun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * SunCalculator 단위 테스트.
 *
 * ## 기준값 출처 — KASI 조회 2026-11-09 (docs/pleos/api-notes/sunset-reference.md)
 * - 1순위: 한국천문연구원(KASI) 천문우주지식정보 "일출일몰시각계산" https://astro.kasi.re.kr/life/pageView/9
 *   (지평선 고도 -0.8333° = 천정각 90.833°). 분 단위 표시이며 반올림/버림 여부는 불확실하다.
 * - 교차검증: NOAA GML Solar Calculator https://gml.noaa.gov/grad/solcalc/ — 우리 계산값과 초 단위까지 일치.
 * - 조회 방식: 사이트의 자체 계산 함수를 브라우저에서 실행한 결과(폼 UI 정상 조회 화면은 아님). 자세한 내용은 sunset-reference.md 참고.
 * 허용 오차 ±2분은 유지한다(KASI 분 표시 규칙 불확실 + KASI·NOAA 간 차이 1분 이내).
 */
class SunCalculatorTest {

    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val seoulLat = 37.5665
    private val seoulLon = 126.9780
    private val busanLat = 35.1796
    private val busanLon = 129.0756

    private val tolerance: Duration = Duration.ofMinutes(2)

    private fun assertNear(label: String, expected: ZonedDateTime, actual: ZonedDateTime?) {
        assertNotNull("$label 이 null 이면 안 된다", actual)
        val diff = Duration.between(expected.toInstant(), actual!!.toInstant()).abs()
        assertTrue("$label 기대 $expected, 실제 $actual (오차 ${diff.seconds}초)", diff <= tolerance)
    }

    private fun seoulAt(date: String, hhmm: String): ZonedDateTime =
        LocalDateTime.parse("${date}T$hhmm").atZone(seoulZone)

    private fun seoul(date: String) = SunCalculator.sunTimes(LocalDate.parse(date), seoulLat, seoulLon, seoulZone)
    private fun busan(date: String) = SunCalculator.sunTimes(LocalDate.parse(date), busanLat, busanLon, seoulZone)

    // ------------------------------------------------------------------
    // 서울 (37.5665N, 126.9780E, Asia/Seoul)
    // ------------------------------------------------------------------

    /** 춘분 근처. 기준(서울 2026-03-20): 일출 06:36, 일몰 18:43 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 06:36:21 / 18:43:30 */
    @Test
    fun seoulNearMarchEquinox() {
        val s = seoul("2026-03-20")
        assertEquals(SunState.NORMAL, s.state)
        assertNear("서울 춘분 일출", seoulAt("2026-03-20", "06:36"), s.sunrise)
        assertNear("서울 춘분 일몰", seoulAt("2026-03-20", "18:43"), s.sunset)
    }

    /** 하지. 기준(서울 2026-06-21): 일출 05:11, 일몰 19:56 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 05:10:59 / 19:56:42 */
    @Test
    fun seoulSummerSolstice() {
        val s = seoul("2026-06-21")
        assertNear("서울 하지 일출", seoulAt("2026-06-21", "05:11"), s.sunrise)
        assertNear("서울 하지 일몰", seoulAt("2026-06-21", "19:56"), s.sunset)
    }

    /** 추분 근처. 기준(서울 2026-09-23): 일출 06:20, 일몰 18:28 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 06:20:21 / 18:28:15 */
    @Test
    fun seoulNearSeptemberEquinox() {
        val s = seoul("2026-09-23")
        assertNear("서울 추분 일출", seoulAt("2026-09-23", "06:20"), s.sunrise)
        assertNear("서울 추분 일몰", seoulAt("2026-09-23", "18:28"), s.sunset)
    }

    /** 동지. 기준(서울 2026-12-22): 일출 07:43, 일몰 17:17 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 07:43:28 / 17:17:31 */
    @Test
    fun seoulWinterSolstice() {
        val s = seoul("2026-12-22")
        assertNear("서울 동지 일출", seoulAt("2026-12-22", "07:43"), s.sunrise)
        assertNear("서울 동지 일몰", seoulAt("2026-12-22", "17:17"), s.sunset)
    }

    /** 작업일. 기준(서울 2026-11-09): 일출 07:05, 일몰 17:26 (KASI 조회 2026-11-09, sunset-reference.md). NOAA 07:04:56 / 17:26:18 */
    @Test
    fun seoulOnNovember9() {
        val s = seoul("2026-11-09")
        assertNear("서울 11/09 일출", seoulAt("2026-11-09", "07:05"), s.sunrise)
        assertNear("서울 11/09 일몰", seoulAt("2026-11-09", "17:26"), s.sunset)
    }

    // ------------------------------------------------------------------
    // 부산 (35.1796N, 129.0756E, Asia/Seoul)
    // ------------------------------------------------------------------

    /** 기준(부산 2026-06-21): 일출 05:09, 일몰 19:41 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 05:09:31 / 19:41:22 */
    @Test
    fun busanSummerSolstice() {
        val b = busan("2026-06-21")
        assertNear("부산 하지 일출", seoulAt("2026-06-21", "05:09"), b.sunrise)
        assertNear("부산 하지 일몰", seoulAt("2026-06-21", "19:41"), b.sunset)
    }

    /** 기준(부산 2026-12-22): 일출 07:28, 일몰 17:15 (KASI 조회 2026-11-09, sunset-reference.md). 계산값 07:28:31 / 17:15:40 */
    @Test
    fun busanWinterSolstice() {
        val b = busan("2026-12-22")
        assertNear("부산 동지 일출", seoulAt("2026-12-22", "07:28"), b.sunrise)
        assertNear("부산 동지 일몰", seoulAt("2026-12-22", "17:15"), b.sunset)
    }

    /** 부산은 서울보다 동쪽·남쪽 → 동지 일출이 서울보다 빠르다(검증 가능한 물리적 관계). */
    @Test
    fun busanWinterSunriseIsEarlierThanSeoul() {
        assertTrue(busan("2026-12-22").sunrise!! < seoul("2026-12-22").sunrise!!)
    }

    // ------------------------------------------------------------------
    // 로컬 날짜 유지 (UTC 변환으로 날짜가 밀리지 않는지)
    // ------------------------------------------------------------------

    /** 서울 일출(06시 KST)은 UTC로는 전날 21시다. 결과는 요청한 로컬 날짜에 있어야 한다. */
    @Test
    fun seoulTimesStayOnRequestedLocalDate() {
        val dates = listOf("2026-01-01", "2026-03-20", "2026-06-21", "2026-09-23", "2026-12-31")
        for (d in dates) {
            val s = seoul(d)
            val date = LocalDate.parse(d)
            assertEquals("$d 일출 날짜", date, s.sunrise!!.toLocalDate())
            assertEquals("$d 일몰 날짜", date, s.sunset!!.toLocalDate())
            assertEquals("$d 남중 날짜", date, s.solarNoon.toLocalDate())
            assertEquals(seoulZone, s.sunset!!.zone)
            assertTrue(s.sunrise!! < s.solarNoon && s.solarNoon < s.sunset!!)
        }
    }

    /** 연말 → 연초 경계: 12/31과 1/1 일몰은 약 1일(±2분) 차이여야 한다. */
    @Test
    fun yearBoundaryIsContinuous() {
        val dec31 = seoul("2026-12-31").sunset!!
        val jan1 = seoul("2027-01-01").sunset!!
        val gap = Duration.between(dec31, jan1)
        assertTrue("gap=$gap", (gap - Duration.ofDays(1)).abs() <= Duration.ofMinutes(2))
    }

    // ------------------------------------------------------------------
    // 날짜 변경선 부근
    // ------------------------------------------------------------------

    /**
     * 시간대가 경도와 크게 어긋나는 곳(UTC+13/+14인데 서경). 기준일을 잘못 잡으면 하루가 밀린다.
     * - 통가 누쿠알로파 (21.14S, 175.20W, Pacific/Tongatapu, UTC+13)
     * - 키리티마티 (1.87N, 157.43W, Pacific/Kiritimati, UTC+14)
     * - 피지 수바 (18.14S, 178.44E, Pacific/Fiji, UTC+12)
     */
    @Test
    fun dateLineLocationsStayOnRequestedLocalDate() {
        val date = LocalDate.parse("2026-06-21")
        val cases = listOf(
            Triple(-21.1394, -175.2044, "Pacific/Tongatapu"),
            Triple(1.87, -157.43, "Pacific/Kiritimati"),
            Triple(-18.1416, 178.4419, "Pacific/Fiji"),
            Triple(51.88, -176.65, "America/Adak"),
        )
        for ((lat, lon, zoneId) in cases) {
            val s = SunCalculator.sunTimes(date, lat, lon, ZoneId.of(zoneId))
            assertEquals(SunState.NORMAL, s.state)
            assertEquals("$zoneId 일출 날짜", date, s.sunrise!!.toLocalDate())
            assertEquals("$zoneId 일몰 날짜", date, s.sunset!!.toLocalDate())
            assertEquals("$zoneId 남중 날짜", date, s.solarNoon.toLocalDate())
        }
    }

    @Test
    fun longitudeBoundariesAreAccepted() {
        val date = LocalDate.parse("2026-06-21")
        val east = SunCalculator.sunTimes(date, 0.0, 180.0, ZoneId.of("Etc/GMT-12"))
        val west = SunCalculator.sunTimes(date, 0.0, -180.0, ZoneId.of("Etc/GMT+12"))
        // 180E와 180W는 같은 자오선 → 같은 로컬 시각(로컬 날짜만 하루 다름)
        assertEquals(east.sunset!!.toLocalTime().toSecondOfDay().toDouble(), west.sunset!!.toLocalTime().toSecondOfDay().toDouble(), 120.0)
        assertEquals(date, east.sunset!!.toLocalDate())
        assertEquals(date, west.sunset!!.toLocalDate())
    }

    // ------------------------------------------------------------------
    // 고위도 백야/극야
    // ------------------------------------------------------------------

    private val tromsoZone = ZoneId.of("Europe/Oslo")
    private fun tromso(date: String) = SunCalculator.sunTimes(LocalDate.parse(date), 69.6492, 18.9553, tromsoZone)

    @Test
    fun tromsoSummerSolsticeIsAlwaysUp() {
        val s = tromso("2026-06-21")
        assertEquals(SunState.ALWAYS_UP, s.state)
        assertNull(s.sunrise)
        assertNull(s.sunset)
        assertEquals(LocalDate.parse("2026-06-21"), s.solarNoon.toLocalDate())
    }

    @Test
    fun tromsoWinterSolsticeIsAlwaysDown() {
        val s = tromso("2026-12-21")
        assertEquals(SunState.ALWAYS_DOWN, s.state)
        assertNull(s.sunrise)
        assertNull(s.sunset)
    }

    /** 백야 직전(5/15)에는 아직 해가 진다(늦은 밤 일몰). */
    @Test
    fun tromsoBeforeMidnightSunStillHasSunset() {
        val s = tromso("2026-05-15")
        assertEquals(SunState.NORMAL, s.state)
        assertTrue(s.sunset!!.hour >= 22)
    }

    @Test
    fun polesFollowSeason() {
        val june = LocalDate.parse("2026-06-21")
        assertEquals(SunState.ALWAYS_UP, SunCalculator.sunTimes(june, 90.0, 0.0, ZoneId.of("UTC")).state)
        assertEquals(SunState.ALWAYS_DOWN, SunCalculator.sunTimes(june, -90.0, 0.0, ZoneId.of("UTC")).state)
    }

    // ------------------------------------------------------------------
    // 시민 박명
    // ------------------------------------------------------------------

    /** 시민 박명 끝은 일몰보다 늦다(서울 중위도에서 대략 25~35분). */
    @Test
    fun civilDuskIsAfterSunset() {
        val date = LocalDate.parse("2026-09-23")
        val sunset = seoul("2026-09-23").sunset!!
        val dusk = SunCalculator.sunTimes(date, seoulLat, seoulLon, seoulZone, SunCalculator.CIVIL_ZENITH).sunset!!
        val gap = Duration.between(sunset, dusk).toMinutes()
        assertTrue("gap=$gap", gap in 25..35)
    }

    // ------------------------------------------------------------------
    // 입력 검증 / 순수성
    // ------------------------------------------------------------------

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidInput() {
        val d = LocalDate.parse("2026-06-21")
        assertRejected { SunCalculator.sunTimes(d, 90.0001, 0.0, seoulZone) }
        assertRejected { SunCalculator.sunTimes(d, -90.0001, 0.0, seoulZone) }
        assertRejected { SunCalculator.sunTimes(d, 0.0, 180.0001, seoulZone) }
        assertRejected { SunCalculator.sunTimes(d, 0.0, -180.0001, seoulZone) }
        assertRejected { SunCalculator.sunTimes(d, Double.NaN, 0.0, seoulZone) }
        assertRejected { SunCalculator.sunTimes(d, 0.0, 0.0, seoulZone, zenithDegrees = 180.0) }
    }

    @Test
    fun sameInputGivesSameOutput() {
        assertEquals(seoul("2026-06-21"), seoul("2026-06-21"))
    }

    @Test
    fun sunTimesRejectsInconsistentState() {
        val noon = seoulAt("2026-06-21", "12:30")
        assertRejected { SunTimes(LocalDate.parse("2026-06-21"), SunState.NORMAL, null, noon, noon, 90.833) }
        assertRejected { SunTimes(LocalDate.parse("2026-06-21"), SunState.ALWAYS_UP, noon, noon, noon, 90.833) }
    }
}

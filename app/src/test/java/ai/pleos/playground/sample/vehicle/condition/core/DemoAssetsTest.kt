package ai.pleos.playground.sample.vehicle.condition.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * assets/condition/ 아래 시연 파일이 생성기와 같은지 확인한다.
 * 생성기나 파라미터를 바꿨으면 다시 만든다: .\gradlew.bat :condition:testDebugUnitTest -PwriteDemo=true
 */
class DemoAssetsTest {

    private val dir = File(System.getProperty("conditionAssetsDir") ?: "src/main/assets/condition")

    private fun expected(): Map<String, String> = mapOf(
        "trip_demo.json" to ReplayFormat.write(DemoScenarioTest.trip),
        "baseline_seed.json" to BaselineSeedFormat.write(DemoScenarioTest.seed),
    )

    @Test
    fun `assets 시연 파일이 생성기 결과와 같다`() {
        val files = expected()
        if (System.getProperty("writeDemo") == "true") {
            dir.mkdirs()
            files.forEach { (name, text) -> File(dir, name).writeText(text, Charsets.UTF_8) }
            println("시연 파일 생성: ${dir.absolutePath} ${files.keys}")
        }
        files.forEach { (name, text) ->
            val f = File(dir, name)
            assertTrue("$name 없음 → -PwriteDemo=true 로 생성", f.exists())
            assertEquals("$name 이 생성기와 다름 → -PwriteDemo=true 로 다시 생성", text, f.readText(Charsets.UTF_8).replace("\r\n", "\n"))
        }
    }

    @Test
    fun `재생 형식은 쓰고 다시 읽어도 같다`() {
        val t = DemoScenarioTest.trip
        assertEquals(t, ReplayFormat.read(ReplayFormat.write(t)))
    }

    @Test
    fun `평소 기록 형식은 쓰고 다시 읽어도 값이 같다(소수 둘째 자리까지)`() {
        val s = DemoScenarioTest.seed
        val back = BaselineSeedFormat.read(BaselineSeedFormat.write(s))
        assertEquals(s.size, back.size)
        s.zip(back).forEach { (a, b) ->
            assertEquals(a.reversalsSmallPerMin, b.reversalsSmallPerMin, 0.01f)
            assertEquals(a.reversalsLargePerMin, b.reversalsLargePerMin, 0.01f)
            assertEquals(a.stdDeg, b.stdDeg, 0.001f)
        }
    }

    @Test
    fun `시연 데이터는 실측된 SDK 전달 방식을 따른다`() {
        val t = DemoScenarioTest.trip
        assertTrue("약 4분", t.durationMs in 230_000L..250_000L)

        val steerTimes = t.events.filter { it.kind == SignalKind.STEERING }.map { it.tMs }
        val gaps = steerTimes.zipWithNext { a, b -> b - a }
        // 변경 콜백 최소 간격 약 106ms 실측 → 110ms 이상, 고정이어도 약 3.1초마다 재전송 → 3.2초 이하
        assertTrue("조향 최소 간격 ${gaps.min()}", gaps.min() >= 110)
        assertTrue("조향 최대 간격 ${gaps.max()}", gaps.max() <= 3_200)

        val speedGaps = t.events.filter { it.kind == SignalKind.SPEED_KPH }.map { it.tMs }.zipWithNext { a, b -> b - a }.toSet()
        assertEquals(setOf(100L, 200L), speedGaps)

        val gears = t.events.filter { it.kind == SignalKind.GEAR }.map { it.tMs to it.text }
        assertEquals(listOf(0L to "PARK", 14_500L to "DRIVE"), gears)
        println("trip_demo: 이벤트 ${t.events.size}개 (조향 ${steerTimes.size}, 속도 ${speedGaps.size}종 간격)")
    }
}

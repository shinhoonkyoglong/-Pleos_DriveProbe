package ai.pleos.playground.sample.vehicle.condition.engine

import ai.pleos.playground.sample.vehicle.condition.core.BaselineSeedFormat
import ai.pleos.playground.sample.vehicle.condition.core.BaselineStore
import ai.pleos.playground.sample.vehicle.condition.core.TripSummary
import java.io.File

/**
 * 개인 기준선(최근 주행 요약 최대 20개)을 파일 하나에 저장한다. 형식은 assets의 baseline_seed.json과 같다.
 * 나중에 팀 Room DB로 옮기려면 BaselineStore만 새로 구현하면 된다.
 *
 * @param initial 파일이 아직 없을 때 쓸 기록 (예: 시연용 평소 기록). 저장하기 전까지는 파일을 만들지 않는다
 */
class FileBaselineStore(
    private val file: File,
    private val initial: List<TripSummary> = emptyList(),
) : BaselineStore {

    override fun load(): List<TripSummary> {
        if (!file.exists()) return initial
        return try {
            BaselineSeedFormat.read(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 파일이 깨졌으면 기록 없음으로 시작한다(기본 기준선 + isCalibrating)
            emptyList()
        }
    }

    override fun save(trips: List<TripSummary>) {
        file.parentFile?.mkdirs()
        // 쓰는 도중 앱이 꺼져도 기존 파일이 깨지지 않도록 임시 파일에 쓰고 바꿔치기한다
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(BaselineSeedFormat.write(trips), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

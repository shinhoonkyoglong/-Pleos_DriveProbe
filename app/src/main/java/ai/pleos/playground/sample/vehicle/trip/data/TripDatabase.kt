package ai.pleos.playground.sample.vehicle.trip.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 5번(주행 후) 전용 DB. 공용 AppDatabase(drive_probe_local.db)와 파일이 분리되어 있어
 * 다른 파트의 DB 버전 변경과 서로 영향을 주지 않는다.
 */
@Database(
    entities = [TripRecordEntity::class],
    // 1 → 2: 점수 항목(판정 시간·피로 점수·반응 체크·응답 횟수) 추가. 아직 배포 전이라 마이그레이션 없이 새로 만든다
    version = 2,
    exportSchema = false,
)
abstract class TripDatabase : RoomDatabase() {

    abstract fun tripRecordDao(): TripRecordDao

    companion object {
        const val FILE_NAME = "trip_local.db"

        @Volatile
        private var INSTANCE: TripDatabase? = null

        fun getInstance(context: Context): TripDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, TripDatabase::class.java, FILE_NAME)
                    // 배포 전(개발 기기의 시험 기록만 있음). 첫 배포 뒤부터는 Migration을 추가해야 한다
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

package ai.pleos.playground.sample.vehicle.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.pleos.playground.sample.vehicle.data.local.dao.DriverConditionDao
import ai.pleos.playground.sample.vehicle.data.local.dao.InterventionHistoryDao
import ai.pleos.playground.sample.vehicle.data.local.dao.ReactionScoreDao
import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity

@Database(
    entities = [
        ReactionScoreEntity::class,
        InterventionHistoryEntity::class,
        DriverConditionEntity::class
    ],
    version = 2, // 1 → 2: driver_conditions 추가 (MIGRATION_1_2)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reactionScoreDao(): ReactionScoreDao
    abstract fun interventionHistoryDao(): InterventionHistoryDao
    abstract fun driverConditionDao(): DriverConditionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2: 운전자 조건 테이블(driver_conditions)만 새로 만든다. 기존 reaction_scores·intervention_histories 데이터는 그대로 둔다.
         * SQL 은 Room 이 DriverConditionEntity 로 만드는 스키마와 같아야 한다(다르면 DB 를 열 때 검증 실패).
         * 이 마이그레이션이 없으면 아래 fallbackToDestructiveMigration() 때문에 기존 기록이 모두 지워진다.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `driver_conditions` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`timeConstraints` TEXT NOT NULL, " +
                        "`routeConstraints` TEXT NOT NULL, " +
                        "`radiusCenterLatitude` REAL, " +
                        "`radiusCenterLongitude` REAL, " +
                        "`allowedRadiusMeters` REAL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`isSynced` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drive_probe_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration() // 마이그레이션이 없는 버전 조합에만 적용된다
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

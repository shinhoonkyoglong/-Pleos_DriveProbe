package ai.pleos.playground.sample.vehicle.data.local

import ai.pleos.playground.sample.vehicle.data.local.entity.DriverConditionEntity
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppDatabase v1 → v2 마이그레이션(MIGRATION_1_2) 검증.
 *
 * room-testing(MigrationTestHelper)은 스키마 내보내기(exportSchema)가 필요해 쓰지 않았다. 대신
 * v1 앱이 만들던 DB 를 그대로 재현(테이블 SQL·room_master_table 해시는 v1 빌드의 생성 코드 AppDatabase_Impl 에서 복사)하고,
 * **fallbackToDestructiveMigration 없이** MIGRATION_1_2 만 넣어 연다.
 * 마이그레이션 SQL 이 Entity 스키마와 다르면 Room 이 여는 순간 IllegalStateException 으로 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration_test_drive_probe.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_keepsExistingRows_andCreatesDriverConditions() = runBlocking {
        createVersion1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            // 기존 데이터 유지
            val scores = db.reactionScoreDao().getUnsyncedScores()
            assertEquals(1, scores.size)
            assertEquals("session-v1", scores.single().sessionId)
            assertEquals(87, scores.single().reactionScore)

            // 새 테이블: 처음에는 비어 있고(미설정), 저장·조회 가능
            val dao = db.driverConditionDao()
            assertNull(dao.getCondition())
            val entity = DriverConditionEntity(
                timeConstraints = "NO_NIGHT_DRIVING",
                routeConstraints = "PREFER_TOLL_FREE",
                radiusCenterLatitude = 37.5563,
                radiusCenterLongitude = 126.9236,
                allowedRadiusMeters = 10_000.0,
                updatedAt = 1_000L,
            )
            dao.upsertCondition(entity)
            assertEquals(entity, dao.getCondition())

            // 전체 교체(단일 행)
            dao.upsertCondition(entity.copy(timeConstraints = "", updatedAt = 2_000L))
            assertEquals("", dao.getCondition()!!.timeConstraints)

            // 동기화 표시: 보낸 시각과 다르면 바꾸지 않는다
            assertEquals(0, dao.markAsSynced(updatedAt = 1_000L))
            assertEquals(1, dao.markAsSynced(updatedAt = 2_000L))
            assertNull(dao.getUnsyncedCondition())
        } finally {
            db.close()
        }
    }

    /** v1(2026-11-10 이전) 앱이 만든 DB 와 같은 상태를 만든다. */
    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            raw.execSQL("CREATE TABLE IF NOT EXISTS `reaction_scores` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `simpleReactionTimeMs` INTEGER NOT NULL, `choiceReactionTimeMs` INTEGER, `reactionScore` INTEGER NOT NULL, `passed` INTEGER NOT NULL, `notes` TEXT, `isSynced` INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS `intervention_histories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `interventionLevel` INTEGER NOT NULL, `fatigueScore` REAL NOT NULL, `triggerReason` TEXT NOT NULL, `actionDetails` TEXT NOT NULL, `userResponse` TEXT, `isSynced` INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd37cf6b2503f02667cebb9a4cb1ea15f')")
            raw.execSQL(
                "INSERT INTO reaction_scores (sessionId, timestamp, simpleReactionTimeMs, choiceReactionTimeMs, reactionScore, passed, notes, isSynced) " +
                    "VALUES ('session-v1', 1700000000000, 320, NULL, 87, 1, NULL, 0)"
            )
            raw.version = 1
        } finally {
            raw.close()
        }
    }
}

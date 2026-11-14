package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.trip.data.TripDatabase
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordDao
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.report.DrivingReport
import ai.pleos.playground.sample.vehicle.trip.report.DrivingReportHtml
import ai.pleos.playground.sample.vehicle.trip.report.DemoHistoryGenerator
import ai.pleos.playground.sample.vehicle.trip.report.TripPeriodStats
import ai.pleos.playground.sample.vehicle.trip.report.periodStart
import ai.pleos.playground.sample.vehicle.trip.share.FamilyShareSender
import ai.pleos.playground.sample.vehicle.trip.share.FamilyShareText
import ai.pleos.playground.sample.vehicle.trip.share.MockFamilyShareSender
import ai.pleos.playground.sample.vehicle.trip.share.ShareResult
import ai.pleos.playground.sample.vehicle.trip.source.DepartureDriverConditionsSource
import ai.pleos.playground.sample.vehicle.trip.source.DriverConditionsSource
import ai.pleos.playground.sample.vehicle.trip.source.DepartureComplianceSource
import ai.pleos.playground.sample.vehicle.trip.source.EnginePreDriveSource
import ai.pleos.playground.sample.vehicle.trip.source.MockComplianceSource
import ai.pleos.playground.sample.vehicle.trip.source.InterventionRestAdviceSource
import ai.pleos.playground.sample.vehicle.trip.ui.TripSummaryActivity
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 5번(주행 후) 진입점. Application.onCreate 맨 끝에서 한 번 부른다.
 *
 * ```kotlin
 * TripModule.init(this)                      // ConditionModule.init 다음
 *
 * // 다른 화면에서 쓰는 공개 API (이식 방법은 docs/trip-summary.md)
 * TripModule.observeSummary(tripId)          // 요약 1건(null = 가장 최근) → Flow<TripSummaryContent?>
 * TripModule.observeHistory(limit)           // 지난 기록 목록 → Flow<List<TripHistoryItem>>
 * TripModule.tripEnded                       // 주행이 끝날 때마다 새 tripId
 * TripModule.observeWeekStats()             // 최근 7일 집계(운전 횟수·조건 준수율 등) → Flow<TripPeriodStats>
 * TripModule.shareToFamily(tripId)          // 주행 요약을 가족에게 보내기(운전자가 버튼을 눌렀을 때) → ShareResult
 * TripModule.observeDrivingReport(weeks)   // 운전 리포트(기본 8주) → Flow<DrivingReport>
 * TripModule.drivingReportHtml(weeks)      // 인쇄용 HTML 문자열
 * ```
 *
 * 기본값으로는 주행이 끝나면 임시 단독 요약 화면([TripSummaryActivity])을 띄운다([summaryPresenter]).
 * 팀원 화면으로 옮기면 [summaryPresenter]를 팀원 화면 이동으로 바꾸거나 null로 두고 [tripEnded]를 받으면 된다.
 * TripModule이 화면 클래스를 아는 곳은 [summaryPresenter] 기본값 한 줄뿐이다.
 * 앱 화면이 앞에 없으면 Android 백그라운드 실행 제한으로 화면이 뜨지 않을 수 있다(기록은 저장됨).
 *
 * 확인용 logcat 태그: "Trip"
 * debug 빌드만:
 *   진행 중인 주행 강제 종료  adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.END
 *   마지막 요약 화면 열기     adb shell am broadcast --user current -p ai.pleos.playground.sample.vehicle.diagnostic -a ai.pleos.trip.SHOW
 *   시연용 8주 기록 넣기      ... -a ai.pleos.trip.SEED_HISTORY [--ez integrated true]
 *   시연용 기록 지우기        ... -a ai.pleos.trip.CLEAR_HISTORY
 *   리포트 HTML 파일 만들기   ... -a ai.pleos.trip.REPORT  (경로는 logcat "Trip")
 */
object TripModule {

    const val ACTION_END = "ai.pleos.trip.END"
    const val ACTION_SHOW = "ai.pleos.trip.SHOW"
    const val ACTION_SEED_HISTORY = "ai.pleos.trip.SEED_HISTORY"
    const val ACTION_CLEAR_HISTORY = "ai.pleos.trip.CLEAR_HISTORY"
    const val ACTION_REPORT = "ai.pleos.trip.REPORT"

    /** 요약을 보여주는 방법(화면 이동). TripModule은 화면을 모르고 이것만 부른다 */
    fun interface SummaryPresenter {
        fun show(context: Context, tripId: String?)
    }

    /**
     * 주행이 끝나면 부르는 요약 표시 방법. null이면 아무것도 띄우지 않는다(호스트가 [tripEnded]를 받아 처리).
     * [임시] 팀원 UI로 옮기면 이 기본값을 바꾸거나 null로 하고, TripSummaryActivity를 지운다(이 줄 하나만 고치면 됨).
     */
    @Volatile
    var summaryPresenter: SummaryPresenter? = SummaryPresenter { c, id -> c.startActivity(TripSummaryActivity.intent(c, id)) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var recorder: TripRecorder? = null

    lateinit var dao: TripRecordDao
        private set

    /** 리포트에 넣을 운전자 조건 출처. 기본은 2번 운전자 조건 저장소(읽기만) */
    @Volatile
    var driverConditionsSource: DriverConditionsSource = DriverConditionsSource.NONE

    private var prefs: SharedPreferences? = null
    private val _reportUsesDemo = MutableStateFlow(false)

    /**
     * 가족 요약·리포트를 **시연용 기록**으로 만들지. false면 실제 기록만, true면 시연 기록만(섞지 않는다).
     * 시연용 기록을 넣으면(debug) true, 지우면 false가 되고 앱을 다시 켜도 유지된다.
     */
    val reportUsesDemo: StateFlow<Boolean> = _reportUsesDemo.asStateFlow()

    fun setReportUsesDemo(value: Boolean) {
        _reportUsesDemo.value = value
        prefs?.edit()?.putBoolean(PREF_REPORT_USES_DEMO, value)?.apply()
    }

    val isInitialized: Boolean get() = recorder != null

    val lastRecord: StateFlow<TripRecordEntity?>
        get() = (recorder ?: error("TripModule.init()을 먼저 불러야 합니다")).lastRecord

    /** 주행이 끝나 기록이 저장될 때마다 그 tripId. 구독하기 전에 끝난 주행은 오지 않는다 */
    val tripEnded: Flow<String>
        get() = lastRecord.drop(1).filterNotNull().map { it.tripId }

    /** 요약 1건. [tripId]가 null이면 가장 최근 주행. 기록이 없으면 null */
    fun observeSummary(tripId: String? = null): Flow<TripSummaryContent?> =
        (if (tripId == null) dao.observeLatest() else dao.observeById(tripId))
            .map { it?.let(TripSummaryContent::from) }

    /** 지난 기록 목록(최근 순) */
    fun observeHistory(limit: Int = 30): Flow<List<TripHistoryItem>> =
        dao.observeRecent(limit).map { list -> list.map(TripHistoryItem::from) }

    /**
     * 최근 7일(오늘 포함) 집계 — 운전 횟수·시간, 조건 준수율([TripPeriodStats.complianceRate]), 휴식 권고, 피로 신호 등.
     * 팀원 화면의 "이번 주" 숫자용(화면 없이 값만). 시연 기록과 실제 기록은 섞지 않는다([reportUsesDemo]).
     */
    fun observeWeekStats(now: Long = System.currentTimeMillis()): Flow<TripPeriodStats> {
        val from = periodStart(now, 7)
        return combine(dao.observeEndedSince(from), reportUsesDemo) { records, demo ->
            TripPeriodStats.of(records.filter { it.isDemo == demo }, from, now)
        }
    }

    /** 가족에게 보내는 통로. 서버 등 실제 방법이 정해지면 바꾼다(지금은 로그만 남기는 임시) */
    @Volatile
    var familyShareSender: FamilyShareSender = MockFamilyShareSender

    /**
     * 주행 요약 1건을 가족에게 보낸다([tripId]가 null이면 가장 최근 주행). **운전자가 버튼을 눌렀을 때만** 부른다.
     * 보내는 글은 [FamilyShareText] — 요약 항목뿐, 위치·경로·시각 없음.
     */
    suspend fun shareToFamily(tripId: String?): ShareResult {
        val record = (if (tripId == null) dao.observeLatest() else dao.observeById(tripId)).first()
            ?: return ShareResult.Failed("보낼 주행 기록이 없어요.")
        val text = FamilyShareText.from(TripSummaryContent.from(record))
        return try {
            familyShareSender.send(record.tripId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TripRecorder.TAG, "family share failed", e)
            ShareResult.Failed("보내지 못했어요. 잠시 뒤 다시 눌러 주세요.")
        }
    }

    /** 운전 리포트: [now] 기준 최근 [weeks]주 기록 + 운전자 조건 */
    fun observeDrivingReport(weeks: Int = 8, now: Long = System.currentTimeMillis()): Flow<DrivingReport> =
        combine(
            dao.observeEndedSince(periodStart(now, weeks * 7)),
            reportUsesDemo,
            driverConditionsSource.observe(),
        ) { records, demo, conditions ->
            DrivingReport.build(records.filter { it.isDemo == demo }, conditions, now, weeks)
        }

    /** 운전 리포트 인쇄용 HTML(브라우저에서 열어 인쇄·PDF 저장) */
    suspend fun drivingReportHtml(weeks: Int = 8): String =
        DrivingReportHtml.render(observeDrivingReport(weeks).first())

    /**
     * 리포트 HTML을 앱 내부 폴더(files/reports/)에 저장하고 파일을 돌려준다.
     * 차량에서는 인쇄할 수 없어 시연·확인용이다. 실제로는 서버가 만들어 가족 웹에서 내려받는다.
     * PC로 꺼내기: adb exec-out run-as <패키지> --user 10 cat files/reports/<파일> > report.html
     * (AAOS 사용자 10의 외부 저장소는 adb로 읽을 수 없어 내부 폴더에 둔다)
     */
    suspend fun writeDrivingReportHtml(context: Context, weeks: Int = 8): File {
        val html = drivingReportHtml(weeks)
        return withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "reports").apply { mkdirs() }
            val name = "driving-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".html"
            File(dir, name).apply { writeText(html) }
        }
    }

    /** 시연용 가상 기록([weeks]주)을 넣고 리포트가 시연 기록을 쓰게 한다. 넣은 개수를 돌려준다 */
    suspend fun seedDemoHistory(weeks: Int = 8, integrated: Boolean = false): Int {
        val records = DemoHistoryGenerator.generate(System.currentTimeMillis(), weeks, integrated = integrated)
        dao.deleteByIdPrefix(DemoHistoryGenerator.ID_PREFIX)
        dao.insertAll(records)
        setReportUsesDemo(true)
        return records.size
    }

    /** 시연용 가상 기록을 지우고 리포트가 실제 기록을 쓰게 한다 */
    suspend fun clearDemoHistory(): Int {
        val n = dao.deleteByIdPrefix(DemoHistoryGenerator.ID_PREFIX)
        setReportUsesDemo(false)
        return n
    }

    @Synchronized
    fun init(context: Context) {
        if (recorder != null) return
        val app = context.applicationContext
        dao = TripDatabase.getInstance(app).tripRecordDao()
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
            _reportUsesDemo.value = it.getBoolean(PREF_REPORT_USES_DEMO, false)
        }
        (app as? DriveProbeApplication)?.let { a ->
            // 2번 저장소는 처음 구독할 때 만든다(lazy)
            driverConditionsSource = DepartureDriverConditionsSource(
                repository = { a.driverConditionRepository },
                defaultRadiusMeters = { a.departureConfig.defaultAllowedRadiusMeters },
                radiusEnabled = { a.departureConfig.radiusCheckEnabled },
            )
        }
        recorder = TripRecorder(
            scope = scope,
            dao = dao,
            restAdviceSource = InterventionRestAdviceSource(
                ai.pleos.playground.sample.vehicle.data.local.AppDatabase.getInstance(app).interventionHistoryDao()),
            // 2번 판정 함수(core 0.11.0). 앱 밖(테스트용 Application)에서는 임시 판정
            complianceSource = (app as? DriveProbeApplication)
                ?.let { a -> DepartureComplianceSource { from, to -> a.departureFeature.evaluateTripCompliance(from, to) } }
                ?: MockComplianceSource(),
            preDriveSource = EnginePreDriveSource,
            checkpoints = PrefsCheckpointStore(app),
            onTripStarted = { engine, id -> (app as DriveProbeApplication).interventions.bindReportTrip(engine, id) },
            onTripEnding = { id -> (app as DriveProbeApplication).interventions.finishReportTrip(id) },
        ).also { it.start() }
        scope.launch {
            // 앱 시작 때의 null은 건너뛰고, 새 주행 기록이 저장될 때마다 요약 화면을 띄운다
            tripEnded.collect { tripId ->
                showSummary(app, tripId)
            }
        }
        registerDebugReceiver(app)
    }

    /** [summaryPresenter]로 요약을 보여준다. [tripId]가 null이면 가장 최근 주행. presenter가 없으면 아무것도 안 함 */
    fun showSummary(context: Context, tripId: String? = null) {
        val presenter = summaryPresenter ?: return
        runCatching { presenter.show(context, tripId) }
            .onFailure { Log.w(TripRecorder.TAG, "summary screen not shown: ${it.message}") }
    }

    private fun registerDebugReceiver(app: Context) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_END -> {
                        val r = recorder ?: return
                        scope.launch { r.finishNow("debug broadcast") }
                    }
                    ACTION_SHOW -> showSummary(app)
                    ACTION_SEED_HISTORY -> {
                        val integrated = intent.getBooleanExtra("integrated", false)
                        scope.launch {
                            val n = seedDemoHistory(integrated = integrated)
                            Log.i(TripRecorder.TAG, "demo history seeded: $n trips (integrated=$integrated)")
                        }
                    }
                    ACTION_CLEAR_HISTORY -> scope.launch {
                        Log.i(TripRecorder.TAG, "demo history cleared: ${clearDemoHistory()} trips")
                    }
                    ACTION_REPORT -> scope.launch {
                        runCatching { writeDrivingReportHtml(app) }
                            .onSuccess { Log.i(TripRecorder.TAG, "driving report written: ${it.absolutePath}") }
                            .onFailure { Log.w(TripRecorder.TAG, "driving report failed", it) }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_END).apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_SEED_HISTORY)
            addAction(ACTION_CLEAR_HISTORY)
            addAction(ACTION_REPORT)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private const val PREFS_NAME = "trip_prefs"
    private const val PREF_REPORT_USES_DEMO = "report_uses_demo"
}

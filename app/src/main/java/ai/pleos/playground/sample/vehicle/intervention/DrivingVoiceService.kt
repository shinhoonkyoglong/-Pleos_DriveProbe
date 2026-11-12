package ai.pleos.playground.sample.vehicle.intervention

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.sample.vehicle.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Started explicitly from the visible parked UI. This service itself never records audio. */
class DrivingVoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "주행 음성 개입", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, DrivingVoiceService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("계속운전 · 주행 음성 준비됨")
                .setContentText("피로 개입의 질문·응답 중에만 마이크를 사용해요")
                .setContentIntent(PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "음성 준비 해제", stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(5901, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(5901, notification)
            _error.value = null
            _ready.value = true
            Log.i("Intervention", "Driving voice foreground service ready; no recording requested")
        } catch (e: Exception) {
            _error.value = "주행 음성 준비 실패: ${e.javaClass.simpleName}. 정차 중 앱 화면에서 다시 준비해 주세요"
            Log.w("Intervention", "Driving voice preparation failed", e)
            _ready.value = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _ready.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i("Intervention", "Driving voice foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "driving_voice"
        private const val STOP = "ai.pleos.driveprobe.STOP_DRIVING_VOICE"
        private val _ready = MutableStateFlow(false)
        val ready = _ready.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()

        fun prepare(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _error.value = "마이크 권한을 허용한 뒤 주행 음성을 준비해 주세요"
                return
            }
            _error.value = null
            try { ContextCompat.startForegroundService(context, Intent(context, DrivingVoiceService::class.java)) }
            catch (e: Exception) { _error.value = "음성 준비를 시작하지 못했어요: ${e.javaClass.simpleName}" }
        }

        fun stop(context: Context) { context.stopService(Intent(context, DrivingVoiceService::class.java)) }
    }
}

package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.MainActivity
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat

/** User-started short transaction. No microphone, audio capture or automatic restart. */
class NavigationProbeService:Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val controller get()=(application as DriveProbeApplication).interventions.navigationProbeController
    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!BuildConfig.DEBUG){stopSelf();return START_NOT_STICKY}
        if(intent?.action==CANCEL){
            controller.cancel()
            if(!controller.isBusy)stopSelf()
            return START_NOT_STICKY
        }
        if(controller.isBusy)return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"휴게소 추가·복원 시험",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel=PendingIntent.getService(this,1,Intent(this,NavigationProbeService::class.java).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("계속운전 · 휴게소 시험 중")
                .setContentText("내비로 전환돼도 추가·삭제 확인을 이어가요. 결과는 앱에서 확인하세요")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null,"시험 취소",cancel).build()).build()
            if(Build.VERSION.SDK_INT>=34)startForeground(5902,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(5902,notification)
            Log.i("NaviWaypointProbe","NAV_PROBE_SERVICE foreground ready")
            val started=controller.start {
                Log.i("NaviWaypointProbe","NAV_PROBE_SERVICE finished state=${controller.state.value}")
                stopSelf()
            }
            if(!started){stopSelf();return START_NOT_STICKY}
            handler.postDelayed({
                controller.markServiceInterrupted("시험 서비스의 제한 시간이 지났어요. 내비에서 경유지 상태를 확인해 주세요")
                stopSelf()
            },150_000L)
        } catch(e:Exception) {
            controller.failToStart("휴게소 시험 서비스를 시작하지 못했어요: ${e.javaClass.simpleName}")
            Log.w("NaviWaypointProbe","NAV_PROBE_SERVICE failed",e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId:Int) {timeoutStop()}
    override fun onTimeout(startId:Int,fgsType:Int) {timeoutStop()}
    private fun timeoutStop() {
        controller.markServiceInterrupted("시험 서비스가 종료됐어요. 내비의 적용·삭제 상태를 확인해 주세요")
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if(controller.isBusy)controller.markServiceInterrupted("시험 서비스가 중간에 종료됐어요. 내비 화면을 확인해 주세요")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL="navigation_probe"
        private const val CANCEL="ai.pleos.driveprobe.CANCEL_NAVIGATION_PROBE"
        fun start(context:Context) {
            val app=context.applicationContext as DriveProbeApplication
            try{ContextCompat.startForegroundService(context,Intent(context,NavigationProbeService::class.java))}
            catch(e:Exception){app.interventions.navigationProbeController.failToStart("휴게소 시험 시작 실패: ${e.javaClass.simpleName}")}
        }
    }
}

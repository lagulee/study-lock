package com.lagulee.studylock

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

/**
 * 집중하는 동안만 도는 감시 서비스.
 * "사용 정보 접근" 권한으로 지금 앞에 나온 앱이 무엇인지만 확인하고(화면 내용은 보지 않음),
 * 막을 앱이면 "다른 앱 위에 표시" 권한으로 잠금 화면을 띄운다.
 * 폰이 서비스를 끄지 않도록 알림창에 "집중 중" 알림을 하나 띄워 둔다.
 */
class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var since = 0L
    private var current: String? = null
    private var lastBlockPkg = ""
    private var lastBlockAt = 0L
    private var shownWaitUntil = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        since = System.currentTimeMillis() - 5_000
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!store.active || store.finishWaitIfDone()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            if (shownWaitUntil != store.waitUntil) goForeground()
            val pkg = foregroundApp()
            if (pkg != null && pkg != packageName && store.isBlocked(pkg)) block(pkg)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 마지막으로 앞에 나온(화면에 뜬) 앱. 이전 확인 이후의 기록만 읽는다. */
    private fun foregroundApp(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return current
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(since, now)
        val e = UsageEvents.Event()
        var resumed = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                current = e.packageName
                resumed = true
            }
        }
        since = now - 1_000 // 기록이 조금 늦게 쌓이는 경우를 위해 1초 겹쳐 읽는다
        return if (resumed || current != null) current else null
    }

    private fun block(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1_500) return
        lastBlockPkg = pkg
        lastBlockAt = now
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        // 잠금 화면이 앞에 나왔으므로, 다시 막힌 앱으로 돌아가야 또 막는다
        current = packageName
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "집중 중 알림", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        shownWaitUntil = store.waitUntil
        val sub = if (store.waitUntil != 0L) "대기 해제: ${clockOnly(store.waitUntil)}에 풀려요" else "할 일: ${store.sessionTask}"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("🔒 집중 중")
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "focus"
        private const val NOTI_ID = 1
        private const val TICK_MS = 600L

        /** 집중 중이고 권한이 있으면 감시를 켜고, 아니면 아무것도 하지 않는다(서비스가 스스로 꺼짐). */
        fun sync(context: Context) {
            if (Store(context).active && context.blockerReady()) {
                context.startForegroundService(Intent(context, WatchService::class.java))
            }
        }
    }
}

/** 폰을 다시 켜거나 앱을 업데이트해도 집중 중이면 감시를 다시 켠다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchService.sync(context)
    }
}

/** "사용 정보 접근"이 허용됐는지. */
fun Context.usageAccessGranted(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    return if (mode == AppOpsManager.MODE_DEFAULT) {
        checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else {
        mode == AppOpsManager.MODE_ALLOWED
    }
}

/** "다른 앱 위에 표시"가 허용됐는지. */
fun Context.overlayGranted(): Boolean = Settings.canDrawOverlays(this)

fun Context.blockerReady(): Boolean = usageAccessGranted() && overlayGranted()

private fun clockOnly(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(ms))

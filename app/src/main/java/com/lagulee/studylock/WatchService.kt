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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 감시 서비스. 집중하는 동안, 그리고 다른 기기와 연동할 때 돈다.
 * - 집중 중: "사용 정보 접근" 권한으로 지금 앞에 나온 앱이 무엇인지만 확인하고(화면 내용은 보지 않음),
 *   막을 앱이면 "다른 앱 위에 표시" 권한으로 잠금 화면을 띄운다.
 * - 연동: 연동 서버와 소켓을 열어 두고 다른 기기가 집중을 시작·해제하면 여기도 따라 한다.
 *   이 기기에서 한 동작(시작·대기·해제)은 Store 에 쌓인 것을 차례로 보낸다.
 * 폰이 서비스를 끄지 않도록 알림창에 알림을 하나 띄워 둔다.
 */
class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var since = 0L
    private var current: String? = null
    private var lastBlockPkg = ""
    private var lastBlockAt = 0L
    private var shownKey = ""

    // 연동
    private val io = Executors.newSingleThreadExecutor()
    private var socket: WebSocket? = null
    private var socketCode: String? = null
    private var connected = false
    private var reconnectDelay = RECONNECT_MIN
    private var nextConnectAt = 0L
    private var flushing = false
    private var flushRetryAt = 0L

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 화면을 켜면 끊긴 연결을 바로 다시 잇고 최신 상태를 한 번 받아 온다
            if (socket == null) {
                nextConnectAt = 0L
                reconnectDelay = RECONNECT_MIN
            }
            fetchState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        since = System.currentTimeMillis() - 5_000
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOn, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shownKey = "" // startForegroundService 마다 startForeground 를 꼭 다시 부른다
        goForeground()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        try { unregisterReceiver(screenOn) } catch (e: Exception) { }
        closeSocket()
        io.shutdown()
        super.onDestroy()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (store.active) store.finishWaitIfDone()
            if (!store.active && !store.syncEnabled) {
                closeSocket()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            syncTick()
            if (store.active && blockerReady()) {
                val pkg = foregroundApp()
                if (pkg != null && pkg != packageName && store.isBlocked(pkg)) block(pkg)
            }
            goForeground()
            handler.postDelayed(this, if (store.active) TICK_MS else IDLE_TICK_MS)
        }
    }

    // ---- 연동 ----

    private fun syncTick() {
        val code = if (store.syncEnabled) store.syncCode else null
        if (code != socketCode) {
            closeSocket()
            socketCode = code
            nextConnectAt = 0L
            reconnectDelay = RECONNECT_MIN
        }
        if (code == null) return
        val now = SystemClock.elapsedRealtime()
        if (socket == null && now >= nextConnectAt) connect(code)
        if (!flushing && now >= flushRetryAt && store.pendingOps().isNotEmpty()) flush(code)
    }

    private fun connect(code: String) {
        val holder = arrayOfNulls<WebSocket>(1)
        holder[0] = SyncApi.listen(store.syncServer, code, store.deviceName,
            onMessage = { msg -> handler.post { if (socket === holder[0]) onRemote(msg, fromSocket = true) } },
            onClosed = {
                handler.post {
                    if (socket === holder[0]) {
                        socket = null
                        setConnected(false)
                        nextConnectAt = SystemClock.elapsedRealtime() + reconnectDelay
                        reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX)
                    }
                }
            })
        socket = holder[0]
    }

    private fun closeSocket() {
        socket?.close(1000, null)
        socket = null
        setConnected(false)
    }

    private fun setConnected(on: Boolean) {
        connected = on
        if (store.syncConnected != on) store.syncConnected = on
    }

    /** 서버 상태를 받으면: 보낼 동작이 남아 있지 않을 때만 이 기기에 맞춘다(이 기기 동작이 먼저). */
    private fun onRemote(msg: JSONObject, fromSocket: Boolean = false) {
        if (fromSocket) {
            setConnected(true)
            reconnectDelay = RECONNECT_MIN
        }
        val r = try { SyncApi.parse(msg) } catch (e: Exception) { return }
        store.syncDevices = r.devices
        if (flushing || store.pendingOps().isNotEmpty()) return
        val wasActive = store.active
        store.applyRemote(r)
        if (!wasActive && store.active) {
            // 다른 기기가 집중을 시작함: 지금 켜 둔 앱도 바로 막도록 최근 기록부터 다시 읽는다
            since = System.currentTimeMillis() - 3_600_000
            current = null
        }
    }

    /** 쌓인 동작을 순서대로 보낸다. 마지막 응답(서버 상태)으로 이 기기를 맞춘다. */
    private fun flush(code: String) {
        flushing = true
        val server = store.syncServer
        io.execute {
            var last: JSONObject? = null
            var retry = false
            try {
                while (true) {
                    val op = store.pendingOps().firstOrNull() ?: break
                    last = SyncApi.send(server, code, op)
                    store.dropFirstOp()
                }
            } catch (e: SyncApi.SyncException) {
                if (e.permanent) store.clearOps() else retry = true
            }
            handler.post {
                flushing = false
                if (retry) flushRetryAt = SystemClock.elapsedRealtime() + FLUSH_RETRY_MS
                last?.let { onRemote(it) }
            }
        }
    }

    private fun fetchState() {
        val code = socketCode ?: return
        val server = store.syncServer
        io.execute {
            val msg = try { SyncApi.state(server, code) } catch (e: Exception) { null }
            if (msg != null) handler.post { onRemote(msg) }
        }
    }

    // ---- 앱 막기 ----

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

    // ---- 알림 ----

    private fun goForeground() {
        val title: String
        val sub: String
        val channel: String
        if (store.active) {
            channel = CHANNEL
            title = "🔒 집중 중"
            val from = if (store.sessionBy.isNotBlank()) " · ${store.sessionBy}에서 시작" else ""
            sub = (if (store.waitUntil != 0L) "대기 해제: ${clockOnly(store.waitUntil)}에 풀려요" else "할 일: ${store.sessionTask}") + from
        } else {
            channel = CHANNEL_SYNC
            title = "🔗 다른 기기와 연동 중"
            sub = if (connected) "연결됨 · ${store.syncDevices.joinToString(", ")}" else "연동 서버에 연결하는 중…"
        }
        val key = "$channel|$title|$sub"
        if (key == shownKey) return
        shownKey = key
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "집중 중 알림", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "연동 대기 알림", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
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
        private const val CHANNEL_SYNC = "sync"
        private const val NOTI_ID = 1
        private const val TICK_MS = 600L
        private const val IDLE_TICK_MS = 1_000L
        private const val RECONNECT_MIN = 2_000L
        private const val RECONNECT_MAX = 60_000L
        private const val FLUSH_RETRY_MS = 5_000L

        /** 집중 중(권한 있음)이거나 연동을 켰으면 서비스를 켠다. 아니면 서비스가 스스로 꺼진다. */
        fun sync(context: Context) {
            val store = Store(context)
            if ((store.active && context.blockerReady()) || store.syncEnabled) {
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

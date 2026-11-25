package com.lagulee.studylock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 앱의 모든 설정과 집중 세션 상태를 이 기기 안에 저장한다.
 * 연동 코드가 있으면 집중 시작·대기·해제를 보낼 동작(pendingOps)으로 쌓아 두고, WatchService 가 연동 서버로 보낸다.
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val p = app.getSharedPreferences("study_lock", Context.MODE_PRIVATE)

    // ---- 준비 단계에서 고르는 것 ----
    var task: String
        get() = p.getString("task", "") ?: ""
        set(v) = p.edit().putString("task", v).apply()

    var blocked: Set<String>
        get() = p.getStringSet("blocked", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("blocked", v.toSet()).apply()

    /** 대기 해제에 걸리는 시간(분). */
    var waitMinutes: Int
        get() = p.getInt("waitMinutes", DEFAULT_WAIT_MINUTES)
        set(v) = p.edit().putInt("waitMinutes", v).apply()

    /** Claude 판단에 가벼운 모델(Haiku)을 쓸지. 기본은 가장 똑똑한 모델(Opus). */
    var useLightModel: Boolean
        get() = p.getBoolean("lightModel", false)
        set(v) = p.edit().putBoolean("lightModel", v).apply()

    /** Gemini 판단에 더 똑똑한 Flash 를 쓸지. 기본은 가벼운 Flash-Lite. */
    var geminiSmart: Boolean
        get() = p.getBoolean("geminiSmart", false)
        set(v) = p.edit().putBoolean("geminiSmart", v).apply()

    /** 기기 안 열쇠로 암호화한 API 키(평문은 저장하지 않음). name = "claude" 또는 "gemini". */
    fun keyCipher(name: String): String? = p.getString("keyCipher_$name", null)
    fun setKeyCipher(name: String, v: String?) = p.edit().putString("keyCipher_$name", v).apply()

    /** 집중 중에 설정 앱·앱 삭제 화면도 막을지. */
    var lockSettings: Boolean
        get() = p.getBoolean("lockSettings", false)
        set(v) = p.edit().putBoolean("lockSettings", v).apply()

    // ---- 집중 세션 ----
    val active: Boolean get() = p.getBoolean("active", false)
    val sessionTask: String get() = p.getString("sessionTask", "") ?: ""
    val startedAt: Long get() = p.getLong("startedAt", 0L)
    /** 세션 동안 적용되는 대기 시간(시작할 때 고정, 도중에 줄일 수 없음). */
    val sessionWaitMinutes: Int get() = p.getInt("sessionWaitMinutes", waitMinutes)
    val sessionLockSettings: Boolean get() = p.getBoolean("sessionLockSettings", false)
    /** 대기 해제가 끝나는 시각. 0 이면 대기를 시작하지 않음. */
    val waitUntil: Long get() = p.getLong("waitUntil", 0L)

    fun startSession(now: Long = System.currentTimeMillis()) {
        p.edit()
            .putBoolean("active", true)
            .putString("sessionTask", task.trim())
            .putLong("startedAt", now)
            .putInt("sessionWaitMinutes", waitMinutes)
            .putBoolean("sessionLockSettings", lockSettings)
            .putLong("waitUntil", 0L)
            .remove("failAt")
            .remove("sessionBy")
            .apply()
        queueOp(JSONObject().put("type", "start").put("task", task.trim()).put("waitMinutes", waitMinutes))
    }

    fun startWait(now: Long = System.currentTimeMillis()) {
        if (waitUntil == 0L) {
            p.edit().putLong("waitUntil", now + sessionWaitMinutes * 60_000L).apply()
            queueOp(JSONObject().put("type", "wait"))
        }
    }

    /** 대기 시간이 다 지났으면 세션을 끝내고 true. */
    fun finishWaitIfDone(now: Long = System.currentTimeMillis()): Boolean {
        if (active && waitUntil != 0L && now >= waitUntil) {
            endSession("대기 해제", now)
            return true
        }
        return false
    }

    fun endSession(
        method: String,
        now: Long = System.currentTimeMillis(),
        photo: String = "",
        summary: String = "",
        note: String = "",
    ) {
        if (!active) return
        addRecord(Record(sessionTask, startedAt, now, method, photo, summary, note))
        p.edit()
            .putBoolean("active", false)
            .putLong("waitUntil", 0L)
            .remove("failAt")
            .apply()
        queueOp(JSONObject().put("type", "end").put("method", method).put("note", note))
    }

    // ---- 다른 기기와 연동 ----
    /** 연동 서버 주소(예: https://study-lock-sync.이름.workers.dev). */
    var syncServer: String
        get() = p.getString("syncServer", null) ?: SyncApi.DEFAULT_SERVER
        set(v) = p.edit().putString("syncServer", v.trim()).apply()

    /** 연동 코드(12자). 없으면 연동하지 않음. */
    var syncCode: String?
        get() = p.getString("syncCode", null)
        set(v) = p.edit().putString("syncCode", v).remove("pendingOps").apply()

    /** 다른 기기에 보일 이 기기 이름(예: 폰, 탭). */
    var deviceName: String
        get() = p.getString("deviceName", null)?.takeIf { it.isNotBlank() }
            ?: if (app.resources.configuration.smallestScreenWidthDp >= 600) "탭" else "폰"
        set(v) = p.edit().putString("deviceName", v.trim().take(20)).apply()

    val syncEnabled: Boolean get() = syncCode != null && syncServer.isNotBlank()

    /** 서비스가 기록해 두는 연결 상태(화면 표시용). */
    var syncConnected: Boolean
        get() = p.getBoolean("syncConnected", false)
        set(v) = p.edit().putBoolean("syncConnected", v).apply()
    var syncDevices: List<String>
        get() = (p.getString("syncDevices", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = p.edit().putString("syncDevices", v.joinToString("\n")).apply()
    /** 지금 세션을 다른 기기에서 시작했으면 그 기기 이름. */
    val sessionBy: String get() = p.getString("sessionBy", "") ?: ""

    /** 아직 서버에 못 보낸 동작들(순서대로). */
    fun pendingOps(): List<JSONObject> {
        val arr = JSONArray(p.getString("pendingOps", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun queueOp(op: JSONObject) {
        if (!syncEnabled) return
        op.put("by", deviceName)
        val arr = JSONArray(p.getString("pendingOps", "[]")).put(op)
        p.edit().putString("pendingOps", arr.toString()).apply()
    }

    /** 맨 앞 동작을 서버가 받았으면 지운다. */
    fun dropFirstOp() {
        val arr = JSONArray(p.getString("pendingOps", "[]"))
        if (arr.length() > 0) arr.remove(0)
        p.edit().putString("pendingOps", arr.toString()).apply()
    }

    fun clearOps() = p.edit().remove("pendingOps").apply()

    /**
     * 서버 상태를 이 기기에 맞춘다(보낼 동작이 남아 있으면 부르지 않는다). 시각은 이 기기 시계로 바꾼 값.
     * 다른 기기가 시작한 집중이면 이 기기에서 고른 앱으로 잠그고, 다른 기기에서 풀었으면 여기도 푼다.
     */
    fun applyRemote(r: SyncApi.Remote) {
        if (r.active) {
            val e = p.edit()
            if (!active) {
                e.putBoolean("active", true)
                    .putBoolean("sessionLockSettings", lockSettings)
                    .remove("failAt")
            }
            e.putString("sessionTask", r.task)
                .putLong("startedAt", r.startedAt)
                .putInt("sessionWaitMinutes", r.waitMinutes)
                .putLong("waitUntil", r.waitUntil)
                .putString("sessionBy", if (r.by == deviceName) "" else r.by)
                .apply()
        } else if (active) {
            val where = if (r.endedBy.isNotBlank() && r.endedBy != deviceName) " (${r.endedBy})" else ""
            addRecord(Record(sessionTask, startedAt, System.currentTimeMillis(), (r.endedMethod.ifBlank { "해제" }) + where, note = r.endedNote))
            p.edit()
                .putBoolean("active", false)
                .putLong("waitUntil", 0L)
                .remove("failAt")
                .remove("sessionBy")
                .apply()
        }
    }

    /** 지금 막아야 하는 앱인지. */
    fun isBlocked(pkg: String): Boolean =
        active && (pkg in blocked || (sessionLockSettings && pkg in SETTINGS_PACKAGES))

    // ---- 인증 실패 쉬는 시간 ----
    val lastFailAt: Long get() = p.getLong("failAt", 0L)
    fun markFail(now: Long = System.currentTimeMillis()) = p.edit().putLong("failAt", now).apply()

    // ---- 기록 ----
    data class Record(
        val task: String,
        val start: Long,
        val end: Long,
        val method: String,
        val photo: String = "",
        val summary: String = "",
        val note: String = "",
    ) {
        /** 인증 사진 경로들(여러 장이면 줄바꿈으로 이어 저장). */
        fun photos(): List<String> = photo.split("\n").filter { it.isNotBlank() }
    }

    fun records(): List<Record> {
        val arr = JSONArray(p.getString("records", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Record(
                o.optString("task"), o.optLong("start"), o.optLong("end"), o.optString("method"),
                o.optString("photo"), o.optString("summary"), o.optString("note"),
            )
        }
    }

    private fun addRecord(r: Record) {
        val all = listOf(r) + records()
        val keep = all.take(MAX_RECORDS)
        // 오래된 기록의 인증 사진은 지운다
        for (old in all.drop(MAX_RECORDS)) old.photos().forEach { File(it).delete() }
        val arr = JSONArray(keep.map {
            JSONObject().put("task", it.task).put("start", it.start).put("end", it.end).put("method", it.method)
                .put("photo", it.photo).put("summary", it.summary).put("note", it.note)
        })
        p.edit().putString("records", arr.toString()).apply()
    }

    /** 인증 사진을 오래 남길 폴더. */
    fun photoDir(): File = File(app.filesDir, "records").apply { mkdirs() }

    companion object {
        const val DEFAULT_WAIT_MINUTES = 20
        const val FAIL_COOLDOWN_MS = 30_000L
        const val MAX_RECORDS = 30
        val WAIT_CHOICES = listOf(10, 20, 30, 60)
        /** "설정 앱도 잠그기"를 켰을 때 함께 막는 화면(설정, 앱 삭제 창). */
        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }
}

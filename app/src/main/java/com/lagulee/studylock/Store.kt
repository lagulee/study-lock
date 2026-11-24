package com.lagulee.studylock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 앱의 모든 설정과 집중 세션 상태를 이 기기 안에만 저장한다(다른 기기와 동기화하지 않음).
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
            .apply()
    }

    fun startWait(now: Long = System.currentTimeMillis()) {
        if (waitUntil == 0L) p.edit().putLong("waitUntil", now + sessionWaitMinutes * 60_000L).apply()
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
    )

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
        for (old in all.drop(MAX_RECORDS)) if (old.photo.isNotEmpty()) File(old.photo).delete()
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

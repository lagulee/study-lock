package com.lagulee.studylock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 앱의 모든 설정과 집중 세션 상태를 이 기기 안에만 저장한다(다른 기기와 동기화하지 않음).
 */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("study_lock", Context.MODE_PRIVATE)

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

    /** 사진에서 읽어야 하는 최소 글자 수(숫자 제외). */
    var minChars: Int
        get() = p.getInt("minChars", DEFAULT_MIN_CHARS)
        set(v) = p.edit().putInt("minChars", v).apply()

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
    val sessionMinChars: Int get() = p.getInt("sessionMinChars", minChars)
    val sessionLockSettings: Boolean get() = p.getBoolean("sessionLockSettings", false)
    /** 대기 해제가 끝나는 시각. 0 이면 대기를 시작하지 않음. */
    val waitUntil: Long get() = p.getLong("waitUntil", 0L)

    fun startSession(now: Long = System.currentTimeMillis()) {
        p.edit()
            .putBoolean("active", true)
            .putString("sessionTask", task.trim())
            .putLong("startedAt", now)
            .putInt("sessionWaitMinutes", waitMinutes)
            .putInt("sessionMinChars", minChars)
            .putBoolean("sessionLockSettings", lockSettings)
            .putLong("waitUntil", 0L)
            .remove("code").remove("codeAt").remove("failAt")
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

    fun endSession(method: String, now: Long = System.currentTimeMillis()) {
        if (!active) return
        addRecord(sessionTask, startedAt, now, method)
        p.edit()
            .putBoolean("active", false)
            .putLong("waitUntil", 0L)
            .remove("code").remove("codeAt").remove("failAt")
            .apply()
    }

    /** 지금 막아야 하는 앱인지. */
    fun isBlocked(pkg: String): Boolean =
        active && (pkg in blocked || (sessionLockSettings && pkg in SETTINGS_PACKAGES))

    // ---- 인증 숫자 ----
    /** 지금 쓸 4자리 숫자. 없거나 오래됐으면 새로 만든다. */
    fun currentCode(now: Long = System.currentTimeMillis()): String {
        val code = p.getString("code", null)
        val at = p.getLong("codeAt", 0L)
        if (code != null && now - at < CODE_VALID_MS) return code
        val fresh = Random.nextInt(1000, 10000).toString()
        p.edit().putString("code", fresh).putLong("codeAt", now).apply()
        return fresh
    }

    val lastFailAt: Long get() = p.getLong("failAt", 0L)
    fun markFail(now: Long = System.currentTimeMillis()) = p.edit().putLong("failAt", now).apply()

    // ---- 기록 ----
    data class Record(val task: String, val start: Long, val end: Long, val method: String)

    fun records(): List<Record> {
        val arr = JSONArray(p.getString("records", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Record(o.optString("task"), o.optLong("start"), o.optLong("end"), o.optString("method"))
        }
    }

    private fun addRecord(task: String, start: Long, end: Long, method: String) {
        val arr = JSONArray(p.getString("records", "[]"))
        val o = JSONObject().put("task", task).put("start", start).put("end", end).put("method", method)
        val list = mutableListOf(o)
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        p.edit().putString("records", JSONArray(list.take(MAX_RECORDS)).toString()).apply()
    }

    companion object {
        const val DEFAULT_WAIT_MINUTES = 20
        const val DEFAULT_MIN_CHARS = 30
        const val CODE_VALID_MS = 15 * 60_000L
        const val FAIL_COOLDOWN_MS = 30_000L
        const val MAX_RECORDS = 30
        val WAIT_CHOICES = listOf(10, 20, 30, 60)
        val MIN_CHAR_CHOICES = listOf(15, 30, 60)
        /** "설정 앱도 잠그기"를 켰을 때 함께 막는 화면(설정, 앱 삭제 창). */
        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )
    }
}

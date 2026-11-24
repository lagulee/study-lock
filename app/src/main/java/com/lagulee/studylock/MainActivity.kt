package com.lagulee.studylock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: Store
    private val tick = Handler(Looper.getMainLooper())
    private var liveText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    override fun onResume() {
        super.onResume()
        store.finishWaitIfDone()
        render()
        tick.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        tick.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (store.active && store.finishWaitIfDone()) {
                Toast.makeText(this@MainActivity, "기다린 시간이 끝나 잠금이 풀렸어요", Toast.LENGTH_LONG).show()
                render()
            }
            liveText?.text = liveStatus()
            tick.postDelayed(this, 1000)
        }
    }

    private fun liveStatus(): String {
        if (!store.active) return ""
        val now = System.currentTimeMillis()
        val sb = StringBuilder("시작 ${clock(store.startedAt)} · ${minutesText(now - store.startedAt)} 지남")
        if (store.waitUntil != 0L) sb.append("\n⏳ 대기 해제까지 ${remainText(store.waitUntil - now)}")
        return sb.toString()
    }

    private fun render() {
        page {
            text("공부 잠금", 26f, bold = true, topDp = 0)
            text("할 일을 끝내고 사진으로 인증해야 고른 앱이 풀려요.", 14f)

            // 준비 상태
            divider()
            val ready = blockerEnabled()
            text(if (ready) "✅ 앱 막기 기능이 켜져 있어요" else "❌ 앱 막기 기능이 꺼져 있어요", 16f, bold = true)
            if (!ready) {
                text("설정 → 접근성 → 설치된 앱 → '공부 잠금'을 켜 주세요. " +
                    "'제한된 설정'이라는 창이 뜨면 아래 두 번째 버튼으로 앱 정보에 가서 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜면 돼요.", 14f)
                button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                button("앱 정보 열기 (제한된 설정 허용)", topDp = 8) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }

            divider()
            if (store.active) activeSection() else setupSection(ready)

            // 기록
            val records = store.records()
            if (records.isNotEmpty()) {
                divider()
                text("기록", 18f, bold = true)
                for (r in records.take(10)) {
                    text("${clock(r.start)} · ${minutesText(r.end - r.start)} · ${r.method}\n   ${r.task}", 14f, topDp = 6)
                }
            }
        }
    }

    private fun LinearLayout.activeSection() {
        text("🔒 집중 중", 22f, bold = true)
        text("할 일: ${store.sessionTask}", 18f)
        liveText = text(liveStatus(), 14f)
        text("막힌 앱 ${store.blocked.size}개" + if (store.sessionLockSettings) " + 설정 앱" else "", 14f)
        button("📸 할 일 인증하고 풀기") { startActivity(Intent(this@MainActivity, VerifyActivity::class.java)) }
        if (store.waitUntil == 0L) {
            button("⏳ ${store.sessionWaitMinutes}분 기다려서 풀기", topDp = 8) { confirmWait() }
        }
        button("차단할 앱 더 넣기", topDp = 8) { startActivity(Intent(this@MainActivity, AppPickerActivity::class.java)) }
        text("집중 중에는 차단 앱을 빼거나 설정을 바꿀 수 없어요.", 13f)
    }

    private fun LinearLayout.setupSection(ready: Boolean) {
        liveText = null
        text("1. 할 일", 18f, bold = true)
        val input = EditText(context).apply {
            setText(store.task)
            hint = "예: 수학 문제집 24~27쪽 풀기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        addView(input)

        text("2. 막을 앱", 18f, bold = true, topDp = 16)
        text("${store.blocked.size}개 골랐어요", 14f)
        button("막을 앱 고르기") {
            store.task = input.text.toString()
            startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
        }

        text("3. 시작", 18f, bold = true, topDp = 16)
        text("풀려면: 할 일을 한 종이에 새 숫자를 써서 찍거나, ${store.waitMinutes}분 기다리기", 14f)
        button("🔒 집중 시작") {
            store.task = input.text.toString()
            when {
                !ready -> toast("먼저 앱 막기 기능(접근성)을 켜 주세요.")
                store.task.isBlank() -> toast("할 일을 적어 주세요.")
                store.blocked.isEmpty() -> toast("막을 앱을 하나 이상 골라 주세요.")
                else -> confirmStart()
            }
        }
        button("설정", topDp = 8) {
            store.task = input.text.toString()
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
    }

    private fun confirmStart() {
        AlertDialog.Builder(this)
            .setTitle("집중을 시작할까요?")
            .setMessage("할 일: ${store.task.trim()}\n\n고른 앱 ${store.blocked.size}개가 잠겨요. " +
                "할 일을 끝내고 사진으로 인증하거나 ${store.waitMinutes}분을 기다려야 풀려요.")
            .setPositiveButton("시작") { _, _ -> store.startSession(); render() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmWait() {
        AlertDialog.Builder(this)
            .setTitle("기다려서 풀까요?")
            .setMessage("지금부터 ${store.sessionWaitMinutes}분이 지나면 잠금이 풀려요. 기록에는 '대기 해제'로 남아요. 기다리는 동안에도 사진 인증으로 바로 풀 수 있어요.")
            .setPositiveButton("기다리기 시작") { _, _ -> store.startWait(); render() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

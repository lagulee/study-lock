package com.lagulee.studylock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** 막힌 앱을 열었을 때 뜨는 화면. */
class BlockActivity : Activity() {
    private lateinit var store: Store
    private val tick = Handler(Looper.getMainLooper())
    private var waitText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    override fun onResume() {
        super.onResume()
        if (!store.active || store.finishWaitIfDone()) {
            finish()
            return
        }
        render()
        tick.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        tick.removeCallbacks(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (store.finishWaitIfDone()) {
                finish()
                return
            }
            if (store.waitUntil != 0L) waitText?.text = "⏳ 대기 해제까지 ${remainText(store.waitUntil - System.currentTimeMillis())}"
            tick.postDelayed(this, 1000)
        }
    }

    private fun render() {
        val pkg = intent.getStringExtra(EXTRA_PKG)
        waitText = null
        page {
            text("🔒", 56f, center = true, topDp = 24)
            text("지금은 집중 시간이에요", 24f, bold = true, center = true)
            if (pkg != null) text("'${appLabel(pkg)}'은(는) 잠겨 있어요", 16f, center = true)
            text("할 일: ${store.sessionTask}", 18f, center = true, topDp = 20)
            text("${minutesText(System.currentTimeMillis() - store.startedAt)}째 집중 중", 14f, center = true)

            button("📸 할 일 인증하고 풀기", topDp = 28) {
                startActivity(Intent(this@BlockActivity, VerifyActivity::class.java))
            }
            if (store.waitUntil == 0L) {
                button("⏳ ${store.sessionWaitMinutes}분 기다려서 풀기", topDp = 8) {
                    store.startWait()
                    render()
                }
            } else {
                waitText = text("⏳ 대기 해제까지 ${remainText(store.waitUntil - System.currentTimeMillis())}", 16f, center = true, topDp = 12)
            }
            button("홈으로 가기", topDp = 8) { leave() }
        }
    }

    private fun leave() {
        goHome()
        finish()
    }

    // 뒤로 가기도 막힌 앱이 아니라 홈으로 보낸다
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = leave()

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}

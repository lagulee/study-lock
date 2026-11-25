package com.lagulee.studylock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private var syncText: TextView? = null
    /** 화면을 그릴 때의 상태. 다른 기기 때문에 바뀌면 다시 그린다. */
    private var drawn = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    override fun onResume() {
        super.onResume()
        store.finishWaitIfDone()
        WatchService.sync(this)
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
            if (drawnKey() != drawn) render()
            liveText?.text = liveStatus()
            syncText?.text = syncStatus()
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

    private fun drawnKey() = "${store.active}|${store.waitUntil}|${store.sessionTask}"

    private fun syncStatus(): String = when {
        !store.syncEnabled -> ""
        store.syncConnected -> "🔗 연동됨: ${store.syncDevices.joinToString(", ")}"
        else -> "🔗 연동 서버에 연결하는 중…"
    }

    private fun render() {
        drawn = drawnKey()
        page {
            text("공부 잠금", 26f, bold = true, topDp = 0)
            text("할 일을 끝내고 사진으로 인증해야 고른 앱이 풀려요.", 14f)

            // 준비 상태: 권한 2개
            divider()
            val usage = usageAccessGranted()
            val overlay = overlayGranted()
            val ready = usage && overlay
            text(if (ready) "✅ 앱 막기 준비 완료" else "❌ 앱 막기에 필요한 권한 2개를 켜 주세요", 16f, bold = true)
            if (!usage) {
                text("① 사용 정보 접근: 지금 어떤 앱이 켜졌는지만 알아요(화면 내용은 못 봐요). 목록에서 '공부 잠금'을 찾아 켜 주세요.", 14f)
                button("사용 정보 접근 켜기") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            }
            if (!overlay) {
                text("② 다른 앱 위에 표시: 막힌 앱 위에 잠금 화면을 띄워요.", 14f)
                button("다른 앱 위에 표시 켜기") { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            }
            if (!ready) {
                text("켜려고 할 때 '제한된 설정' 창이 뜨면, 아래 버튼 → 오른쪽 위 ⋮ → '제한된 설정 허용' 뒤에 다시 켜 주세요.", 13f)
                button("앱 정보 열기", topDp = 8) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            if (store.active && !ready) {
                text("⚠️ 집중 중인데 권한이 꺼져 있어서 지금은 앱이 막히지 않아요.", 14f, bold = true)
            }

            syncText = if (store.syncEnabled) text(syncStatus(), 14f) else null

            divider()
            if (store.active) activeSection() else setupSection(ready)

            // 기록
            val records = store.records()
            if (records.isNotEmpty()) {
                divider()
                text("기록", 18f, bold = true)
                text("눌러서 인증 사진과 한 일을 다시 볼 수 있어요.", 13f)
                records.take(15).forEachIndexed { i, r ->
                    val mark = if (r.photo.isNotEmpty()) "📷 " else ""
                    text("$mark${clock(r.start)} · ${minutesText(r.end - r.start)} · ${r.method}\n   ${r.task}", 14f, topDp = 8)
                        .setOnClickListener {
                            startActivity(Intent(this@MainActivity, PhotoActivity::class.java).putExtra(PhotoActivity.EXTRA_INDEX, i))
                        }
                }
            }
        }
    }

    private fun LinearLayout.activeSection() {
        text("🔒 집중 중", 22f, bold = true)
        text("할 일: ${store.sessionTask}", 18f)
        if (store.sessionBy.isNotBlank()) text("${store.sessionBy}에서 시작한 집중이에요.", 14f)
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
        val how = VerifyActivity.aiName(KeyBox.provider(store))?.let { "${it}가 사진을 확인" } ?: "사진 찍기"
        text("풀려면: $how, 또는 ${store.waitMinutes}분 기다리기", 14f)
        button("🔒 집중 시작") {
            store.task = input.text.toString()
            when {
                !ready -> toast("먼저 위의 권한 2개를 켜 주세요.")
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
                "할 일을 끝낸 뒤 사진으로 인증하거나, ${store.waitMinutes}분을 기다려야 풀려요.")
            .setPositiveButton("시작") { _, _ ->
                store.startSession()
                WatchService.sync(this)
                askNotificationPermission()
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmWait() {
        AlertDialog.Builder(this)
            .setTitle("기다려서 풀까요?")
            .setMessage("지금부터 ${store.sessionWaitMinutes}분이 지나면 잠금이 풀려요. 기록에는 '대기 해제'로 남아요. 기다리는 동안에도 사진 인증으로 바로 풀 수 있어요.")
            .setPositiveButton("기다리기 시작") { _, _ -> store.startWait(); WatchService.sync(this); render() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 설정 화면을 이 앱 항목으로 바로 연다(안 되는 폰이면 목록으로). */
    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(action))
        }
    }

    /** 안드로이드 13 이상: "집중 중" 알림이 보이도록 알림 허용을 한 번 묻는다(거절해도 차단은 된다). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

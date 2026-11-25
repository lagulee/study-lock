package com.lagulee.studylock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * 화면 점검용 스크린샷(CI 의 가상 기기에서만 돌림).
 * 상태를 직접 만들어 각 화면을 띄우고, 위·중간·아래로 스크롤하며 시스템 막대까지 포함해 찍는다.
 * 기기 크기·글씨 크기·다크 모드는 워크플로가 바꾸고, 이름 앞에 붙일 값을 variant 인자로 넘긴다.
 */
class ScreenshotTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val variant = InstrumentationRegistry.getArguments().getString("variant") ?: "dev"
    private val out = File(ctx.filesDir, "shots").apply { mkdirs() }

    @Test
    fun screens() {
        val pkg = ctx.packageName
        // 처음 깐 상태: 권한 없음, 기록 없음
        reset()
        shell("appops set $pkg GET_USAGE_STATS default")
        shell("appops set $pkg SYSTEM_ALERT_WINDOW default")
        shoot<MainActivity>("01_main_new")

        // 준비된 상태: 권한 켜짐, 할 일·막을 앱·기록 있음
        shell("appops set $pkg GET_USAGE_STATS allow")
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        val store = Store(ctx)
        store.blocked = setOf("com.android.chrome", "com.google.android.youtube", "com.android.settings")
        val now = System.currentTimeMillis()
        val tasks = listOf(
            "수학 문제집 24~27쪽 풀기", "영어 단어 3과 외우고 테스트지 풀기",
            "과학 탐구 보고서 결론 쓰기 (실험 3번·4번 결과를 표로 정리하고 그래프 그리기)", "국어 문학 작품 2개 분석",
        )
        tasks.forEachIndexed { i, t ->
            store.task = t
            store.startSession(now - (i + 2) * 3_600_000L)
            store.endSession(if (i % 2 == 0) "Gemini 인증" else "대기 해제 (탭)", now - (i + 1) * 3_600_000L - 600_000L,
                note = if (i == 0) "24~27쪽 모두 풀이와 채점 흔적이 보여요." else "")
        }
        store.task = "수학 문제집 28~31쪽 풀기"
        shoot<MainActivity>("02_main_ready")
        shoot<SettingsActivity>("03_settings")
        shoot<AppPickerActivity>("04_picker")
        shoot<PhotoActivity>("05_record") { it.putExtra(PhotoActivity.EXTRA_INDEX, 0) }

        // 키·연동이 있는 설정
        KeyBox.save(store, KeyBox.GEMINI, "AIzaSyFAKE-screenshot-only-0000000000")
        store.syncServer = "https://study-lock-sync.example.workers.dev"
        store.syncCode = "ABCDEFGHJKMN"
        store.syncConnected = true
        store.syncDevices = listOf("탭", "폰", "컴퓨터")
        shoot<SettingsActivity>("06_settings_keys")

        // 집중 중
        store.startSession()
        store.startWait()
        shoot<MainActivity>("07_main_active")
        shoot<SettingsActivity>("08_settings_locked")
        shoot<VerifyActivity>("09_verify")
        shoot<BlockActivity>("10_block") { it.putExtra(BlockActivity.EXTRA_PKG, "com.google.android.youtube") }
        store.endSession("테스트")
        store.syncCode = null
    }

    private fun reset() {
        ctx.getSharedPreferences("study_lock", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(cmd: String) {
        instr.uiAutomation.executeShellCommand(cmd).close()
    }

    private inline fun <reified A : Activity> shoot(name: String, extra: (Intent) -> Unit = {}) {
        val intent = Intent(ctx, A::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        extra(intent)
        val act = instr.startActivitySync(intent)
        settle()
        val scroll = findScroll(act.window.decorView)
        save("${variant}_${name}_1top")
        if (scroll != null) {
            val range = (scroll.getChildAt(0)?.height ?: 0) - (scroll.height - scroll.paddingTop - scroll.paddingBottom)
            if (range > 0) {
                var page = 2
                var y = 0
                while (y < range && page <= 6) {
                    y = minOf(range, y + (scroll.height * 0.8).toInt())
                    instr.runOnMainSync { scroll.scrollTo(0, y) }
                    settle()
                    save("${variant}_${name}_${page}${if (y >= range) "end" else "mid"}")
                    page++
                }
            }
        }
        instr.runOnMainSync { act.finish() }
        settle()
    }

    private fun settle() {
        instr.waitForIdleSync()
        Thread.sleep(700)
    }

    private fun save(name: String) {
        val bmp = instr.uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }
}

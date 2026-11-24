package com.lagulee.studylock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * 접근성 서비스: 다른 앱 화면이 앞으로 나올 때마다 알림을 받아,
 * 집중 중이고 차단 목록에 있는 앱이면 홈으로 보내고 잠금 화면을 띄운다.
 * 화면 내용은 읽지 않고, 어떤 앱이 열렸는지(앱 이름)만 본다.
 */
class BlockerService : AccessibilityService() {
    private var lastPkg = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val store = Store(this)
        if (!store.active) return
        if (store.finishWaitIfDone()) return
        if (!store.isBlocked(pkg)) return

        // 같은 앱에서 이벤트가 연달아 올 때 잠금 화면을 여러 번 띄우지 않기
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastPkg && now - lastAt < 700) return
        lastPkg = pkg
        lastAt = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    override fun onInterrupt() {}
}

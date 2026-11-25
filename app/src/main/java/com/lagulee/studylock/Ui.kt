package com.lagulee.studylock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 레이아웃 XML 없이 화면을 코드로 간단히 만드는 도우미. */
fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Activity.page(build: LinearLayout.() -> Unit): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        build()
    }
    // 다시 그릴 때(키 저장 등) 보던 자리를 유지한다
    val oldY = window.decorView.findViewWithTag<ScrollView>(PAGE_TAG)?.scrollY ?: 0
    val scroll = PageScroll(this).apply {
        tag = PAGE_TAG
        addView(col)
        isFillViewport = true
    }
    fitSystemBars(scroll)
    setContentView(scroll)
    if (oldY > 0) scroll.post { scroll.scrollTo(0, oldY) }
    return col
}

private const val PAGE_TAG = "page"

/**
 * 탭처럼 넓은 화면에서는 글이 너무 퍼지지 않게 가운데 640dp 만 쓰고, 양옆 여백은 스크롤 영역으로 남긴다.
 * (여백까지 스크롤 영역이라 화면 가장자리를 쓸어도 스크롤된다)
 */
private class PageScroll(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child != null) {
            val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val side = maxOf(context.dp(20), (inner - context.dp(MAX_PAGE_DP)) / 2 + context.dp(20))
            if (child.paddingLeft != side || child.paddingTop != context.dp(20)) {
                child.setPadding(side, context.dp(20), side, context.dp(28))
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private const val MAX_PAGE_DP = 640

/**
 * 안드로이드 15(타깃 SDK 35)부터 앱 화면이 상태 표시줄·내비게이션 바 밑까지 그려진다(edge-to-edge 강제).
 * 그래서 위 글자가 상태 표시줄에, 맨 아래 버튼이 내비게이션 바에, 입력칸이 키보드에 가려졌다.
 * 모든 안드로이드 11 이상에서 똑같이 화면 끝까지 그리고, 막대·노치·키보드만큼 안쪽으로 비켜 준다.
 */
private fun Activity.fitSystemBars(root: View) {
    if (Build.VERSION.SDK_INT < 30) return // 안드로이드 10 은 시스템이 알아서 비켜 준다
    window.setDecorFitsSystemWindows(false)
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    // 밝은 화면이면 상태 표시줄 아이콘을 어둡게(안 그러면 흰 바탕에 흰 아이콘이라 안 보임)
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    root.requestApplyInsets()
}

fun LinearLayout.text(s: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, center: Boolean = false, topDp: Int = 8): TextView =
    TextView(context).also {
        it.text = s
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) it.setTypeface(it.typeface, Typeface.BOLD)
        if (center) it.gravity = Gravity.CENTER_HORIZONTAL
        it.setLineSpacing(0f, 1.15f)
        addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(topDp) })
    }

fun LinearLayout.button(label: CharSequence, topDp: Int = 12, onClick: (View) -> Unit): Button =
    Button(context).also {
        it.text = label
        it.isAllCaps = false
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        it.minHeight = context.dp(52)
        it.setOnClickListener(onClick)
        addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(topDp) })
    }

fun LinearLayout.divider() {
    addView(View(context).apply { setBackgroundColor(0x33888888) },
        LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(16) })
}

fun clock(ms: Long): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(ms))

fun minutesText(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0)
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

/** 남은 시간을 "12분 30초"처럼. */
fun remainText(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0)
    return "${s / 60}분 ${s % 60}초"
}

fun Activity.goHome() {
    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

/** 라디오 버튼 묶음(고르면 onPick 호출). */
fun LinearLayout.choices(
    options: List<Int>, current: Int, label: (Int) -> String, locked: Boolean,
    vertical: Boolean = false, onPick: (Int) -> Unit,
) {
    val group = RadioGroup(context).apply { orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
    for (o in options) {
        group.addView(RadioButton(context).apply {
            id = View.generateViewId()
            text = label(o)
            textSize = 16f
            isEnabled = !locked
            isChecked = o == current
            setOnCheckedChangeListener { _, on -> if (on) onPick(o) }
        })
    }
    addView(group)
}

package com.lagulee.studylock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)
        build()
    }
    val scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
    // 탭 S10+ 같은 넓은 화면에서 너무 퍼지지 않게 가운데 정렬 + 최대 폭
    val frame = LinearLayout(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(scroll, LinearLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(640)), -1))
    }
    setContentView(frame)
    return col
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

/** 이 앱의 차단 기능(접근성 서비스)이 켜져 있는지. */
fun Context.blockerEnabled(): Boolean {
    val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = "$packageName/${BlockerService::class.java.name}"
    val shortMe = "$packageName/.${BlockerService::class.java.simpleName}"
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(on)
    return splitter.any { it.equals(me, true) || it.equals(shortMe, true) }
}

fun Activity.goHome() {
    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

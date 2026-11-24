package com.lagulee.studylock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

/** 홈 화면에 있는 앱 목록에서 막을 앱을 고른다. 집중 중에는 추가만 된다. */
class AppPickerActivity : Activity() {
    private data class App(val pkg: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(compareByDescending<App> { it.pkg in store.blocked }.thenBy { it.label.lowercase() })

        val boxes = mutableListOf<Pair<App, CheckBox>>()
        page {
            text("막을 앱 고르기", 22f, bold = true, topDp = 0)
            text(if (store.active) "집중 중이라 새로 넣기만 할 수 있어요." else "공부할 때 손이 가는 앱을 골라 주세요. 고른 것은 바로 저장돼요.", 14f)
            val search = EditText(context).apply { hint = "앱 이름 찾기" }
            addView(search)
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
            for (app in apps) {
                val box = CheckBox(context).apply {
                    text = app.label
                    textSize = 17f
                    minHeight = dp(48)
                    isChecked = app.pkg in store.blocked
                    // 집중 중에는 이미 막힌 앱을 뺄 수 없다
                    isEnabled = !(store.active && isChecked)
                    setOnCheckedChangeListener { _, on ->
                        store.blocked = if (on) store.blocked + app.pkg else store.blocked - app.pkg
                    }
                }
                list.addView(box)
                boxes.add(app to box)
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    for ((app, box) in boxes) {
                        box.visibility = if (q.isEmpty() || app.label.lowercase().contains(q)) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            })
            button("완료") { finish() }
        }
    }
}

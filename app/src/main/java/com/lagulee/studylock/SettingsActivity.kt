package com.lagulee.studylock

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch

/** 대기 시간·필요 글자 수·설정 앱 잠그기. 집중 중에는 바꿀 수 없다. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val locked = store.active
        page {
            text("설정", 22f, bold = true, topDp = 0)
            if (locked) text("집중 중에는 설정을 바꿀 수 없어요.", 14f)

            text("기다려서 풀 때 걸리는 시간", 17f, bold = true, topDp = 16)
            choices(Store.WAIT_CHOICES, store.waitMinutes, { "${it}분" }, locked) { store.waitMinutes = it }

            text("사진에서 읽어야 하는 글씨 양", 17f, bold = true, topDp = 16)
            text("숫자를 뺀 글자 수예요. 손글씨는 일부만 읽힐 수 있어서, 인증이 자꾸 실패하면 줄여 보세요.", 13f)
            choices(Store.MIN_CHAR_CHOICES, store.minChars, { "${it}자" }, locked) { store.minChars = it }

            text("설정 앱도 잠그기", 17f, bold = true, topDp = 16)
            text("켜면 집중하는 동안 설정 앱과 앱 삭제 창도 막혀서, 앱 막기 기능을 끄거나 이 앱을 지우는 꼼수를 막아요. " +
                "급할 때는 기다려서 풀기를 쓰면 돼요.", 13f)
            val sw = Switch(context).apply {
                text = "설정 앱도 잠그기"
                textSize = 16f
                isChecked = store.lockSettings
                isEnabled = !locked
                setOnCheckedChangeListener { _, on -> store.lockSettings = on }
            }
            addView(sw)

            button("완료", topDp = 24) { finish() }
        }
    }

    private fun LinearLayout.choices(options: List<Int>, current: Int, label: (Int) -> String, locked: Boolean, onPick: (Int) -> Unit) {
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        for (o in options) {
            val rb = RadioButton(context).apply {
                id = android.view.View.generateViewId()
                text = label(o)
                textSize = 16f
                isEnabled = !locked
                isChecked = o == current
                setOnCheckedChangeListener { _, on -> if (on) onPick(o) }
            }
            group.addView(rb)
        }
        addView(group)
    }
}

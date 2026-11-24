package com.lagulee.studylock

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/** 기록 하나의 인증 사진과 요약을 다시 본다. */
class PhotoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent.getIntExtra(EXTRA_INDEX, -1)
        val r = Store(this).records().getOrNull(i) ?: run { finish(); return }
        page {
            text(r.task, 20f, bold = true, topDp = 0)
            text("${clock(r.start)} ~ ${clock(r.end)} · ${minutesText(r.end - r.start)} · ${r.method}", 14f)
            for (path in r.photos()) {
                BitmapFactory.decodeFile(path)?.let { bmp ->
                    addView(ImageView(context).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                }
            }
            if (r.summary.isNotBlank()) {
                text("한 일", 16f, bold = true, topDp = 16)
                text(r.summary, 15f)
            }
            if (r.note.isNotBlank()) {
                text("AI 의견", 16f, bold = true, topDp = 16)
                text(r.note, 15f)
            }
            button("닫기", topDp = 20) { finish() }
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}

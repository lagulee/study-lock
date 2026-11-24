package com.lagulee.studylock

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 할 일 인증.
 * 1) 최소 집중 시간이 지나야 열린다.
 * 2) 한 일을 사진으로 찍는다.
 *    - Claude/Gemini 키가 있으면: AI가 할 일과 사진을 비교해 판단한다(둘 다 있으면 Claude).
 *    - 키가 없으면: 사진은 기록으로 남고 바로 풀린다.
 */
class VerifyActivity : Activity() {
    private lateinit var store: Store
    private val tick = Handler(Looper.getMainLooper())
    private val shot by lazy { File(cacheDir, "photos/verify.jpg") }
    private var resultArea: LinearLayout? = null
    private var countdown: TextView? = null
    private var busy = false

    /** 사진을 판단할 AI(없으면 null). */
    private val provider: String? get() = KeyBox.provider(store)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.active) {
            finish()
            return
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        tick.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        if (!store.active) { finish(); return }
        tick.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val left = store.verifyFrom - System.currentTimeMillis()
            if (countdown != null) {
                if (left <= 0) {
                    render()
                    return
                }
                countdown?.text = "⏳ ${remainText(left)} 더 집중하면 인증할 수 있어요"
            }
            tick.postDelayed(this, 1000)
        }
    }

    private fun render() {
        countdown = null
        val ai = aiName(provider)
        page {
            text("할 일 인증", 22f, bold = true, topDp = 0)
            text("할 일: ${store.sessionTask}", 17f)
            text("${minutesText(System.currentTimeMillis() - store.startedAt)}째 집중 중 · 최소 ${store.sessionMinFocus}분", 14f)

            val left = store.verifyFrom - System.currentTimeMillis()
            if (left > 0) {
                countdown = text("⏳ ${remainText(left)} 더 집중하면 인증할 수 있어요", 18f, bold = true, center = true, topDp = 24)
                text("집중 시작할 때 정한 최소 시간이 지나야 인증 버튼이 열려요. 급하면 '기다려서 풀기'를 쓰세요.", 14f)
                button("돌아가기", topDp = 20) { finish() }
                return@page
            }

            text("📷 할 일을 한 결과물(문제집·노트)이 잘 보이게, 밝은 곳에서 찍어 주세요.", 14f, topDp = 12)
            if (ai != null) text("${ai}가 할 일과 사진을 비교해서 판단해요.", 13f)
            else text("사진은 기록으로 남고 바로 풀려요. 설정에 AI 키를 넣으면 AI가 사진을 직접 판단해요.", 13f)
            button("📷 사진 찍고 인증하기", topDp = 12) { takePhoto() }
            resultArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(resultArea)
            button("돌아가기", topDp = 8) { finish() }
        }
    }

    private fun takePhoto() {
        if (busy) return
        val wait = Store.FAIL_COOLDOWN_MS - (System.currentTimeMillis() - store.lastFailAt)
        if (wait > 0) {
            toast("${wait / 1000 + 1}초 뒤에 다시 시도할 수 있어요.")
            return
        }
        shot.parentFile?.mkdirs()
        shot.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", shot)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PHOTO)
        } catch (e: Exception) {
            toast("카메라 앱을 열 수 없어요.")
        }
    }

    @Deprecated("간단한 화면이라 예전 방식 결과 받기를 쓴다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || !shot.exists()) return
        val bitmap = loadUpright(shot, MAX_EDGE) ?: run { toast("사진을 열 수 없어요."); return }
        val saved = savePhoto(bitmap)
        val who = provider
        val key = who?.let { KeyBox.load(store, it) }

        val area = resultArea ?: return
        area.removeAllViews()
        area.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            maxHeight = dp(320)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        if (who == null || key == null) {
            // 키 없는 방식: 최소 집중 시간은 이미 지났고 사진은 기록으로 남는다
            pass(saved.path, "시간+사진", "")
            return
        }

        busy = true
        val name = aiName(who)
        val status = area.text("${name}가 확인하는 중… (10초쯤 걸려요)", 16f, center = true)
        val b64 = Base64.encodeToString(jpeg(bitmap), Base64.NO_WRAP)
        val task = store.sessionTask
        val light = store.useLightModel
        val geminiSmart = store.geminiSmart
        Thread {
            val outcome = if (who == KeyBox.CLAUDE) ClaudeCheck.check(key, light, task, b64)
            else GeminiCheck.check(key, geminiSmart, task, b64)
            runOnUiThread {
                busy = false
                area.removeView(status)
                when (outcome) {
                    is AiCheck.Outcome.Verdict ->
                        if (outcome.pass) pass(saved.path, "$name 인증", outcome.reason)
                        else fail(outcome.reason, saved)
                    is AiCheck.Outcome.Failed -> {
                        saved.delete()
                        showMessage("⚠️ 확인하지 못했어요", outcome.message + "\n급하면 '기다려서 풀기'를 쓸 수 있어요.")
                    }
                }
            }
        }.start()
    }

    private fun pass(photo: String, method: String, reason: String) {
        store.endSession(method, photo = photo, note = reason)
        val area = resultArea ?: return
        area.text("✅ 인증 완료! 잠금이 풀렸어요", 20f, bold = true, center = true)
        if (reason.isNotBlank()) area.text(reason, 15f, center = true)
        area.button("확인") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun fail(reason: String, saved: File) {
        saved.delete()
        store.markFail()
        showMessage("❌ 아직 인정하기 어려워요", reason + "\n30초 뒤에 다시 찍을 수 있어요.")
    }

    private fun showMessage(title: String, body: String) {
        val area = resultArea ?: return
        area.text(title, 19f, bold = true, center = true, topDp = 12)
        area.text(body, 15f)
    }

    /** 인증 사진을 기록 폴더에 저장한다(긴 변 MAX_EDGE). */
    private fun savePhoto(bitmap: Bitmap): File {
        val f = File(store.photoDir(), "${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { it.write(jpeg(bitmap)) }
        shot.delete()
        return f
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PHOTO = 7
        /** Claude가 이미지를 가장 잘 보는 크기(긴 변 1568px 이하). */
        const val MAX_EDGE = 1568

        fun aiName(provider: String?): String? = when (provider) {
            KeyBox.CLAUDE -> "Claude"
            KeyBox.GEMINI -> "Gemini"
            else -> null
        }

        /** 사진을 줄여 읽고, 카메라가 기록한 방향대로 똑바로 돌린다. */
        fun loadUpright(file: File, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = try {
                when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(raw.width, raw.height))
            if (rotation == 0f && scale == 1f) return raw
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
    }
}

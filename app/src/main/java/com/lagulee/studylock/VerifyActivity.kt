package com.lagulee.studylock

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 할 일 인증. 한 일을 사진으로 찍는다(여러 쪽이면 여러 장, 최대 MAX_PHOTOS장).
 *    - Claude/Gemini 키가 있으면: AI가 할 일과 사진들을 엄격하게 비교해 판단한다(둘 다 있으면 Claude).
 *    - 키가 없으면: 사진은 기록으로 남고 바로 풀린다.
 */
class VerifyActivity : Activity() {
    private lateinit var store: Store
    private val shot by lazy { File(cacheDir, "photos/verify.jpg") }
    /** 찍어 둔 사진(기록 폴더에 저장됨). 인증에 성공하면 기록이 가져가고, 그냥 나가면 지운다. */
    private val photos = mutableListOf<File>()
    private var resultArea: LinearLayout? = null
    private var busy = false
    private var done = false

    /** 사진을 판단할 AI(없으면 null). */
    private val provider: String? get() = KeyBox.provider(store)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.active) {
            finish()
            return
        }
        // 카메라를 여는 동안 이 화면이 닫혔다 다시 열려도 찍어 둔 사진을 이어서 쓴다
        savedInstanceState?.getStringArrayList(KEY_PHOTOS)?.map(::File)?.filter { it.exists() }?.let(photos::addAll)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PHOTOS, ArrayList(photos.map { it.path }))
    }

    override fun onResume() {
        super.onResume()
        if (!store.active && !done) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !done) photos.forEach { it.delete() }
    }

    private fun render() {
        val ai = aiName(provider)
        page {
            text("할 일 인증", 22f, bold = true, topDp = 0)
            text("할 일: ${store.sessionTask}", 17f)
            text("${minutesText(System.currentTimeMillis() - store.startedAt)}째 집중 중", 14f)

            text("📷 한 일(문제집·노트)이 잘 보이게, 밝은 곳에서 찍어 주세요. " +
                "여러 쪽을 했으면 쪽마다 한 장씩, 최대 ${MAX_PHOTOS}장까지 찍을 수 있어요.", 14f, topDp = 12)
            if (ai != null) text("${ai}가 할 일과 사진을 꼼꼼하게 비교해요. 범위가 한 쪽이라도 안 보이면 통과하지 못해요.", 13f)
            else text("사진은 기록으로 남고 바로 풀려요. 설정에 AI 키를 넣으면 AI가 사진을 직접 판단해요.", 13f)

            if (photos.isNotEmpty()) {
                val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (f in photos) {
                    thumb(f)?.let { bmp ->
                        strip.addView(ImageView(context).apply {
                            setImageBitmap(bmp)
                            adjustViewBounds = true
                        }, LinearLayout.LayoutParams(-2, dp(120)).apply { marginEnd = dp(8) })
                    }
                }
                addView(HorizontalScrollView(context).apply { addView(strip) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            text("사진 ${photos.size}/${MAX_PHOTOS}장", 15f, bold = true)

            if (photos.size < MAX_PHOTOS) {
                button(if (photos.isEmpty()) "📷 사진 찍기" else "📷 사진 한 장 더 찍기", topDp = 8) { takePhoto() }
            }
            if (photos.isNotEmpty()) {
                button("✅ 사진 ${photos.size}장으로 인증하기", topDp = 8) { submit() }
                button("🗑 사진 모두 지우고 다시 찍기", topDp = 8) {
                    if (!busy) {
                        photos.forEach { it.delete() }
                        photos.clear()
                        render()
                    }
                }
            }
            resultArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(resultArea)
            button("돌아가기", topDp = 16) { finish() }
        }
    }

    private fun takePhoto() {
        if (busy || photos.size >= MAX_PHOTOS) return
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
        photos.add(savePhoto(bitmap))
        render()
    }

    private fun submit() {
        if (busy || photos.isEmpty()) return
        val wait = Store.FAIL_COOLDOWN_MS - (System.currentTimeMillis() - store.lastFailAt)
        if (wait > 0) {
            toast("${wait / 1000 + 1}초 뒤에 다시 시도할 수 있어요.")
            return
        }
        val who = provider
        val key = who?.let { KeyBox.load(store, it) }
        if (who == null || key == null) {
            // 키 없는 방식: 사진은 기록으로 남는다
            pass("사진", "")
            return
        }

        val area = resultArea ?: return
        area.removeAllViews()
        busy = true
        val name = aiName(who)
        val status = area.text("${name}가 사진 ${photos.size}장을 확인하는 중… (10~30초쯤 걸려요)", 16f, center = true, topDp = 12)
        val files = photos.toList()
        val task = store.sessionTask
        val light = store.useLightModel
        val geminiSmart = store.geminiSmart
        Thread {
            val jpegs = files.map { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
            val outcome = if (who == KeyBox.CLAUDE) ClaudeCheck.check(key, light, task, jpegs)
            else GeminiCheck.check(key, geminiSmart, task, jpegs)
            runOnUiThread {
                busy = false
                area.removeView(status)
                when (outcome) {
                    is AiCheck.Outcome.Verdict ->
                        if (outcome.pass) pass("$name 인증", outcome.reason)
                        else {
                            store.markFail()
                            showMessage("❌ 아직 인정하기 어려워요",
                                outcome.reason + "\n빠진 쪽을 더 찍거나 다시 찍은 뒤, 30초 뒤에 다시 인증할 수 있어요.")
                        }
                    is AiCheck.Outcome.Failed ->
                        showMessage("⚠️ 확인하지 못했어요", outcome.message + "\n급하면 '기다려서 풀기'를 쓸 수 있어요.")
                }
            }
        }.start()
    }

    private fun pass(method: String, reason: String) {
        store.endSession(method, photo = photos.joinToString("\n") { it.path }, note = reason)
        done = true
        page {
            text("✅ 인증 완료! 잠금이 풀렸어요", 22f, bold = true, center = true, topDp = 24)
            if (reason.isNotBlank()) text(reason, 15f, center = true)
            button("확인", topDp = 20) {
                startActivity(Intent(this@VerifyActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
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
        private const val KEY_PHOTOS = "photos"
        /** 한 번에 보낼 수 있는 사진 수. */
        const val MAX_PHOTOS = 5
        /** Claude가 이미지를 가장 잘 보는 크기(긴 변 1568px 이하). */
        const val MAX_EDGE = 1568

        fun aiName(provider: String?): String? = when (provider) {
            KeyBox.CLAUDE -> "Claude"
            KeyBox.GEMINI -> "Gemini"
            else -> null
        }

        /** 미리보기용으로 작게 읽는다(이미 똑바로 저장된 사진). */
        fun thumb(file: File): Bitmap? =
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })

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

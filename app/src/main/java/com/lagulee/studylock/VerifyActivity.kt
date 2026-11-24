package com.lagulee.studylock

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File

/**
 * 사진 인증: 새 4자리 숫자를 보여 주고, 할 일을 한 종이에 그 숫자를 손으로 써서 찍게 한다.
 * 기기 안의 글자 인식(ML Kit, 인터넷 불필요)으로 숫자와 글씨 양을 확인한다.
 */
class VerifyActivity : Activity() {
    private lateinit var store: Store
    private val photo by lazy { File(cacheDir, "photos/verify.jpg") }
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /** 화면 아래에 결과를 보여 줄 자리 */
    private var resultArea: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.active) {
            finish()
            return
        }
        render()
    }

    private fun render() {
        val code = store.currentCode()
        page {
            text("할 일 인증", 22f, bold = true, topDp = 0)
            text("할 일: ${store.sessionTask}", 17f)
            text("이 숫자를 할 일을 한 종이(문제집·노트)에 손으로 크게 써 주세요", 15f, topDp = 16)
            text(code, 56f, bold = true, center = true)
            text("• 숫자와 푼 내용이 함께 나오게, 밝은 곳에서 찍어 주세요\n" +
                "• 글씨를 ${store.sessionMinChars}자·${OcrCheck.MIN_LINES}줄 이상 읽어야 통과해요\n" +
                "• 숫자는 15분마다 바뀌어요", 14f)
            button("📷 사진 찍기", topDp = 20) { takePhoto() }
            resultArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(resultArea)
            button("돌아가기", topDp = 8) { finish() }
        }
    }

    private fun takePhoto() {
        val wait = Store.FAIL_COOLDOWN_MS - (System.currentTimeMillis() - store.lastFailAt)
        if (wait > 0) {
            toast("${wait / 1000 + 1}초 뒤에 다시 찍을 수 있어요.")
            return
        }
        photo.parentFile?.mkdirs()
        photo.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", photo)
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
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || !photo.exists()) return
        val bitmap = loadSmall(photo) ?: run { toast("사진을 열 수 없어요."); return }
        showChecking(bitmap)
        val image = InputImage.fromBitmap(bitmap, exifRotation(photo))
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks.flatMap { b -> b.lines.map { it.text } }
                showResult(OcrCheck.evaluate(lines, store.currentCode(), store.sessionMinChars), lines)
            }
            .addOnFailureListener { toast("글자를 읽지 못했어요. 다시 찍어 주세요.") }
    }

    private fun showChecking(bitmap: Bitmap) {
        val area = resultArea ?: return
        area.removeAllViews()
        area.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            maxHeight = dp(320)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        area.text("확인하는 중…", 16f, center = true)
    }

    private fun showResult(r: OcrCheck.Result, lines: List<String>) {
        val area = resultArea ?: return
        if (area.childCount > 1) area.removeViewAt(area.childCount - 1)
        if (r.pass) {
            store.endSession("사진 인증")
            area.text("✅ 인증 완료! 잠금이 풀렸어요", 20f, bold = true, center = true)
            area.text(r.reason, 14f, center = true)
            area.button("확인") {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        } else {
            store.markFail()
            area.text("❌ 아직 통과하지 못했어요", 20f, bold = true, center = true)
            area.text(r.reason, 15f)
            val read = lines.joinToString(" / ").take(200)
            area.text("읽은 글씨: " + read.ifBlank { "(없음)" }, 12f)
        }
    }

    /** 큰 사진을 긴 변 2048px 이하로 줄여 읽는다(메모리 절약, 글자 인식에는 충분). */
    private fun loadSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PHOTO = 7
    }
}

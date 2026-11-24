package com.lagulee.studylock

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini가 할 일과 인증 사진을 비교해 판단한다(REST 호출, 추가 라이브러리 없음).
 * 무료로 쓰면 보낸 사진이 구글의 AI 학습에 쓰이고 사람이 검토할 수 있다(Gemini API 약관).
 * 네트워크를 쓰므로 화면(메인) 스레드가 아닌 곳에서 부른다.
 */
object GeminiCheck {
    const val MODEL_LIGHT = "gemini-3.5-flash-lite"
    const val MODEL_SMART = "gemini-3.8-flash"

    fun check(apiKey: String, smart: Boolean, task: String, jpegBase64: String): AiCheck.Outcome {
        val model = if (smart) MODEL_SMART else MODEL_LIGHT
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AiCheck.SYSTEM))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray()
                            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64)))
                            .put(JSONObject().put("text", AiCheck.userText(task)))
                    )
                )
            )
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put(
                        "responseSchema", JSONObject()
                            .put("type", "OBJECT")
                            .put(
                                "properties", JSONObject()
                                    .put("pass", JSONObject().put("type", "BOOLEAN"))
                                    .put("reason", JSONObject().put("type", "STRING"))
                            )
                            .put("required", JSONArray().put("pass").put("reason"))
                    )
            )

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            when {
                code == 400 && text.contains("API_KEY_INVALID") ->
                    AiCheck.Outcome.Failed("Gemini API 키가 맞지 않아요. 설정에서 키를 다시 확인해 주세요.")
                code == 401 || code == 403 ->
                    AiCheck.Outcome.Failed("이 Gemini 키로는 쓸 수 없어요(권한 없음). 설정에서 키를 확인해 주세요.")
                code == 404 ->
                    AiCheck.Outcome.Failed("Gemini 모델($model)을 찾지 못했어요. 설정에서 다른 Gemini 모델을 골라 보세요.")
                code == 429 ->
                    AiCheck.Outcome.Failed("오늘 무료 사용 횟수를 넘었거나 너무 자주 보냈어요. 잠시 뒤에 다시 시도해 주세요.")
                code !in 200..299 ->
                    AiCheck.Outcome.Failed("Gemini 서버 오류예요($code). 잠시 뒤에 다시 시도해 주세요.")
                else -> readAnswer(text)
            }
        } catch (e: IOException) {
            AiCheck.Outcome.Failed("인터넷에 연결할 수 없어요. 와이파이나 데이터를 확인해 주세요.")
        } catch (e: Exception) {
            AiCheck.Outcome.Failed("확인 중 오류가 났어요: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            conn.disconnect()
        }
    }

    /** generateContent 응답에서 모델이 쓴 글을 꺼내 판단 결과로 바꾼다. */
    fun readAnswer(response: String): AiCheck.Outcome {
        val json = JSONObject(response)
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            return AiCheck.Outcome.Failed("Gemini가 이 사진은 판단하지 않겠다고 했어요($it). 다른 각도로 다시 찍어 주세요.")
        }
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: return AiCheck.Outcome.Failed("Gemini 답이 비어 있어요. 다시 시도해 주세요.")
        val text = buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (!p.optBoolean("thought", false)) append(p.optString("text", ""))
            }
        }
        return AiCheck.parse(text)
    }
}

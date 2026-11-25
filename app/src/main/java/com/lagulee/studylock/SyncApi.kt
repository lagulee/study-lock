package com.lagulee.studylock

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 연동 서버(Cloudflare)와 주고받기. 네트워크를 쓰므로 화면(메인) 스레드가 아닌 곳에서 부른다(소켓은 스스로 다른 스레드).
 */
object SyncApi {
    /** 배포한 서버 주소. 비어 있으면 설정에서 직접 넣는다. */
    const val DEFAULT_SERVER = ""

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // 끊긴 연결을 빨리 알아채고 다시 잇기
            .build()
    }

    /** 서버 상태를 이 기기 시계 기준으로 바꾼 것. */
    data class Remote(
        val rev: Long,
        val active: Boolean,
        val task: String,
        val startedAt: Long,
        val waitMinutes: Int,
        val waitUntil: Long,
        val by: String,
        val endedMethod: String,
        val endedBy: String,
        val endedNote: String,
        val devices: List<String>,
    )

    class SyncException(message: String, val permanent: Boolean = false) : Exception(message)

    /** "study-lock-sync.abc.workers.dev" 처럼 써도 https:// 를 붙이고 끝의 / 를 뗀다. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    /** 사람이 친 코드를 정리(대문자, 띄어쓰기·- 제거). 형식이 틀리면 null. */
    fun normalizeCode(raw: String): String? {
        val s = raw.uppercase().filter { it != ' ' && it != '-' }
        return if (s.length == 12 && s.all { it in CODE_ALPHABET }) s else null
    }

    fun prettyCode(code: String): String = code.chunked(4).joinToString("-")

    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** 서버 응답 {state, devices, now} 를 읽는다. 시각은 서버와 이 기기 시계 차이만큼 옮긴다. */
    fun parse(msg: JSONObject, localNow: Long = System.currentTimeMillis()): Remote {
        val s = msg.getJSONObject("state")
        val offset = msg.optLong("now", localNow) - localNow
        fun local(t: Long) = if (t == 0L) 0L else t - offset
        val ended = s.optJSONObject("ended")
        val devices = msg.optJSONArray("devices")
        return Remote(
            rev = s.optLong("rev"),
            active = s.optBoolean("active"),
            task = s.optString("task"),
            startedAt = local(s.optLong("startedAt")),
            waitMinutes = s.optInt("waitMinutes", 20),
            waitUntil = local(s.optLong("waitUntil")),
            by = s.optString("by"),
            endedMethod = ended?.optString("method").orEmpty(),
            endedBy = ended?.optString("by").orEmpty(),
            endedNote = ended?.optString("note").orEmpty(),
            devices = if (devices == null) emptyList() else (0 until devices.length()).map { devices.optString(it) },
        )
    }

    private fun call(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { res -> return readBody(res) }
        } catch (e: IOException) {
            throw SyncException("연동 서버에 연결할 수 없어요. 인터넷이나 서버 주소를 확인해 주세요.")
        }
    }

    private fun readBody(res: Response): JSONObject {
        val text = res.body?.string().orEmpty()
        val json = try { JSONObject(text) } catch (e: Exception) { null }
        when {
            res.code == 404 && json?.optString("error") == "noGroup" ->
                throw SyncException("없는 연동 코드예요. 코드를 다시 확인해 주세요.", permanent = true)
            res.code == 400 && json?.optString("error") == "badCode" ->
                throw SyncException("연동 코드 형식이 틀렸어요.", permanent = true)
            json == null || res.code !in 200..299 ->
                throw SyncException("연동 서버 오류예요(${res.code}). 서버 주소를 확인해 주세요.")
        }
        return json!!
    }

    fun createGroup(server: String): String {
        val req = Request.Builder().url("${normalizeServer(server)}/api/group").post("".toRequestBody()).build()
        return call(req).getString("code")
    }

    fun state(server: String, code: String): JSONObject =
        call(Request.Builder().url("${normalizeServer(server)}/g/$code/state").get().build())

    fun send(server: String, code: String, op: JSONObject): JSONObject {
        val body = op.toString().toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url("${normalizeServer(server)}/g/$code/op").post(body).build())
    }

    /** 상태가 바뀔 때마다 onMessage 로 받는 소켓. 끊기면 onClosed 가 불린다(다시 잇기는 부르는 쪽이 한다). */
    fun listen(server: String, code: String, name: String, onMessage: (JSONObject) -> Unit, onClosed: () -> Unit): WebSocket {
        val base = normalizeServer(server).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$base/g/$code/ws?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        return http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (e: Exception) { return }
                if (json.optString("type") == "state") onMessage(json)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed()
        })
    }
}

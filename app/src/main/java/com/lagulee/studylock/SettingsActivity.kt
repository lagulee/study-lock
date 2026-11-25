package com.lagulee.studylock

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

/** 대기 시간·설정 앱 잠그기·AI 키. 집중 중에는 바꿀 수 없다. */
class SettingsActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        render()
    }

    private fun render() {
        val locked = store.active
        page {
            text("설정", 22f, bold = true, topDp = 0)
            if (locked) text("집중 중에는 설정을 바꿀 수 없어요.", 14f)

            text("기다려서 풀 때 걸리는 시간", 17f, bold = true, topDp = 16)
            choices(Store.WAIT_CHOICES, store.waitMinutes, { "${it}분" }, locked) { store.waitMinutes = it }

            text("설정 앱도 잠그기", 17f, bold = true, topDp = 16)
            text("켜면 집중하는 동안 설정 앱과 앱 삭제 창도 막혀서, 권한을 끄거나 이 앱을 지우는 꼼수를 막아요. " +
                "급할 때는 기다려서 풀기를 쓰면 돼요.", 13f)
            addView(Switch(context).apply {
                text = "켜기"
                textSize = 16f
                isChecked = store.lockSettings
                isEnabled = !locked
                setOnCheckedChangeListener { _, on -> store.lockSettings = on }
            })

            divider()
            syncSection(locked)

            divider()
            text("AI 사진 판단 (선택)", 17f, bold = true, topDp = 16)
            text("AI 키를 넣으면 AI가 할 일과 인증 사진을 비교해서 실제로 했는지 판단해요. " +
                "키는 이 기기 안에만 암호화해서 저장돼요. 키가 없으면 사진을 찍기만 하면 풀려요. " +
                "두 키가 다 있으면 Claude를 먼저 써요.", 13f)

            text("Claude 키", 16f, bold = true, topDp = 16)
            text("사진을 AI 학습에 쓰지 않아요. 충전한 만큼만 쓰는 유료예요.", 13f)
            keySection(KeyBox.CLAUDE, "sk-ant-로 시작하는 키 붙여넣기", locked) { k ->
                if (k.startsWith("sk-ant-")) null else "Claude API 키는 sk-ant- 로 시작해요."
            }
            text("Claude 모델", 15f, bold = true, topDp = 12)
            choices(listOf(0, 1), if (store.useLightModel) 1 else 0, {
                if (it == 0) "가장 똑똑한 모델 (1장 약 20원)" else "가벼운 모델 (1장 약 1원)"
            }, locked, vertical = true) { store.useLightModel = it == 1 }

            text("Gemini 키", 16f, bold = true, topDp = 20)
            text("무료로 쓸 수 있지만, 무료일 때는 보낸 사진이 구글의 AI 학습과 사람 검토에 쓰일 수 있어요. " +
                "이름·얼굴이 사진에 안 나오게 찍어 주세요.", 13f)
            keySection(KeyBox.GEMINI, "Gemini API 키 붙여넣기", locked) { k ->
                if (k.length >= 20 && !k.contains(' ')) null else "Gemini API 키가 너무 짧거나 이상해요. 다시 복사해 주세요."
            }
            text("Gemini 모델", 15f, bold = true, topDp = 12)
            choices(listOf(0, 1), if (store.geminiSmart) 1 else 0, {
                if (it == 0) "가벼운 모델 (무료 횟수가 넉넉함)" else "더 똑똑한 모델 (무료 횟수가 적음)"
            }, locked, vertical = true) { store.geminiSmart = it == 1 }

            button("완료", topDp = 24) { finish() }
        }
    }

    /** 다른 기기(폰·탭·컴퓨터)와 연동: 같은 연동 코드를 넣은 기기끼리 집중 시작·해제를 같이 한다. */
    private fun LinearLayout.syncSection(locked: Boolean) {
        text("다른 기기와 연동 (선택)", 17f, bold = true, topDp = 16)
        text("폰·탭·컴퓨터에 같은 연동 코드를 넣으면, 한 곳에서 집중을 시작하거나 풀 때 다른 기기도 똑같이 따라가요. " +
            "막는 앱은 기기마다 따로 골라요.", 13f)

        text("이 기기 이름", 15f, bold = true, topDp = 12)
        val nameInput = EditText(context).apply {
            setText(store.deviceName)
            hint = "예: 폰, 탭"
            isEnabled = !locked
        }
        addView(nameInput)

        text("연동 서버 주소", 15f, bold = true, topDp = 12)
        val serverInput = EditText(context).apply {
            setText(store.syncServer)
            hint = "예: study-lock-sync.이름.workers.dev"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // 긴 주소가 잘리지 않게 줄바꿈
            isSingleLine = false
            maxLines = 3
            setHorizontallyScrolling(false)
            isEnabled = !locked && store.syncCode == null
        }
        addView(serverInput)

        val code = store.syncCode
        if (code != null) {
            text("연동 코드: ${SyncApi.prettyCode(code)}", 20f, bold = true, topDp = 12)
            text("다른 기기 설정에서 이 코드를 넣으면 함께 잠겨요.", 13f)
            text(if (store.syncConnected) "연결된 기기: ${store.syncDevices.joinToString(", ")}" else "연동 서버에 연결하는 중…", 14f)
            if (!locked) {
                button("이름 저장", topDp = 8) {
                    store.deviceName = nameInput.text.toString()
                    toast("저장했어요. 다시 연결할 때 새 이름으로 보여요.")
                    restartService()
                }
                button("연동 끊기", topDp = 8) {
                    store.syncCode = null
                    restartService()
                    render()
                }
            }
            return
        }
        if (locked) return
        text("연동 코드", 15f, bold = true, topDp = 12)
        val codeInput = EditText(context).apply { hint = "다른 기기에서 받은 코드 (예: ABCD-EFGH-JKMN)" }
        addView(codeInput)
        button("이 코드로 연결", topDp = 8) {
            val c = SyncApi.normalizeCode(codeInput.text.toString())
            val server = SyncApi.normalizeServer(serverInput.text.toString())
            when {
                server.isBlank() -> toast("연동 서버 주소를 넣어 주세요.")
                c == null -> toast("연동 코드는 12글자예요. 다시 확인해 주세요.")
                else -> joinGroup(server, nameInput.text.toString()) { SyncApi.state(server, c); c }
            }
        }
        button("새 연동 만들기 (첫 기기일 때)", topDp = 8) {
            val server = SyncApi.normalizeServer(serverInput.text.toString())
            if (server.isBlank()) toast("연동 서버 주소를 넣어 주세요.")
            else joinGroup(server, nameInput.text.toString()) { SyncApi.createGroup(server) }
        }
    }

    /** 서버와 확인한 뒤 연동 코드를 저장한다(네트워크는 다른 스레드에서). */
    private fun joinGroup(server: String, name: String, get: () -> String) {
        toast("연동 서버에 연결하는 중…")
        Thread {
            val result = try { Result.success(get()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                result.onSuccess { c ->
                    store.syncServer = server
                    if (name.isNotBlank()) store.deviceName = name
                    store.syncCode = c
                    store.syncConnected = false
                    WatchService.sync(this)
                    toast("연동했어요!")
                    render()
                }.onFailure { e -> toast(e.message ?: "연결하지 못했어요.") }
            }
        }.start()
    }

    private fun restartService() {
        stopService(android.content.Intent(this, WatchService::class.java))
        WatchService.sync(this)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** 키 하나의 저장·지우기 칸. check 가 글을 돌려주면 저장하지 않고 그 글을 보여 준다. */
    private fun LinearLayout.keySection(name: String, hint: String, locked: Boolean, check: (String) -> String?) {
        val key = KeyBox.load(store, name)
        if (key != null) {
            text("🔑 저장된 키: ${KeyBox.masked(key)}", 15f)
            if (!locked) button("키 지우기", topDp = 8) {
                KeyBox.clear(store, name)
                render()
            }
            return
        }
        if (locked) {
            text("키 없음", 15f)
            return
        }
        val input = EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // 비밀번호 칸은 기본이 고정폭 글꼴이라 안내 글자가 띄엄띄엄 보였다
            typeface = Typeface.DEFAULT
        }
        addView(input)
        button("키 저장", topDp = 8) {
            val k = input.text.toString().trim()
            val problem = check(k)
            if (problem != null) {
                Toast.makeText(this@SettingsActivity, problem, Toast.LENGTH_SHORT).show()
            } else {
                KeyBox.save(store, name, k)
                render()
            }
        }
    }
}

package com.lagulee.studylock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncApiTest {
    @Test fun 서버_주소와_코드를_정리한다() {
        assertEquals("https://a.workers.dev", SyncApi.normalizeServer(" a.workers.dev/ "))
        assertEquals("http://127.0.0.1:8799", SyncApi.normalizeServer("http://127.0.0.1:8799"))
        assertEquals("ABCDEFGHJKMN", SyncApi.normalizeCode("abcd-efgh jkmn"))
        assertNull(SyncApi.normalizeCode("ABCD-EFGH-IJK0"))
        assertEquals("ABCD-EFGH-JKMN", SyncApi.prettyCode("ABCDEFGHJKMN"))
    }

    @Test fun 서버_시각을_이_기기_시계로_옮긴다() {
        val msg = JSONObject("""{"type":"state","now":10000,"devices":["폰","컴퓨터"],
            "state":{"rev":3,"active":true,"task":"수학","startedAt":4000,"waitMinutes":10,"waitUntil":9000,"by":"컴퓨터","ended":null}}""")
        val r = SyncApi.parse(msg, localNow = 7000) // 이 기기 시계가 서버보다 3초 느림
        assertEquals(1000L, r.startedAt)
        assertEquals(6000L, r.waitUntil)
        assertEquals(listOf("폰", "컴퓨터"), r.devices)
        assertEquals("컴퓨터", r.by)
    }

    @Test fun 해제_정보를_읽는다() {
        val msg = JSONObject("""{"now":1,"devices":[],"state":{"rev":4,"active":false,"task":"수학","startedAt":0,
            "waitMinutes":20,"waitUntil":0,"by":"폰","ended":{"method":"Gemini 인증","by":"폰","note":"좋아요","at":1}}}""")
        val r = SyncApi.parse(msg, localNow = 1)
        assertEquals(false, r.active)
        assertEquals(0L, r.waitUntil)
        assertEquals("Gemini 인증", r.endedMethod)
        assertEquals("좋아요", r.endedNote)
    }
}

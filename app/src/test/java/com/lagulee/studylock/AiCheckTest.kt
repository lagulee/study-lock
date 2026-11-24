package com.lagulee.studylock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCheckTest {
    @Test fun 통과_답을_읽는다() {
        val r = AiCheck.parse("""{"pass": true, "reason": "24~27쪽 풀이가 보여요."}""")
        assertEquals(AiCheck.Outcome.Verdict(true, "24~27쪽 풀이가 보여요."), r)
    }

    @Test fun 앞뒤에_글이_있어도_JSON만_읽는다() {
        val r = AiCheck.parse("결과입니다\n{\"pass\": false, \"reason\": \"빈 쪽이에요.\"}\n끝")
        assertEquals(AiCheck.Outcome.Verdict(false, "빈 쪽이에요."), r)
    }

    @Test fun 엉뚱한_답은_실패로_처리한다() {
        assertTrue(AiCheck.parse("모르겠어요") is AiCheck.Outcome.Failed)
        assertTrue(AiCheck.parse("{\"reason\": \"pass 없음\"}") is AiCheck.Outcome.Failed)
    }

    @Test fun Gemini_응답에서_생각_부분은_빼고_읽는다() {
        val res = """{"candidates":[{"content":{"parts":[
            {"text":"{\"pass\": true, \"reason\": \"속임\"}","thought":true},
            {"text":"{\"pass\": false, \"reason\": \"다른 과목이에요.\"}"}
        ]}}]}"""
        assertEquals(AiCheck.Outcome.Verdict(false, "다른 과목이에요."), GeminiCheck.readAnswer(res))
    }

    @Test fun Gemini가_거절하면_실패() {
        val res = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertTrue(GeminiCheck.readAnswer(res) is AiCheck.Outcome.Failed)
        assertTrue(GeminiCheck.readAnswer("""{"candidates":[]}""") is AiCheck.Outcome.Failed)
    }
}

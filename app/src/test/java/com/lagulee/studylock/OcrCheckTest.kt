package com.lagulee.studylock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCheckTest {
    private val page = listOf(
        "3. 이차방정식 x^2 - 5x + 6 = 0 을 풀어라",
        "(x-2)(x-3) = 0 따라서 x = 2 또는 3",
        "4. 근의 공식을 이용하면",
        "x = (5 ± 1) / 2",
    )

    @Test fun 숫자와_충분한_글씨가_있으면_통과() {
        val r = OcrCheck.evaluate(page + "4827", "4827", 30)
        assertTrue(r.reason, r.pass)
    }

    @Test fun 숫자가_없으면_실패() {
        val r = OcrCheck.evaluate(page, "4827", 30)
        assertFalse(r.pass)
        assertTrue(r.reason.contains("4827"))
    }

    @Test fun 다른_숫자면_실패() {
        assertFalse(OcrCheck.evaluate(page + "4828", "4827", 30).pass)
    }

    @Test fun 빈_종이에_숫자만_쓰면_실패() {
        val r = OcrCheck.evaluate(listOf("4827"), "4827", 30)
        assertFalse(r.pass)
        assertEquals(0, r.chars)
    }

    @Test fun 손글씨를_헷갈려_읽어도_숫자로_인정() {
        // 4 8 2 7 을 띄어 쓰고, 1 을 l 로, 0 을 O 로 읽은 경우
        assertTrue(OcrCheck.hasCode(listOf("4 8 2 7"), "4827"))
        assertTrue(OcrCheck.hasCode(listOf("l9O5"), "1905"))
        assertTrue(OcrCheck.hasCode(listOf("코드: 48", "27"), "4827"))
    }

    @Test fun 글자_수는_숫자_코드를_빼고_센다() {
        val r = OcrCheck.evaluate(listOf("abc", "def", "ghi", "1234"), "1234", 9)
        assertEquals(9, r.chars)
        assertTrue(r.pass)
    }
}

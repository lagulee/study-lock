package com.lagulee.studylock

/**
 * 사진에서 읽은 글자(OCR 결과)를 보고 인증을 통과시킬지 정한다.
 * 안드로이드 기능을 쓰지 않는 순수 함수라 컴퓨터에서 단위 테스트할 수 있다.
 *
 * 통과 조건
 *  1) 화면에 보여 준 4자리 숫자가 사진 속 글씨에 있다(손글씨를 잘못 읽기 쉬운 글자는 숫자로 바꿔 본다).
 *  2) 그 숫자를 뺀 글자(한글·영어·숫자)가 minChars 자 이상이고, 글씨 줄이 MIN_LINES 줄 이상이다.
 */
object OcrCheck {
    const val MIN_LINES = 3

    data class Result(val pass: Boolean, val reason: String, val chars: Int, val lines: Int)

    /** 손글씨 숫자를 글자 인식기가 헷갈리기 쉬운 짝. */
    private val LOOKALIKE = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'ㅇ' to '0',
        'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1', 'ㅣ' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'G' to '6', 'b' to '6',
        'T' to '7',
        'B' to '8',
        'g' to '9', 'q' to '9',
    )

    /** 한 줄에서 공백을 지우고 헷갈리는 글자를 숫자로 바꾼다. */
    fun asDigits(line: String): String =
        line.filterNot { it.isWhitespace() || it == '-' || it == '.' || it == ',' }
            .map { LOOKALIKE[it] ?: it }
            .joinToString("")

    fun hasCode(lines: List<String>, code: String): Boolean =
        lines.any { asDigits(it).contains(code) } ||
            // 숫자를 두 줄에 걸쳐 쓴 경우(예: 48 / 27)
            lines.zipWithNext().any { (a, b) -> (asDigits(a) + asDigits(b)).contains(code) }

    fun countChars(text: String): Int = text.count { it.isLetterOrDigit() }

    fun evaluate(lines: List<String>, code: String, minChars: Int): Result {
        val all = lines.joinToString("\n")
        val chars = (countChars(all) - code.length).coerceAtLeast(0)
        val written = lines.count { line -> line.count { it.isLetterOrDigit() } >= 2 }
        if (!hasCode(lines, code)) {
            return Result(false, "사진에서 숫자 $code 을(를) 찾지 못했어요. 종이에 크고 또렷하게 써서 다시 찍어 주세요.", chars, written)
        }
        if (chars < minChars || written < MIN_LINES) {
            return Result(
                false,
                "글씨가 너무 적어요(읽은 글자 ${chars}자·${written}줄, 필요 ${minChars}자·${MIN_LINES}줄). 할 일을 한 쪽 전체가 밝게 나오게 찍어 주세요.",
                chars, written,
            )
        }
        return Result(true, "숫자 $code 확인, 글씨 ${chars}자·${written}줄을 읽었어요.", chars, written)
    }
}

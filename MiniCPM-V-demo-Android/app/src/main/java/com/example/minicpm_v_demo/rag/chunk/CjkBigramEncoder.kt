package com.example.minicpm_v_demo.rag.chunk

object CjkBigramEncoder {
    fun encode(text: String): String {
        val terms = mutableListOf<String>()
        val cjkRun = StringBuilder()
        val other = StringBuilder()

        fun flushCjk() {
            val points = cjkRun.codePoints().toArray()
            when (points.size) {
                0 -> Unit
                1 -> terms += String(Character.toChars(points[0]))
                else -> for (index in 0 until points.lastIndex) {
                    terms += String(Character.toChars(points[index])) + String(Character.toChars(points[index + 1]))
                }
            }
            cjkRun.setLength(0)
        }
        fun flushOther() {
            other.toString().trim().takeIf(String::isNotEmpty)?.let(terms::add)
            other.setLength(0)
        }

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                isCjk(codePoint) -> {
                    flushOther()
                    cjkRun.appendCodePoint(codePoint)
                }
                Character.isLetterOrDigit(codePoint) || codePoint == '-'.code || codePoint == '_'.code -> {
                    flushCjk()
                    other.appendCodePoint(codePoint)
                }
                else -> {
                    flushCjk()
                    flushOther()
                }
            }
            index += Character.charCount(codePoint)
        }
        flushCjk()
        flushOther()
        return terms.joinToString(" ")
    }

    private fun isCjk(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) in setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )
}

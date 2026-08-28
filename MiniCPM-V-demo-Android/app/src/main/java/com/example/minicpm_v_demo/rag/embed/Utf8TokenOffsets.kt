package com.example.minicpm_v_demo.rag.embed

object Utf8TokenOffsets {
    fun toUtf16Boundaries(text: String, utf8Offsets: IntArray): List<Int> {
        val boundaryMap = HashMap<Int, Int>()
        var utf8Index = 0
        var utf16Index = 0
        boundaryMap[0] = 0
        while (utf16Index < text.length) {
            val codePoint = text.codePointAt(utf16Index)
            utf8Index += when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            utf16Index += Character.charCount(codePoint)
            boundaryMap[utf8Index] = utf16Index
        }
        return utf8Offsets.map { offset ->
            require(offset in 0..utf8Index) { "UTF-8 offset is outside input" }
            requireNotNull(boundaryMap[offset]) { "UTF-8 offset splits a code point" }
        }
    }
}

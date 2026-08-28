package com.example.minicpm_v_demo.rag.parser

import org.xml.sax.Attributes

class XlsxParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> {
        val entries = SafeOoxmlReader.read(input) { name ->
            name == SHARED_STRINGS || (name.startsWith(SHEET_PREFIX) && name.endsWith(".xml"))
        }
        val sharedStrings = entries[SHARED_STRINGS]?.let { bytes ->
            SharedStringsHandler().also { SafeOoxmlReader.parseXml(bytes, it) }.values
        }.orEmpty()
        val blocks = mutableListOf<ParsedBlock>()
        var totalChars = 0
        entries.entries.asSequence()
            .filter { (name, _) -> name.startsWith(SHEET_PREFIX) && name.endsWith(".xml") }
            .sortedBy { (name, _) -> sheetNumber(name) }
            .forEach { (name, bytes) ->
                val sheetName = "sheet${sheetNumber(name)}"
                val handler = SheetHandler(sheetName, sharedStrings)
                SafeOoxmlReader.parseXml(bytes, handler)
                handler.blocks.forEach { block ->
                    totalChars += block.text.length
                    if (totalChars > input.maxChars) fail(ParserError.TEXT_LIMIT_EXCEEDED)
                    blocks += block
                }
            }
        if (blocks.isEmpty() && entries.keys.none { it.startsWith(SHEET_PREFIX) }) {
            fail(ParserError.MALFORMED_DOCUMENT)
        }
        return blocks.asSequence()
    }

    private class SharedStringsHandler : BoundedXmlHandler() {
        val values = mutableListOf<String>()
        private val value = StringBuilder()
        private var inItem = false
        private var inText = false

        override fun onStart(name: String, attributes: Attributes) {
            if (name == "si") { inItem = true; value.setLength(0) }
            if (name == "t" && inItem) inText = true
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) value.append(ch, start, length)
        }
        override fun onEnd(name: String) {
            if (name == "t") inText = false
            if (name == "si") { values += value.toString(); inItem = false }
        }
    }

    private class SheetHandler(
        private val sheetName: String,
        private val shared: List<String>,
    ) : BoundedXmlHandler() {
        val blocks = mutableListOf<ParsedBlock>()
        private val cells = mutableListOf<Pair<String, String>>()
        private val value = StringBuilder()
        private var cellRef = ""
        private var cellType = ""
        private var inValue = false
        private var formula: String? = null
        private var inFormula = false

        override fun onStart(name: String, attributes: Attributes) {
            when (name) {
                "row" -> cells.clear()
                "c" -> { cellRef = attributes.value("r").orEmpty(); cellType = attributes.value("t").orEmpty(); formula = null }
                "v", "t" -> { value.setLength(0); inValue = true }
                "f" -> { value.setLength(0); inFormula = true }
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue || inFormula) value.append(ch, start, length)
        }
        override fun onEnd(name: String) {
            when (name) {
                "f" -> { formula = value.toString().trim(); inFormula = false }
                "v", "t" -> inValue = false
                "c" -> {
                    val raw = value.toString().trim()
                    val resolved = when (cellType) {
                        "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                        else -> raw
                    }
                    val displayed = formula?.takeIf { it.isNotEmpty() }?.let { "=$it -> $resolved" } ?: resolved
                    cells += cellRef.ifEmpty { "?" } to displayed
                }
                "row" -> if (cells.any { it.second.isNotEmpty() }) {
                    val first = cells.first().first
                    val last = cells.last().first
                    blocks += ParsedBlock(
                        text = cells.joinToString(" | ") { it.second },
                        structure = BlockStructure.TABLE_ROW,
                        titlePath = sheetName,
                        locatorType = "cell-range",
                        locatorValue = "$sheetName!$first:$last",
                    )
                }
            }
        }
    }

    companion object {
        private const val SHARED_STRINGS = "xl/sharedStrings.xml"
        private const val SHEET_PREFIX = "xl/worksheets/sheet"
        private fun sheetNumber(name: String): Int = name.substringAfter(SHEET_PREFIX).substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
    }
}

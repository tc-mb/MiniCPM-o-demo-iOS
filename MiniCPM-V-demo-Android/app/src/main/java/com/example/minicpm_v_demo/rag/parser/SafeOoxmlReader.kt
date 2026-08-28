package com.example.minicpm_v_demo.rag.parser

import com.example.minicpm_v_demo.rag.config.RagLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal object SafeOoxmlReader {
    fun read(input: ParserInput, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var expandedTotal = 0L
        ZipInputStream(input.input.buffered()).use { zip ->
            while (true) {
                if (!input.shouldContinue()) fail(ParserError.CANCELLED)
                val entry = zip.nextEntry ?: break
                if (++entryCount > RagLimits.MAX_OOXML_ENTRIES) fail(ParserError.ZIP_BOMB_RISK)
                val name = validateEntryName(entry.name)
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val retain = wanted(name) && !isForbiddenPayload(name)
                val bytes = ByteArrayOutputStream().also { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (!input.shouldContinue()) fail(ParserError.CANCELLED)
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        expandedTotal += count
                        if (expandedTotal > RagLimits.MAX_OOXML_UNCOMPRESSED_BYTES ||
                            (retain && output.size() + count > MAX_RELEVANT_ENTRY_BYTES)
                        ) fail(ParserError.ZIP_BOMB_RISK)
                        if (retain) output.write(buffer, 0, count)
                    }
                }.toByteArray()
                val compressed = entry.compressedSize
                val expandedEntry = if (retain) bytes.size.toLong() else entry.size
                if (compressed > 0 && expandedEntry > 0 &&
                    expandedEntry.toDouble() / compressed > RagLimits.MAX_COMPRESSION_RATIO
                ) {
                    fail(ParserError.ZIP_BOMB_RISK)
                }
                if (retain) result[name] = bytes
                zip.closeEntry()
            }
        }
        return result
    }

    fun parseXml(bytes: ByteArray, handler: BoundedXmlHandler) {
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, XML_PROLOG_SCAN_BYTES))
            .toString(Charsets.UTF_8).uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in prefix || "<!ENTITY" in prefix) fail(ParserError.UNSAFE_XML)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            factory.newSAXParser().xmlReader.apply {
                entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities disabled") }
                contentHandler = handler
                parse(InputSource(ByteArrayInputStream(bytes)))
            }
        } catch (error: Exception) {
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<ParserException>()
                .firstOrNull()
                ?.let { throw it }
            fail(ParserError.UNSAFE_XML)
        }
    }

    private fun validateEntryName(raw: String): String {
        val name = raw.replace('\\', '/')
        if (name.isBlank() || name.startsWith('/') || DRIVE_PREFIX.containsMatchIn(name) ||
            name.split('/').any { it == ".." || it == "." }
        ) fail(ParserError.ZIP_SLIP)
        return name
    }

    private fun isForbiddenPayload(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith("vbaproject.bin") || "/embeddings/" in lower || "/externallinks/" in lower
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_RELEVANT_ENTRY_BYTES = 32 * 1024 * 1024
    private const val XML_PROLOG_SCAN_BYTES = 64 * 1024
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}

internal abstract class BoundedXmlHandler : DefaultHandler() {
    private var depth = 0

    final override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        if (++depth > RagLimits.MAX_XML_DEPTH) fail(ParserError.XML_DEPTH_LIMIT)
        onStart(elementName(localName, qName), attributes)
    }

    final override fun endElement(uri: String?, localName: String?, qName: String?) {
        onEnd(elementName(localName, qName))
        depth--
    }

    protected open fun onStart(name: String, attributes: Attributes) = Unit
    protected open fun onEnd(name: String) = Unit
    protected fun Attributes.value(local: String): String? {
        for (index in 0 until length) {
            if (elementName(getLocalName(index), getQName(index)) == local) return getValue(index)
        }
        return null
    }

    private fun elementName(local: String?, qualified: String?): String =
        local?.takeIf { it.isNotEmpty() } ?: qualified.orEmpty().substringAfter(':')
}

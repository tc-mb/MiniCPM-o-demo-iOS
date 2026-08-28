package com.example.minicpm_v_demo.rag.parser

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object ParsedBlockCodec {
    fun write(blocks: Sequence<ParsedBlock>, destination: OutputStream) {
        DataOutputStream(BufferedOutputStream(destination)).use { output ->
            output.write(MAGIC)
            output.writeByte(VERSION)
            var count = 0
            for (block in blocks) {
                if (++count > MAX_BLOCKS) fail(ParserError.TEXT_LIMIT_EXCEEDED)
                output.writeByte(block.structure.ordinal)
                output.writeBounded(block.text, MAX_TEXT_BYTES)
                output.writeBounded(block.titlePath.orEmpty(), MAX_METADATA_BYTES)
                output.writeBounded(block.locatorType, MAX_METADATA_BYTES)
                output.writeBounded(block.locatorValue, MAX_METADATA_BYTES)
            }
            output.writeByte(RECORD_END)
        }
    }

    fun read(source: InputStream): Sequence<ParsedBlock> = sequence {
        DataInputStream(BufferedInputStream(source)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC) || input.readUnsignedByte() != VERSION) {
                fail(ParserError.MALFORMED_DOCUMENT)
            }
            var count = 0
            while (true) {
                val structure = try { input.readUnsignedByte() } catch (_: EOFException) { fail(ParserError.MALFORMED_DOCUMENT) }
                if (structure == RECORD_END) break
                if (++count > MAX_BLOCKS || structure !in BlockStructure.entries.indices) fail(ParserError.MALFORMED_DOCUMENT)
                val text = input.readBounded(MAX_TEXT_BYTES)
                val title = input.readBounded(MAX_METADATA_BYTES).ifEmpty { null }
                val locatorType = input.readBounded(MAX_METADATA_BYTES)
                val locatorValue = input.readBounded(MAX_METADATA_BYTES)
                yield(ParsedBlock(text, BlockStructure.entries[structure], title, locatorType, locatorValue))
            }
        }
    }

    private fun DataOutputStream.writeBounded(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) fail(ParserError.RECORD_TOO_LARGE)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBounded(maxBytes: Int): String {
        val size = readInt()
        if (size !in 0..maxBytes) fail(ParserError.MALFORMED_DOCUMENT)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private val MAGIC = "RPB1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val RECORD_END = 255
    private const val MAX_BLOCKS = 1_000_000
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 16 * 1024
}

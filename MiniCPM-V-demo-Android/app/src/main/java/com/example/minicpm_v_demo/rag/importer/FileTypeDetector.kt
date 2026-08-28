package com.example.minicpm_v_demo.rag.importer

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class DetectedFileType {
    EMPTY,
    TEXT,
    PDF,
    PNG,
    JPEG,
    WEBP,
    OOXML_ZIP,
    UNSUPPORTED_BINARY,
}

data class FileTypeDetection(
    val type: DetectedFileType,
    val declarationMismatch: Boolean,
)

object FileTypeDetector {
    fun detect(
        header: ByteArray,
        declaredMimeType: String?,
        displayName: String?,
        sampleIsComplete: Boolean = true,
    ): FileTypeDetection {
        val detected = when {
            header.isEmpty() -> DetectedFileType.EMPTY
            header.startsWith(PDF_MAGIC) -> DetectedFileType.PDF
            header.startsWith(PNG_MAGIC) -> DetectedFileType.PNG
            header.startsWith(JPEG_MAGIC) -> DetectedFileType.JPEG
            header.isWebp() -> DetectedFileType.WEBP
            header.startsWith(ZIP_MAGIC) -> DetectedFileType.OOXML_ZIP
            header.looksLikeUtf8Text(sampleIsComplete) -> DetectedFileType.TEXT
            else -> DetectedFileType.UNSUPPORTED_BINARY
        }
        return FileTypeDetection(
            type = detected,
            declarationMismatch = mimeMismatch(detected, declaredMimeType) || extensionMismatch(detected, displayName),
        )
    }

    private fun mimeMismatch(type: DetectedFileType, declaredMimeType: String?): Boolean {
        val mime = declaredMimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (mime == "application/octet-stream") return false
        return when (type) {
            DetectedFileType.EMPTY -> false
            DetectedFileType.TEXT -> !mime.startsWith("text/") && mime !in TEXT_APPLICATION_MIMES
            DetectedFileType.PDF -> mime != "application/pdf"
            DetectedFileType.PNG -> mime != "image/png"
            DetectedFileType.JPEG -> mime !in setOf("image/jpeg", "image/jpg")
            DetectedFileType.WEBP -> mime != "image/webp"
            DetectedFileType.OOXML_ZIP -> mime !in OOXML_MIMES && mime != "application/zip"
            DetectedFileType.UNSUPPORTED_BINARY -> false
        }
    }

    private fun extensionMismatch(type: DetectedFileType, displayName: String?): Boolean {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (extension.isEmpty()) return false
        val declaredByExtension = when (extension) {
            "txt", "md", "csv", "html", "htm" -> DetectedFileType.TEXT
            "pdf" -> DetectedFileType.PDF
            "png" -> DetectedFileType.PNG
            "jpg", "jpeg" -> DetectedFileType.JPEG
            "webp" -> DetectedFileType.WEBP
            "docx", "xlsx", "pptx" -> DetectedFileType.OOXML_ZIP
            else -> return false
        }
        return declaredByExtension != type
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.isWebp(): Boolean =
        size >= 12 &&
            copyOfRange(0, 4).contentEquals(RIFF_MAGIC) &&
            copyOfRange(8, 12).contentEquals(WEBP_MAGIC)

    private fun ByteArray.looksLikeUtf8Text(sampleIsComplete: Boolean): Boolean {
        if (any { it == 0.toByte() }) return false
        return runCatching {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val output = CharBuffer.allocate(size.coerceAtLeast(1))
            val result = decoder.decode(ByteBuffer.wrap(this), output, sampleIsComplete)
            !result.isError && (!sampleIsComplete || decoder.flush(output).isUnderflow)
        }.getOrDefault(false)
    }

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val JPEG_MAGIC = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
    private val TEXT_APPLICATION_MIMES = setOf("application/json", "application/xml", "application/csv")
    private val OOXML_MIMES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}

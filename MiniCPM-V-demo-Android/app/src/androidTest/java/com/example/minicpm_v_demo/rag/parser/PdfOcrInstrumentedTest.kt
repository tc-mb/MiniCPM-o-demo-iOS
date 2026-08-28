package com.example.minicpm_v_demo.rag.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfOcrInstrumentedTest {
    @Test
    fun blankScannedPageRequestsOcrButSelectableTextPageDoesNot() {
        val blankParser = PdfDocumentParser()
        blankParser.parse(input(pdfBytes())).toList()
        assertTrue(blankParser.requiresOcr)

        val text = "This selectable PDF paragraph has enough readable characters for indexing without OCR."
        val textParser = PdfDocumentParser()
        val blocks = textParser.parse(input(pdfBytes(text))).toList()
        assertFalse(textParser.requiresOcr)
        assertTrue(blocks.single().text.contains("selectable PDF paragraph"))
        assertEquals("1", blocks.single().locatorValue)
    }

    @Test
    fun bundledRecognizerReadsRenderedOfficeTextWithoutNetwork() {
        val bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 92f }
        canvas.drawText("Invoice 12345", 40f, 180f, paint)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                30,
                TimeUnit.SECONDS,
            )
            assertTrue(result.text.replace(" ", "").contains("12345"))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    @Test
    fun corruptPdfReturnsStableNonSensitiveError() {
        val error = assertThrows(ParserException::class.java) {
            PdfDocumentParser().parse(input("%PDF-corrupt secret body".toByteArray())).toList()
        }
        assertEquals(ParserError.PDF_CORRUPT, error.error)
        assertFalse(error.message.orEmpty().contains("secret body"))
    }

    private fun input(bytes: ByteArray) = ParserInput(ByteArrayInputStream(bytes))

    private fun pdfBytes(text: String? = null): ByteArray = ByteArrayOutputStream().also { output ->
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            if (text != null) {
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(40f, 700f)
                    content.showText(text)
                    content.endText()
                }
            }
            document.save(output)
        }
    }.toByteArray()

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializePdfBox() {
            PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
        }
    }
}

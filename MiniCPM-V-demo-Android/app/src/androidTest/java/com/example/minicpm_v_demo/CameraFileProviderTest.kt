package com.example.minicpm_v_demo

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraFileProviderTest {

    @Test
    fun providerIsPrivateAndOnlyServesTheCameraCacheDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)

        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val cameraFile = File.createTempFile("provider-test-", ".jpg", cameraDir)
        try {
            val uri = FileProvider.getUriForFile(context, authority, cameraFile)
            assertEquals("content", uri.scheme)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(byteArrayOf(0x01, 0x02, 0x03))
            }
            assertEquals(3L, cameraFile.length())

            val outsideFile = File.createTempFile("outside-camera-", ".jpg", context.cacheDir)
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, authority, outsideFile)
                }
            } finally {
                outsideFile.delete()
            }
        } finally {
            cameraFile.delete()
        }
    }
}

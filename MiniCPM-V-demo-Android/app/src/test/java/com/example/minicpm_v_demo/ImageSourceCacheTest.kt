package com.example.minicpm_v_demo

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSourceCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cachesOneShotSourceWithExactlyOneOpen() {
        val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        var openCount = 0
        val cache = ImageSourceCache(temporaryFolder.newFolder("images"), bytes.size.toLong())

        val cached = cache.cache {
            openCount++
            check(openCount == 1) { "The selected URI was opened more than once" }
            ByteArrayInputStream(bytes)
        }

        assertEquals(1, openCount)
        assertEquals(bytes.size.toLong(), cached.byteCount)
        assertArrayEquals(bytes, Files.readAllBytes(cached.file.toPath()))
        assertEquals(cached.file.canonicalFile, cache.resolve(cached.token))
    }

    @Test
    fun resolvesOnlyOpaqueTokensInsidePrivateCache() {
        val directory = temporaryFolder.newFolder("images")
        val cache = ImageSourceCache(directory, 1024)
        val cached = cache.cache { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }

        assertEquals(cached.file.canonicalFile, cache.resolve(cached.token))
        assertNull(cache.resolve("../${cached.token}"))
        assertNull(cache.resolve("not-a-source.img"))
        assertNull(cache.resolve(""))
    }

    @Test
    fun deletesCachedSourceByOpaqueToken() {
        val cache = ImageSourceCache(temporaryFolder.newFolder("images"), 1024)
        val cached = cache.cache { ByteArrayInputStream(byteArrayOf(1)) }

        cache.deleteToken(cached.token)

        assertFalse(cached.file.exists())
        assertNull(cache.resolve(cached.token))
    }

    @Test
    fun rejectsEmptySourceAndRemovesTemporaryFile() {
        val directory = temporaryFolder.newFolder("images")
        val cache = ImageSourceCache(directory, 1024)

        assertThrows(ImageSourceUnreadableException::class.java) {
            cache.cache { ByteArrayInputStream(ByteArray(0)) }
        }

        assertFalse(directory.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun rejectsOversizedSourceAndRemovesTemporaryFile() {
        val directory = temporaryFolder.newFolder("images")
        val cache = ImageSourceCache(directory, 8)

        assertThrows(ImageSourceTooLargeException::class.java) {
            cache.cache { ByteArrayInputStream(ByteArray(9)) }
        }

        assertFalse(directory.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun removesOnlyGeneratedFilesNotReferencedByArchive() {
        val directory = temporaryFolder.newFolder("images")
        val cache = ImageSourceCache(directory, 1024)
        val retained = cache.cache { ByteArrayInputStream(byteArrayOf(1)) }
        val orphan = cache.cache { ByteArrayInputStream(byteArrayOf(2)) }
        val unrelated = directory.resolve("do-not-delete.txt").apply { writeText("keep") }

        cache.deleteUnreferencedTokens(setOf(retained.token, "../${orphan.token}"))

        assertEquals(retained.file.canonicalFile, cache.resolve(retained.token))
        assertFalse(orphan.file.exists())
        assertEquals("keep", unrelated.readText())
    }
}

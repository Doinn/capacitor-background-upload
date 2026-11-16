package com.doinn.plugins.backgroundupload

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadFilePreparerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun deleteAllInRemovesEveryFile() {
        val dir = folder.newFolder("cache")
        repeat(3) { File(dir, "f$it.jpg").writeText("x") }

        assertEquals(3, UploadFilePreparer.deleteAllIn(dir))
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun deleteAllInMissingDirReturnsZero() {
        assertEquals(0, UploadFilePreparer.deleteAllIn(File(folder.root, "missing")))
    }

    @Test
    fun isInsideAcceptsChildAndRejectsSiblingAndTraversal() {
        val dir = folder.newFolder("cache")
        assertTrue(UploadFilePreparer.isInside(dir, File(dir, "a.jpg").path))
        assertFalse(UploadFilePreparer.isInside(dir, dir.path + "/../x"))
        assertFalse(UploadFilePreparer.isInside(dir, dir.path + "-other/a"))
    }

    @Test
    fun keepsJpegSourceWhenOutputIsLarger() {
        assertTrue(UploadFilePreparer.shouldKeepSource("image/jpeg", 1_000L, 1_001L))
    }

    @Test
    fun keepsJpegSourceWhenOutputIsEqual() {
        assertTrue(UploadFilePreparer.shouldKeepSource("image/jpeg", 1_000L, 1_000L))
    }

    @Test
    fun keepsOutputWhenItIsSmallerThanJpegSource() {
        assertFalse(UploadFilePreparer.shouldKeepSource("image/jpeg", 1_000L, 999L))
    }

    @Test
    fun keepsOutputForNonJpegSources() {
        listOf("image/heic", "image/png", "image/webp", null).forEach {
            assertFalse("for $it", UploadFilePreparer.shouldKeepSource(it, 10L, 1_000L))
        }
    }

    @Test
    fun keepsOutputWhenSourceSizeIsUnknown() {
        assertFalse(UploadFilePreparer.shouldKeepSource("image/jpeg", null, 1_000L))
    }

    @Test
    fun readsJpegMimeTypeInAnyCaseAndWithParameters() {
        assertTrue(UploadFilePreparer.shouldKeepSource("IMAGE/JPEG", 1_000L, 2_000L))
        assertTrue(UploadFilePreparer.shouldKeepSource("image/jpeg; charset=binary", 1_000L, 2_000L))
    }
}

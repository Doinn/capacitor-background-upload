package com.doinn.plugins.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifTagsTest {
    @Test
    fun keepsPositionAndCaptureTime() {
        listOf(
            "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSTimeStamp",
            "GPSDateStamp", "DateTimeOriginal", "DateTime", "OffsetTimeOriginal", "Make", "Model"
        ).forEach { assertTrue("missing $it", ExifTags.PRESERVED.contains(it)) }
    }

    @Test
    fun leavesOrientationAndDimensionsToTheWriter() {
        listOf("Orientation", "ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension")
            .forEach { assertFalse("must not copy $it", ExifTags.PRESERVED.contains(it)) }
    }

    @Test
    fun hasNoDuplicates() {
        assertEquals(ExifTags.PRESERVED.size, ExifTags.PRESERVED.toSet().size)
    }
}

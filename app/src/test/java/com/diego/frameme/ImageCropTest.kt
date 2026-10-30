package com.diego.frameme

import com.diego.frameme.domain.ImageCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCropTest {

    @Test
    fun `landscape source cropped to portrait window trims the sides`() {
        // 4000x3000 (1.333) -> window aspect 34/54 ≈ 0.6296
        val rect = ImageCrop.coverCropRect(4000, 3000, 34f / 54f)

        assertEquals(1888, rect.width) // floor(3000 * 34/54) = 1888
        assertEquals(3000, rect.height)
        assertEquals((4000 - 1888) / 2, rect.x) // centered horizontally
        assertEquals(0, rect.y)
    }

    @Test
    fun `portrait source cropped to landscape window trims top and bottom`() {
        // 2000x4000 (0.5) -> target aspect 2.0
        val rect = ImageCrop.coverCropRect(2000, 4000, 2f)

        assertEquals(2000, rect.width)
        assertEquals(1000, rect.height) // 2000 / 2.0
        assertEquals(0, rect.x)
        assertEquals((4000 - 1000) / 2, rect.y) // centered vertically
    }

    @Test
    fun `matching aspect keeps the full frame`() {
        val rect = ImageCrop.coverCropRect(1000, 500, 2f)

        assertEquals(1000, rect.width)
        assertEquals(500, rect.height)
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
    }

    @Test
    fun `cropped rect always has the requested aspect`() {
        val targetAspect = 0.63f
        listOf(
            intArrayOf(4000, 3000),
            intArrayOf(1000, 4000),
            intArrayOf(1080, 1080),
            intArrayOf(640, 480),
        ).forEach { (w, h) ->
            val rect = ImageCrop.coverCropRect(w, h, targetAspect)
            val achieved = rect.width.toFloat() / rect.height.toFloat()
            assertEquals("aspect for ${w}x$h", targetAspect, achieved, 0.002f)
            assertTrue("width fits for ${w}x$h", rect.width <= w)
            assertTrue("height fits for ${w}x$h", rect.height <= h)
            assertTrue("rect inside source for ${w}x$h", rect.x + rect.width <= w && rect.y + rect.height <= h)
        }
    }
}

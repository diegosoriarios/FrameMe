package com.diego.frameme

import com.diego.frameme.config.FrameConfig
import com.diego.frameme.domain.FrameDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameDimensionsTest {

    @Test
    fun `default 50x70 config converts centimeters to meters and subtracts rim plus mat`() {
        val dims = FrameDimensions.fromConfig(FrameConfig.DEFAULT) // 50x70, rim 3, mat 5

        assertEquals(0.50f, dims.outerWidthM, 1e-6f)
        assertEquals(0.70f, dims.outerHeightM, 1e-6f)
        assertEquals(0.03f, dims.mouldingWidthM, 1e-6f)
        assertEquals(0.02f, dims.mouldingDepthM, 1e-6f)
        assertEquals(0.05f, dims.matBorderM, 1e-6f)

        assertEquals(0.34f, dims.windowWidthM, 1e-6f) // 50 - 2*(3+5)
        assertEquals(0.54f, dims.windowHeightM, 1e-6f) // 70 - 2*(3+5)
        assertEquals(0.34f / 0.54f, dims.artworkAspect, 1e-6f)
    }

    @Test
    fun `zero mat width leaves only the moulding rim`() {
        val dims = FrameDimensions.fromConfig(
            FrameConfig.DEFAULT.copy(matWidthCm = 0f),
        )
        assertEquals(0.44f, dims.windowWidthM, 1e-6f) // 50 - 2*3
        assertEquals(0.64f, dims.windowHeightM, 1e-6f) // 70 - 2*3
    }

    @Test
    fun `landscape frame keeps window proportional per axis`() {
        val dims = FrameDimensions.fromConfig(
            FrameConfig.DEFAULT.copy(widthCm = 100f, heightCm = 40f, matWidthCm = 2f, mouldingThicknessCm = 4f),
        )
        assertEquals(0.88f, dims.windowWidthM, 1e-6f) // 100 - 2*(4+2)
        assertEquals(0.28f, dims.windowHeightM, 1e-6f) // 40 - 2*(4+2)
    }

    @Test
    fun `absurdly thick borders still yield a positive window`() {
        val dims = FrameDimensions.fromConfig(
            FrameConfig.DEFAULT.copy(
                widthCm = 15f,
                heightCm = 15f,
                mouldingThicknessCm = 8f,
                matWidthCm = 15f,
            ),
        )
        assert(dims.windowWidthM > 0f)
        assert(dims.windowHeightM > 0f)
    }
}

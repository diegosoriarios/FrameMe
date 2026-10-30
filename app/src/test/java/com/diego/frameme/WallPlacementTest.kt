package com.diego.frameme

import com.diego.frameme.ar.WallPlacement
import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallPlacementTest {

    private fun assertVec(expected: Float3, actual: Float3, epsilon: Float = 1e-4f, message: String = "") {
        assertEquals(message, expected.x, actual.x, epsilon)
        assertEquals(message, expected.y, actual.y, epsilon)
        assertEquals(message, expected.z, actual.z, epsilon)
    }

    @Test
    fun `forward aligned with world up hint yields identity`() {
        val q = WallPlacement.quatFromForwardUp(
            forward = Float3(0f, 0f, 1f),
            upHint = Float3(0f, 1f, 0f),
        )
        assertVec(Float3(0f, 0f, 1f), q.rotate(Float3(0f, 0f, 1f)), message = "forward")
        assertVec(Float3(0f, 1f, 0f), q.rotate(Float3(0f, 1f, 0f)), message = "up")
        assertVec(Float3(1f, 0f, 0f), q.rotate(Float3(1f, 0f, 0f)), message = "right")
    }

    @Test
    fun `wall facing plus x maps frame front to the normal and keeps top level`() {
        val q = WallPlacement.quatFromForwardUp(
            forward = Float3(1f, 0f, 0f),
            upHint = Float3(0f, 1f, 0f),
        )
        assertVec(Float3(1f, 0f, 0f), q.rotate(Float3(0f, 0f, 1f)), message = "front->normal")
        assertVec(Float3(0f, 1f, 0f), q.rotate(Float3(0f, 1f, 0f)), message = "top stays level")
    }

    @Test
    fun `tilted wall keeps the frame top level with the floor`() {
        val normal = WallPlacement.normalized(Float3(0.7f, 0f, -0.7f))
        val q = WallPlacement.quatFromForwardUp(normal, Float3(0f, 1f, 0f))

        assertVec(normal, q.rotate(Float3(0f, 0f, 1f)), message = "front->normal")
        val top = q.rotate(Float3(0f, 1f, 0f))
        assertTrue("top has no z-tilt", kotlin.math.abs(top.z) < 1e-4f)
        assertEquals("top is level", 1f, top.y, 1e-4f)
    }

    @Test
    fun `parallel forward and up hint falls back without degenerating`() {
        val q = WallPlacement.quatFromForwardUp(
            forward = Float3(0f, 1f, 0f),
            upHint = Float3(0f, 1f, 0f),
        )
        val len = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        assertEquals(1f, len, 1e-4f)
        assertVec(Float3(0f, 1f, 0f), q.rotate(Float3(0f, 0f, 1f)))
    }

    @Test
    fun `yaw twists the frame within the wall plane`() {
        val world = WallPlacement.frameWorldQuaternion(
            forward = Float3(0f, 0f, 1f),
            yawDegrees = 90f,
        )
        assertVec(Float3(0f, 0f, 1f), world.rotate(Float3(0f, 0f, 1f)), message = "normal unchanged")
        assertVec(Float3(-1f, 0f, 0f), world.rotate(Float3(0f, 1f, 0f)), message = "top twisted +90° about Z")
    }

    @Test
    fun `negative yaw twists the other way`() {
        val world = WallPlacement.frameWorldQuaternion(
            forward = Float3(0f, 0f, 1f),
            yawDegrees = -90f,
        )
        assertVec(Float3(1f, 0f, 0f), world.rotate(Float3(0f, 1f, 0f)))
    }

    @Test
    fun `local rotation composes back to the world rotation under any anchor pose`() {
        // An ARCore-ish anchor frame facing +X.
        val parentWorld = WallPlacement.quatFromForwardUp(Float3(1f, 0f, 0f), Float3(0f, 1f, 0f))
        val desiredWorld = WallPlacement.frameWorldQuaternion(
            forward = Float3(0f, 0f, -1f),
            yawDegrees = 25f,
        )
        val local = WallPlacement.localRotation(parentWorld, desiredWorld)

        // world = parent ⊗ local  =>  parent.rotate(local.rotate(v)) == desired.rotate(v)
        val front = parentWorld.rotate(local.rotate(Float3(0f, 0f, 1f)))
        val top = parentWorld.rotate(local.rotate(Float3(0f, 1f, 0f)))
        assertVec(desiredWorld.rotate(Float3(0f, 0f, 1f)), front, message = "front")
        assertVec(desiredWorld.rotate(Float3(0f, 1f, 0f)), top, message = "top")
    }

    @Test
    fun `twist delta wraps correctly across the 180 boundary`() {
        assertEquals(2f, WallPlacement.twistDeltaDegrees(179f, -179f), 1e-4f)
        assertEquals(-5f, WallPlacement.twistDeltaDegrees(10f, 5f), 1e-4f)
        assertEquals(-5f, WallPlacement.twistDeltaDegrees(-170f, -175f), 1e-4f)
        assertEquals(20f, WallPlacement.twistDeltaDegrees(170f, -170f), 1e-4f)
        assertEquals(0f, WallPlacement.twistDeltaDegrees(45f, 45f), 1e-4f)
    }

    @Test
    fun `normal flips toward the camera side of the wall`() {
        val camera = Float3(0f, 0f, 5f)
        val surface = Float3(0f, 0f, 0f)
        assertVec(
            Float3(0f, 0f, 1f),
            WallPlacement.towardCamera(Float3(0f, 0f, -1f), camera, surface),
        )
        assertVec(
            Float3(0f, 0f, 1f),
            WallPlacement.towardCamera(Float3(0f, 0f, 1f), camera, surface),
        )
    }

    @Test
    fun `pose quaternion parses xyzw order`() {
        // +90° yaw in Hamilton {x,y,z,w} order: (0, sin45°, 0, cos45°)
        val q = WallPlacement.quatFromXYZW(floatArrayOf(0f, 0.70710678f, 0f, 0.70710678f))
        assertVec(Float3(1f, 0f, 0f), q.rotate(Float3(0f, 0f, 1f)))
        assertVec(Float3(0f, 1f, 0f), q.rotate(Float3(0f, 1f, 0f)))
    }
}

package com.diego.frameme.ar

import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wall-placement math + ARCore hit filtering.
 *
 * Orientation problem being solved: an ARCore vertical-plane hit yields an
 * anchor whose pose frame is ARCore's own (not necessarily aligned to gravity
 * or the room). A picture frame must hang **flush**: its back parallel to the
 * wall, its top parallel to the floor, plus an optional user twist *in the
 * wall plane only*.
 *
 * Solution: build a world-space quaternion from an orthonormal basis where
 *   +Z (frame front)  -> wall normal pointing into the room
 *   +Y (frame top)    -> world up projected onto the wall plane
 *   +X (frame right)  -> cross(up, normal), completing the right-handed basis
 * then compose the user twist as a local Z-axis rotation (Z == wall normal).
 */
object WallPlacement {

    /** Plain quaternion [x, y, z, w] — kept self-contained for exact unit testing. */
    data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
        operator fun times(o: Quat): Quat = Quat(
            x = w * o.x + x * o.w + y * o.z - z * o.y,
            y = w * o.y - x * o.z + y * o.w + z * o.x,
            z = w * o.z + x * o.y - y * o.x + z * o.w,
            w = w * o.w - x * o.x - y * o.y - z * o.z,
        )

        fun conjugate(): Quat = Quat(-x, -y, -z, w)

        fun normalized(): Quat {
            val len = sqrt(x * x + y * y + z * z + w * w)
            if (len < 1e-8f) return identity
            return Quat(x / len, y / len, z / len, w / len)
        }

        /** Rotate [v] by this (unit) quaternion: q * v * q⁻¹. */
        fun rotate(v: Float3): Float3 {
            val qv = Quat(v.x, v.y, v.z, 0f)
            val out = this * qv * conjugate()
            return Float3(out.x, out.y, out.z)
        }

        companion object {
            val identity = Quat()
        }
    }

    fun dot(a: Float3, b: Float3): Float = a.x * b.x + a.y * b.y + a.z * b.z

    fun cross(a: Float3, b: Float3): Float3 = Float3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

    fun normalized(v: Float3): Float3 {
        val len = sqrt(dot(v, v))
        if (len < 1e-8f) return Float3(0f, 0f, 1f)
        return Float3(v.x / len, v.y / len, v.z / len)
    }

    /**
     * World-space orientation quaternion that points the frame's local +Z along
     * [forward] (wall normal, facing the viewer) while keeping the frame's top
     * as level with the floor as the wall allows. [yawDegrees] twists the frame
     * within the wall plane (about the wall normal).
     */
    fun frameWorldQuaternion(
        forward: Float3,
        worldUp: Float3 = Float3(0f, 1f, 0f),
        yawDegrees: Float = 0f,
    ): Quat {
        val wall = quatFromForwardUp(forward, worldUp)
        return if (yawDegrees == 0f) wall else wall * twist(yawDegrees)
    }

    /**
     * Quaternion aligning local +Z to [forward] with [upHint]. Falls back to an
     * alternate hint when forward is (nearly) parallel to the hint — a real
     * risk for walls vs. gravity-aligned up.
     */
    fun quatFromForwardUp(forward: Float3, upHint: Float3): Quat {
        val z = normalized(forward)
        val hint = if (abs(dot(z, normalized(upHint))) > 0.999f) {
            if (abs(z.y) > 0.999f) Float3(0f, 0f, 1f) else Float3(0f, 1f, 0f)
        } else upHint
        val x = normalized(cross(hint, z))
        val y = cross(z, x)

        // Column-major basis matrix -> quaternion (Shepperd's method).
        val m00 = x.x; val m01 = y.x; val m02 = z.x
        val m10 = x.y; val m11 = y.y; val m12 = z.y
        val m20 = x.z; val m21 = y.z; val m22 = z.z

        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            Quat(
                x = (m21 - m12) / s,
                y = (m02 - m20) / s,
                z = (m10 - m01) / s,
                w = 0.25f * s,
            ).normalized()
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            Quat(
                x = 0.25f * s,
                y = (m01 + m10) / s,
                z = (m02 + m20) / s,
                w = (m21 - m12) / s,
            ).normalized()
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            Quat(
                x = (m01 + m10) / s,
                y = 0.25f * s,
                z = (m12 + m21) / s,
                w = (m02 - m20) / s,
            ).normalized()
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            Quat(
                x = (m02 + m20) / s,
                y = (m12 + m21) / s,
                z = 0.25f * s,
                w = (m10 - m01) / s,
            ).normalized()
        }
    }

    /** Rotation about the frame's local Z axis (= the wall normal) by [degrees]. */
    fun twist(degrees: Float): Quat {
        val half = Math.toRadians(degrees.toDouble()) / 2.0
        return Quat(x = 0f, y = 0f, z = sin(half).toFloat(), w = cos(half).toFloat())
    }

    /**
     * Local rotation for a child of a parent whose world rotation is
     * [parentWorld], so that the child lands at [world]: local = parent⁻¹ ⊗ world.
     * Anchor nodes carry ARCore's own pose frame; frame content must be
     * expressed relative to it.
     */
    fun localRotation(parentWorld: Quat, world: Quat): Quat =
        parentWorld.conjugate().normalized() * world.normalized()

    /** Extract an ARCore pose's rotation quaternion given as [xyzw]. */
    fun quatFromXYZW(xyzw: FloatArray): Quat =
        Quat(xyzw[0], xyzw[1], xyzw[2], xyzw[3]).normalized()

    /**
     * Wall normal extracted from a hit/anchor pose: ARCore's surface poses map
     * their local +Y onto the surface normal.
     */
    fun normalFromPoseQuat(pose: Quat): Float3 = pose.rotate(Float3(0f, 1f, 0f))

    /**
     * Flips [normal] so it points toward the camera side of the wall (ARCore's
     * plane normal sign is not guaranteed to face the viewer).
     */
    fun towardCamera(normal: Float3, cameraPosition: Float3, surfacePosition: Float3): Float3 {
        val toCamera = normalized(cameraPosition - surfacePosition)
        return if (dot(normal, toCamera) >= 0f) normal else normal * -1f
    }

    /**
     * Signed angle delta between two two-pointer gesture angles, wrapped to
     * (-180°, 180°] so a crossing over ±180° doesn't spin the frame.
     */
    fun twistDeltaDegrees(previousAngle: Float, currentAngle: Float): Float {
        var delta = currentAngle - previousAngle
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }

    // ---- ARCore hit filtering -------------------------------------------------

    /** Sanity distance window for wall placements, in meters. */
    const val MIN_HIT_DISTANCE_M = 0.2f
    const val MAX_HIT_DISTANCE_M = 5f

    /**
     * How far outside a tracked wall's detected polygon a hit still counts, in
     * meters. The polygon only covers the textured patch ARCore has seen so
     * far; a plain painted wall goes on well past its edge (or is never seen as
     * a plane at all).
     */
    const val WALL_POLYGON_TOLERANCE_M = 1f

    /**
     * A depth hit whose surface normal's world-Y is within this of zero is a
     * vertical surface. 0.25 is roughly 14° off vertical: a wall, door or
     * cupboard front — never a floor or table top.
     */
    const val WALL_DEPTH_NORMAL_MAX_Y = 0.25f

    /**
     * True when a hit can host a frame. Accepts the three ways ARCore describes
     * a wall:
     *  - a tracked **vertical** plane hit inside its polygon;
     *  - that same plane up to [WALL_POLYGON_TOLERANCE_M] outside its polygon;
     *  - a depth hit on a vertical surface (ARCore returns no plane at all on
     *    plain painted walls, but the depth API still finds them).
     *
     * All must be currently tracked and within a sane distance.
     */
    fun isPlaceableWallHit(hit: HitResult): Boolean {
        val trackable = hit.trackable
        val pose = hit.hitPose
        val tracking = trackable.trackingState == TrackingState.TRACKING
        val normalY = pose.getYAxis()[1]
        val plane = trackable as? Plane
        if (plane != null) {
            val vertical = plane.type == Plane.Type.VERTICAL && plane.subsumedBy == null
            val inPolygon = vertical && plane.isPoseInPolygon(pose)
            val outside = if (vertical && !inPolygon) plane.outsidePolygonDistance(pose) else 0f
            return isPlaceableWall(
                isVerticalPlane = vertical,
                isPoseInPolygon = inPolygon,
                outsidePolygonMeters = outside,
                isDepthPoint = false,
                normalY = normalY,
                isTrackableTracking = tracking,
                distanceMeters = hit.distance,
            )
        }
        return isPlaceableWall(
            isVerticalPlane = false,
            isPoseInPolygon = false,
            outsidePolygonMeters = Float.POSITIVE_INFINITY,
            isDepthPoint = trackable is DepthPoint,
            normalY = normalY,
            isTrackableTracking = tracking,
            distanceMeters = hit.distance,
        )
    }

    /** Pure, JVM-testable wall acceptance rule used by [isPlaceableWallHit]. */
    fun isPlaceableWall(
        isVerticalPlane: Boolean,
        isPoseInPolygon: Boolean,
        outsidePolygonMeters: Float,
        isDepthPoint: Boolean,
        normalY: Float,
        isTrackableTracking: Boolean,
        distanceMeters: Float,
    ): Boolean {
        if (!isTrackableTracking || !distanceMeters.isFinite()) return false
        if (distanceMeters < MIN_HIT_DISTANCE_M || distanceMeters > MAX_HIT_DISTANCE_M) return false
        return when {
            isVerticalPlane && isPoseInPolygon -> true
            isVerticalPlane && outsidePolygonMeters.isFinite() &&
                outsidePolygonMeters <= WALL_POLYGON_TOLERANCE_M -> true
            isDepthPoint && normalY.isFinite() && abs(normalY) < WALL_DEPTH_NORMAL_MAX_Y -> true
            else -> false
        }
    }

    /**
     * How far [pose] lies outside this plane's detected polygon, in meters;
     * `0` when inside. Computed in the plane's centre-pose frame (X/Z).
     */
    fun Plane.outsidePolygonDistance(pose: Pose): Float {
        val local = centerPose.inverse().transformPoint(pose.translation)
        val px = local[0]
        val pz = local[2]
        val buffer = polygon.duplicate()
        buffer.rewind()
        val points = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val count = points.size / 2
        if (count < 3) return Float.POSITIVE_INFINITY
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            val ax = points[i * 2]
            val az = points[i * 2 + 1]
            val j = (i + 1) % count
            val bx = points[j * 2]
            val bz = points[j * 2 + 1]
            val ex = bx - ax
            val ez = bz - az
            val lengthSquared = ex * ex + ez * ez
            if (lengthSquared < 1e-8f) continue
            val t = (((px - ax) * ex + (pz - az) * ez) / lengthSquared).coerceIn(0f, 1f)
            val dx = ax + ex * t - px
            val dz = az + ez * t - pz
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < best) best = distance
        }
        return best
    }
}

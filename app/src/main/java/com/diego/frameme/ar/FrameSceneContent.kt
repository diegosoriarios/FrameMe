package com.diego.frameme.ar

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.diego.frameme.config.FrameConfig
import com.diego.frameme.domain.FrameDimensions
import io.github.sceneview.NodeScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node

/**
 * Procedural picture-frame node tree.
 *
 * Geometry convention (see [FrameDimensions]):
 *  - origin: center of the frame's back face (flush against the wall)
 *  - +Z: out of the wall, into the room · +Y: up · +X: right (viewer's view)
 *
 * Stack, back to front along +Z:
 *  1. drop shadow        z = -1 mm (against the wall, halo outside silhouette)
 *  2. mat strips         z = +2 mm (recessed like a real framed print)
 *  3. artwork quad       z = +3 mm
 *  4. moulding bars      z = [0, depth] — the physical frame box
 *
 * Every size is consumed directly in meters from [FrameDimensions]; node scale
 * stays at (1, 1, 1), which is what makes the frame true-to-scale in AR.
 *
 * [rootRotation] is the caller-computed local rotation that aligns the frame
 * flush with the wall (see [WallPlacement]). Every node carries
 * [FRAME_NODE_TAG] so gesture code can identify frame taps.
 */
@Composable
fun NodeScope.FrameSceneContent(
    dimensions: FrameDimensions,
    config: FrameConfig,
    rootRotation: Rotation,
    artwork: Bitmap?,
    materialLoader: MaterialLoader,
) {
    val frameMaterial = remember(materialLoader, config.style) {
        FrameMaterials.frameMaterial(materialLoader, config.style)
    }
    val matMaterial = remember(materialLoader, config.matColor) {
        FrameMaterials.matMaterial(materialLoader, config.matColor)
    }
    val placeholderMaterial = remember(materialLoader) {
        FrameMaterials.placeholderMaterial(materialLoader)
    }
    val shadowBitmap = remember { FrameMaterials.createDropShadowBitmap() }

    val w = dimensions.outerWidthM
    val h = dimensions.outerHeightM
    val t = dimensions.mouldingWidthM
    val d = dimensions.mouldingDepthM
    val m = dimensions.matBorderM
    val outOfWall = Direction(z = 1f)
    val tag: Node.() -> Unit = { name = FRAME_NODE_TAG }

    Node(rotation = rootRotation, apply = tag) {
        // 1 — Ambient drop shadow, 1 mm off the wall. The moulding occludes the
        // middle, leaving only the feathered halo around the silhouette.
        ImageNode(
            bitmap = shadowBitmap,
            size = Size(w * 1.06f, h * 1.08f, 1f),
            normal = outOfWall,
            position = Position(x = 0.004f, y = -0.005f, z = SHADOW_OFFSET_M),
            apply = tag,
        )

        // 2 — Passe-partout strips forming the mat border around the window.
        if (m > 0.0005f) {
            PlaneNode(
                size = Size(w - 2 * t, m, 1f),
                normal = outOfWall,
                materialInstance = matMaterial,
                position = Position(y = h / 2 - t - m / 2, z = MAT_OFFSET_M),
                apply = tag,
            )
            PlaneNode(
                size = Size(w - 2 * t, m, 1f),
                normal = outOfWall,
                materialInstance = matMaterial,
                position = Position(y = -(h / 2 - t - m / 2), z = MAT_OFFSET_M),
                apply = tag,
            )
            PlaneNode(
                size = Size(m, h - 2 * t - 2 * m, 1f),
                normal = outOfWall,
                materialInstance = matMaterial,
                position = Position(x = w / 2 - t - m / 2, z = MAT_OFFSET_M),
                apply = tag,
            )
            PlaneNode(
                size = Size(m, h - 2 * t - 2 * m, 1f),
                normal = outOfWall,
                materialInstance = matMaterial,
                position = Position(x = -(w / 2 - t - m / 2), z = MAT_OFFSET_M),
                apply = tag,
            )
        }

        // 3 — Artwork (or placeholder) exactly filling the mat window.
        val window = Size(dimensions.windowWidthM, dimensions.windowHeightM, 1f)
        if (artwork != null) {
            ImageNode(
                bitmap = artwork,
                size = window,
                normal = outOfWall,
                position = Position(z = ARTWORK_OFFSET_M),
                apply = tag,
            )
        } else {
            PlaneNode(
                size = window,
                normal = outOfWall,
                materialInstance = placeholderMaterial,
                position = Position(z = ARTWORK_OFFSET_M),
                apply = tag,
            )
        }

        // 4 — Moulding bars (butt-jointed box, back face flush at z = 0).
        CubeNode(
            size = Size(w, t, d),
            materialInstance = frameMaterial,
            position = Position(y = (h - t) / 2, z = d / 2),
            apply = tag,
        )
        CubeNode(
            size = Size(w, t, d),
            materialInstance = frameMaterial,
            position = Position(y = -(h - t) / 2, z = d / 2),
            apply = tag,
        )
        CubeNode(
            size = Size(t, h - 2 * t, d),
            materialInstance = frameMaterial,
            position = Position(x = -(w - t) / 2, z = d / 2),
            apply = tag,
        )
        CubeNode(
            size = Size(t, h - 2 * t, d),
            materialInstance = frameMaterial,
            position = Position(x = (w - t) / 2, z = d / 2),
            apply = tag,
        )
    }
}

private const val SHADOW_OFFSET_M = -0.001f
private const val MAT_OFFSET_M = 0.002f
private const val ARTWORK_OFFSET_M = 0.003f

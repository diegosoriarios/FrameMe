package com.diego.frameme.domain

import com.diego.frameme.config.FrameConfig
import kotlin.math.max

/**
 * Physical frame dimensions in **meters** — the unit ARCore anchors and all
 * SceneView geometry use natively (1 world unit == 1 real-world meter).
 *
 * Geometry layout convention shared with [FrameSceneContent]:
 *  - Origin at the center of the frame's BACK face (the face against the wall).
 *  - +Z points out of the wall, into the room (the "front" the viewer sees).
 *  - +Y is up, +X is right (as seen by the viewer).
 *
 *  |← outer W →|
 *  ┌─────────────────┐ ─
 *  │ ┏━━━━━━━━━━━┓   │   mouldingThickness (rim of the frame bars)
 *  │ ┃ ┌───────┐ ┃   │   matWidth (passe-partout border)
 *  │ ┃ │ photo │ ┃   │   window (artwork quad, exact crop target)
 *  │ ┃ └───────┘ ┃   │
 *  │ ┗━━━━━━━━━━━┛   │
 *  └─────────────────┘ ─
 */
data class FrameDimensions(
    val outerWidthM: Float,
    val outerHeightM: Float,
    val mouldingWidthM: Float,
    val mouldingDepthM: Float,
    val matBorderM: Float,
) {
    /** Visible mat window edge to edge: outer minus moulding rim and mat border, per axis. */
    val windowWidthM: Float = max(
        0.01f,
        outerWidthM - 2f * (mouldingWidthM + matBorderM),
    )
    val windowHeightM: Float = max(
        0.01f,
        outerHeightM - 2f * (mouldingWidthM + matBorderM),
    )

    /**
     * Aspect ratio (width / height) the artwork bitmap must be cropped to.
     * Cropping to exactly this ratio is what guarantees zero distortion: the
     * bitmap's aspect matches the physical quad's aspect by construction.
     */
    val artworkAspect: Float = windowWidthM / windowHeightM

    /** Half extents, handy for positioning bars/strips around the center. */
    val halfWidthM: Float = outerWidthM / 2f
    val halfHeightM: Float = outerHeightM / 2f

    companion object {
        const val CM_PER_METER = 100f

        fun fromConfig(config: FrameConfig): FrameDimensions = FrameDimensions(
            outerWidthM = config.widthCm / CM_PER_METER,
            outerHeightM = config.heightCm / CM_PER_METER,
            mouldingWidthM = config.mouldingThicknessCm / CM_PER_METER,
            mouldingDepthM = config.mouldingDepthCm / CM_PER_METER,
            matBorderM = config.matWidthCm / CM_PER_METER,
        )
    }
}

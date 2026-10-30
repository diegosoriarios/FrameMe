package com.diego.frameme.config

import androidx.compose.ui.graphics.Color

/**
 * Frame style presets mapped to physically-based material parameters.
 * [metallic]/[roughness] feed Filament's PBR model so materials react to the
 * ARCore-estimated room lighting like their real counterparts would.
 */
enum class FrameStyle(
    val label: String,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
) {
    OAK("Oak", Color(0xFF9C6B3F), metallic = 0.0f, roughness = 0.55f),
    MATTE_BLACK("Matte black", Color(0xFF1D1D1F), metallic = 0.2f, roughness = 0.5f),
    WHITE("White", Color(0xFFF2EFE9), metallic = 0.0f, roughness = 0.7f),
    GOLD("Gold", Color(0xFFD4AF37), metallic = 1.0f, roughness = 0.3f),
}

/**
 * Common physical frame sizes in centimeters. [CUSTOM] defers to the sliders.
 */
enum class FramePreset(
    val label: String,
    val widthCm: Float,
    val heightCm: Float,
) {
    SMALL("30 × 40", widthCm = 30f, heightCm = 40f),
    MEDIUM("50 × 70", widthCm = 50f, heightCm = 70f),
    LARGE("70 × 100", widthCm = 70f, heightCm = 100f),
    CUSTOM("Custom", widthCm = 40f, heightCm = 50f),
}

/** Passe-partout paper colors, matched to common mat board shades. */
enum class MatColor(
    val label: String,
    val color: Color,
) {
    WHITE("Pure white", Color(0xFFFFFFFF)),
    IVORY("Ivory", Color(0xFFF3EBDD)),
    GRAPHITE("Graphite", Color(0xFF2B2B2B)),
    SAND("Sand", Color(0xFFD8C8A8)),
}

/**
 * Everything the 3D frame generator and the customization sheet need.
 * All dimensions are centimeters, as chosen by the user in the UI.
 *
 * The printable area (mat window) is what remains after subtracting the two
 * moulding bars and the two mat borders from each dimension:
 * window = outer − 2·(mouldingThickness + matWidth), per axis.
 */
data class FrameConfig(
    val widthCm: Float,
    val heightCm: Float,
    val mouldingThicknessCm: Float,
    val mouldingDepthCm: Float,
    val style: FrameStyle,
    val matWidthCm: Float,
    val matColor: MatColor,
) {
    val preset: FramePreset
        get() = FramePreset.entries.firstOrNull {
            it != FramePreset.CUSTOM &&
                it.widthCm == widthCm &&
                it.heightCm == heightCm
        } ?: FramePreset.CUSTOM

    companion object {
        const val MIN_SIZE_CM = 15f
        const val MAX_SIZE_CM = 150f
        const val MIN_MOULDING_CM = 1f
        const val MAX_MOULDING_CM = 8f
        const val MIN_DEPTH_CM = 1f
        const val MAX_DEPTH_CM = 6f
        const val MIN_MAT_CM = 0f
        const val MAX_MAT_CM = 15f

        val DEFAULT = FrameConfig(
            widthCm = 50f,
            heightCm = 70f,
            mouldingThicknessCm = 3f,
            mouldingDepthCm = 2f,
            style = FrameStyle.OAK,
            matWidthCm = 5f,
            matColor = MatColor.WHITE,
        )
    }
}

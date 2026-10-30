package com.diego.frameme.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.diego.frameme.config.FrameStyle
import com.diego.frameme.config.MatColor
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.roundToInt

private val PLACEHOLDER_COLOR = 0xFFA9A49B.toInt()

/**
 * Factories for the frame's Filament materials and the procedural drop-shadow
 * bitmap. All material creation must happen on the main thread (Filament rule),
 * so callers invoke these inside `remember { }` blocks during composition.
 */
object FrameMaterials {

    /** PBR material for the moulding bars, driven by the selected [FrameStyle]. */
    fun frameMaterial(
        materialLoader: MaterialLoader,
        style: FrameStyle,
    ): MaterialInstance = materialLoader.createColorInstance(
        style.color.toArgb(),
        style.metallic,
        style.roughness,
    )

    /** Nearly matte material for the passe-partout paper strips. */
    fun matMaterial(
        materialLoader: MaterialLoader,
        matColor: MatColor,
    ): MaterialInstance = materialLoader.createColorInstance(
        matColor.color.toArgb(),
        0.0f,
        0.9f,
    )

    /** Placeholder shown before the user picks a photo. */
    fun placeholderMaterial(materialLoader: MaterialLoader): MaterialInstance =
        materialLoader.createColorInstance(
            PLACEHOLDER_COLOR,
            0.0f,
            0.85f,
        )

    /**
     * Procedural ambient drop shadow: a soft, square-ish dark halo that sits
     * 1 mm behind the frame against the wall. The frame geometry occludes its
     * center, so only the feathered rim outside the silhouette is visible —
     * the classic "hanging on a real wall" contact shadow.
     *
     * Drawn as stacked translucent rounded rects (no BlurMaskFilter) so it is
     * identical on every device and GPU.
     */
    fun createDropShadowBitmap(resolutionPx: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(resolutionPx, resolutionPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        val layers = 14
        val maxAlpha = 85
        val softMargin = (resolutionPx * 0.16f).roundToInt()
        val cornerRadius = resolutionPx * 0.03f
        for (layer in layers downTo 1) {
            val t = layer.toFloat() / layers
            val inset = softMargin * (1f - t)
            paint.alpha = (maxAlpha * t * t).roundToInt()
            canvas.drawRoundRect(
                RectF(inset, inset, resolutionPx - inset, resolutionPx - inset),
                cornerRadius,
                cornerRadius,
                paint,
            )
        }
        return bitmap
    }
}

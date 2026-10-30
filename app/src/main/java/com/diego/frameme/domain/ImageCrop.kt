package com.diego.frameme.domain

import android.graphics.Bitmap

/**
 * Aspect-ratio math for filling the mat window without distortion.
 *
 * Strategy (like a real framer's mat window): the photo is scaled to COVER the
 * window and the overflow is cropped, centered. The resulting bitmap has the
 * exact aspect of the physical window quad, so a 1:1 UV mapping can never
 * stretch a pixel.
 */
object ImageCrop {

    /** (x, y, width, height) crop rect in source pixels. */
    data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Solve the center cover-crop rect of [sourceWidth]×[sourceHeight] pixels
     * for a target aspect ratio ([targetAspect] = width / height).
     */
    fun coverCropRect(sourceWidth: Int, sourceHeight: Int, targetAspect: Float): CropRect {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source must have pixels" }
        require(targetAspect > 0f) { "Target aspect must be positive" }

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        return if (sourceAspect > targetAspect) {
            // Source is wider than the window: trim the sides.
            val width = (sourceHeight * targetAspect).toInt().coerceIn(1, sourceWidth)
            val x = (sourceWidth - width) / 2
            CropRect(x = x, y = 0, width = width, height = sourceHeight)
        } else {
            // Source is taller (or exact): trim top/bottom.
            val height = (sourceWidth / targetAspect).toInt().coerceIn(1, sourceHeight)
            val y = (sourceHeight - height) / 2
            CropRect(x = 0, y = y, width = sourceWidth, height = height)
        }
    }

    /**
     * Center cover-crop a bitmap to [targetAspect]. Returns [source] untouched
     * when the aspect already matches within [epsilon].
     */
    fun centerCrop(source: Bitmap, targetAspect: Float, epsilon: Float = 0.002f): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        if (kotlin.math.abs(sourceAspect - targetAspect) < epsilon) return source
        val rect = coverCropRect(source.width, source.height, targetAspect)
        return Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
    }
}

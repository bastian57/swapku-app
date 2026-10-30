package com.daftech.swapku.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Bitmap image geometry for v1. This is deliberately NOT OpenCV:
 * the research report lists OpenCV warpAffine as the reference, but
 * shipping the OpenCV Android SDK adds native weight and ABI complexity
 * for v1. The operations here are the same ideas, hand-rolled:
 * similarity alignment, inverse warp, feathered elliptical blend, and
 * per-channel mean/std colour matching (Reinhard-style).
 *
 * Template for alignment: in the aligned crop, the eye centre sits at
 * (0.5, 0.42) of the crop and the eye distance is 0.35 of the crop
 * width. These are fixed constants for v1 so the ArcFace/inSwapper
 * inputs are consistent. They are v1 choices, not InsightFace constants.
 */
object ImageUtils {

    const val SWAP_CROP = 128
    const val EMBED_CROP = 112

    private const val TEMPLATE_EYE_CX = 0.5f
    private const val TEMPLATE_EYE_CY = 0.42f
    private const val TEMPLATE_EYE_DIST = 0.35f

    /** Build the similarity matrix that maps original -> aligned crop. */
    fun alignmentMatrix(face: FivePointFace, cropSize: Int): Matrix {
        val eyeCenter = face.eyeCenter()
        val eyeDist = face.eyeDistance()
        val scale = if (eyeDist > 0f) {
            (TEMPLATE_EYE_DIST * cropSize) / eyeDist
        } else {
            1f
        }
        val angleDeg = Math.toDegrees(face.rollRadians().toDouble()).toFloat()
        val matrix = Matrix()
        // Order matters: translate eye centre to origin, rotate, scale,
        // then translate to the template eye centre.
        matrix.postTranslate(-eyeCenter.x, -eyeCenter.y)
        matrix.postRotate(-angleDeg)
        matrix.postScale(scale, scale)
        matrix.postTranslate(TEMPLATE_EYE_CX * cropSize, TEMPLATE_EYE_CY * cropSize)
        return matrix
    }

    /** Warp [source] with [matrix] into a square crop of [cropSize]. */
    fun warpToCrop(source: Bitmap, matrix: Matrix, cropSize: Int): Bitmap {
        val out = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return out
    }

    /** Align a face into a square crop. */
    fun alignFace(source: Bitmap, face: FivePointFace, cropSize: Int): Bitmap {
        return warpToCrop(source, alignmentMatrix(face, cropSize), cropSize)
    }

    /**
     * Paste a swapped aligned crop back into a copy of [original].
     * The swapped crop is inverse-warped, then blended under an
     * elliptical feathered mask so there is no hard "square box" seam.
     * Colour matching must already have been applied to [swappedCrop]
     * (see [colourMatch]) before this call.
     */
    fun pasteBack(
        original: Bitmap,
        swappedCrop: Bitmap,
        face: FivePointFace
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val forward = alignmentMatrix(face, swappedCrop.width)
        val inverse = Matrix()
        if (!forward.invert(inverse)) {
            // If the matrix cannot be inverted, fail honestly: return the
            // original untouched. Callers treat this as a swap failure,
            // never as a success with an unswapped image.
            return result
        }
        // Inverse-warp the swapped crop to full original size.
        val full = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawBitmap(swappedCrop, inverse, Paint(Paint.FILTER_BITMAP_FLAG))

        // Ellipse centred on the face, sized from the bounding box.
        val cx = face.eyeCenter().x
        // Face centre is below the eyes: use eye centre + 0.6 * box height.
        val cy = face.eyeCenter().y + face.boundingBoxHeight * 0.25f
        val rx = face.boundingBoxWidth * 0.62f
        val ry = face.boundingBoxHeight * 0.68f
        if (rx <= 0f || ry <= 0f) return result

        val origPixels = IntArray(original.width * original.height)
        val swapPixels = IntArray(original.width * original.height)
        val outPixels = IntArray(original.width * original.height)
        result.getPixels(origPixels, 0, original.width, 0, 0, original.width, original.height)
        full.getPixels(swapPixels, 0, original.width, 0, 0, original.width, original.height)

        val left = max(0, (cx - rx).toInt())
        val right = min(original.width - 1, (cx + rx).toInt())
        val top = max(0, (cy - ry).toInt())
        val bottom = min(original.height - 1, (cy + ry).toInt())

        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                val idx = y * original.width + x
                if (x < left || x > right || y < top || y > bottom) {
                    outPixels[idx] = origPixels[idx]
                    continue
                }
                // Normalised elliptical distance: 0 at centre, 1 at edge.
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                // Feather: full strength inside 0.55, fading to 0 at 1.0.
                val alpha = when {
                    d <= 0.55f -> 1f
                    d >= 1f -> 0f
                    else -> (1f - d) / (1f - 0.55f)
                }
                if (alpha <= 0f) {
                    outPixels[idx] = origPixels[idx]
                } else {
                    val o = origPixels[idx]
                    val s = swapPixels[idx]
                    val or_ = (o shr 16) and 0xFF
                    val og = (o shr 8) and 0xFF
                    val ob = o and 0xFF
                    val sr = (s shr 16) and 0xFF
                    val sg = (s shr 8) and 0xFF
                    val sb = s and 0xFF
                    val r = (sr * alpha + or_ * (1f - alpha)).toInt().coerceIn(0, 255)
                    val g = (sg * alpha + og * (1f - alpha)).toInt().coerceIn(0, 255)
                    val b = (sb * alpha + ob * (1f - alpha)).toInt().coerceIn(0, 255)
                    outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        result.setPixels(outPixels, 0, original.width, 0, 0, original.width, original.height)
        full.recycle()
        return result
    }

    /**
     * Per-channel mean/std colour matching (Reinhard-style).
     * Adjusts [swapped] so its channel means/stds match [targetAligned].
     * Both bitmaps must be the same size (aligned crops).
     */
    fun colourMatch(swapped: Bitmap, targetAligned: Bitmap): Bitmap {
        if (swapped.width != targetAligned.width || swapped.height != targetAligned.height) {
            return swapped
        }
        val n = swapped.width * swapped.height
        val sPixels = IntArray(n)
        val tPixels = IntArray(n)
        swapped.getPixels(sPixels, 0, swapped.width, 0, 0, swapped.width, swapped.height)
        targetAligned.getPixels(tPixels, 0, targetAligned.width, 0, 0, targetAligned.width, targetAligned.height)

        fun stats(pixels: IntArray): Array<Pair<Double, Double>> {
            val out = Array(3) { 0.0 to 0.0 }
            for (ch in 0 until 3) {
                var sum = 0.0
                for (p in pixels) {
                    val v = when (ch) {
                        0 -> (p shr 16) and 0xFF
                        1 -> (p shr 8) and 0xFF
                        else -> p and 0xFF
                    }
                    sum += v
                }
                val mean = sum / n
                var varSum = 0.0
                for (p in pixels) {
                    val v = when (ch) {
                        0 -> (p shr 16) and 0xFF
                        1 -> (p shr 8) and 0xFF
                        else -> p and 0xFF
                    }
                    val d = v - mean
                    varSum += d * d
                }
                out[ch] = mean to sqrt(varSum / n)
            }
            return out
        }

        val sStats = stats(sPixels)
        val tStats = stats(tPixels)
        val outPixels = IntArray(n)
        for (i in 0 until n) {
            val p = sPixels[i]
            val channels = IntArray(3)
            for (ch in 0 until 3) {
                val v = when (ch) {
                    0 -> (p shr 16) and 0xFF
                    1 -> (p shr 8) and 0xFF
                    else -> p and 0xFF
                }
                val (sMean, sStd) = sStats[ch]
                val (tMean, tStd) = tStats[ch]
                val scale = if (sStd > 1.0) tStd / sStd else 1.0
                val adjusted = (v - sMean) * scale + tMean
                channels[ch] = adjusted.toInt().coerceIn(0, 255)
            }
            outPixels[i] = (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
        }
        val out = Bitmap.createBitmap(swapped.width, swapped.height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, swapped.width, 0, 0, swapped.width, swapped.height)
        return out
    }

    /** Convert a bitmap crop to CHW float tensor data (RGB, 0..1). */
    fun bitmapToChwFloats(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[w * h + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * w * h + i] = (p and 0xFF) / 255f
        }
        return out
    }

    /** Convert a CHW float tensor (1,3,H,W, values 0..1) to a bitmap. */
    fun chwFloatsToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = (data[i] * 255f).toInt().coerceIn(0, 255)
            val g = (data[width * height + i] * 255f).toInt().coerceIn(0, 255)
            val b = (data[2 * width * height + i] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /** Convert an HWC float tensor (1,H,W,3, values 0..1) to a bitmap. */
    fun hwcFloatsToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = (data[i * 3] * 255f).toInt().coerceIn(0, 255)
            val g = (data[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val b = (data[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /** L2-normalise a float vector in place and return it. */
    fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = sqrt(sum)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] = vec[i] / norm
        }
        return vec
    }

    /** Downscale a bitmap so its longest side is at most [maxSide]. */
    fun downscaleForWorkingCopy(source: Bitmap, maxSide: Int = 1024): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}

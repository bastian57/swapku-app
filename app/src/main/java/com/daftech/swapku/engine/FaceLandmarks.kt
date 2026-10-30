package com.daftech.swapku.engine

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * The 5-point ArcFace template used by the whole pipeline:
 * left eye, right eye, nose tip, left mouth corner, right mouth corner.
 *
 * ML Kit is a DETECTOR, not a recogniser. It does NOT produce embeddings.
 * This file only maps ML Kit landmarks onto the 5-point template so the
 * ArcFace/inSwapper alignment step has a consistent input. It does not
 * claim ML Kit landmarks are identical to SCRFD 5-point keypoints: the
 * research report warns that mapping must be validated on-device, and
 * that the SCRFD path is the preferred one if accuracy is poor (v2).
 *
 * A face is rejected as "not frontal enough" for v1 if the nose x is far
 * outside the eye span or the mouth is above the eyes, because a profile
 * or occluded face gives a wrong embedding.
 */
data class FivePointFace(
    val leftEye: PointF,
    val rightEye: PointF,
    val nose: PointF,
    val mouthLeft: PointF,
    val mouthRight: PointF,
    val boundingBoxWidth: Float,
    val boundingBoxHeight: Float
) {
    /** Eye midpoint, used as the centre of the similarity transform. */
    fun eyeCenter(): PointF =
        PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)

    /** Distance between the eyes, used as the scale reference. */
    fun eyeDistance(): Float {
        val dx = rightEye.x - leftEye.x
        val dy = rightEye.y - leftEye.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Head roll angle in radians, from the eye line. */
    fun rollRadians(): Float =
        kotlin.math.atan2(rightEye.y - leftEye.y, rightEye.x - leftEye.x)

    fun isRoughlyFrontal(): Boolean {
        if (eyeDistance() <= 0f) return false
        if (mouthLeft.y < leftEye.y || mouthRight.y < rightEye.y) return false
        val minX = minOf(leftEye.x, rightEye.x)
        val maxX = maxOf(leftEye.x, rightEye.x)
        // Nose should sit between (or only slightly outside) the eyes.
        if (nose.x < minX - eyeDistance() * 0.6f) return false
        if (nose.x > maxX + eyeDistance() * 0.6f) return false
        return true
    }
}

object FaceLandmarks {
    /**
     * Map one ML Kit [Face] to [FivePointFace], or null if any of the
     * five required landmarks is missing. Callers must handle null as
     * "face not usable for swap", never as a reason to guess points.
     */
    fun fromMlKit(face: Face): FivePointFace? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null
        val box = face.boundingBox
        return FivePointFace(
            leftEye = leftEye,
            rightEye = rightEye,
            nose = nose,
            mouthLeft = mouthLeft,
            mouthRight = mouthRight,
            boundingBoxWidth = box.width().toFloat(),
            boundingBoxHeight = box.height().toFloat()
        )
    }
}

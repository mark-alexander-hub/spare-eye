package io.github.markalexanderhub.spareeye

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * Dead-simple frame differencing on the luma plane.
 *
 * Every frame is shrunk by sampling one pixel in each [step] x [step] block, then compared
 * with the previous shrunk frame. The fraction of samples that moved by more than
 * [pixelThreshold] is the "motion level". Motion is declared when that fraction beats the
 * sensitivity threshold on two frames in a row, which filters single-frame noise.
 */
class MotionDetector(sensitivity: Int) {
    private val step = 4
    private val pixelThreshold = 28
    private val areaThreshold: Float = (0.12 * 0.72.pow((sensitivity.coerceIn(1, 10) - 1).toDouble())).toFloat()

    private var previous: ByteArray? = null
    private var consecutive = 0

    /** Feed one Y plane. Returns the fraction of the picture that changed (0..1). */
    fun feed(y: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Float {
        val sw = width / step
        val sh = height / step
        if (sw <= 0 || sh <= 0) return 0f
        val current = ByteArray(sw * sh)
        var k = 0
        for (j in 0 until sh) {
            val rowBase = j * step * rowStride
            for (i in 0 until sw) {
                current[k++] = y.get(rowBase + i * step * pixelStride)
            }
        }
        val prev = previous
        previous = current
        if (prev == null || prev.size != current.size) return 0f
        var changed = 0
        for (n in current.indices) {
            val d = (current[n].toInt() and 0xFF) - (prev[n].toInt() and 0xFF)
            if (abs(d) > pixelThreshold) changed++
        }
        return changed.toFloat() / current.size
    }

    /** True when the level has been above the threshold for two frames running. */
    fun isMotion(level: Float): Boolean {
        if (level > areaThreshold) consecutive++ else consecutive = 0
        return consecutive >= 2
    }

    /** Forget the last frame, e.g. after a photo changed the exposure. */
    fun reset() {
        previous = null
        consecutive = 0
    }

    val threshold: Float get() = areaThreshold
}

package io.github.markalexanderhub.spareeye

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** YUV_420_888 from CameraX to NV21 bytes, which YuvImage can turn into a JPEG. */
fun ImageProxy.toNv21(): ByteArray {
    val w = width
    val h = height
    val out = ByteArray(w * h + (w / 2) * (h / 2) * 2)

    val yPlane = planes[0]
    val yBuf = yPlane.buffer
    val yRow = yPlane.rowStride
    val yPix = yPlane.pixelStride
    var pos = 0
    if (yPix == 1 && yRow == w) {
        yBuf.rewind()
        yBuf.get(out, 0, w * h)
        pos = w * h
    } else {
        for (r in 0 until h) {
            val base = r * yRow
            for (c in 0 until w) out[pos++] = yBuf.get(base + c * yPix)
        }
    }

    val uBuf = planes[1].buffer
    val vBuf = planes[2].buffer
    val cRow = planes[1].rowStride
    val cPix = planes[1].pixelStride
    val cw = w / 2
    val ch = h / 2
    val uLimit = uBuf.limit()
    val vLimit = vBuf.limit()
    for (r in 0 until ch) {
        val base = r * cRow
        for (c in 0 until cw) {
            val i = base + c * cPix
            out[pos++] = if (i < vLimit) vBuf.get(i) else 0.toByte()
            out[pos++] = if (i < uLimit) uBuf.get(i) else 0.toByte()
        }
    }
    return out
}

fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
    val img = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream(width * height / 4)
    img.compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}

package com.rfsat.vtb.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Post-processes a recorded clip to pull out the vapor-trail pixel track.
 * Simpler and more robust than live analysis during recording: it uses a
 * single reference (background) frame taken just before the shot, then
 * looks for the brightest new blob in each subsequent frame relative to
 * that fixed reference. Works well for a static, tripod-mounted phone;
 * if the camera moves during the shot this will need image stabilisation
 * first (not implemented here).
 */
object TrailExtractor {

    fun extract(
        context: Context,
        videoUri: Uri,
        shotBreakOffsetS: Double,
        clipDurationAfterShotS: Double = 2.0,
        sampleIntervalMs: Long = 16, // ~60 samples/sec; drop to 33 for 30fps sources
        brightnessDeltaThreshold: Int = 18
    ): List<PixelObservation> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        try {
            val referenceUs = ((shotBreakOffsetS - 0.15).coerceAtLeast(0.0) * 1_000_000).toLong()
            val reference = retriever.getFrameAtTime(referenceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return emptyList()
            val w = reference.width
            val h = reference.height
            val refLum = luminance(reference)

            val results = mutableListOf<PixelObservation>()
            var lastX = w / 2.0
            var lastY = h / 2.0
            val searchRadius = (w.coerceAtMost(h)) / 3

            var tS = shotBreakOffsetS
            val endS = shotBreakOffsetS + clipDurationAfterShotS
            while (tS <= endS) {
                val frame = retriever.getFrameAtTime((tS * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null && frame.width == w && frame.height == h) {
                    val lum = luminance(frame)
                    val x0 = (lastX - searchRadius).toInt().coerceIn(0, w - 1)
                    val x1 = (lastX + searchRadius).toInt().coerceIn(0, w - 1)
                    val y0 = (lastY - searchRadius).toInt().coerceIn(0, h - 1)
                    val y1 = (lastY + searchRadius).toInt().coerceIn(0, h - 1)

                    var sumW = 0.0; var sumWx = 0.0; var sumWy = 0.0
                    for (y in y0..y1) {
                        val rowOff = y * w
                        for (x in x0..x1) {
                            val idx = rowOff + x
                            val delta = lum[idx] - refLum[idx]
                            if (delta > brightnessDeltaThreshold) {
                                sumW += delta; sumWx += delta * x; sumWy += delta * y
                            }
                        }
                    }
                    if (sumW > 0) {
                        val px = sumWx / sumW
                        val py = sumWy / sumW
                        lastX = px; lastY = py
                        val confidence = (sumW / (255.0 * (x1 - x0 + 1) * (y1 - y0 + 1))).coerceIn(0.0, 1.0)
                        results.add(PixelObservation(tS - shotBreakOffsetS, px, py, confidence))
                    }
                }
                tS += sampleIntervalMs / 1000.0
            }
            return results
        } finally {
            retriever.release()
        }
    }

    private fun luminance(bitmap: Bitmap): DoubleArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = DoubleArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        return out
    }
}

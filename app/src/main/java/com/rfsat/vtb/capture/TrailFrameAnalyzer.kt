package com.rfsat.vtb.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

data class PixelObservation(val timestampS: Double, val pixelX: Double, val pixelY: Double, val confidence: Double)

/**
 * Detects the vapor trail as a transient bright/hazy region against a
 * slowly-varying background (sky, terrain) using an exponential-moving-
 * average background model plus a search-window-constrained weighted
 * centroid. This is a deliberately simple, dependency-free approach (no
 * OpenCV) — good enough for a bright trail against a reasonably plain sky
 * backdrop; a hazy trail against clutter will need a better detector.
 *
 * Usage: feed frames in order via [ImageAnalysis.Analyzer.analyze]; read
 * [observations] afterwards (or poll [latest] live).
 */
class TrailFrameAnalyzer(
    private val shotBreakEpochS: Double,
    private val searchRadiusPx: Int = 220,
    private val backgroundAlpha: Double = 0.05,
    private val brightnessDeltaThreshold: Int = 18
) : ImageAnalysis.Analyzer {

    private var background: DoubleArray? = null
    private var bgWidth = 0
    private var bgHeight = 0
    private var lastTrackedX: Double? = null
    private var lastTrackedY: Double? = null

    val observations = mutableListOf<PixelObservation>()

    override fun analyze(image: ImageProxy) {
        try {
            val t = (image.imageInfo.timestamp / 1_000_000_000.0) - shotBreakEpochS
            if (t < -0.5) return // ignore frames well before the shot; keeps background model clean

            val yPlane = image.planes[0]
            val w = image.width
            val h = image.height
            val luminance = extractLuminance(yPlane.buffer, yPlane.rowStride, w, h)

            if (background == null || bgWidth != w || bgHeight != h) {
                background = luminance.copyOf()
                bgWidth = w; bgHeight = h
            }
            val bg = background!!

            if (t < 0.0) {
                // Still pre-shot: keep adapting the background model, don't track.
                for (i in luminance.indices) bg[i] = bg[i] * (1 - backgroundAlpha) + luminance[i] * backgroundAlpha
                return
            }

            // Search window around the last known position (or full frame on the first post-shot frame).
            val cx = lastTrackedX ?: (w / 2.0)
            val cy = lastTrackedY ?: (h / 2.0)
            val x0 = (cx - searchRadiusPx).toInt().coerceIn(0, w - 1)
            val x1 = (cx + searchRadiusPx).toInt().coerceIn(0, w - 1)
            val y0 = (cy - searchRadiusPx).toInt().coerceIn(0, h - 1)
            val y1 = (cy + searchRadiusPx).toInt().coerceIn(0, h - 1)

            var sumW = 0.0; var sumWx = 0.0; var sumWy = 0.0
            for (y in y0..y1) {
                val rowOff = y * w
                for (x in x0..x1) {
                    val idx = rowOff + x
                    val delta = luminance[idx] - bg[idx]
                    if (delta > brightnessDeltaThreshold) {
                        sumW += delta
                        sumWx += delta * x
                        sumWy += delta * y
                    }
                }
            }

            if (sumW > 0) {
                val px = sumWx / sumW
                val py = sumWy / sumW
                lastTrackedX = px
                lastTrackedY = py
                val confidence = (sumW / (255.0 * (x1 - x0 + 1) * (y1 - y0 + 1))).coerceIn(0.0, 1.0)
                observations.add(PixelObservation(t, px, py, confidence))
            }
            // Background continues to adapt slowly everywhere except where the
            // trail was just detected, so it doesn't "learn" the trail itself.
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    val nearTrail = lastTrackedX != null &&
                        kotlin.math.abs(x - lastTrackedX!!) < 15 && kotlin.math.abs(y - lastTrackedY!!) < 15
                    if (!nearTrail) bg[idx] = bg[idx] * (1 - backgroundAlpha) + luminance[idx] * backgroundAlpha
                }
            }
        } finally {
            image.close()
        }
    }

    private fun extractLuminance(buffer: ByteBuffer, rowStride: Int, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                val v = bytes[rowStart + x].toInt() and 0xFF
                out[y * w + x] = v.toDouble()
            }
        }
        return out
    }
}

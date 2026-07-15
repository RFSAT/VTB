package com.rfsat.vtb.capture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.rfsat.vtb.log.Logger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Post-processes a recorded clip to pull out the vapor-trail pixel track.
 *
 * DETECTION MODEL: a vapor trail is mostly a refraction phenomenon — the
 * pressure/heat wake behind the bullet bends light passing through it,
 * which (a) shifts edge positions (distortion) and (b) perturbs pixel
 * intensity BOTH up and down relative to the undisturbed background.
 * So per pixel we score:
 *     score = |luminance delta vs reference| + GRADIENT_WEIGHT * |gradient-magnitude delta vs reference|
 * and track the weighted centroid of above-threshold pixels in a search
 * window that follows the trail frame-to-frame. The gradient term is what
 * catches the "shimmering" light-bending signature even when the trail
 * barely changes average brightness.
 *
 * CRASH HARDENING (v4.0): frames are decoded at reduced resolution
 * (<= MAX_DECODE_WIDTH px wide) — full 4K frames cost ~33 MB each as
 * bitmaps and were the prime OOM suspect behind the app dying / resetting
 * during analysis of imported clips. OOM is also caught explicitly and
 * reported to the log rather than killing the process.
 *
 * Runs many blocking MediaMetadataRetriever seeks — call ONLY from a
 * background thread (CaptureActivity dispatches on Dispatchers.Default).
 */
object TrailExtractor {
    private const val TAG = "TrailExtractor"
    private const val MAX_DECODE_WIDTH = 640
    private const val GRADIENT_WEIGHT = 2.0
    /** Tracer mode: weight on the red-dominance delta term. */
    private const val RED_WEIGHT = 1.5
    /** Tracer mode: a pyrotechnic point is BRIGHT — a much higher bar than
     *  the faint refraction signature, which also rejects most sky noise. */
    private const val TRACER_SCORE_THRESHOLD = 48.0

    /**
     * VAPOR — the refraction wake: |luminance delta| + weighted |gradient
     *   delta|, both signs, diffuse centroid (the original detector).
     * TRACER (v14.0) — the burning pyrotechnic element: POSITIVE luminance
     *   delta plus a red-dominance delta term (tracers burn red/orange;
     *   strontium/magnesium compositions). Scores are squared in the
     *   centroid weighting so the compact saturated point dominates over
     *   any residual diffuse smoke it also produces.
     */
    enum class Mode { VAPOR, TRACER }

    data class ExtractionResult(
        val observations: List<PixelObservation>,
        val decodedFrameWidth: Int,
        val decodedFrameHeight: Int
    )

    fun extract(
        videoPath: String,
        shotBreakOffsetS: Double,
        clipDurationAfterShotS: Double = 2.0,
        sampleIntervalMs: Long = 33,
        scoreThreshold: Double = 16.0,
        externalReferenceBitmap: Bitmap? = null,
        mode: Mode = Mode.VAPOR
    ): ExtractionResult {
        val threshold = if (mode == Mode.TRACER) TRACER_SCORE_THRESHOLD else scoreThreshold
        val retriever = MediaMetadataRetriever()
        var scaledExternalReference: Bitmap? = null
        try {
            // Plain file path, not a content:// Uri — SAF content Uris are a
            // known source of native-layer MediaMetadataRetriever failures on
            // some devices/codecs, which kill the process without a catchable
            // Java exception (matching the "app resets with no log" symptom).
            // CaptureActivity copies the video into app cache first.
            retriever.setDataSource(videoPath)
            logVideoMetadata(retriever)

            // Probe one frame to learn source resolution, then pick decode size.
            val probe = decodeFrame(retriever, shotBreakOffsetS, null, null)
            if (probe == null) {
                Logger.e(TAG, "Cannot decode any frame near shot-break time ${shotBreakOffsetS}s — unsupported codec/container, or time offset beyond clip length?")
                return ExtractionResult(emptyList(), 0, 0)
            }
            val srcW = probe.width; val srcH = probe.height
            probe.recycle()
            // v19.6: analysis dimensions use the SAME integer-step rule as
            // FastFrameDecoder. The old fractional scale (1080 -> 640x1137)
            // never matched the decoder's downsample (1080/2 -> 540x960), so
            // the dimension guard rejected every fast frame and every
            // analysis silently paid the ~30 s legacy fallback — the log's
            // "decode complete ... falling back" pair. One rule, both paths.
            val step = ((srcW + MAX_DECODE_WIDTH - 1) / MAX_DECODE_WIDTH).coerceAtLeast(1)
            val w = srcW / step
            val h = srcH / step
            Logger.i(TAG, "Source ${srcW}x${srcH}, analyzing at ${w}x${h} (step=$step)")

            // Reference (undisturbed background) luminance + gradient maps
            // (+ red-dominance map in tracer mode).
            val refLum: DoubleArray
            var refRed: DoubleArray? = null
            if (externalReferenceBitmap != null) {
                scaledExternalReference = Bitmap.createScaledBitmap(externalReferenceBitmap, w, h, true)
                refLum = luminance(scaledExternalReference)
                if (mode == Mode.TRACER) refRed = redDominance(scaledExternalReference)
                scaledExternalReference.recycle(); scaledExternalReference = null
                Logger.i(TAG, "Using external pre-arm reference frame (auto-trigger mode)")
            } else {
                val refT = (shotBreakOffsetS - 0.15).coerceAtLeast(0.0)
                val refFrame = decodeFrame(retriever, refT, w, h)
                if (refFrame == null) {
                    Logger.e(TAG, "No reference frame decodable at ${refT}s")
                    return ExtractionResult(emptyList(), w, h)
                }
                refLum = luminance(refFrame)
                if (mode == Mode.TRACER) refRed = redDominance(refFrame)
                refFrame.recycle()
            }
            // Gradient maps only matter for the refraction signature.
            val refGrad = if (mode == Mode.VAPOR) gradientMagnitude(refLum, w, h) else DoubleArray(0)

            val endS = shotBreakOffsetS + clipDurationAfterShotS
            val scorer = Scorer(mode, threshold, refLum, refRed, refGrad, w, h, shotBreakOffsetS)

            // FAST PATH (v19.0): one sequential MediaCodec pass — ~10x faster
            // than per-frame seeks, and every native frame in the window
            // (60/120 fps clips deliver 2-4x more samples than the old
            // 33 ms grid). Any failure falls back to the legacy path below.
            var framesRead = 0
            var framesFailed = 0
            val fastOk = FastFrameDecoder.decode(
                videoPath, shotBreakOffsetS, endS, MAX_DECODE_WIDTH,
                needRed = (mode == Mode.TRACER)
            ) { f ->
                if (f.width == w && f.height == h) {
                    framesRead++
                    scorer.accept(f.tS, f.lum, f.red)
                } else framesFailed++ // dims disagree with the probe — count and skip
            }

            if (!fastOk || framesRead == 0) {
                Logger.i(TAG, "Falling back to MediaMetadataRetriever path")
                scorer.reset(); framesRead = 0; framesFailed = 0
                var tS = shotBreakOffsetS
                while (tS <= endS) {
                    var frame: Bitmap? = null
                    try {
                        frame = decodeFrame(retriever, tS, w, h)
                        if (frame != null) {
                            framesRead++
                            val lum = luminance(frame)
                            val red = if (mode == Mode.TRACER) redDominance(frame) else null
                            scorer.accept(tS, lum, red)
                        } else framesFailed++
                    } catch (oom: OutOfMemoryError) {
                        framesFailed++
                        Logger.e(TAG, "Out of memory decoding frame at ${tS}s — aborting extraction; try a shorter/lower-resolution clip")
                        break
                    } catch (t: Throwable) {
                        framesFailed++
                        Logger.w(TAG, "Frame decode failed at ${"%.3f".format(tS)}s: ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        frame?.recycle()
                    }
                    tS += sampleIntervalMs / 1000.0
                }
            }
            val results = scorer.results
            Logger.i(TAG, "Extraction done: $framesRead frames read, $framesFailed failed, ${results.size} trail points (fast=${fastOk})")
            if (results.isEmpty() && framesRead > 0) {
                Logger.w(TAG, "Frames decoded fine but nothing exceeded score threshold $threshold — trail too faint, wrong shot-break time, or camera moved between reference and shot")
            }
            return ExtractionResult(results, w, h)
        } catch (oom: OutOfMemoryError) {
            Logger.e(TAG, "Out of memory during extraction setup")
            return ExtractionResult(emptyList(), 0, 0)
        } catch (t: Throwable) {
            Logger.e(TAG, "Trail extraction failed", t)
            return ExtractionResult(emptyList(), 0, 0)
        } finally {
            scaledExternalReference?.recycle()
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Per-frame scoring + centroid tracking, shared by the fast sequential
     * path and the legacy retriever path so the two can never diverge.
     */
    private class Scorer(
        val mode: Mode,
        val threshold: Double,
        val refLum: DoubleArray,
        val refRed: DoubleArray?,
        val refGrad: DoubleArray,
        val w: Int,
        val h: Int,
        val shotBreakOffsetS: Double
    ) {
        val results = mutableListOf<PixelObservation>()
        private var lastX = w / 2.0
        private var lastY = h / 2.0
        private val searchRadius = (w.coerceAtMost(h)) / 3

        fun reset() {
            results.clear(); lastX = w / 2.0; lastY = h / 2.0
        }

        fun accept(tS: Double, lum: DoubleArray, red: DoubleArray?) {
            val grad = if (mode == Mode.VAPOR) gradientMagnitude(lum, w, h) else DoubleArray(0)
            val x0 = (lastX - searchRadius).toInt().coerceIn(1, w - 2)
            val x1 = (lastX + searchRadius).toInt().coerceIn(1, w - 2)
            val y0 = (lastY - searchRadius).toInt().coerceIn(1, h - 2)
            val y1 = (lastY + searchRadius).toInt().coerceIn(1, h - 2)

            var sumW = 0.0; var sumWx = 0.0; var sumWy = 0.0
            for (y in y0..y1) {
                val rowOff = y * w
                for (x in x0..x1) {
                    val idx = rowOff + x
                    val score = if (mode == Mode.TRACER) {
                        // Positive brightness rise + red-dominance rise:
                        // the burning element, not shadows or smoke.
                        (lum[idx] - refLum[idx]).coerceAtLeast(0.0) +
                            RED_WEIGHT * (red!![idx] - refRed!![idx]).coerceAtLeast(0.0)
                    } else {
                        abs(lum[idx] - refLum[idx]) +
                            GRADIENT_WEIGHT * abs(grad[idx] - refGrad[idx])
                    }
                    if (score > threshold) {
                        // Squared weighting in tracer mode: the compact
                        // saturated point must dominate the centroid over
                        // any co-detected smoke haze.
                        val wgt = if (mode == Mode.TRACER) score * score else score
                        sumW += wgt; sumWx += wgt * x; sumWy += wgt * y
                    }
                }
            }
            if (sumW > 0) {
                val px = sumWx / sumW
                val py = sumWy / sumW
                lastX = px; lastY = py
                val confNorm = if (mode == Mode.TRACER) 255.0 * 255.0 else 500.0
                val confidence = (sumW / (confNorm * (x1 - x0 + 1) * (y1 - y0 + 1))).coerceIn(0.0, 1.0)
                results.add(PixelObservation(tS - shotBreakOffsetS, px, py, confidence))
            }
        }
    }

    /** Decodes one frame; scaled decode where the API supports it (>= 27). */
    private fun decodeFrame(retriever: MediaMetadataRetriever, tS: Double, w: Int?, h: Int?): Bitmap? {
        val us = (tS * 1_000_000).toLong().coerceAtLeast(0)
        return if (w != null && h != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
        } else {
            val full = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            if (w == null || h == null || (full.width == w && full.height == h)) full
            else Bitmap.createScaledBitmap(full, w, h, true).also { if (it != full) full.recycle() }
        }
    }

    private fun logVideoMetadata(retriever: MediaMetadataRetriever) {
        try {
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            Logger.i(TAG, "Video metadata: ${vw}x${vh} rot=$rot dur=${dur}ms mime=$mime")
        } catch (t: Throwable) {
            Logger.w(TAG, "Could not read video metadata: ${t.message}")
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

    /** Red-dominance per pixel: max(0, R - max(G, B)). A burning tracer
     *  element is strongly red/orange-saturated, which separates it from
     *  white muzzle smoke, sky, and sun glints far better than brightness
     *  alone. */
    private fun redDominance(bitmap: Bitmap): DoubleArray {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = DoubleArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val d = r - if (g > b) g else b
            out[i] = if (d > 0) d.toDouble() else 0.0
        }
        return out
    }

    /** Sobel gradient magnitude — edge map whose local changes betray the
     *  light-bending distortion of the trail even without a brightness change. */
    private fun gradientMagnitude(lum: DoubleArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w; val r1 = y * w; val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = (lum[r0 + x + 1] + 2 * lum[r1 + x + 1] + lum[r2 + x + 1]) -
                         (lum[r0 + x - 1] + 2 * lum[r1 + x - 1] + lum[r2 + x - 1])
                val gy = (lum[r2 + x - 1] + 2 * lum[r2 + x] + lum[r2 + x + 1]) -
                         (lum[r0 + x - 1] + 2 * lum[r0 + x] + lum[r0 + x + 1])
                out[r1 + x] = sqrt(gx * gx + gy * gy) / 4.0
            }
        }
        return out
    }
}

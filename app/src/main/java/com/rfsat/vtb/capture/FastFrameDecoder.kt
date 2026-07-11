package com.rfsat.vtb.capture

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.rfsat.vtb.log.Logger

/**
 * Sequential video frame decoder (v19.0, performance).
 *
 * MediaMetadataRetriever.getFrameAtTime() performs a full seek + decode
 * per requested frame — with a fixed 33 ms grid that made extraction the
 * dominant cost of an analysis, and silently dropped every frame between
 * grid points. This decoder makes ONE pass with MediaCodec: seek to the
 * nearest sync frame before the window, then decode continuously and
 * stream every frame inside the window to the caller — roughly an order
 * of magnitude faster, and at the clip's native frame rate (60/120 fps
 * clips deliver 2–4x the samples the old grid ever saw).
 *
 * Output: downsampled luminance (and optionally red-dominance) arrays in
 * DISPLAY orientation — the rotation flag in the container is applied
 * here, because MediaCodec yields frames in coded (unrotated) orientation
 * while the legacy Bitmap path yielded rotated ones; skipping this would
 * silently swap the wind axes on portrait clips.
 *
 * Red-dominance is computed from YUV directly (BT.601):
 *   R−max(G,B) with R=Y+1.402(V−128), G=Y−0.344(U−128)−0.714(V−128),
 *   B=Y+1.772(U−128) — matching the RGB definition used by the Bitmap
 *   path within rounding.
 *
 * Returns false on ANY failure — the caller falls back to the legacy
 * retriever path, so a codec quirk can never lose an analysis.
 */
object FastFrameDecoder {

    private const val TAG = "FastFrameDecoder"
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DRAIN_TRIES = 500 // ~5 s of consecutive timeouts

    class Frame(
        val tS: Double,
        val lum: DoubleArray,
        val red: DoubleArray?,
        val width: Int,
        val height: Int
    )

    fun decode(
        videoPath: String,
        startS: Double,
        endS: Double,
        maxWidth: Int,
        needRed: Boolean,
        onFrame: (Frame) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(videoPath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) {
                Logger.w(TAG, "No video track"); return false
            }
            extractor.selectTrack(track)
            extractor.seekTo((startS * 1_000_000).toLong().coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0
            var drainTries = 0
            val endUs = (endS * 1_000_000).toLong()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime > endUs + 500_000) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        drainTries = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val tS = info.presentationTimeUs / 1_000_000.0
                        if (info.size > 0 && tS >= startS && tS <= endS) {
                            val img = codec.getOutputImage(outIdx)
                            if (img != null) {
                                onFrame(convert(img, tS, maxWidth, needRed, rotation))
                                frames++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++drainTries > MAX_DRAIN_TRIES) {
                            Logger.w(TAG, "Decoder stalled after $frames frames"); return frames > 0
                        }
                    }
                    // format/buffers changed: nothing to do, loop again
                }
            }
            Logger.i(TAG, "Sequential decode complete: $frames frames in [${"%.2f".format(startS)}, ${"%.2f".format(endS)}]s, rotation=$rotation")
            return frames > 0
        } catch (t: Throwable) {
            Logger.w(TAG, "Sequential decode failed (falling back to retriever): ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downsample + rotate one YUV_420_888 image into display-oriented
     *  luminance (and optionally red-dominance) arrays. */
    private fun convert(img: Image, tS: Double, maxWidth: Int, needRed: Boolean, rotation: Int): Frame {
        val cw = img.width; val ch = img.height
        // Display dimensions after rotation.
        val dwFull = if (rotation == 90 || rotation == 270) ch else cw
        val dhFull = if (rotation == 90 || rotation == 270) cw else ch
        val step = ((dwFull + maxWidth - 1) / maxWidth).coerceAtLeast(1)
        val w = dwFull / step
        val h = dhFull / step

        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRS = yP.rowStride; val yPS = yP.pixelStride
        val uRS = uP.rowStride; val uPS = uP.pixelStride
        val vRS = vP.rowStride; val vPS = vP.pixelStride

        val lum = DoubleArray(w * h)
        val red = if (needRed) DoubleArray(w * h) else null

        for (dy in 0 until h) {
            val rowOff = dy * w
            for (dx in 0 until w) {
                val px = dx * step; val py = dy * step
                // Display (px,py) -> coded (cx,cy). rotation = degrees the
                // coded frame must be rotated CW for display.
                val cx: Int; val cy: Int
                when (rotation) {
                    90 -> { cx = py; cy = ch - 1 - px }
                    180 -> { cx = cw - 1 - px; cy = ch - 1 - py }
                    270 -> { cx = cw - 1 - py; cy = px }
                    else -> { cx = px; cy = py }
                }
                val yV = yB.get(cy * yRS + cx * yPS).toInt() and 0xFF
                lum[rowOff + dx] = yV.toDouble()
                if (red != null) {
                    val ux = cx / 2; val uy = cy / 2
                    val uV = (uB.get(uy * uRS + ux * uPS).toInt() and 0xFF) - 128
                    val vV = (vB.get(uy * vRS + ux * vPS).toInt() and 0xFF) - 128
                    val r = yV + 1.402 * vV
                    val g = yV - 0.344 * uV - 0.714 * vV
                    val b = yV + 1.772 * uV
                    val d = r - if (g > b) g else b
                    red[rowOff + dx] = if (d > 0) d else 0.0
                }
            }
        }
        return Frame(tS, lum, red, w, h)
    }
}

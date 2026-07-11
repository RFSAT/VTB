package com.rfsat.vtb.wind

import com.rfsat.vtb.capture.PixelObservation
import com.rfsat.vtb.capture.TrailCalibration
import kotlin.math.abs

data class WindSample(
    val timeS: Double,          // seconds after shot break (observation time, NOT bullet flight time)
    val downrangeM: Double,     // effective distance the estimate refers to
    val crosswindMps: Double,   // +right
    val verticalWindMps: Double,// +up (updraft)
    val confidence: Double      // 0..1, tracking confidence x fit quality
)

/**
 * Estimates wind from the DRIFT of the vapor trail after the shot.
 *
 * PHYSICAL MODEL (v6.0 — replaces the equations-of-motion inversion):
 * once the bullet has passed, the vapor trail is a passive tracer that is
 * simply carried by the air. Its angular drift rate seen from the muzzle,
 * dTheta/dt, times the distance D to the drifting segment, IS the wind
 * component perpendicular to the line of sight at that segment:
 *
 *     crosswind  = D * d(thetaX)/dt      vertical = D * d(thetaY)/dt
 *
 * No drag model, no differentiation of noisy position into acceleration,
 * and — critically — no division by a drag rate that goes to zero when
 * the tracked object is (correctly!) not moving like a bullet.
 *
 * The previous approach fed post-flight trail observations into the
 * bullet's equations of motion. Past the ~0.3 s time of flight the tracked
 * centroid is nearly stationary, so the drag-rate term k -> 0 and the
 * solved wind (which contains g/k) diverged to thousands of m/s — the
 * -5382 MOA adjustments seen in v5.1.
 *
 * The tracker follows the trail's weighted centroid, which mixes segments
 * at all distances, so D is an EFFECTIVE distance: half the target
 * distance by default (mid-trail). The result is therefore an average
 * crosswind over the flight path — exactly what a uniform-wind holdover
 * solution needs, and the honest limit of what one boresighted camera can
 * observe. (Per-distance wind profiling would need the trail segmented by
 * range, which a single centroid can't provide.)
 */
object WindEstimator {

    /** Fraction of target distance treated as the centroid's distance. */
    const val EFFECTIVE_DISTANCE_FRACTION = 0.5

    /** Width of the sliding local-fit window (seconds). */
    private const val LOCAL_WINDOW_S = 0.6

    /** Minimum observations for any fit. */
    private const val MIN_POINTS = 5

    /**
     * @param settleTimeS observations earlier than this (seconds after shot
     *   break) are discarded — while the bullet is still in flight the
     *   centroid motion is trail FORMATION, not wind drift. Pass ~1.2x the
     *   expected time of flight.
     */
    /**
     * @param timeToDownrangeM v17.2: maps a sample's observation time to the
     *   bullet's downrange distance at that time (drag-decayed speed
     *   integrated — BallisticsEngine.downrangeAtTime), used as the sample's
     *   chart index. Since drift samples are observed AFTER the flight, the
     *   mapping saturates at the target distance — the honest reading: the
     *   trail's drift measures the path-average wind, charted at the
     *   terminal distance. Null keeps the legacy mid-range effective index.
     *   NOTE: this changes ONLY the chart index; the wind MAGNITUDE always
     *   uses the effective centroid distance (dEff) — the drifting trail
     *   centroid physically sits mid-path regardless of how we index it.
     */
    fun estimate(
        calibration: TrailCalibration,
        observations: List<PixelObservation>,
        targetDistanceM: Double,
        settleTimeS: Double,
        minConfidence: Double = 0.02,
        timeToDownrangeM: ((Double) -> Double)? = null,
        /** v19.0: calibration multiplier on the effective trail-centroid
         *  distance (default 1.0). The crosswind scales linearly with that
         *  assumed distance, so one shot in a Kestrel-measured wind pins it
         *  empirically — solved in Profiles > Wind calibration. */
        windScale: Double = 1.0
    ): List<WindSample> {
        val obs = observations
            .filter { it.confidence >= minConfidence && it.timestampS >= settleTimeS }
            .sortedBy { it.timestampS }
        if (obs.size < MIN_POINTS) return emptyList()

        val dEff = targetDistanceM * EFFECTIVE_DISTANCE_FRACTION * windScale
        val t = DoubleArray(obs.size) { obs[it].timestampS }
        val ax = DoubleArray(obs.size) { calibration.pixelAngleX(obs[it].pixelX) }
        val ay = DoubleArray(obs.size) { calibration.pixelAngleY(obs[it].pixelY) }
        val cw = DoubleArray(obs.size) { obs[it].confidence }

        val out = mutableListOf<WindSample>()
        val half = LOCAL_WINDOW_S / 2.0
        for (i in obs.indices) {
            val tc = t[i]
            var lo = i; while (lo > 0 && t[lo - 1] >= tc - half) lo--
            var hi = i; while (hi < obs.size - 1 && t[hi + 1] <= tc + half) hi++
            if (hi - lo + 1 < MIN_POINTS) continue

            val fx = weightedSlope(t, ax, cw, lo, hi) ?: continue
            val fy = weightedSlope(t, ay, cw, lo, hi) ?: continue

            var meanConf = 0.0
            for (j in lo..hi) meanConf += cw[j]
            meanConf /= (hi - lo + 1)

            out.add(
                WindSample(
                    timeS = tc,
                    downrangeM = timeToDownrangeM?.invoke(tc) ?: dEff,
                    crosswindMps = dEff * fx.slope,
                    verticalWindMps = dEff * fy.slope,
                    confidence = (meanConf * fx.quality * fy.quality).coerceIn(0.0, 1.0)
                )
            )
        }
        return out
    }

    private data class Fit(val slope: Double, val quality: Double)

    /**
     * Confidence-weighted least-squares line through (t, y) over [lo, hi].
     * quality in (0, 1]: penalises fits whose RMS residual is large
     * compared to the total angular travel in the window (i.e. noise-
     * dominated windows score low).
     */
    private fun weightedSlope(t: DoubleArray, y: DoubleArray, w: DoubleArray, lo: Int, hi: Int): Fit? {
        var sw = 0.0; var st = 0.0; var sy = 0.0
        for (i in lo..hi) { sw += w[i]; st += w[i] * t[i]; sy += w[i] * y[i] }
        if (sw < 1e-12) return null
        val tBar = st / sw; val yBar = sy / sw
        var stt = 0.0; var sty = 0.0
        for (i in lo..hi) {
            val dt = t[i] - tBar
            stt += w[i] * dt * dt
            sty += w[i] * dt * (y[i] - yBar)
        }
        if (stt < 1e-12) return null
        val slope = sty / stt

        var ss = 0.0; var span = 0.0
        var yMin = y[lo]; var yMax = y[lo]
        for (i in lo..hi) {
            val r = y[i] - (yBar + slope * (t[i] - tBar))
            ss += w[i] * r * r
            if (y[i] < yMin) yMin = y[i]
            if (y[i] > yMax) yMax = y[i]
        }
        span = yMax - yMin
        val rms = kotlin.math.sqrt(ss / sw)
        val quality = if (span < 1e-9) 0.5 else (1.0 / (1.0 + 3.0 * rms / span)).coerceIn(0.0, 1.0)
        return Fit(slope, quality)
    }

    /**
     * Single best estimate of the (uniform) wind over the observation:
     * confidence-weighted mean of the local samples with one pass of
     * outlier trimming (drop samples > 2.5 sigma from the weighted mean,
     * refit). Returns (crosswindMps, verticalWindMps, confidence) or null.
     */
    /** Hard plausibility ceiling for a single wind sample (m/s). 15 m/s is
     *  already a near-gale (54 km/h) — no practical shooting session, and no
     *  coherent vapor trail on video, happens beyond that. Samples above it
     *  are tracking artefacts and must not drag the average. */
    const val MAX_PLAUSIBLE_SAMPLE_MPS = 15.0

    /** Averaged wind with spread (v19.0): means, mean confidence, and the
     *  sample standard deviations of the trimmed set — the honest gust/noise
     *  spread behind the single number. */
    data class WindStats(
        val crossMps: Double,
        val vertMps: Double,
        val confidence: Double,
        val crossSdMps: Double,
        val vertSdMps: Double
    )

    fun averageWindStats(samples: List<WindSample>): WindStats? {
        val avg = averageWind(samples) ?: return null
        // Recompute the spread on the plausibility-trimmed set.
        val plausible = samples.filter {
            abs(it.crosswindMps) <= MAX_PLAUSIBLE_SAMPLE_MPS &&
            abs(it.verticalWindMps) <= MAX_PLAUSIBLE_SAMPLE_MPS
        }
        fun sd(f: (WindSample) -> Double, mean: Double): Double {
            if (plausible.size < 2) return 0.0
            return kotlin.math.sqrt(plausible.sumOf { val d = f(it) - mean; d * d } / plausible.size)
        }
        return WindStats(avg.first, avg.second, avg.third,
            sd({ it.crosswindMps }, avg.first), sd({ it.verticalWindMps }, avg.second))
    }

    fun averageWind(samples: List<WindSample>): Triple<Double, Double, Double>? {
        val plausible = samples.filter {
            abs(it.crosswindMps) <= MAX_PLAUSIBLE_SAMPLE_MPS &&
            abs(it.verticalWindMps) <= MAX_PLAUSIBLE_SAMPLE_MPS
        }
        // If most of what we measured is implausible, the measurement itself
        // is untrustworthy — report no estimate rather than a laundered one.
        if (plausible.size < samples.size / 2 || plausible.isEmpty()) return null
        return averageWindInner(plausible)
    }

    private fun averageWindInner(samples: List<WindSample>): Triple<Double, Double, Double>? {
        if (samples.isEmpty()) return null
        fun wMean(sel: List<WindSample>, f: (WindSample) -> Double): Double {
            var sw = 0.0; var s = 0.0
            for (x in sel) { sw += x.confidence; s += x.confidence * f(x) }
            return if (sw < 1e-12) sel.sumOf(f) / sel.size else s / sw
        }
        var sel = samples
        repeat(2) {
            val mc = wMean(sel) { it.crosswindMps }
            val mv = wMean(sel) { it.verticalWindMps }
            val sdC = kotlin.math.sqrt(sel.sumOf { val d = it.crosswindMps - mc; d * d } / sel.size)
            val sdV = kotlin.math.sqrt(sel.sumOf { val d = it.verticalWindMps - mv; d * d } / sel.size)
            val kept = sel.filter {
                abs(it.crosswindMps - mc) <= 2.5 * sdC.coerceAtLeast(1e-9) &&
                abs(it.verticalWindMps - mv) <= 2.5 * sdV.coerceAtLeast(1e-9)
            }
            if (kept.size >= MIN_POINTS) sel = kept
        }
        val conf = sel.map { it.confidence }.average()
        return Triple(wMean(sel) { it.crosswindMps }, wMean(sel) { it.verticalWindMps }, conf)
    }
}

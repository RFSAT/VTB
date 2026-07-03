package com.rfsat.vtb.wind

import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.ballistics.BallisticsEngine
import com.rfsat.vtb.profiles.BulletProfile

/**
 * One tracked point on the vapor trail, already converted from video pixels
 * into real-world coordinates relative to the muzzle/line-of-sight origin
 * (see [com.rfsat.vtb.capture.TrailFrameAnalyzer] for the pixel
 * tracking and [com.rfsat.vtb.capture.TrailCalibration] for the
 * pixel -> metre conversion).
 */
data class TrailSample(
    val timeS: Double,
    val downrangeM: Double,
    val lateralM: Double,   // +right, matches Vec3.z
    val verticalM: Double   // +up, matches Vec3.y, relative to the bore line
)

data class WindSample(
    val timeS: Double,
    val downrangeM: Double,
    val crosswindMps: Double,   // +right
    val verticalWindMps: Double // +up (updraft)
)

/**
 * Inverts a smoothed trail path back into an estimated wind vector at each
 * instant of the bullet's flight.
 *
 * Method: the lateral/vertical equations of motion are
 *   a_z(t) = -K(t) * (v_z(t) - wind_z(t))
 *   a_y(t) = -g - K(t) * (v_y(t) - wind_y(t))
 * where K(t) is the drag decay-rate at the bullet's instantaneous speed
 * (from [BallisticsEngine]). Given observed position samples we estimate
 * v(t) and a(t) by local quadratic fits (a cheap, dependency-free stand-in
 * for a Savitzky-Golay filter), then solve directly for wind_z(t)/wind_y(t).
 * This only works well when the trail is tracked with reasonably low pixel
 * noise and reasonably dense, evenly-spaced samples — smooth/resample the
 * raw pixel track before calling this.
 */
object WindEstimator {

    private const val G = 9.80665

    fun estimate(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        samples: List<TrailSample>,
        smoothingWindow: Int = 5
    ): List<WindSample> {
        require(smoothingWindow % 2 == 1) { "smoothingWindow must be odd" }
        val sorted = samples.sortedBy { it.timeS }
        if (sorted.size < smoothingWindow + 2) return emptyList()

        val half = smoothingWindow / 2
        val out = mutableListOf<WindSample>()

        for (i in half until sorted.size - half) {
            val window = sorted.subList(i - half, i + half + 1)
            val (vz, az) = fitVelocityAndAccel(window) { it.lateralM }
            val (vy, ay) = fitVelocityAndAccel(window) { it.verticalM }

            // Total (relative) speed for the drag-rate lookup: downrange
            // component dominates for any sane crosswind, so approximate
            // with the local downrange speed plus the small lateral/vertical
            // components already estimated.
            val (vx, _) = fitVelocityAndAccel(window) { it.downrangeM }
            val speedApprox = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
            val k = BallisticsEngine.effectiveDragRate(bullet, atmosphere, speedApprox)
            if (k < 1e-9) continue

            val windZ = vz + az / k
            val windY = vy + (ay + G) / k

            out.add(WindSample(sorted[i].timeS, sorted[i].downrangeM, windZ, windY))
        }
        return out
    }

    /**
     * Least-squares fit of a quadratic p(t) = c0 + c1*t + c2*t^2 to the
     * windowed samples, returning (velocity, acceleration) = (c1, 2*c2) at
     * the window's own local time origin. Small closed-form 3x3 solve —
     * no external math library needed.
     */
    private fun fitVelocityAndAccel(window: List<TrailSample>, coord: (TrailSample) -> Double): Pair<Double, Double> {
        val t0 = window[window.size / 2].timeS
        val ts = window.map { it.timeS - t0 }
        val ys = window.map(coord)

        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var sy0 = 0.0; var sy1 = 0.0; var sy2 = 0.0
        for (i in ts.indices) {
            val t = ts[i]; val y = ys[i]
            val t2 = t * t
            s0 += 1.0; s1 += t; s2 += t2; s3 += t2 * t; s4 += t2 * t2
            sy0 += y; sy1 += y * t; sy2 += y * t2
        }
        // Solve the 3x3 normal-equations system [s0 s1 s2; s1 s2 s3; s2 s3 s4] * c = [sy0 sy1 sy2]
        val a = arrayOf(
            doubleArrayOf(s0, s1, s2, sy0),
            doubleArrayOf(s1, s2, s3, sy1),
            doubleArrayOf(s2, s3, s4, sy2)
        )
        gaussianEliminate(a)
        val c1 = a[1][3]
        val c2 = a[2][3]
        return Pair(c1, 2.0 * c2)
    }

    /** In-place Gauss-Jordan elimination on a 3x4 augmented matrix. */
    private fun gaussianEliminate(a: Array<DoubleArray>) {
        val n = 3
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val pv = a[col][col]
            if (kotlin.math.abs(pv) < 1e-12) continue
            for (c in col..n) a[col][c] = a[col][c] / pv
            for (r in 0 until n) {
                if (r == col) continue
                val factor = a[r][col]
                for (c in col..n) a[r][c] -= factor * a[col][c]
            }
        }
    }
}

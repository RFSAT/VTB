package com.rfsat.vtb.ballistics

import com.rfsat.vtb.profiles.BulletProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TrajectoryPoint(
    val timeS: Double,
    val position: Vec3, // meters, x=downrange, y=up, z=right
    val velocity: Vec3  // m/s
)

/**
 * Wind as a function of time-of-flight since the shot broke, and of
 * downrange distance already travelled (m). z-component convention:
 * positive = blowing left-to-right as seen by the shooter (pushes the
 * bullet toward +z). y-component lets a vertical (updraft/downdraft)
 * component be modelled too; usually 0.
 */
typealias WindProfile = (timeS: Double, downrangeM: Double) -> Vec3

private val NO_WIND: WindProfile = { _, _ -> Vec3.ZERO }

object BallisticsEngine {

    private const val G = 9.80665
    private const val DT = 0.0002 // s — small enough for stable RK4 on rimfire velocities

    /**
     * Approximate drag coefficient vs Mach number for a small-caliber
     * round-nose bullet. This is a hand-built, physically-reasonable
     * curve (subsonic plateau, transonic rise, supersonic decay) — NOT a
     * digitised standard G1 table. It gets the qualitative trajectory
     * shape (including the transonic hump relevant to .22LR at typical
     * ranges) right, but for match-grade precision you should calibrate
     * `BulletProfile.dragCalibrationFactor` against your own chronograph
     * and drop data, or swap this function for a validated G1/G7 table.
     */
    /**
     * Digitised STANDARD G1 reference drag table, Cd vs Mach (v17.0) —
     * the published Mayevski/Ingalls-derived curve every commercial G1 BC
     * is quoted against (as tabulated in McCoy, "Modern Exterior
     * Ballistics"). Replaces the earlier hand-built approximation, which
     * peaked at Cd≈1.15 at Mach 1 (a generic-projectile guess) where the
     * G1 reference actually peaks at ≈0.66 near Mach 1.4. With the true
     * reference curve, an accurate published BC should now need a
     * dragCalibrationFactor close to 1.0 — re-run the official-drop
     * calibration after upgrading, since factors tuned against the old
     * curve compensate for its shape error.
     */
    private val G1_MACH = doubleArrayOf(
        0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45,
        0.50, 0.55, 0.60, 0.65, 0.70, 0.725, 0.75, 0.775, 0.80, 0.825,
        0.85, 0.875, 0.90, 0.925, 0.95, 0.975, 1.00, 1.025, 1.05, 1.075,
        1.10, 1.125, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.45, 1.50,
        1.55, 1.60, 1.65, 1.70, 1.75, 1.80, 1.85, 1.90, 1.95, 2.00,
        2.05, 2.10, 2.15, 2.20, 2.25, 2.30, 2.35, 2.40, 2.45, 2.50,
        2.60, 2.70, 2.80, 2.90, 3.00, 3.10, 3.20, 3.30, 3.40, 3.50,
        3.60, 3.70, 3.80, 3.90, 4.00
    )
    private val G1_CD = doubleArrayOf(
        0.2629, 0.2558, 0.2487, 0.2413, 0.2344, 0.2278, 0.2214, 0.2155, 0.2104, 0.2061,
        0.2032, 0.2020, 0.2034, 0.2089, 0.2165, 0.2230, 0.2313, 0.2417, 0.2546, 0.2706,
        0.2901, 0.3136, 0.3415, 0.3734, 0.4084, 0.4448, 0.4805, 0.5136, 0.5427, 0.5677,
        0.5883, 0.6053, 0.6191, 0.6393, 0.6518, 0.6589, 0.6621, 0.6625, 0.6607, 0.6573,
        0.6528, 0.6474, 0.6413, 0.6347, 0.6280, 0.6210, 0.6141, 0.6072, 0.6003, 0.5934,
        0.5867, 0.5804, 0.5743, 0.5685, 0.5630, 0.5577, 0.5527, 0.5481, 0.5435, 0.5393,
        0.5313, 0.5238, 0.5168, 0.5102, 0.5040, 0.4980, 0.4922, 0.4866, 0.4811, 0.4757,
        0.4705, 0.4653, 0.4602, 0.4552, 0.4503
    )

    /** Standard G1 Cd(Mach), linear interpolation; clamped at the table ends
     *  (above Mach 4 the curve is nearly flat; below the table start it IS
     *  the table start). */
    private fun dragCoefficient(mach: Double): Double {
        val m = abs(mach)
        if (m <= G1_MACH.first()) return G1_CD.first()
        if (m >= G1_MACH.last()) return G1_CD.last()
        var lo = 0; var hi = G1_MACH.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (G1_MACH[mid] <= m) lo = mid else hi = mid
        }
        val f = (m - G1_MACH[lo]) / (G1_MACH[hi] - G1_MACH[lo])
        return G1_CD[lo] + f * (G1_CD[hi] - G1_CD[lo])
    }

    /**
     * Drag "decay rate" k such that drag acceleration = -k * (velocity - wind).
     * Exposed publicly because the wind estimator needs the exact same
     * coefficient to invert observed trail curvature back into an estimated
     * wind vector at each point along the flight.
     */
    /** 1 lb/in^2 in kg/m^2 — G1 BCs are quoted in lb/in^2. */
    private const val BC_LB_IN2_TO_KG_M2 = 703.069

    fun dragDecayRate(bullet: BulletProfile, atmosphere: Atmosphere, relativeSpeedMps: Double): Double {
        val speed = max(relativeSpeedMps, 1e-6)
        val mach = speed / atmosphere.speedOfSound
        val cd = dragCoefficient(mach) * bullet.dragCalibrationFactor
        // STANDARD BC-referenced retardation: a = rho * v^2 * Cd_ref(M) / (2 * BC_SI),
        // i.e. per-distance decay rate k [1/m] = rho * Cd_ref(M) / (2 * BC_SI).
        // The G1 BC (lb/in^2 -> kg/m^2) IS the bullet's effective sectional
        // density; it must NOT be combined with the bullet's own mass and
        // cross-section. The previous formula did exactly that (divided the
        // physical m/A drag by BC=0.139 again), inflating drag ~7x: the
        // simulated .22LR stalled short of a 200 m target and then FELL for
        // the rest of simulate()'s 10 s budget — the "15 km drop" bug.
        return 0.5 * atmosphere.airDensity * cd /
            (bullet.ballisticCoefficientG1 * BC_LB_IN2_TO_KG_M2)
    }

    /** Convenience: the coefficient K such that drag accel = -K * (velocity - wind). */
    fun effectiveDragRate(bullet: BulletProfile, atmosphere: Atmosphere, relativeSpeedMps: Double): Double =
        dragDecayRate(bullet, atmosphere, relativeSpeedMps) * max(relativeSpeedMps, 1e-6)

    private fun acceleration(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        velocity: Vec3,
        wind: Vec3
    ): Vec3 {
        val relVel = velocity - wind
        val speed = relVel.length
        if (speed < 1e-6) return Vec3(0.0, -G, 0.0)
        val k = dragDecayRate(bullet, atmosphere, speed) * speed
        val drag = relVel * (-k)
        return drag + Vec3(0.0, -G, 0.0)
    }

    /**
     * Integrate the trajectory from the muzzle out to [maxRangeM], sampling
     * every [sampleEveryS] seconds. `pitchRad`/`yawRad` are the barrel's
     * launch angles relative to the line of sight (i.e. already include any
     * zero elevation). `wind` may vary with time and downrange distance.
     */
    fun simulate(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        pitchRad: Double,
        yawRad: Double,
        maxRangeM: Double,
        wind: WindProfile = NO_WIND,
        sampleEveryS: Double = 0.002
    ): List<TrajectoryPoint> {
        val v0 = bullet.muzzleVelocityMps
        var vel = Vec3(
            x = v0 * kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad),
            y = v0 * kotlin.math.sin(pitchRad),
            z = v0 * kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)
        )
        var pos = Vec3.ZERO
        var t = 0.0
        var nextSample = 0.0
        val out = mutableListOf<TrajectoryPoint>()

        // Guards besides range: a hard time cap, a "hit the ground" cutoff
        // (60 m below the sight line is beyond any sane engagement), and a
        // horizontal-stall cutoff — a bullet that has stopped moving
        // downrange can never reach the target, and integrating its free
        // fall only manufactures absurd numbers.
        while (pos.x < maxRangeM && t < 10.0 && pos.y > -60.0 && vel.x > 5.0) {
            if (t >= nextSample) {
                out.add(TrajectoryPoint(t, pos, vel))
                nextSample += sampleEveryS
            }
            val w = wind(t, pos.x)
            // classic RK4 on velocity+position
            val a1 = acceleration(bullet, atmosphere, vel, w)
            val v1 = vel
            val a2 = acceleration(bullet, atmosphere, vel + a1 * (DT / 2), w)
            val v2 = vel + a1 * (DT / 2)
            val a3 = acceleration(bullet, atmosphere, vel + a2 * (DT / 2), w)
            val v3 = vel + a2 * (DT / 2)
            val a4 = acceleration(bullet, atmosphere, vel + a3 * DT, w)
            val v4 = vel + a3 * DT

            pos += (v1 + (v2 * 2.0) + (v3 * 2.0) + v4) * (DT / 6.0)
            vel += (a1 + (a2 * 2.0) + (a3 * 2.0) + a4) * (DT / 6.0)
            t += DT
        }
        out.add(TrajectoryPoint(t, pos, vel))
        return out
    }

    /**
     * Finds the launch pitch angle (radians, relative to the line of
     * sight) that zeroes the bullet at [zeroDistanceM] given [sightHeightM]
     * above the bore, with no wind. Simple bisection — robust, and this
     * only runs once per profile change.
     */
    /**
     * Bullet's downrange distance at time tS along a simulated trajectory —
     * i.e. the integral of the drag-decayed speed (v17.2, per user: map the
     * wind chart's time axis to distance from the barrel). Clamped to the
     * trajectory: before launch it's 0; after the bullet arrives/lands the
     * speed is 0, so the distance saturates at the final point.
     */
    fun downrangeAtTime(traj: List<TrajectoryPoint>, tS: Double): Double {
        if (traj.isEmpty()) return 0.0
        if (tS <= traj.first().timeS) return traj.first().position.x
        if (tS >= traj.last().timeS) return traj.last().position.x
        var lo = 0; var hi = traj.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (traj[mid].timeS <= tS) lo = mid else hi = mid
        }
        val a = traj[lo]; val b = traj[hi]
        val dt = b.timeS - a.timeS
        if (dt < 1e-9) return a.position.x
        return a.position.x + (tS - a.timeS) / dt * (b.position.x - a.position.x)
    }

    /**
     * v18.0: time -> distance mapping for the wind chart, WITH drag decay
     * but WITHOUT trajectory termination. Integrates the bullet's speed
     * decay (dv/dt = -k(v)*v, same G1/BC/atmosphere retardation as the
     * full simulation, dragCalibrationFactor included) as if the bullet
     * flew on past the target — so late drift samples keep spreading
     * sub-linearly instead of saturating (the v17.2 clamped variant the
     * user rejected). Speed is floored at 1 m/s to keep the mapping
     * strictly monotonic. Returns a lookup closure valid on [0, maxTS].
     */
    fun dragDecayedDistanceFn(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        maxTS: Double
    ): (Double) -> Double {
        val dt = 0.001
        val n = (maxTS / dt).toInt() + 2
        val xs = DoubleArray(n)
        var v = bullet.muzzleVelocityMps
        var x = 0.0
        for (i in 1 until n) {
            // Midpoint step on dv/dt = -k(v)*v, dx/dt = v.
            val a1 = dragDecayRate(bullet, atmosphere, v) * v
            val vMid = (v - 0.5 * dt * a1).coerceAtLeast(1.0)
            val a2 = dragDecayRate(bullet, atmosphere, vMid) * vMid
            x += vMid * dt
            v = (v - dt * a2).coerceAtLeast(1.0)
            xs[i] = x
        }
        return { t ->
            val idx = t / dt
            val i = idx.toInt().coerceIn(0, n - 2)
            val f = (idx - i).coerceIn(0.0, 1.0)
            xs[i] + f * (xs[i + 1] - xs[i])
        }
    }

    fun solveZeroPitch(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        zeroDistanceM: Double,
        sightHeightM: Double
    ): Double {
        fun dropAtZeroForPitch(pitch: Double): Double {
            val traj = simulate(bullet, atmosphere, pitch, 0.0, zeroDistanceM + 1.0)
            val atZero = traj.lastOrNull { it.position.x <= zeroDistanceM } ?: traj.last()
            // line-of-sight sits sightHeightM above the bore at the muzzle and
            // is level; bullet path is measured relative to bore, so subtract.
            return atZero.position.y - sightHeightM
        }
        var lo = -0.02
        var hi = 0.12 // ~6.9 deg — covers slow subsonic loads zeroed at 200 m+
        repeat(60) {
            val mid = (lo + hi) / 2
            if (dropAtZeroForPitch(mid) < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Zero-wind estimate of time-of-flight to [targetDistanceM] — used to
     * size the auto-trigger recording window (see [com.rfsat.vtb.capture.ShotDetector]).
     * Pitch doesn't matter much for this estimate (a few degrees of elevation
     * barely changes forward velocity decay), so a flat (0 rad) shot is fine.
     */
    fun timeOfFlight(bullet: BulletProfile, atmosphere: Atmosphere, targetDistanceM: Double): Double {
        val traj = simulate(bullet, atmosphere, 0.0, 0.0, targetDistanceM + 1.0)
        val atTarget = traj.lastOrNull { it.position.x <= targetDistanceM } ?: traj.last()
        return atTarget.timeS
    }

    /**
     * Time-of-flight along the ZEROED trajectory: solves the launch pitch
     * implied by the scope's zero distance and sight height, then integrates
     * with that pitch. The pitch effect on TOF is small at rimfire ranges,
     * but this keeps the capture-timing and settle-time estimates consistent
     * with the same zero calibration the correction solver uses.
     */
    fun zeroedTimeOfFlight(
        bullet: BulletProfile,
        atmosphere: Atmosphere,
        targetDistanceM: Double,
        zeroDistanceM: Double,
        sightHeightM: Double
    ): Double {
        val pitch = solveZeroPitch(bullet, atmosphere, zeroDistanceM, sightHeightM)
        val traj = simulate(bullet, atmosphere, pitch, 0.0, targetDistanceM + 1.0)
        val atTarget = traj.lastOrNull { it.position.x <= targetDistanceM } ?: traj.last()
        return atTarget.timeS
    }
}

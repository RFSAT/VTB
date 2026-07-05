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
    private fun dragCoefficient(mach: Double): Double {
        val m = abs(mach)
        return when {
            m < 0.75 -> 0.50
            m < 1.0 -> 0.50 + (m - 0.75) / 0.25 * (1.15 - 0.50)
            m < 1.2 -> 1.15 - (m - 1.0) / 0.2 * (1.15 - 0.95)
            else -> max(0.20, 0.95 * (1.2 / m))
        }
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

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
    fun dragDecayRate(bullet: BulletProfile, atmosphere: Atmosphere, relativeSpeedMps: Double): Double {
        val speed = max(relativeSpeedMps, 1e-6)
        val mach = speed / atmosphere.speedOfSound
        val cd = dragCoefficient(mach) * bullet.dragCalibrationFactor
        // BC scales the effective ballistic mass: higher BC -> less drag for
        // a given shape. We fold it in as a divisor on the drag term, which
        // is the standard convention (BC = sectional density / form factor).
        return 0.5 * atmosphere.airDensity * cd *
            bullet.crossSectionalAreaM2 / (bullet.massKg * bullet.ballisticCoefficientG1)
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

        while (pos.x < maxRangeM && t < 10.0) {
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
        var hi = 0.05
        repeat(60) {
            val mid = (lo + hi) / 2
            if (dropAtZeroForPitch(mid) < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}

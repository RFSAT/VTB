package com.rfsat.vtb.results

import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.ballistics.BallisticsEngine
import com.rfsat.vtb.ballistics.Vec3
import com.rfsat.vtb.profiles.BulletProfile
import com.rfsat.vtb.profiles.RifleProfile
import com.rfsat.vtb.profiles.ScopeProfile
import com.rfsat.vtb.wind.WindSample
import kotlin.math.atan

data class ScopeAdjustment(
    val windageMoa: Double,   // +right correction needed on target
    val elevationMoa: Double, // +up correction needed on target
    val windageClicks: Int,
    val elevationClicks: Int,
    val windageDirection: String, // "LEFT" or "RIGHT" turret direction
    val elevationDirection: String, // "UP" or "DOWN" turret direction
    val impactOffsetInAtTarget: Vec3 // diagnostic: where the last shot landed relative to POA, inches (x unused)
)

object AdjustmentCalculator {

    /**
     * Builds a smooth, interpolated wind function from the discrete
     * [WindSample]s produced by [com.rfsat.vtb.wind.WindEstimator],
     * for use as a [com.rfsat.vtb.ballistics.WindProfile] in a
     * forward simulation. Falls back to the nearest sample outside the
     * observed time range (i.e. holds the last known wind for target
     * distances beyond what the trail video covered).
     */
    private fun interpolatedWind(samples: List<WindSample>): (Double, Double) -> Vec3 {
        if (samples.isEmpty()) return { _, _ -> Vec3.ZERO }
        val sorted = samples.sortedBy { it.timeS }
        return wind@{ t, _ ->
            if (t <= sorted.first().timeS) return@wind Vec3(0.0, sorted.first().verticalWindMps, sorted.first().crosswindMps)
            if (t >= sorted.last().timeS) return@wind Vec3(0.0, sorted.last().verticalWindMps, sorted.last().crosswindMps)
            val idx = sorted.indexOfLast { it.timeS <= t }
            val a = sorted[idx]
            val b = sorted[idx + 1]
            val f = (t - a.timeS) / (b.timeS - a.timeS)
            Vec3(0.0, a.verticalWindMps + f * (b.verticalWindMps - a.verticalWindMps),
                a.crosswindMps + f * (b.crosswindMps - a.crosswindMps))
        }
    }

    /**
     * Computes the scope adjustment needed so the *next* shot lands on the
     * point of aim, given the estimated wind profile from the last shot's
     * vapor trail and the current target distance.
     */
    fun computeAdjustment(
        bullet: BulletProfile,
        rifle: RifleProfile,
        scope: ScopeProfile,
        atmosphere: Atmosphere,
        targetDistanceYd: Double,
        windSamples: List<WindSample>
    ): ScopeAdjustment {
        val targetDistanceM = targetDistanceYd * 0.9144
        val sightHeightM = rifle.sightHeightIn * 0.0254
        val zeroDistanceM = rifle.zeroDistanceYards * 0.9144

        val pitch = BallisticsEngine.solveZeroPitch(bullet, atmosphere, zeroDistanceM, sightHeightM)
        val wind = interpolatedWind(windSamples)

        val traj = BallisticsEngine.simulate(bullet, atmosphere, pitch, 0.0, targetDistanceM + 1.0, wind)
        val atTarget = traj.lastOrNull { it.position.x <= targetDistanceM } ?: traj.last()

        // Line of sight is level and starts sightHeightM above the bore.
        val verticalMissM = atTarget.position.y - sightHeightM
        val lateralMissM = atTarget.position.z

        // Convert linear miss at range into angular correction (MOA), then to the
        // scope's own click unit.
        val rangeM = atTarget.position.x.coerceAtLeast(1.0)
        val elevationMoa = radToMoa(atan(verticalMissM / rangeM)) * -1.0 // correct opposite to the miss
        val windageMoa = radToMoa(atan(lateralMissM / rangeM)) * -1.0

        val clickMoa = if (scope.clickUnitIsMoa) scope.clickValue else scope.clickValue * MRAD_TO_MOA

        return ScopeAdjustment(
            windageMoa = windageMoa,
            elevationMoa = elevationMoa,
            windageClicks = Math.round(windageMoa / clickMoa).toInt(),
            elevationClicks = Math.round(elevationMoa / clickMoa).toInt(),
            windageDirection = if (windageMoa >= 0) "RIGHT" else "LEFT",
            elevationDirection = if (elevationMoa >= 0) "UP" else "DOWN",
            impactOffsetInAtTarget = Vec3(0.0, verticalMissM / 0.0254, lateralMissM / 0.0254)
        )
    }

    private const val MRAD_TO_MOA = 3.43775 // 1 mrad = 3.43775 MOA
    private fun radToMoa(rad: Double) = Math.toDegrees(rad) * 60.0
}

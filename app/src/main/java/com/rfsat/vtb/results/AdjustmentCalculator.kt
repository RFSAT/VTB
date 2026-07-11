package com.rfsat.vtb.results

import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.ballistics.BallisticsEngine
import com.rfsat.vtb.ballistics.Vec3
import com.rfsat.vtb.profiles.BulletProfile
import com.rfsat.vtb.profiles.RifleProfile
import com.rfsat.vtb.profiles.ScopeProfile
import com.rfsat.vtb.wind.WindEstimator
import com.rfsat.vtb.wind.WindSample
import kotlin.math.abs
import kotlin.math.atan

data class ScopeAdjustment(
    // Angular corrections in the SCOPE'S OWN unit (MRAD for mil scopes,
    // MOA for MOA scopes). +right / +up.
    val windageScopeUnits: Double,
    val elevationScopeUnits: Double,
    val scopeUnitLabel: String,       // "MRAD" or "MOA"
    val windageClicks: Int,
    val elevationClicks: Int,
    val windageDirection: String,     // "LEFT" or "RIGHT" turret direction
    val elevationDirection: String,   // "UP" or "DOWN" turret direction
    val estimatedCrosswindMps: Double,     // uniform wind used in the solution, +right
    val estimatedVerticalWindMps: Double,  // +up
    val windConfidence: Double,            // 0..1
    /** v19.0: sample spread (1 sd) of the trimmed wind estimates — gust/
     *  noise scatter behind the mean. 0.0 on payloads from older versions. */
    val crosswindStdMps: Double = 0.0,
    val verticalWindStdMps: Double = 0.0,
    val impactOffsetMAtTarget: Vec3,  // diagnostic: last shot's landing point vs POA, metres (x unused)
    val warnings: List<String>,       // practicality/sanity flags for the Results screen
    /** false when the simulated trajectory never reached the target — the
     *  numbers above are then meaningless and must not be displayed. */
    val valid: Boolean = true
)

object AdjustmentCalculator {

    private const val MRAD_TO_MOA = 3.43775 // 1 mrad = 3.43775 MOA

    /** Hard rejection: above this the estimate is an artefact, full stop.
     *  15 m/s = 54 km/h = near-gale; not a practical shooting condition and
     *  not something a filmed vapor trail survives coherently. */
    private const val MAX_CREDIBLE_WIND_MPS = 15.0
    /** Caution band: 8 m/s (29 km/h, Beaufort 5) is already an unusually
     *  strong wind to be shooting in — plausible, but worth flagging. */
    private const val STRONG_WIND_MPS = 8.0
    /** Below this the fit is statistically meaningless — using it would be
     *  worse than the zero-wind solution it replaces. */
    private const val MIN_USABLE_CONFIDENCE = 0.05
    /** TESTING SWITCH (v13.0): while tuning the tracking pipeline the raw
     *  low-confidence estimates are exactly the data of interest, so the
     *  revert-to-zero behaviour hides the signal being debugged. Set back
     *  to true before any release build — a <5% fit must not be dialled. */
    private const val ENFORCE_MIN_CONFIDENCE = false

    /**
     * Computes the scope adjustment needed so the *next* shot lands on the
     * point of aim, using the UNIFORM average wind derived from the trail's
     * drift (see [WindEstimator] for why one boresighted camera can only
     * support a uniform-wind model). Output is in the scope's own angular
     * unit and click count, with warnings when the correction is not
     * practically achievable or the wind estimate is not credible.
     */
    fun computeAdjustment(
        bullet: BulletProfile,
        rifle: RifleProfile,
        scope: ScopeProfile,
        atmosphere: Atmosphere,
        targetDistanceYd: Double,
        windSamples: List<WindSample>
    ): ScopeAdjustment {
        val warnings = mutableListOf<String>()

        val avg = WindEstimator.averageWindStats(windSamples)
        var crossMps = avg?.crossMps ?: 0.0
        var vertMps = avg?.vertMps ?: 0.0
        val windConf = avg?.confidence ?: 0.0
        val crossSd = avg?.crossSdMps ?: 0.0
        val vertSd = avg?.vertSdMps ?: 0.0
        if (avg == null) {
            warnings.add("No usable wind estimate from the trail (none, or mostly implausible samples) — this is a zero-wind solution.")
        } else if (abs(crossMps) > MAX_CREDIBLE_WIND_MPS || abs(vertMps) > MAX_CREDIBLE_WIND_MPS) {
            warnings.add(
                "Estimated wind exceeds any practical shooting condition (>${MAX_CREDIBLE_WIND_MPS.toInt()} m/s) — " +
                "check camera FOV, boresight calibration and shot-break time. Falling back to a zero-wind solution."
            )
            crossMps = 0.0; vertMps = 0.0
        } else if (ENFORCE_MIN_CONFIDENCE && windConf < MIN_USABLE_CONFIDENCE) {
            warnings.add(
                "Confidence ${(windConf * 100).toInt()}% below threshold (${(MIN_USABLE_CONFIDENCE * 100).toInt()}%) — zero-wind solution."
            )
            crossMps = 0.0; vertMps = 0.0
        } else {
            if (windConf < MIN_USABLE_CONFIDENCE) {
                warnings.add(
                    "Confidence ${(windConf * 100).toInt()}% below threshold (${(MIN_USABLE_CONFIDENCE * 100).toInt()}%)."
                )
            }
            if (abs(crossMps) > STRONG_WIND_MPS) {
                warnings.add(
                    "Estimated crosswind ${"%.1f".format(abs(crossMps))} m/s is unusually strong for " +
                    "practical shooting — verify it matches conditions at the range before dialling."
                )
            }
            if (windConf < 0.15) {
                warnings.add("Low confidence — use wind estimate with caution.")
            }
        }

        val targetDistanceM = targetDistanceYd * 0.9144
        val sightHeightM = effectiveSightHeightM(rifle, scope)
        val zeroDistanceM = rifle.zeroDistanceM

        val pitch = BallisticsEngine.solveZeroPitch(bullet, atmosphere, zeroDistanceM, sightHeightM)
        val uniformWind = Vec3(0.0, vertMps, crossMps)

        // NOTE: wind must be a NAMED argument — it is not the last parameter
        // of simulate() (sampleEveryS is), so a trailing lambda mis-binds.
        val traj = BallisticsEngine.simulate(
            bullet, atmosphere, pitch, 0.0, targetDistanceM + 1.0,
            wind = { _, _ -> uniformWind }
        )
        val atTarget = traj.lastOrNull { it.position.x <= targetDistanceM } ?: traj.last()
        val reachedTarget = atTarget.position.x >= targetDistanceM * 0.95
        if (!reachedTarget) {
            warnings.add("Simulated trajectory fell short of the target — check bullet profile / target distance.")
        }

        // Line of sight is level and starts sightHeightM above the bore.
        val verticalMissM = atTarget.position.y - sightHeightM
        val lateralMissM = atTarget.position.z

        // Linear miss at range -> angular correction (opposite to the miss),
        // first in MOA, then into the scope's own unit.
        val rangeM = atTarget.position.x.coerceAtLeast(1.0)
        val elevationMoa = radToMoa(atan(verticalMissM / rangeM)) * -1.0
        val windageMoa = radToMoa(atan(lateralMissM / rangeM)) * -1.0

        val moaPerScopeUnit = if (scope.clickUnitIsMoa) 1.0 else MRAD_TO_MOA
        val windageScope = windageMoa / moaPerScopeUnit
        val elevationScope = elevationMoa / moaPerScopeUnit
        val unitLabel = if (scope.clickUnitIsMoa) "MOA" else "MRAD"

        // Practicality: turret travel specs are TOTAL range; from an
        // optically-centred zero roughly half is available in each direction.
        val windageHalfTravelMoa = scope.maxWindageTravelMoa / 2.0
        val elevationHalfTravelMoa = scope.maxElevationTravelMoa / 2.0
        if (abs(windageMoa) > windageHalfTravelMoa) {
            warnings.add(
                "Windage correction (${fmt(abs(windageScope))} $unitLabel) exceeds the scope's usable travel " +
                "(±${fmt(windageHalfTravelMoa / moaPerScopeUnit)} $unitLabel from centre) — hold off instead."
            )
        }
        if (abs(elevationMoa) > elevationHalfTravelMoa) {
            warnings.add(
                "Elevation correction (${fmt(abs(elevationScope))} $unitLabel) exceeds the scope's usable travel " +
                "(±${fmt(elevationHalfTravelMoa / moaPerScopeUnit)} $unitLabel from centre) — hold over instead."
            )
        }

        val clickMoa = if (scope.clickUnitIsMoa) scope.clickValue else scope.clickValue * MRAD_TO_MOA

        return ScopeAdjustment(
            windageScopeUnits = windageScope,
            elevationScopeUnits = elevationScope,
            scopeUnitLabel = unitLabel,
            windageClicks = Math.round(windageMoa / clickMoa).toInt(),
            elevationClicks = Math.round(elevationMoa / clickMoa).toInt(),
            windageDirection = if (windageMoa >= 0) "RIGHT" else "LEFT",
            elevationDirection = if (elevationMoa >= 0) "UP" else "DOWN",
            estimatedCrosswindMps = crossMps,
            estimatedVerticalWindMps = vertMps,
            windConfidence = windConf,
            crosswindStdMps = crossSd,
            verticalWindStdMps = vertSd,
            impactOffsetMAtTarget = Vec3(0.0, verticalMissM, lateralMissM),
            warnings = warnings,
            valid = reachedTarget
        )
    }

    /** The scope profile owns the optical-centerline height; fall back to
     *  the rifle's legacy sightHeightIn if a profile predates the field. */
    fun effectiveSightHeightM(rifle: RifleProfile, scope: ScopeProfile): Double =
        (if (scope.heightAboveBarrelIn > 0) scope.heightAboveBarrelIn else rifle.sightHeightIn) * 0.0254

    private fun radToMoa(rad: Double) = Math.toDegrees(rad) * 60.0
    private fun fmt(v: Double) = String.format("%.1f", v)
}

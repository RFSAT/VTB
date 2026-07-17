package com.rfsat.vtb.profiles

/**
 * Diameter/length in inches, weight in grains, muzzle velocity in fps,
 * ballistic coefficient is G1 (matches what's printed on ammo boxes/specs).
 */
data class BulletProfile(
    val name: String = "Federal Champion Solid 40gr .22 LR",
    val caliberDiameterIn: Double = 0.224,
    val weightGrains: Double = 40.0,
    val muzzleVelocityFps: Double = 1240.0,
    val ballisticCoefficientG1: Double = 0.139,
    /** Optional local calibration multiplier on drag; 1.0 = trust the BC as given.
     *  Tune this against your own chronograph/drop data if the model runs fast or slow. */
    val dragCalibrationFactor: Double = 1.0,
    /** Tracer round (v14.0): the pyrotechnic point is tracked instead of the
     *  vapor trail, and crosswind is inferred from the BULLET's lag
     *  deflection rather than trail drift. Gson leaves this false on
     *  profiles saved by older versions. NOTE: a burning tracer loses mass
     *  as it flies, so its published BC drifts over the flight — treat the
     *  BC as approximate and lean on dragCalibrationFactor if drop data
     *  disagrees. */
    val isTracer: Boolean = false,
    /** v20.1: rimfire/centerfire powder is temperature-sensitive — MV shifts
     *  roughly 0.5-1 m/s per degC for .22LR. With the Kestrel supplying real
     *  temperature, the analysis can correct for it: effective MV =
     *  MV + coeff x (ambient - reference). 0.0 = feature off (and the safe
     *  Gson value for profiles saved by older versions). */
    val mvTempCoeffMpsPerC: Double = 0.0,
    val mvRefTempC: Double = 15.0
) {
    val massKg: Double get() = weightGrains * 0.00006479891
    val diameterM: Double get() = caliberDiameterIn * 0.0254
    val crossSectionalAreaM2: Double get() = Math.PI * (diameterM / 2.0).let { it * it }
    val muzzleVelocityMps: Double get() = muzzleVelocityFps * 0.3048

    /** Copy with MV corrected to the given ambient temperature (v20.1).
     *  Returns this unchanged when the coefficient is unset — so every
     *  downstream consumer (engine, estimators, recorded MV) stays
     *  consistent with zero special-casing. */
    fun adjustedForTemperature(ambientC: Double): BulletProfile {
        if (mvTempCoeffMpsPerC == 0.0) return this
        val newMps = muzzleVelocityMps + mvTempCoeffMpsPerC * (ambientC - mvRefTempC)
        return copy(muzzleVelocityFps = (newMps / 0.3048).coerceAtLeast(1.0))
    }

    companion object {
        val DEFAULT = BulletProfile()
    }
}

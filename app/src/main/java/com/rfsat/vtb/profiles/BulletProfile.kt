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
    val dragCalibrationFactor: Double = 1.0
) {
    val massKg: Double get() = weightGrains * 0.00006479891
    val diameterM: Double get() = caliberDiameterIn * 0.0254
    val crossSectionalAreaM2: Double get() = Math.PI * (diameterM / 2.0).let { it * it }
    val muzzleVelocityMps: Double get() = muzzleVelocityFps * 0.3048

    companion object {
        val DEFAULT = BulletProfile()
    }
}

package com.rfsat.vtb.capture

import kotlin.math.atan
import kotlin.math.tan

/**
 * Calibration for converting tracked trail pixel positions into real-world
 * lateral/vertical offsets from the line of fire.
 *
 * ASSUMED CAMERA SETUP (this matters — get it wrong and the wind estimate
 * will be wrong): the phone is positioned at or close to the muzzle,
 * pointed roughly down the line of fire so the whole trail is visible
 * snaking away toward the target (the standard "trace cam" position used
 * for wind-reading footage). A camera positioned off to the side, looking
 * across the line of fire, is NOT compatible with this model — see the
 * README for why side-on footage can't recover crosswind this way.
 *
 * [boresightPixelX]/[boresightPixelY] should be marked by the shooter by
 * tapping the point of aim (the target centre / crosshair position) in a
 * reference frame taken before the shot — that pixel defines the optical
 * centreline the trail's deviation is measured against.
 */
data class TrailCalibration(
    val horizontalFovDeg: Double,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    val boresightPixelX: Double,
    val boresightPixelY: Double
) {
    private val focalLengthPx: Double
        get() = (frameWidthPx / 2.0) / tan(Math.toRadians(horizontalFovDeg / 2.0))

    fun pixelAngleX(pixelX: Double): Double = atan((pixelX - boresightPixelX) / focalLengthPx)

    /** Positive = up, since pixel-Y grows downward on screen. */
    fun pixelAngleY(pixelY: Double): Double = atan((boresightPixelY - pixelY) / focalLengthPx)

    /**
     * Converts a tracked pixel into world lateral/vertical offsets (metres)
     * from the boresight, given the bullet's downrange distance at that
     * instant (from the baseline no-wind trajectory time-of-flight model —
     * see [TrailSampleBuilder]).
     */
    fun toWorldOffsets(pixelX: Double, pixelY: Double, downrangeM: Double): Pair<Double, Double> {
        val lateral = downrangeM * tan(pixelAngleX(pixelX))
        val vertical = downrangeM * tan(pixelAngleY(pixelY))
        return Pair(lateral, vertical)
    }
}

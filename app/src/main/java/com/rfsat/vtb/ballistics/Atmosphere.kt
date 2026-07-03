package com.rfsat.vtb.ballistics

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Minimal ICAO-style standard atmosphere, parameterised by the shooter's
 * local conditions (these should be entered at the range, not assumed).
 *
 * All quantities SI. This is intentionally simple (troposphere only, dry
 * air) — good to well beyond any rimfire or centerfire engagement altitude.
 */
data class Atmosphere(
    val seaLevelPressurePa: Double = 101325.0,
    val temperatureC: Double = 15.0,
    val altitudeM: Double = 0.0,
    val relativeHumidity: Double = 0.0 // 0..1, small effect, kept for completeness
) {
    companion object {
        private const val LAPSE_RATE = 0.0065 // K/m
        private const val GAS_CONSTANT_DRY_AIR = 287.058 // J/(kg*K)
        private const val G0 = 9.80665
        private const val GAMMA = 1.4
    }

    private val temperatureK = temperatureC + 273.15

    /** Air density in kg/m^3 at the shooter's altitude, from ideal gas law. */
    val airDensity: Double by lazy {
        val tAtAlt = temperatureK - LAPSE_RATE * altitudeM
        val pAtAlt = seaLevelPressurePa * (tAtAlt / temperatureK).pow(G0 / (GAS_CONSTANT_DRY_AIR * LAPSE_RATE))
        // Small humidity correction: moist air is *less* dense than dry air.
        val humidityFactor = 1.0 - 0.0037 * relativeHumidity
        (pAtAlt / (GAS_CONSTANT_DRY_AIR * tAtAlt)) * humidityFactor
    }

    /** Local speed of sound in m/s, needed to compute Mach number for drag. */
    val speedOfSound: Double by lazy {
        sqrt(GAMMA * GAS_CONSTANT_DRY_AIR * temperatureK)
    }
}

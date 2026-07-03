package com.rfsat.vtb.profiles

enum class ClickUnit { MOA_QUARTER, MOA_EIGHTH, MRAD_TENTH }

/**
 * Placeholder scope — replace once the real scope's specs are known.
 * clickUnit determines the turret's angular value per click; the
 * adjustment calculator reports required clicks in this unit.
 */
data class ScopeProfile(
    val name: String = "Generic 1/4-MOA Scope (placeholder — update with real scope)",
    val clickUnit: ClickUnit = ClickUnit.MOA_QUARTER,
    val maxElevationTravelMoa: Double = 60.0,
    val maxWindageTravelMoa: Double = 60.0
) {
    /** Angular value of one click, in the scope's own units. */
    val clickValue: Double get() = when (clickUnit) {
        ClickUnit.MOA_QUARTER -> 0.25
        ClickUnit.MOA_EIGHTH -> 0.125
        ClickUnit.MRAD_TENTH -> 0.1
    }
    val clickUnitIsMoa: Boolean get() = clickUnit != ClickUnit.MRAD_TENTH

    companion object {
        val DEFAULT = ScopeProfile()
    }
}

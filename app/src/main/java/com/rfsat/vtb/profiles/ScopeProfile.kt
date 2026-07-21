package com.rfsat.vtb.profiles

enum class ClickUnit { MOA_QUARTER, MOA_EIGHTH, MRAD_TENTH }

/**
 * Scope description including the optical parameters relevant to both the
 * ballistic solution (click value, height above barrel) and record-keeping
 * (magnification range, objective, focal length). The default is the
 * Vector Optics Continental 5-30x56 (VCT-34FFP Tactical MIL / VEC-MBR):
 * 0.1 MRAD/click, 26 MRAD elevation / 16 MRAD windage travel
 * (manufacturer spec).
 */
data class ScopeProfile(
    val name: String = "Vector Optics Continental 5-30x56",
    val clickUnit: ClickUnit = ClickUnit.MRAD_TENTH,
    val maxElevationTravelMoa: Double = 26.0 * 3.43775,
    val maxWindageTravelMoa: Double = 16.0 * 3.43775,
    val zoomMin: Double = 5.0,
    val zoomMax: Double = 30.0,
    val objectiveDiameterMm: Double = 56.0,
    /** Effective focal length of the objective assembly (mm). Informational. */
    val focalLengthMm: Double = 112.0,
    /** Scope optical centerline height above the bore axis (inches) — this is
     *  the value the ballistic solver actually uses for the sight-height offset. */
    val heightAboveBarrelIn: Double = 1.97,
    /** v20.18: for DIGITAL scopes that record video (ATN etc.): the optical
     *  field of view in degrees at the scope's BASE magnification. 0 = not a
     *  video scope / unknown. Lets Capture import scope-recorded clips with
     *  the right geometry (FOV@1x-equivalent = fovAtBaseDeg * zoomMin). */
    val fovAtBaseDeg: Double = 0.0,
    /** v20.23: scope streams live video over its own Wi-Fi (ATN etc.) —
     *  enables the scope-stream capture source on the Capture tab. */
    val streamCapable: Boolean = false
) {
    val clickValue: Double get() = when (clickUnit) {
        ClickUnit.MOA_QUARTER -> 0.25
        ClickUnit.MOA_EIGHTH -> 0.125
        ClickUnit.MRAD_TENTH -> 0.1
    }
    val clickUnitIsMoa: Boolean get() = clickUnit != ClickUnit.MRAD_TENTH

    companion object {
        val DEFAULT = ScopeProfile()

        /** Pull-down presets; "Custom" leaves fields editable as-is. */
        val PRESETS: List<ScopeProfile> = listOf(
            DEFAULT,
            ScopeProfile(
                name = "Vector Optics Continental 3-18x50 Tactical",
                clickUnit = ClickUnit.MRAD_TENTH,
                maxElevationTravelMoa = 30.0 * 3.43775, maxWindageTravelMoa = 15.0 * 3.43775,
                zoomMin = 3.0, zoomMax = 18.0, objectiveDiameterMm = 50.0,
                focalLengthMm = 100.0, heightAboveBarrelIn = 1.85
            ),
            ScopeProfile(
                name = "Generic 1/4-MOA hunting scope 3-9x40",
                clickUnit = ClickUnit.MOA_QUARTER,
                maxElevationTravelMoa = 60.0, maxWindageTravelMoa = 60.0,
                zoomMin = 3.0, zoomMax = 9.0, objectiveDiameterMm = 40.0,
                focalLengthMm = 80.0, heightAboveBarrelIn = 1.5
            ),
            ScopeProfile(
                name = "Generic 1/8-MOA target scope 8-32x56",
                clickUnit = ClickUnit.MOA_EIGHTH,
                maxElevationTravelMoa = 40.0, maxWindageTravelMoa = 40.0,
                zoomMin = 8.0, zoomMax = 32.0, objectiveDiameterMm = 56.0,
                focalLengthMm = 110.0, heightAboveBarrelIn = 1.9
            ),
            ScopeProfile(name = "Custom…")
        )
    }
}

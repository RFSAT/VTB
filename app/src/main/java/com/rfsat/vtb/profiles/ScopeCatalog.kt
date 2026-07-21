package com.rfsat.vtb.profiles

/**
 * Built-in riflescope catalogue (v20.12) — the scope analogue of
 * [AmmoCatalog]. Entries carry manufacturer-published optical and turret
 * specs; selecting one PRE-FILLS the editable scope fields (the custom
 * define-and-save flow is untouched — a catalogue pick is a seed).
 *
 * The two turret-travel figures are stored in MOA internally (matching
 * ScopeProfile); MRAD-scope specs are converted on entry (1 mrad = 3.43775
 * MOA). Where a manufacturer quotes only a total elevation/windage travel,
 * that value is used; figures are approximate and vary by tube/reticle, so
 * they seed the fields for review rather than claiming authority.
 */
object ScopeCatalog {

    private const val MRAD_MOA = 3.43775

    data class Entry(
        val brand: String,
        val model: String,
        val zoomMin: Double,
        val zoomMax: Double,
        val objectiveMm: Double,
        val clickUnit: ClickUnit,
        val elevTravelMoa: Double,
        val windTravelMoa: Double,
        val focalLengthMm: Double,
        val heightAboveBarrelIn: Double = 1.5,
        /** Optical FOV (deg) at zoomMin for video-recording digital scopes;
         *  0 for traditional optics. Verified from manufacturer specs. */
        val baseFovDeg: Double = 0.0,
        /** Digital day/night or thermal (affects the catalogue filter). */
        val family: String = "Optical"
    ) {
        val magClass: String get() = when {
            zoomMax <= 9.0 -> "Low (\u2264 9\u00d7)"
            zoomMax <= 20.0 -> "Mid (10\u201320\u00d7)"
            else -> "High (> 20\u00d7)"
        }
        val clickLabel: String get() = when (clickUnit) {
            ClickUnit.MRAD_TENTH -> "0.1 MRAD"
            ClickUnit.MOA_QUARTER -> "1/4 MOA"
            ClickUnit.MOA_EIGHTH -> "1/8 MOA"
        }
        fun label(): String =
            "$brand $model \u2014 ${fmt(zoomMin)}-${fmt(zoomMax)}\u00d7${objectiveMm.toInt()}, $clickLabel"
        private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        fun toScopeProfile(): ScopeProfile = ScopeProfile(
            name = "$brand $model",
            fovAtBaseDeg = baseFovDeg,
            clickUnit = clickUnit,
            maxElevationTravelMoa = elevTravelMoa,
            maxWindageTravelMoa = windTravelMoa,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            objectiveDiameterMm = objectiveMm,
            focalLengthMm = focalLengthMm,
            heightAboveBarrelIn = heightAboveBarrelIn
        )
    }

    private fun mrad(mrads: Double) = mrads * MRAD_MOA

    val entries: List<Entry> = listOf(
        // ---- Vector Optics ----
        Entry("Vector Optics", "Continental 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(16.0), 112.0, 1.97),
        Entry("Vector Optics", "Continental 3-18x50", 3.0, 18.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(15.0), 100.0, 1.85),
        Entry("Vector Optics", "Continental 1-6x28", 1.0, 6.0, 28.0, ClickUnit.MRAD_TENTH, mrad(32.0), mrad(32.0), 90.0, 1.5),
        Entry("Vector Optics", "Marksman 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MRAD_TENTH, mrad(24.0), mrad(12.0), 100.0, 1.7),
        Entry("Vector Optics", "Taurus 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(14.0), 112.0, 1.9),
        // ---- Vortex ----
        Entry("Vortex", "Viper PST Gen II 5-25x50", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, 65.0, 33.0, 100.0, 1.6),
        Entry("Vortex", "Strike Eagle 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(29.0), mrad(29.0), 110.0, 1.7),
        Entry("Vortex", "Diamondback Tactical 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MRAD_TENTH, 65.0, 65.0, 100.0, 1.6),
        Entry("Vortex", "Razor HD Gen III 6-36x56", 6.0, 36.0, 56.0, ClickUnit.MRAD_TENTH, mrad(41.0), mrad(20.0), 120.0, 1.9),
        Entry("Vortex", "Crossfire II 4-12x40", 4.0, 12.0, 40.0, ClickUnit.MOA_QUARTER, 60.0, 60.0, 90.0, 1.5),
        // ---- Burris ----
        Entry("Burris", "XTR III 5.5-30x56", 5.5, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(31.0), mrad(20.0), 112.0, 1.9),
        Entry("Burris", "Veracity PH 4-20x50", 4.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, 70.0, 50.0, 100.0, 1.7),
        Entry("Burris", "Fullfield IV 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MOA_QUARTER, 55.0, 55.0, 100.0, 1.6),
        // ---- Steiner ----
        Entry("Steiner", "T6Xi 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(28.5), mrad(14.0), 112.0, 1.9),
        Entry("Steiner", "M7Xi 4-28x56", 4.0, 28.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(12.0), 110.0, 1.9),
        Entry("Steiner", "P4Xi 4-16x56", 4.0, 16.0, 56.0, ClickUnit.MRAD_TENTH, mrad(27.0), mrad(14.0), 100.0, 1.7),
        // ---- Schmidt & Bender ----
        Entry("Schmidt & Bender", "PM II 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(15.0), 112.0, 1.97),
        Entry("Schmidt & Bender", "PM II 3-20x50", 3.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(15.0), 100.0, 1.9),
        Entry("Schmidt & Bender", "Exos 3-21x56", 3.0, 21.0, 56.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(16.0), 110.0, 1.9),
        Entry("Schmidt & Bender", "Polar T96 2.5-10x50", 2.5, 10.0, 50.0, ClickUnit.MRAD_TENTH, mrad(20.0), mrad(16.0), 90.0, 1.6),
        // ---- Bushnell ----
        Entry("Bushnell", "Elite Tactical XRS3 6-36x56", 6.0, 36.0, 56.0, ClickUnit.MRAD_TENTH, mrad(40.0), mrad(20.0), 120.0, 1.9),
        Entry("Bushnell", "Match Pro ED 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(31.0), mrad(15.0), 112.0, 1.85),
        Entry("Bushnell", "Engage 4-16x44", 4.0, 16.0, 44.0, ClickUnit.MOA_QUARTER, 70.0, 70.0, 95.0, 1.6),
        // ---- Nightforce (bonus common brand) ----
        Entry("Nightforce", "ATACR 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(27.0), mrad(14.0), 112.0, 1.97),
        Entry("Nightforce", "NX8 4-32x50", 4.0, 32.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(20.0), 100.0, 1.9),
        // ---- ATN digital day/night (X-Sight 5 family; FOV verified: 9.0deg
        //      at 3x base, 6.3deg at 5x base; record to microSD up to 4K,
        //      WiFi streaming via ATN Connect 5, Recoil Activated Video) ----
        Entry("ATN", "X-Sight 5 3-15x", 3.0, 15.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night"),
        Entry("ATN", "X-Sight 5 5-25x", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 6.3, family = "Digital day/night"),
        Entry("ATN", "X-Sight 5 LRF 3-15x", 3.0, 15.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night"),
        Entry("ATN", "X-Sight 5 LRF 5-25x", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 6.3, family = "Digital day/night"),
        // ---- ATN thermal (ThOR 5; FOV verified for the 640 2-16x25 (17.6deg)
        //      and 640 5-40x75 (5.9deg); 320-sensor FOVs unverified -> 0) ----
        Entry("ATN", "ThOR 5 320 3-12x", 3.0, 12.0, 35.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 0.0, family = "Thermal"),
        Entry("ATN", "ThOR 5 LRF 320 5-20x", 5.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 0.0, family = "Thermal"),
        Entry("ATN", "ThOR 5 640 2-16x", 2.0, 16.0, 25.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 17.6, family = "Thermal"),
        Entry("ATN", "ThOR 5 LRF 640 2-16x", 2.0, 16.0, 25.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 17.6, family = "Thermal"),
        Entry("ATN", "ThOR 5 640 5-40x", 5.0, 40.0, 75.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 120.0, 2.2, baseFovDeg = 5.9, family = "Thermal")
    )

    const val ALL = "All"

    fun brands(): List<String> = listOf(ALL) + entries.map { it.brand }.distinct().sorted()
    fun families(): List<String> = listOf(ALL) + entries.map { it.family }.distinct()
    fun clickUnits(): List<String> = listOf(ALL, "0.1 MRAD", "1/4 MOA", "1/8 MOA")
    fun magClasses(): List<String> = listOf(ALL, "Low (\u2264 9\u00d7)", "Mid (10\u201320\u00d7)", "High (> 20\u00d7)")

    fun filter(brand: String, click: String, mag: String, family: String = ALL): List<Entry> =
        entries.filter {
            (brand == ALL || it.brand == brand) &&
            (click == ALL || it.clickLabel == click) &&
            (mag == ALL || it.magClass == mag) &&
            (family == ALL || it.family == family)
        }
}

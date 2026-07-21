package com.rfsat.vtb.profiles

/**
 * Built-in factory ammunition catalogue (v20.6).
 *
 * Entries carry MANUFACTURER-PUBLISHED weight, muzzle velocity and G1
 * ballistic coefficient. Published figures come from test barrels: real
 * rifles commonly run 20-50 fps different, and BCs vary by lot — so a
 * catalogue pick is a SEED for the editable bullet fields, to be refined
 * by chronograph data and the drop calibration, not gospel. Selecting an
 * entry resets dragCalibrationFactor to 1.0 and the MV temperature
 * coefficient to 0, because calibration belongs to a load, not a listing.
 *
 * Velocity classes follow the usual convention: below 1125 fps (sea-level
 * speed of sound at 15 degC) counts as subsonic.
 */
object AmmoCatalog {

    const val SUBSONIC_LIMIT_FPS = 1125.0

    data class Entry(
        val manufacturer: String,
        val product: String,
        val caliber: String,          // display name, e.g. ".22 LR"
        val diameterIn: Double,
        val weightGr: Double,
        val mvFps: Double,
        val bcG1: Double,
        val type: String,             // RN, HP, FMJ, SP, Match, Pellet, Slug
        val pellet: Boolean = false   // airgun projectile -> PELLET tracking
    ) {
        val subsonic: Boolean get() = mvFps < SUBSONIC_LIMIT_FPS
        val velocityClass: String get() = if (subsonic) "Subsonic" else "Supersonic"
        fun label(): String =
            "$manufacturer $product \u2014 $caliber ${weightGr.toInt()}gr $type, ${mvFps.toInt()} fps, BC %.3f".format(bcG1)
        fun toBulletProfile(): BulletProfile = BulletProfile(
            name = "$manufacturer $product ${weightGr.toInt()}gr $caliber",
            caliberDiameterIn = diameterIn,
            weightGrains = weightGr,
            muzzleVelocityFps = mvFps,
            ballisticCoefficientG1 = bcG1,
            dragCalibrationFactor = 1.0,
            isTracer = false,
            isPellet = pellet,
            mvTempCoeffMpsPerC = 0.0,
            mvRefTempC = 15.0
        )
    }

    private const val D22 = 0.224   // app-wide .22 convention (drop cal absorbs the heeled-bullet nuance)
    private const val D308 = 0.308
    private const val D264 = 0.264

    val entries: List<Entry> = listOf(
        // ---- .22 LR ----
        Entry("CCI", "Standard Velocity", ".22 LR", D22, 40.0, 1070.0, 0.120, "RN"),
        Entry("CCI", "Mini-Mag", ".22 LR", D22, 40.0, 1235.0, 0.137, "RN"),
        Entry("CCI", "Mini-Mag HP", ".22 LR", D22, 36.0, 1260.0, 0.113, "HP"),
        Entry("CCI", "Stinger", ".22 LR", D22, 32.0, 1640.0, 0.084, "HP"),
        Entry("CCI", "Velocitor", ".22 LR", D22, 40.0, 1435.0, 0.119, "HP"),
        Entry("CCI", "Quiet-22", ".22 LR", D22, 40.0, 710.0, 0.120, "RN"),
        Entry("CCI", "Suppressor", ".22 LR", D22, 45.0, 970.0, 0.150, "HP"),
        Entry("CCI", "Blazer", ".22 LR", D22, 40.0, 1235.0, 0.130, "RN"),
        Entry("Federal", "Champion", ".22 LR", D22, 40.0, 1240.0, 0.138, "RN"),
        Entry("Federal", "AutoMatch", ".22 LR", D22, 40.0, 1200.0, 0.140, "RN"),
        Entry("Federal", "Gold Medal Target", ".22 LR", D22, 40.0, 1080.0, 0.130, "Match"),
        Entry("Federal", "American Eagle HP", ".22 LR", D22, 38.0, 1260.0, 0.110, "HP"),
        Entry("Remington", "Thunderbolt", ".22 LR", D22, 40.0, 1255.0, 0.138, "RN"),
        Entry("Remington", "Golden Bullet HP", ".22 LR", D22, 36.0, 1280.0, 0.110, "HP"),
        Entry("Remington", "Subsonic HP", ".22 LR", D22, 38.0, 1050.0, 0.110, "HP"),
        Entry("Fiocchi", "Standard Velocity", ".22 LR", D22, 40.0, 1070.0, 0.130, "RN"),
        Entry("Fiocchi", "High Velocity", ".22 LR", D22, 40.0, 1250.0, 0.130, "RN"),
        Entry("Fiocchi", "Subsonic HP", ".22 LR", D22, 38.0, 1030.0, 0.110, "HP"),
        Entry("Winchester", "Super-X", ".22 LR", D22, 40.0, 1300.0, 0.121, "RN"),
        Entry("Aguila", "SuperExtra SV", ".22 LR", D22, 40.0, 1130.0, 0.130, "RN"),
        Entry("Aguila", "Subsonic", ".22 LR", D22, 40.0, 1025.0, 0.120, "RN"),
        Entry("SK", "Standard Plus", ".22 LR", D22, 40.0, 1073.0, 0.130, "Match"),
        Entry("Lapua", "Center-X", ".22 LR", D22, 40.0, 1073.0, 0.132, "Match"),
        Entry("Eley", "Club", ".22 LR", D22, 40.0, 1085.0, 0.130, "Match"),
        // ---- .22 WMR ----
        Entry("CCI", "Maxi-Mag", ".22 WMR", D22, 40.0, 1875.0, 0.118, "HP"),
        // ---- .223 Remington ----
        Entry("Federal", "American Eagle FMJBT", ".223 Rem", D22, 55.0, 3240.0, 0.269, "FMJ"),
        Entry("Federal", "Gold Medal 69 SMK", ".223 Rem", D22, 69.0, 2950.0, 0.301, "Match"),
        Entry("Federal", "Gold Medal 77 SMK", ".223 Rem", D22, 77.0, 2750.0, 0.372, "Match"),
        Entry("Remington", "UMC FMJ", ".223 Rem", D22, 55.0, 3240.0, 0.267, "FMJ"),
        Entry("Fiocchi", "Range Dynamics FMJBT", ".223 Rem", D22, 55.0, 3240.0, 0.270, "FMJ"),
        Entry("Winchester", "USA FMJ", ".223 Rem", D22, 55.0, 3240.0, 0.267, "FMJ"),
        Entry("Hornady", "Frontier FMJ", ".223 Rem", D22, 55.0, 3240.0, 0.243, "FMJ"),
        Entry("CCI", "Blazer Brass FMJ", ".223 Rem", D22, 55.0, 3240.0, 0.267, "FMJ"),
        // ---- .308 Winchester ----
        Entry("Federal", "Gold Medal 168 SMK", ".308 Win", D308, 168.0, 2650.0, 0.462, "Match"),
        Entry("Federal", "Gold Medal 175 SMK", ".308 Win", D308, 175.0, 2600.0, 0.505, "Match"),
        Entry("Federal", "Power-Shok SP", ".308 Win", D308, 150.0, 2820.0, 0.313, "SP"),
        Entry("Remington", "Core-Lokt PSP", ".308 Win", D308, 150.0, 2820.0, 0.314, "SP"),
        Entry("Fiocchi", "Range Dynamics FMJBT", ".308 Win", D308, 150.0, 2890.0, 0.398, "FMJ"),
        Entry("Winchester", "Super-X Power-Point", ".308 Win", D308, 150.0, 2820.0, 0.294, "SP"),
        Entry("Sellier & Bellot", "FMJ", ".308 Win", D308, 147.0, 2808.0, 0.390, "FMJ"),
        Entry("Hornady", "Sub-X", ".308 Win", D308, 190.0, 1050.0, 0.437, "SP"),
        // ---- 6.5 Creedmoor ----
        Entry("Hornady", "ELD Match", "6.5 CM", D264, 140.0, 2710.0, 0.646, "Match"),
        Entry("Federal", "American Eagle OTM", "6.5 CM", D264, 140.0, 2700.0, 0.580, "Match"),
        // ---- Airgun pellets (diabolo; JSB-published G1 BCs) ----
        Entry("JSB", "Exact Jumbo", ".22 pellet", 0.2165, 15.89, 950.0, 0.033, "Pellet", pellet = true),
        Entry("JSB", "Exact Jumbo Heavy", ".22 pellet", 0.2165, 18.13, 1000.0, 0.036, "Pellet", pellet = true),
        Entry("JSB", "Exact King", ".25 pellet", 0.250, 25.39, 950.0, 0.041, "Pellet", pellet = true),
        Entry("JSB", "Exact King Heavy", ".25 pellet", 0.250, 33.95, 900.0, 0.048, "Pellet", pellet = true),
        Entry("JSB", "Exact .30", ".30 pellet", 0.300, 44.75, 820.0, 0.055, "Pellet", pellet = true),
        Entry("H&N", "Baracuda Match .22", ".22 pellet", 0.2165, 21.14, 920.0, 0.036, "Pellet", pellet = true),
        // ---- Airgun slugs incl. AEA big-bore (velocities AEA-published for
        //      the Element Max; slug BCs approximate -> drop-calibrate) ----
        Entry("JSB", "KnockOut Slug .22", ".22 slug", 0.217, 25.4, 900.0, 0.090, "Slug", pellet = true),
        Entry("AEA", "Slug .45 (Element Max)", ".45 slug", 0.457, 195.0, 960.0, 0.150, "Slug", pellet = true),
        Entry("AEA", "Slug .50 (Element Max)", ".50 slug", 0.504, 245.0, 909.0, 0.150, "Slug", pellet = true),
        Entry("AEA", "Slug .510 (Element Max)", ".510 slug", 0.510, 300.0, 849.0, 0.170, "Slug", pellet = true),
        Entry("AEA", "Slug .58 (Element Max)", ".58 slug", 0.579, 445.0, 779.0, 0.200, "Slug", pellet = true),
    )

    const val ALL = "All"

    fun manufacturers(): List<String> = listOf(ALL) + entries.map { it.manufacturer }.distinct().sorted()
    fun calibers(): List<String> = listOf(ALL) + entries.map { it.caliber }.distinct()
    fun velocityClasses(): List<String> = listOf(ALL, "Subsonic", "Supersonic")
    fun weights(): List<String> = listOf(ALL) +
        entries.map { it.weightGr.toInt() }.distinct().sorted().map { "$it gr" }
    fun types(): List<String> = listOf(ALL) + entries.map { it.type }.distinct().sorted()

    /** Any filter may be [ALL]; weight is the "N gr" string from [weights]. */
    fun filter(mfr: String, cal: String, vel: String, weight: String, type: String): List<Entry> =
        entries.filter {
            (mfr == ALL || it.manufacturer == mfr) &&
            (cal == ALL || it.caliber == cal) &&
            (vel == ALL || it.velocityClass == vel) &&
            (weight == ALL || "${it.weightGr.toInt()} gr" == weight) &&
            (type == ALL || it.type == type)
        }
}

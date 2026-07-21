package com.rfsat.vtb.profiles

/**
 * Built-in rifle catalogue (v20.22) — the third leg beside AmmoCatalog and
 * ScopeCatalog, so a full equipment change never leaves a stale rifle
 * profile behind (the gap that had Home showing the old rifle while bullet
 * and scope had moved on). Air rifles included per user priority: AEA
 * Element (.22/.25), Element Max big-bores (barrel/twist AEA-published),
 * SF Sniper, plus the common rimfire trainers.
 */
object RifleCatalog {

    data class Entry(
        val brand: String,
        val model: String,
        val type: String,             // "Air (PCP)" or "Rimfire"
        val barrelLengthIn: Double,
        val twistRateInPerTurn: Double,
        val zeroDistanceM: Double
    ) {
        fun label(): String = "$brand $model \u2014 $type, ${barrelLengthIn}\" barrel, 1:${twistRateInPerTurn.toInt()}\""
        fun toRifleProfile(): RifleProfile = RifleProfile(
            name = "$brand $model",
            barrelLengthIn = barrelLengthIn,
            twistRateInPerTurn = twistRateInPerTurn,
            sightHeightIn = RifleProfile.DEFAULT.sightHeightIn,
            zeroDistanceM = zeroDistanceM,
            boresightOffsetXNorm = 0.0,
            boresightOffsetYNorm = 0.0
        )
    }

    val entries: List<Entry> = listOf(
        // ---- Air rifles (AEA specs; Element Max twist 1:28 verified) ----
        Entry("AEA", "Element .22", "Air (PCP)", 16.0, 16.0, 45.0),
        Entry("AEA", "Element .25", "Air (PCP)", 16.0, 16.0, 45.0),
        Entry("AEA", "Element Max .45", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .50", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .510", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .58", "Air (PCP)", 22.0, 28.0, 50.0),
        Entry("AEA", "SF Sniper .22", "Air (PCP)", 18.0, 16.0, 45.0),
        Entry("AEA", "SF Sniper .25", "Air (PCP)", 18.0, 16.0, 45.0),
        Entry("AEA", "SF Sniper .30", "Air (PCP)", 18.0, 16.0, 45.0),
        // ---- Rimfire trainers ----
        Entry("Ruger", "Precision Rimfire .22LR", "Rimfire", 18.0, 16.0, 50.0),
        Entry("CZ", "457 Varmint .22LR", "Rimfire", 20.5, 16.0, 50.0),
        Entry("Tikka", "T1x MTR .22LR", "Rimfire", 20.0, 16.5, 50.0)
    )

    const val ALL = "All"
    fun brands(): List<String> = listOf(ALL) + entries.map { it.brand }.distinct().sorted()
    fun types(): List<String> = listOf(ALL) + entries.map { it.type }.distinct()

    fun filter(brand: String, type: String): List<Entry> =
        entries.filter { (brand == ALL || it.brand == brand) && (type == ALL || it.type == type) }
}

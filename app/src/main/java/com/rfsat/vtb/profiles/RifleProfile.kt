package com.rfsat.vtb.profiles

data class RifleProfile(
    val name: String = "Ruger Precision Rimfire",
    val barrelLengthIn: Double = 18.0,
    val twistRateInPerTurn: Double = 16.0, // 1:16" — factory RPR .22LR twist
    val sightHeightIn: Double = 1.7,       // scope centerline over bore, adjust per mount
    val zeroDistanceYards: Double = 50.0
) {
    companion object {
        val DEFAULT = RifleProfile()
    }
}

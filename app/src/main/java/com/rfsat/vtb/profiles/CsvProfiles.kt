package com.rfsat.vtb.profiles

/**
 * CSV serialization for the three profile kinds (v20.7).
 *
 * One header row + one row per profile. Fields are quoted when they contain
 * commas/quotes, so files round-trip through Excel/Sheets. Import is
 * tolerant: blank lines and a repeated header are skipped, and missing
 * trailing columns fall back to defaults — so a hand-maintained file with
 * just name,weight,MV,BC still loads.
 */
object CsvProfiles {

    // ---- CSV core ----

    private fun esc(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun joinRow(cells: List<String>): String = cells.joinToString(",") { esc(it) }

    fun splitRow(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out.map { it.trim().trimEnd('\r') }
    }

    private fun rows(text: String, header: List<String>): List<List<String>> =
        text.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map { splitRow(it) }
            .filter { !it.first().equals(header.first(), ignoreCase = true) } // drop header row(s)

    private fun List<String>.d(i: Int, def: Double): Double = getOrNull(i)?.toDoubleOrNull() ?: def
    private fun List<String>.s(i: Int, def: String): String = getOrNull(i)?.ifBlank { def } ?: def
    private fun List<String>.b(i: Int, def: Boolean): Boolean = getOrNull(i)?.lowercase()?.let {
        when (it) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> def } } ?: def

    // ---- Bullet ----

    val BULLET_HEADER = listOf("name","caliberDiameterIn","weightGrains","muzzleVelocityFps",
        "ballisticCoefficientG1","dragCalibrationFactor","isTracer","mvTempCoeffMpsPerC","mvRefTempC","isPellet")

    fun bulletsToCsv(list: List<BulletProfile>): String =
        (listOf(joinRow(BULLET_HEADER)) + list.map { b -> joinRow(listOf(
            b.name, b.caliberDiameterIn.toString(), b.weightGrains.toString(),
            b.muzzleVelocityFps.toString(), b.ballisticCoefficientG1.toString(),
            b.dragCalibrationFactor.toString(), b.isTracer.toString(),
            b.mvTempCoeffMpsPerC.toString(), b.mvRefTempC.toString(), b.isPellet.toString()
        )) }).joinToString("\n") + "\n"

    fun bulletsFromCsv(text: String): List<BulletProfile> =
        rows(text, BULLET_HEADER).map { r -> BulletProfile(
            name = r.s(0, BulletProfile.DEFAULT.name),
            caliberDiameterIn = r.d(1, BulletProfile.DEFAULT.caliberDiameterIn),
            weightGrains = r.d(2, BulletProfile.DEFAULT.weightGrains),
            muzzleVelocityFps = r.d(3, BulletProfile.DEFAULT.muzzleVelocityFps),
            ballisticCoefficientG1 = r.d(4, BulletProfile.DEFAULT.ballisticCoefficientG1),
            dragCalibrationFactor = r.d(5, 1.0),
            isTracer = r.b(6, false),
            mvTempCoeffMpsPerC = r.d(7, 0.0),
            mvRefTempC = r.d(8, 15.0),
            isPellet = r.b(9, false)
        ) }

    // ---- Rifle ----

    val RIFLE_HEADER = listOf("name","barrelLengthIn","twistRateInPerTurn","sightHeightIn",
        "zeroDistanceM","boresightOffsetXNorm","boresightOffsetYNorm")

    fun riflesToCsv(list: List<RifleProfile>): String =
        (listOf(joinRow(RIFLE_HEADER)) + list.map { r -> joinRow(listOf(
            r.name, r.barrelLengthIn.toString(), r.twistRateInPerTurn.toString(),
            r.sightHeightIn.toString(), r.zeroDistanceM.toString(),
            r.boresightOffsetXNorm.toString(), r.boresightOffsetYNorm.toString()
        )) }).joinToString("\n") + "\n"

    fun riflesFromCsv(text: String): List<RifleProfile> =
        rows(text, RIFLE_HEADER).map { r -> RifleProfile(
            name = r.s(0, RifleProfile.DEFAULT.name),
            barrelLengthIn = r.d(1, RifleProfile.DEFAULT.barrelLengthIn),
            twistRateInPerTurn = r.d(2, RifleProfile.DEFAULT.twistRateInPerTurn),
            sightHeightIn = r.d(3, RifleProfile.DEFAULT.sightHeightIn),
            zeroDistanceM = r.d(4, RifleProfile.DEFAULT.zeroDistanceM),
            boresightOffsetXNorm = r.d(5, 0.0),
            boresightOffsetYNorm = r.d(6, 0.0)
        ) }

    // ---- Scope ----

    val SCOPE_HEADER = listOf("name","clickUnit","maxElevationTravelMoa","maxWindageTravelMoa",
        "zoomMin","zoomMax","objectiveDiameterMm","focalLengthMm","heightAboveBarrelIn","fovAtBaseDeg")

    fun scopesToCsv(list: List<ScopeProfile>): String =
        (listOf(joinRow(SCOPE_HEADER)) + list.map { s -> joinRow(listOf(
            s.name, s.clickUnit.name, s.maxElevationTravelMoa.toString(), s.maxWindageTravelMoa.toString(),
            s.zoomMin.toString(), s.zoomMax.toString(), s.objectiveDiameterMm.toString(),
            s.focalLengthMm.toString(), s.heightAboveBarrelIn.toString(), s.fovAtBaseDeg.toString()
        )) }).joinToString("\n") + "\n"

    fun scopesFromCsv(text: String): List<ScopeProfile> =
        rows(text, SCOPE_HEADER).map { r -> ScopeProfile(
            name = r.s(0, ScopeProfile.DEFAULT.name),
            clickUnit = runCatching { ClickUnit.valueOf(r.s(1, "MRAD_TENTH")) }.getOrDefault(ClickUnit.MRAD_TENTH),
            maxElevationTravelMoa = r.d(2, ScopeProfile.DEFAULT.maxElevationTravelMoa),
            maxWindageTravelMoa = r.d(3, ScopeProfile.DEFAULT.maxWindageTravelMoa),
            zoomMin = r.d(4, ScopeProfile.DEFAULT.zoomMin),
            zoomMax = r.d(5, ScopeProfile.DEFAULT.zoomMax),
            objectiveDiameterMm = r.d(6, ScopeProfile.DEFAULT.objectiveDiameterMm),
            focalLengthMm = r.d(7, ScopeProfile.DEFAULT.focalLengthMm),
            heightAboveBarrelIn = r.d(8, ScopeProfile.DEFAULT.heightAboveBarrelIn),
            fovAtBaseDeg = r.d(9, 0.0)
        ) }
}

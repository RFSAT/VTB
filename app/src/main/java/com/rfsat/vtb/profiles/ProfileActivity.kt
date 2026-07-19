package com.rfsat.vtb.profiles

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.rfsat.vtb.databinding.ActivityProfileBinding
import com.rfsat.vtb.ui.BaseActivity

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var repo: ProfileRepository
    private var suppressPresetCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)
        setupBottomNav(com.rfsat.vtb.R.id.nav_profiles)

        binding.spinnerClickUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1/4 MOA per click", "1/8 MOA per click", "0.1 MRAD per click")
        )
        binding.spinnerScopePreset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            ScopeProfile.PRESETS.map { it.name }
        )
        binding.spinnerScopePreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressPresetCallback) { suppressPresetCallback = false; return }
                val preset = ScopeProfile.PRESETS[position]
                if (preset.name.startsWith("Custom")) return // leave fields as user typed them
                fillScopeFields(preset)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadIntoFields()

        binding.btnSave.setOnClickListener {
            saveFromFields()
            finish()
        }
        binding.btnResetDefaults.setOnClickListener {
            repo.resetToDefaults()
            loadIntoFields()
        }
        binding.btnCalibrateDrag.setOnClickListener { calibrateDragFromOfficialDrop() }
        binding.tvTableZeroLabel.text = "Table zero (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"
        binding.tvTableRangeLabel.text = "Range (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"
        binding.tvTableDropLabel.text = "Drop (${com.rfsat.vtb.ui.UnitsManager.offsetUnitLabel()})"
        refreshDropReadouts()

        // Underline section titles for visibility (no XML attribute for
        // underline; paint flags are the standard way).
        setupDisplaySpinners()
        binding.btnAmmoCatalog.setOnClickListener { showAmmoCatalog() }

        listOf(binding.tvHeaderDisplay, binding.tvHeaderRifle, binding.tvHeaderBullet, binding.tvHeaderScope,
               binding.tvHeaderDropCal, binding.tvHeaderWindCal, binding.tvHeaderSets).forEach {
            it.paintFlags = it.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        }

        binding.tvTrueWindLabel.text = "Measured wind (${com.rfsat.vtb.ui.UnitsManager.speedUnitLabel()})"
        binding.btnSolveWindScale.setOnClickListener { solveWindScale() }
        refreshWindScale()

        binding.btnSaveSet.setOnClickListener { promptSaveSet() }
        binding.btnLoadSet.setOnClickListener { loadSelectedSet() }
        binding.btnDeleteSet.setOnClickListener { deleteSelectedSet() }
        refreshSetSpinner()
    }

    // ---- Factory ammunition catalogue (v20.6) ----

    /** Set by a catalogue pick, consumed by Save: the drag calibration
     *  factor belongs to a LOAD, so choosing a new cartridge resets it to
     *  1.0 — but only when the user actually saves. Resetting the stored
     *  profile at pick time would corrupt the previous load's calibration
     *  if the user cancelled instead. */
    private var pendingDragCalReset = false

    private fun showAmmoCatalog() {
        val v = layoutInflater.inflate(com.rfsat.vtb.R.layout.dialog_ammo_catalog, null)
        val spMfr = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatMfr)
        val spCal = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatCal)
        val spVel = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatVel)
        val spWeight = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatWeight)
        val spType = v.findViewById<android.widget.Spinner>(com.rfsat.vtb.R.id.spCatType)
        val tvCount = v.findViewById<android.widget.TextView>(com.rfsat.vtb.R.id.tvCatCount)
        val lv = v.findViewById<android.widget.ListView>(com.rfsat.vtb.R.id.lvCatResults)

        fun spinner(sp: android.widget.Spinner, items: List<String>) {
            val a = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            sp.adapter = a
        }
        spinner(spMfr, AmmoCatalog.manufacturers())
        spinner(spCal, AmmoCatalog.calibers())
        spinner(spVel, AmmoCatalog.velocityClasses())
        spinner(spWeight, AmmoCatalog.weights())
        spinner(spType, AmmoCatalog.types())

        var current: List<AmmoCatalog.Entry> = emptyList()
        fun refresh() {
            current = AmmoCatalog.filter(
                spMfr.selectedItem as String, spCal.selectedItem as String,
                spVel.selectedItem as String, spWeight.selectedItem as String,
                spType.selectedItem as String
            )
            tvCount.text = "${current.size} of ${AmmoCatalog.entries.size} cartridges"
            lv.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_1, current.map { it.label() })
        }
        val onSel = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, w: android.view.View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        listOf(spMfr, spCal, spVel, spWeight, spType).forEach { it.onItemSelectedListener = onSel }
        refresh()

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ammunition catalogue")
            .setView(v)
            .setNegativeButton("Cancel", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val b = current.getOrNull(pos) ?: return@setOnItemClickListener
            applyCatalogEntry(b.toBulletProfile())
            dlg.dismiss()
        }
        dlg.show()
    }

    /** Fill the bullet FIELDS only — review, tweak and Save via the normal
     *  flow, so custom profiles keep working exactly as before. */
    private fun applyCatalogEntry(b: BulletProfile) {
        with(binding) {
            etBulletName.setText(b.name)
            etCaliber.setText(b.caliberDiameterIn.toString())
            etWeightGrains.setText(b.weightGrains.toString())
            etMuzzleVelocity.setText(b.muzzleVelocityFps.toString())
            etBallisticCoefficient.setText(b.ballisticCoefficientG1.toString())
            etMvTempCoeff.setText("0.0")
            etMvRefTemp.setText("15.0")
            cbTracer.isChecked = false
        }
        pendingDragCalReset = true
        notifyUser("Catalogue values loaded — review and Save (drag factor resets to 1.0; re-run drop calibration for the new load).")
    }

    // ---- Display & units (v20.0, moved from Home) ----

    private fun setupDisplaySpinners() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val tm = com.rfsat.vtb.ui.ThemeManager

        binding.spinnerTheme.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            com.rfsat.vtb.ui.ThemeMode.values().map { it.label }
        )
        binding.spinnerTheme.setSelection(com.rfsat.vtb.ui.ThemeMode.values().indexOf(tm.mode()))
        binding.spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = com.rfsat.vtb.ui.ThemeMode.values()[position]
                if (selected != tm.mode()) {
                    tm.setMode(this@ProfileActivity, selected)
                    recreate() // re-inflate with the new theme
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.spinnerUnits.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            com.rfsat.vtb.ui.UnitSystem.values().map { it.label }
        )
        binding.spinnerUnits.setSelection(com.rfsat.vtb.ui.UnitSystem.values().indexOf(um.system()))
        binding.spinnerUnits.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = com.rfsat.vtb.ui.UnitSystem.values()[position]
                if (selected != um.system()) {
                    um.setSystem(this@ProfileActivity, selected)
                    recreate() // re-render every field/label in the new units
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ---- Wind calibration (v19.0) ----

    private fun refreshWindScale() {
        val scale = getSharedPreferences("vtb_wind_cal", MODE_PRIVATE).getFloat("scale", 1.0f)
        binding.tvWindScale.text = "Wind scale factor: %.2f%s".format(
            scale, if (scale == 1.0f) " (uncalibrated)" else "")
    }

    /**
     * Solves the vapor estimator's effective-distance scale from ONE
     * reference: enter the true crosswind (Kestrel-measured) that was
     * blowing during the LAST analysis, and since the estimate is linear
     * in the assumed centroid distance, newScale = oldScale x true/est.
     */
    private fun solveWindScale() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val input = binding.etTrueWind.text.toString().toDoubleOrNull()
        if (input == null || input <= 0.0) {
            notifyUser("Enter the wind speed the Kestrel measured during the last analyzed shot.")
            return
        }
        val trueMps = if (um.isImperial()) input * 0.44704 else input
        com.rfsat.vtb.results.AnalysisSession.restore(this)
        val estMps = kotlin.math.abs(
            com.rfsat.vtb.results.AnalysisSession.adjustment?.estimatedCrosswindMps ?: 0.0
        )
        if (estMps < 0.1) {
            notifyUser("Last analysis has no usable wind estimate to calibrate against.")
            return
        }
        val prefs = getSharedPreferences("vtb_wind_cal", MODE_PRIVATE)
        val old = prefs.getFloat("scale", 1.0f)
        val solved = (old * trueMps / estMps).toFloat().coerceIn(0.2f, 5.0f)
        prefs.edit().putFloat("scale", solved).apply()
        com.rfsat.vtb.log.Logger.i("ProfileActivity",
            "Wind scale calibrated: true=%.2f m/s est=%.2f m/s -> scale %.2f (was %.2f)"
                .format(trueMps, estMps, solved, old))
        refreshWindScale()
        notifyUser("Wind scale set to %.2f — applies to the next analysis.".format(solved))
    }

    // ---- Profile sets (v16.0) ----

    private fun refreshSetSpinner() {
        val names = repo.getSets().map { it.name }
        binding.spinnerProfileSets.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("(no saved sets)") else names
        )
    }

    private fun promptSaveSet() {
        val input = android.widget.EditText(this).apply {
            hint = "Set name"
            setText("${binding.etRifleName.text} — ${binding.etBulletName.text}".trim(' ', '—'))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save current profiles as set")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // Snapshot what's TYPED, including calibration factors.
                saveFromFields()
                repo.saveSet(ProfileSet(name, repo.getRifle(), repo.getBullet(), repo.getScope()))
                refreshSetSpinner()
                notifyUser("Saved set \"$name\".")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectedSet(): ProfileSet? {
        val sets = repo.getSets()
        val idx = binding.spinnerProfileSets.selectedItemPosition
        return sets.getOrNull(idx)
    }

    private fun loadSelectedSet() {
        val set = selectedSet() ?: run {
            notifyUser("No saved sets yet — use \"Save as set\" first.")
            return
        }
        repo.saveRifle(set.rifle)
        repo.saveBullet(set.bullet)
        repo.saveScope(set.scope)
        loadIntoFields()
        refreshDropReadouts()
        notifyUser("Loaded set \"${set.name}\" as active.")
    }

    private fun deleteSelectedSet() {
        val set = selectedSet() ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete set \"${set.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteSet(set.name)
                refreshSetSpinner()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Solves BulletProfile.dragCalibrationFactor so the simulated drop at
     * the entered range matches ONE official manufacturer table point
     * (v15.0). The engine's Cd(M) curve is approximate; the maker's
     * ballistic table is measured — this pins the model to it.
     *
     * The official point is taken RELATIVE TO THE TABLE'S OWN ZERO (which
     * usually differs from your scope zero): the solver re-zeroes the
     * simulation at the table zero, matches the drop there, and the
     * calibrated drag then applies to trajectories at YOUR zero everywhere
     * else in the app. Beyond the zero, drop is strictly increasing in
     * drag, so bisection is safe.
     */
    private fun calibrateDragFromOfficialDrop() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val tableZeroM = binding.etTableZero.text.toString().toDoubleOrNull()?.let { um.inputDistanceToMeters(it) }
        val rangeM = binding.etTableRange.text.toString().toDoubleOrNull()?.let { um.inputDistanceToMeters(it) }
        val dropM = binding.etTableDrop.text.toString().toDoubleOrNull()
            ?.let { it * if (um.isImperial()) 0.0254 else 0.01 }
        if (tableZeroM == null || rangeM == null || dropM == null) {
            notifyUser("Fill table zero, range and official drop first.")
            return
        }
        if (rangeM <= tableZeroM + 1.0) {
            notifyUser("Reference range must be beyond the table zero (drop is 0 at the zero).")
            return
        }
        // Calibrate against what's TYPED (MV, BC), not what was last saved.
        saveFromFields()
        val bullet = repo.getBullet()
        val rifle = repo.getRifle()
        val scope = repo.getScope()
        val sightHeightM = com.rfsat.vtb.results.AdjustmentCalculator.effectiveSightHeightM(rifle, scope)
        val atmosphere = com.rfsat.vtb.ballistics.Atmosphere() // official tables assume standard conditions

        fun dropAt(k: Double): Double? {
            val b = bullet.copy(dragCalibrationFactor = k)
            val pitch = com.rfsat.vtb.ballistics.BallisticsEngine.solveZeroPitch(b, atmosphere, tableZeroM, sightHeightM)
            val traj = com.rfsat.vtb.ballistics.BallisticsEngine.simulate(b, atmosphere, pitch, 0.0, rangeM + 1.0)
            val pt = traj.lastOrNull { it.position.x <= rangeM } ?: return null
            if (pt.position.x < rangeM - 2.0) return null // stalled short — drag way too high
            // Drop below the (level) line of sight, positive down.
            return sightHeightM - pt.position.y
        }

        var lo = 0.2; var hi = 5.0
        val dLo = dropAt(lo); val dHi = dropAt(hi)
        if (dLo == null || dLo > dropM) {
            notifyUser("Official drop is below what the model can reach even at minimal drag — check muzzle velocity, BC and units.")
            return
        }
        if (dHi != null && dHi < dropM) {
            notifyUser("Official drop exceeds the model even at maximal drag — check muzzle velocity, BC and units.")
            return
        }
        repeat(48) {
            val mid = 0.5 * (lo + hi)
            val d = dropAt(mid)
            if (d == null || d > dropM) hi = mid else lo = mid
        }
        val k = 0.5 * (lo + hi)
        repo.saveBullet(repo.getBullet().copy(dragCalibrationFactor = k))
        com.rfsat.vtb.log.Logger.i("ProfileActivity",
            "Drag calibrated from official drop: k=${"%.3f".format(k)} " +
            "(tableZero=${"%.0f".format(tableZeroM)}m range=${"%.0f".format(rangeM)}m drop=${"%.3f".format(dropM)}m)")
        notifyUser("Drag calibration factor set to ${"%.3f".format(k)}.")
        refreshDropReadouts()
    }

    /** Predicted drop table at the USER's zero — compare it against the
     *  manufacturer's published table to judge the calibration. */
    private fun refreshDropReadouts() {
        val um = com.rfsat.vtb.ui.UnitsManager
        val bullet = repo.getBullet()
        val rifle = repo.getRifle()
        val scope = repo.getScope()
        binding.tvDragFactor.text = "Drag calibration factor: ${"%.3f".format(bullet.dragCalibrationFactor)}" +
            if (bullet.dragCalibrationFactor == 1.0) " (uncalibrated)" else ""
        try {
            val sightHeightM = com.rfsat.vtb.results.AdjustmentCalculator.effectiveSightHeightM(rifle, scope)
            val atmosphere = com.rfsat.vtb.ballistics.Atmosphere()
            val pitch = com.rfsat.vtb.ballistics.BallisticsEngine.solveZeroPitch(bullet, atmosphere, rifle.zeroDistanceM, sightHeightM)
            val maxM = if (um.isImperial()) 300 * 0.9144 else 300.0
            val traj = com.rfsat.vtb.ballistics.BallisticsEngine.simulate(bullet, atmosphere, pitch, 0.0, maxM + 1.0)
            val sb = StringBuilder("Predicted drop (zero ${"%.0f".format(um.displayDistance(rifle.zeroDistanceM))} ${um.distanceUnitLabel()}) / dial-up:\n")
            var step = 25
            while (step <= 300) {
                val rM = if (um.isImperial()) step * 0.9144 else step.toDouble()
                val pt = traj.lastOrNull { it.position.x <= rM }
                if (pt != null && pt.position.x >= rM - 2.0) {
                    val drop = sightHeightM - pt.position.y
                    val sign = if (drop >= 0) "-" else "+" // shooters read drop below LOS as negative
                    // Elevation correction to dial: the angular size of the
                    // drop at that range. atan, not the small-angle shortcut,
                    // though they agree to <0.1% at rifle angles.
                    val mrad = kotlin.math.atan(kotlin.math.abs(drop) / rM) * 1000.0
                    // Fixed-width right-aligned value columns (monospace font),
                    // matching the right-aligned distance column.
                    val dropStr = "%s%.1f".format(sign, um.displayOffset(kotlin.math.abs(drop)))
                    val mradStr = "%s%.2f".format(if (drop >= 0) "+" else "-", mrad)
                    sb.append("%4d %s: %8s %s %7s MRAD\n".format(
                        step, um.distanceUnitLabel(), dropStr, um.offsetUnitLabel(), mradStr))
                }
                step += 25
            }
            binding.tvDropTable.text = sb.toString().trimEnd()
        } catch (e: Exception) {
            binding.tvDropTable.text = "Drop table unavailable: ${e.message}"
        }
    }

    private fun fillScopeFields(scope: ScopeProfile) = with(binding) {
        etScopeName.setText(scope.name)
        etZoomMin.setText(scope.zoomMin.toString())
        etZoomMax.setText(scope.zoomMax.toString())
        etObjectiveDiameter.setText(scope.objectiveDiameterMm.toString())
        etFocalLength.setText(scope.focalLengthMm.toString())
        etHeightAboveBarrel.setText(scope.heightAboveBarrelIn.toString())
        spinnerClickUnit.setSelection(
            when (scope.clickUnit) {
                ClickUnit.MOA_QUARTER -> 0
                ClickUnit.MOA_EIGHTH -> 1
                ClickUnit.MRAD_TENTH -> 2
            }
        )
    }

    private fun loadIntoFields() {
        val rifle = repo.getRifle()
        val bullet = repo.getBullet()
        val scope = repo.getScope()

        with(binding) {
            etRifleName.setText(rifle.name)
            etBarrelLength.setText(rifle.barrelLengthIn.toString())
            etTwistRate.setText(rifle.twistRateInPerTurn.toString())
            tvZeroLabel.text = "Zero (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"
            etZeroDistance.setText(String.format("%.1f",
                com.rfsat.vtb.ui.UnitsManager.displayDistance(rifle.zeroDistanceM)))

            etBulletName.setText(bullet.name)
            etCaliber.setText(bullet.caliberDiameterIn.toString())
            etWeightGrains.setText(bullet.weightGrains.toString())
            etMuzzleVelocity.setText(bullet.muzzleVelocityFps.toString())
            etMvTempCoeff.setText(bullet.mvTempCoeffMpsPerC.toString())
            etMvRefTemp.setText(bullet.mvRefTempC.toString())
            etBallisticCoefficient.setText(bullet.ballisticCoefficientG1.toString())
            cbTracer.isChecked = bullet.isTracer

            // Select the matching preset (or Custom) without clobbering fields.
            suppressPresetCallback = true
            val presetIdx = ScopeProfile.PRESETS.indexOfFirst { it.name == scope.name }
            spinnerScopePreset.setSelection(if (presetIdx >= 0) presetIdx else ScopeProfile.PRESETS.size - 1)
        }
        fillScopeFields(scope)
    }

    private fun saveFromFields() = with(binding) {
        repo.saveRifle(
            repo.getRifle().copy( // preserve boresight calibration offsets
                name = etRifleName.text.toString().ifBlank { RifleProfile.DEFAULT.name },
                barrelLengthIn = etBarrelLength.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.barrelLengthIn,
                twistRateInPerTurn = etTwistRate.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.twistRateInPerTurn,
                zeroDistanceM = etZeroDistance.text.toString().toDoubleOrNull()
                    ?.let { com.rfsat.vtb.ui.UnitsManager.inputDistanceToMeters(it) }
                    ?: RifleProfile.DEFAULT.zeroDistanceM
            )
        )
        repo.saveBullet(
            // copy() from the stored profile so dragCalibrationFactor — set
            // by the drop-calibration solver, not by any text field — is
            // PRESERVED. Constructing a fresh BulletProfile here silently
            // reset it to 1.0 on every save.
            repo.getBullet()
                .let { if (pendingDragCalReset) it.copy(dragCalibrationFactor = 1.0) else it }
                .also { pendingDragCalReset = false }
                .copy(
                name = etBulletName.text.toString().ifBlank { BulletProfile.DEFAULT.name },
                caliberDiameterIn = etCaliber.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.caliberDiameterIn,
                weightGrains = etWeightGrains.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.weightGrains,
                muzzleVelocityFps = etMuzzleVelocity.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.muzzleVelocityFps,
                mvTempCoeffMpsPerC = etMvTempCoeff.text.toString().toDoubleOrNull() ?: 0.0,
                mvRefTempC = etMvRefTemp.text.toString().toDoubleOrNull() ?: 15.0,
                ballisticCoefficientG1 = etBallisticCoefficient.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.ballisticCoefficientG1,
                isTracer = cbTracer.isChecked
            )
        )
        val unit = when (spinnerClickUnit.selectedItemPosition) {
            1 -> ClickUnit.MOA_EIGHTH
            2 -> ClickUnit.MRAD_TENTH
            else -> ClickUnit.MOA_QUARTER
        }
        repo.saveScope(
            ScopeProfile(
                name = etScopeName.text.toString().ifBlank { ScopeProfile.DEFAULT.name },
                clickUnit = unit,
                zoomMin = etZoomMin.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.zoomMin,
                zoomMax = etZoomMax.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.zoomMax,
                objectiveDiameterMm = etObjectiveDiameter.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.objectiveDiameterMm,
                focalLengthMm = etFocalLength.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.focalLengthMm,
                heightAboveBarrelIn = etHeightAboveBarrel.text.toString().toDoubleOrNull() ?: ScopeProfile.DEFAULT.heightAboveBarrelIn
            )
        )
    }
}

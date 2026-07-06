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

        binding.btnSaveSet.setOnClickListener { promptSaveSet() }
        binding.btnLoadSet.setOnClickListener { loadSelectedSet() }
        binding.btnDeleteSet.setOnClickListener { deleteSelectedSet() }
        refreshSetSpinner()
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Save current profiles as set")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // Snapshot what's TYPED, including calibration factors.
                saveFromFields()
                repo.saveSet(ProfileSet(name, repo.getRifle(), repo.getBullet(), repo.getScope()))
                refreshSetSpinner()
                android.widget.Toast.makeText(this, "Saved set \"$name\".", android.widget.Toast.LENGTH_SHORT).show()
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
            android.widget.Toast.makeText(this, "No saved sets yet — use \"Save as set\" first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        repo.saveRifle(set.rifle)
        repo.saveBullet(set.bullet)
        repo.saveScope(set.scope)
        loadIntoFields()
        refreshDropReadouts()
        android.widget.Toast.makeText(this, "Loaded set \"${set.name}\" as active.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedSet() {
        val set = selectedSet() ?: return
        android.app.AlertDialog.Builder(this)
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
            android.widget.Toast.makeText(this, "Fill table zero, range and official drop first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (rangeM <= tableZeroM + 1.0) {
            android.widget.Toast.makeText(this, "Reference range must be beyond the table zero (drop is 0 at the zero).", android.widget.Toast.LENGTH_LONG).show()
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
            android.widget.Toast.makeText(this,
                "Official drop is below what the model can reach even at minimal drag — check muzzle velocity, BC and units.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (dHi != null && dHi < dropM) {
            android.widget.Toast.makeText(this,
                "Official drop exceeds the model even at maximal drag — check muzzle velocity, BC and units.",
                android.widget.Toast.LENGTH_LONG).show()
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
        android.widget.Toast.makeText(this, "Drag calibration factor set to ${"%.3f".format(k)}.", android.widget.Toast.LENGTH_LONG).show()
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
            val sb = StringBuilder("Predicted drop (zero ${"%.0f".format(um.displayDistance(rifle.zeroDistanceM))} ${um.distanceUnitLabel()}):\n")
            var step = 25
            while (step <= 300) {
                val rM = if (um.isImperial()) step * 0.9144 else step.toDouble()
                val pt = traj.lastOrNull { it.position.x <= rM }
                if (pt != null && pt.position.x >= rM - 2.0) {
                    val drop = sightHeightM - pt.position.y
                    val sign = if (drop >= 0) "-" else "+" // shooters read drop below LOS as negative
                    sb.append("%4d %s: %s%.1f %s\n".format(
                        step, um.distanceUnitLabel(), sign,
                        um.displayOffset(kotlin.math.abs(drop)), um.offsetUnitLabel()))
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
            repo.getBullet().copy(
                name = etBulletName.text.toString().ifBlank { BulletProfile.DEFAULT.name },
                caliberDiameterIn = etCaliber.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.caliberDiameterIn,
                weightGrains = etWeightGrains.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.weightGrains,
                muzzleVelocityFps = etMuzzleVelocity.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.muzzleVelocityFps,
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

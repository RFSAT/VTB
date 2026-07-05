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
            BulletProfile(
                name = etBulletName.text.toString().ifBlank { BulletProfile.DEFAULT.name },
                caliberDiameterIn = etCaliber.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.caliberDiameterIn,
                weightGrains = etWeightGrains.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.weightGrains,
                muzzleVelocityFps = etMuzzleVelocity.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.muzzleVelocityFps,
                ballisticCoefficientG1 = etBallisticCoefficient.text.toString().toDoubleOrNull() ?: BulletProfile.DEFAULT.ballisticCoefficientG1
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

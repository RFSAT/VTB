package com.rfsat.vtb.profiles

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.rfsat.vtb.databinding.ActivityProfileBinding

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var repo: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)

        binding.spinnerClickUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1/4 MOA per click", "1/8 MOA per click", "0.1 MRAD per click")
        )

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

    private fun loadIntoFields() {
        val rifle = repo.getRifle()
        val bullet = repo.getBullet()
        val scope = repo.getScope()

        with(binding) {
            etRifleName.setText(rifle.name)
            etBarrelLength.setText(rifle.barrelLengthIn.toString())
            etTwistRate.setText(rifle.twistRateInPerTurn.toString())
            etSightHeight.setText(rifle.sightHeightIn.toString())
            etZeroDistance.setText(rifle.zeroDistanceYards.toString())

            etBulletName.setText(bullet.name)
            etCaliber.setText(bullet.caliberDiameterIn.toString())
            etWeightGrains.setText(bullet.weightGrains.toString())
            etMuzzleVelocity.setText(bullet.muzzleVelocityFps.toString())
            etBallisticCoefficient.setText(bullet.ballisticCoefficientG1.toString())

            etScopeName.setText(scope.name)
            spinnerClickUnit.setSelection(
                when (scope.clickUnit) {
                    ClickUnit.MOA_QUARTER -> 0
                    ClickUnit.MOA_EIGHTH -> 1
                    ClickUnit.MRAD_TENTH -> 2
                }
            )
        }
    }

    private fun saveFromFields() = with(binding) {
        repo.saveRifle(
            RifleProfile(
                name = etRifleName.text.toString().ifBlank { RifleProfile.DEFAULT.name },
                barrelLengthIn = etBarrelLength.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.barrelLengthIn,
                twistRateInPerTurn = etTwistRate.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.twistRateInPerTurn,
                sightHeightIn = etSightHeight.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.sightHeightIn,
                zeroDistanceYards = etZeroDistance.text.toString().toDoubleOrNull() ?: RifleProfile.DEFAULT.zeroDistanceYards
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
                clickUnit = unit
            )
        )
    }
}

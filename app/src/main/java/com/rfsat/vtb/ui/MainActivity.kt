package com.rfsat.vtb.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rfsat.vtb.capture.CaptureActivity
import com.rfsat.vtb.databinding.ActivityMainBinding
import com.rfsat.vtb.profiles.ProfileActivity
import com.rfsat.vtb.profiles.ProfileRepository

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = ProfileRepository(this)

        refreshSummary(repo)

        binding.btnProfiles.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.btnCapture.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummary(ProfileRepository(this))
    }

    private fun refreshSummary(repo: ProfileRepository) {
        val rifle = repo.getRifle()
        val bullet = repo.getBullet()
        val scope = repo.getScope()
        binding.tvSummary.text = getString(
            com.rfsat.vtb.R.string.active_profile_summary,
            rifle.name, bullet.name, scope.name
        )
    }
}

package com.rfsat.vtb.about

import android.os.Bundle
import com.rfsat.vtb.ui.BaseActivity
import com.rfsat.vtb.BuildConfig
import com.rfsat.vtb.databinding.ActivityAboutBinding

class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        // v20.0: tab removed — content lives on Home; this activity is
        // unreachable (no manifest entry, no nav item). Kept so the
        // zip-overlay workflow can't strand a broken file; safe to delete
        // together with activity_about.xml via git rm whenever convenient.
        setupBottomNav(0)
    }
}

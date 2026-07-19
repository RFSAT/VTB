package com.rfsat.vtb.ui

import android.content.Intent
import android.os.Bundle
import com.rfsat.vtb.ui.BaseActivity
import com.rfsat.vtb.capture.CaptureActivity
import com.rfsat.vtb.databinding.ActivityMainBinding
import com.rfsat.vtb.profiles.ProfileActivity
import com.rfsat.vtb.profiles.ProfileRepository

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v19.3: report FIRST, then initialize. Previously this was the
        // LAST line of onCreate — so a crash anywhere in the screen's own
        // startup recorded a stack that no launch could ever display.
        // Everything after this line is guarded: if init throws, the Home
        // screen still comes up (degraded) and shows THAT stack too.
        maybeShowCrashReport()
        runCatching { initHome() }.onFailure { showStack("VTB startup error", 
            "thread main\n" + android.util.Log.getStackTraceString(it)) }
    }

    private fun initHome() {

        refreshSummary(ProfileRepository(this))

        binding.tvVersion.text =
            "Version ${com.rfsat.vtb.BuildConfig.VERSION_NAME} " +
            "(build ${com.rfsat.vtb.BuildConfig.VERSION_CODE}, ${com.rfsat.vtb.BuildConfig.BUILD_TYPE})"

        // v20.0 credits: "Claude AI" is a live link.
        binding.tvClaudeCredit.text = androidx.core.text.HtmlCompat.fromHtml(
            "with support from <a href=\"https://claude.ai\">Claude AI</a>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.tvClaudeCredit.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        setupBottomNav(com.rfsat.vtb.R.id.nav_home)
    }

    /** v19.1/19.3: if the previous launch died, show the recorded stack in
     *  a shareable dialog — crash diagnosis without adb. Dismiss clears the
     *  record (which also re-enables stored-analysis restore next launch). */
    private fun maybeShowCrashReport() {
        val prefs = getSharedPreferences(com.rfsat.vtb.VtbApp.CRASH_PREFS, MODE_PRIVATE)
        val stack = prefs.getString(com.rfsat.vtb.VtbApp.KEY_STACK, null) ?: return
        prefs.edit().clear().apply() // consumed — a fresh crash re-records
        showStack("VTB crashed on the previous launch", stack)
    }

    private fun showStack(title: String, stack: String) {
        runCatching {
            val tv = android.widget.TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
                setPadding(32, 16, 32, 0)
                text = stack
                setTextIsSelectable(true)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Share") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "VTB crash report")
                        putExtra(Intent.EXTRA_TEXT, stack)
                    }
                    startActivity(Intent.createChooser(send, "Share crash report"))
                }
                .setNegativeButton("Dismiss", null)
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummary(ProfileRepository(this))
        setupBottomNav(com.rfsat.vtb.R.id.nav_home)
    }

    private fun refreshSummary(repo: ProfileRepository) {
        val rifle = repo.getRifle()
        val bullet = repo.getBullet()
        val scope = repo.getScope()
        // v20.10: name the active saved set (if any) and add each profile's
        // key identifying spec, so the Home tab unambiguously reflects what
        // is selected in Settings — not just names that might coincide.
        val setLine = repo.getActiveSetName()?.let { "Set: $it\n" } ?: ""
        val bulletSpec = "${bullet.weightGrains.toInt()}gr, ${bullet.muzzleVelocityFps.toInt()} fps" +
            if (bullet.isTracer) ", tracer" else ""
        val scopeSpec = when (scope.clickUnit) {
            com.rfsat.vtb.profiles.ClickUnit.MRAD_TENTH -> "0.1 MRAD/click"
            com.rfsat.vtb.profiles.ClickUnit.MOA_QUARTER -> "1/4 MOA/click"
            com.rfsat.vtb.profiles.ClickUnit.MOA_EIGHTH -> "1/8 MOA/click"
        }
        val zeroM = rifle.zeroDistanceM
        val zeroTxt = com.rfsat.vtb.ui.UnitsManager.let {
            "${String.format("%.0f", it.displayDistance(zeroM))} ${it.distanceUnitLabel()}"
        }
        binding.tvSummary.text = setLine +
            "Rifle: ${rifle.name} (zero $zeroTxt)\n" +
            "Bullet: ${bullet.name} ($bulletSpec)\n" +
            "Scope: ${scope.name} ($scopeSpec)"
    }
}

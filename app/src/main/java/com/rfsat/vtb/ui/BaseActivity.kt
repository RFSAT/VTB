package com.rfsat.vtb.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.rfsat.vtb.R

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        enterFullScreen()
    }

    /**
     * Themed replacement for Toast (v18.1). System Toasts render in the
     * OS palette — bright white text regardless of the app theme, which in
     * the night modes destroys the very dark adaptation those themes
     * protect. This Snackbar is recoloured from the ACTIVE theme:
     * background = colorSurface, text = textColorPrimary.
     */
    fun notifyUser(message: String) {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val sb = com.google.android.material.snackbar.Snackbar.make(
            root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )
        fun attrColor(attr: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        }
        sb.view.setBackgroundColor(
            attrColor(com.google.android.material.R.attr.colorSurface, 0xFF202020.toInt())
        )
        sb.setTextColor(attrColor(android.R.attr.textColorPrimary, 0xFFF2F7F0.toInt()))
        sb.setTextMaxLines(4)
        sb.show()
    }

    /** Bars can transiently reappear (keyboard dismiss, edge swipe, dialog)
     *  — re-hide whenever the window regains focus so the app STAYS
     *  full-screen, not just starts that way. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullScreen()
    }

    /**
     * Immersive full-screen (v13.1): hides status + navigation bars on every
     * screen. A swipe from the top/bottom edge shows them transiently and
     * they auto-hide again (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
     *
     * Deliberately does NOT call setDecorFitsSystemWindows(false): with the
     * bars hidden the content already fills the screen, and leaving decor
     * fitting enabled keeps the default IME resize behaviour intact — the
     * capture screen's EditTexts must not end up under the keyboard.
     */
    private fun enterFullScreen() {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Draw into the camera-cutout strip too (S24 punch-hole) — otherwise
        // a letterboxed black band remains where the status bar used to be.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Wires the shared bottom toolbar (include_bottom_nav) if the layout has
     * one. Call after setContentView with this screen's own nav item id.
     */
    protected fun setupBottomNav(selectedItemId: Int) {
        // Exit lives OUTSIDE the BottomNavigationView because Material caps
        // its menu at 5 items (a 6th throws at inflate time).
        findViewById<android.view.View>(R.id.btnExit)?.setOnClickListener {
            finishAffinity() // close the whole task, not just this screen
        }
        // Results also lives outside the capped menu; tint it like a selected
        // nav item when the Results screen is active.
        findViewById<android.view.View>(R.id.btnResults)?.setOnClickListener {
            if (selectedItemId != R.id.nav_results) {
                startActivity(Intent(this, com.rfsat.vtb.results.ResultsActivity::class.java))
                if (this !is MainActivity) finish()
                overridePendingTransition(0, 0) // v17.1: no tab-hop animation
            }
        }
        findViewById<android.widget.ImageView>(R.id.ivResults)?.let { iv ->
            if (selectedItemId == R.id.nav_results) {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorAccent, tv, true)
                iv.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
                )
            }
        }
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        // v17.1: nav_results is NOT a BottomNavigationView menu item (it's the
        // separate toolbar icon), so assigning it as selectedItemId was a
        // no-op — leaving the default first item (Home) lit while the
        // Corrections screen was active. Deselect the whole group instead.
        if (nav.menu.findItem(selectedItemId) != null) {
            nav.selectedItemId = selectedItemId // set BEFORE the listener to avoid a callback loop
        } else {
            nav.menu.setGroupCheckable(0, true, false)
            for (i in 0 until nav.menu.size()) nav.menu.getItem(i).isChecked = false
            nav.menu.setGroupCheckable(0, true, true)
        }
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_capture -> com.rfsat.vtb.capture.CaptureActivity::class.java
                R.id.nav_profiles -> com.rfsat.vtb.profiles.ProfileActivity::class.java
                R.id.nav_log -> com.rfsat.vtb.log.LogActivity::class.java
                R.id.nav_about -> com.rfsat.vtb.about.AboutActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(this, target)
            if (target == MainActivity::class.java) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            if (this !is MainActivity) finish() // keep the back stack flat when hopping tabs
            overridePendingTransition(0, 0) // v17.1: no tab-hop animation
            false
        }
    }
}

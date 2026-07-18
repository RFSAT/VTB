package com.rfsat.vtb.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.rfsat.vtb.R

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { ThemeManager.apply(this) } // v19.3: never let shared
        super.onCreate(savedInstanceState)       // startup code kill a screen
        runCatching { enterFullScreen() }
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
        // v20.4: Results is a REAL menu item now (About's removal freed the
        // fifth slot), so the v17.1 fake-highlight workaround is gone —
        // selection and tinting come from BottomNavigationView itself.
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        // Deselect the whole group for screens outside the tab set (the
        // retired About screen passes 0).
        if (nav.menu.findItem(selectedItemId) != null) {
            nav.selectedItemId = selectedItemId // set BEFORE the listener to avoid a callback loop
        } else {
            nav.menu.setGroupCheckable(0, true, false)
            for (i in 0 until nav.menu.size()) nav.menu.getItem(i).isChecked = false
            nav.menu.setGroupCheckable(0, true, true)
        }
        navSelectedItemId = selectedItemId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            openTab(item.itemId)
            false
        }
    }

    /** Opens a bottom-nav tab — shared by taps and swipe navigation (v20.2). */
    private fun openTab(itemId: Int) {
        val target = when (itemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_capture -> com.rfsat.vtb.capture.CaptureActivity::class.java
            R.id.nav_results -> com.rfsat.vtb.results.ResultsActivity::class.java
            R.id.nav_profiles -> com.rfsat.vtb.profiles.ProfileActivity::class.java
            R.id.nav_log -> com.rfsat.vtb.log.LogActivity::class.java
            else -> return
        }
        val intent = Intent(this, target)
        if (target == MainActivity::class.java) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        if (this !is MainActivity) finish() // keep the back stack flat when hopping tabs
        overridePendingTransition(0, 0) // v17.1: no tab-hop animation
    }

    // ---- Swipe navigation (v20.2): slide left/right to switch tabs ----
    //
    // Implemented as a manual fling check in dispatchTouchEvent rather than
    // a GestureDetector: deterministic thresholds, no listener-signature
    // coupling, and it observes without consuming — child views behave
    // exactly as before. A swipe must be long (>=100 dp), fast
    // (>=500 dp/s), and strongly horizontal (|dx| > 2.5|dy|); swipe left
    // opens the tab to the RIGHT (pager convention). Screens outside the
    // tab order (Results) ignore swipes, and activities can exempt
    // interactive horizontal controls via swipeExemptViews().

    private var navSelectedItemId: Int = 0
    private val tabOrder = intArrayOf(R.id.nav_home, R.id.nav_capture, R.id.nav_results, R.id.nav_profiles, R.id.nav_log)
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownT = 0L
    private var swipeBlocked = false

    /** Views whose touches must never become tab swipes (e.g. sliders). */
    protected open fun swipeExemptViews(): List<android.view.View> = emptyList()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX; swipeDownY = ev.rawY; swipeDownT = ev.eventTime
                swipeBlocked = swipeExemptViews().any { it.isShown && hitInside(it, ev) }
            }
            android.view.MotionEvent.ACTION_UP -> if (!swipeBlocked) maybeTabSwipe(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hitInside(v: android.view.View, ev: android.view.MotionEvent): Boolean {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
               ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }

    private fun maybeTabSwipe(ev: android.view.MotionEvent) {
        val d = resources.displayMetrics.density
        val dx = ev.rawX - swipeDownX
        val dy = ev.rawY - swipeDownY
        if (kotlin.math.abs(dx) < 100f * d) return
        if (kotlin.math.abs(dx) < 2.5f * kotlin.math.abs(dy)) return
        val dtMs = (ev.eventTime - swipeDownT).coerceAtLeast(1)
        if (kotlin.math.abs(dx) * 1000f / dtMs < 500f * d) return
        val idx = tabOrder.indexOf(navSelectedItemId)
        if (idx < 0) return
        val next = idx + if (dx < 0) 1 else -1 // finger left => next tab
        if (next in tabOrder.indices) openTab(tabOrder[next])
    }
}

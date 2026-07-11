package com.rfsat.vtb.results

import android.os.Bundle
import android.view.View
import com.rfsat.vtb.databinding.ActivityResultsBinding
import com.rfsat.vtb.ui.BaseActivity
import com.rfsat.vtb.ui.UnitsManager
import kotlin.math.abs

class ResultsActivity : BaseActivity() {

    private lateinit var binding: ActivityResultsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNav(com.rfsat.vtb.R.id.nav_results)

        val adjustment = AnalysisSession.adjustment
        if (adjustment == null) {
            binding.tvWindageBig.text = "—"
            binding.tvElevationBig.text = "—"
            binding.tvRowTarget.text = "No analysis yet — run a capture first."
            return
        }
        if (!adjustment.valid) {
            // Simulation never reached the target: the numbers are garbage
            // by construction — show why instead of showing them.
            binding.tvWindageBig.text = "—"
            binding.tvElevationBig.text = "—"
            binding.tvRowTarget.text =
                "${fmt1(UnitsManager.displayDistance(AnalysisSession.targetDistanceYd * 0.9144))} " +
                UnitsManager.distanceUnitLabel() + cameraSuffix()
            binding.tvWarnings.visibility = View.VISIBLE
            binding.tvWarnings.text = adjustment.warnings.joinToString("\n") { "\u26A0 $it" }
            return
        }

        // Arrows show the direction to TURN: ◀▶ windage, ▲▼ elevation.
        val windArrow = if (adjustment.windageDirection == "LEFT") "\u25C0" else "\u25B6"
        val elevArrow = if (adjustment.elevationDirection == "UP") "\u25B2" else "\u25BC"

        binding.tvWindageBig.text =
            "$windArrow ${fmt2(abs(adjustment.windageScopeUnits))} ${adjustment.scopeUnitLabel}"
        binding.tvWindageCaption.text =
            "WINDAGE — ${abs(adjustment.windageClicks)} clk ${adjustment.windageDirection}"

        binding.tvElevationBig.text =
            "$elevArrow ${fmt2(abs(adjustment.elevationScopeUnits))} ${adjustment.scopeUnitLabel}"
        binding.tvElevationCaption.text =
            "ELEVATION — ${abs(adjustment.elevationClicks)} clk ${adjustment.elevationDirection}"

        val dU = UnitsManager.distanceUnitLabel()
        val sU = UnitsManager.speedUnitLabel()
        val oU = UnitsManager.offsetUnitLabel()

        binding.tvRowTarget.text =
            "${fmt1(UnitsManager.displayDistance(AnalysisSession.targetDistanceYd * 0.9144))} $dU" +
            cameraSuffix()

        // v19.0: show the gust/noise spread behind the mean — "4.2 ± 1.1"
        // is more honest and more actionable than a bare confidence figure.
        val sdTxt = if (adjustment.crosswindStdMps > 0.0)
            " ±${fmt1(UnitsManager.displaySpeed(adjustment.crosswindStdMps))}" else ""
        binding.tvRowWind.text =
            "${fmt1(UnitsManager.displaySpeed(abs(adjustment.estimatedCrosswindMps)))}$sdTxt $sU " +
            (if (adjustment.estimatedCrosswindMps >= 0) "\u2192" else "\u2190") + // →  L-to-R / ← R-to-L
            " · conf ${(adjustment.windConfidence * 100).toInt()}%"

        val latM = adjustment.impactOffsetMAtTarget.z
        val vertM = adjustment.impactOffsetMAtTarget.y
        binding.tvRowImpact.text =
            "${fmt1(UnitsManager.displayOffset(abs(latM)))} $oU ${if (latM >= 0) "R" else "L"} · " +
            "${fmt1(UnitsManager.displayOffset(abs(vertM)))} $oU ${if (vertM >= 0) "U" else "D"}"

        if (adjustment.warnings.isNotEmpty()) {
            binding.tvWarnings.visibility = View.VISIBLE
            binding.tvWarnings.text =
                adjustment.warnings.joinToString("\n") { "\u26A0 $it" }
        }

        // Chart x-axis (v18.0, per user): distance covered by the bullet
        // at the sample's time INCLUDING drag decay — computed at analysis
        // time into each sample's downrangeM (un-terminated speed-decay
        // integral, never saturates). Legacy payloads (vapor pre-18.0)
        // carry one constant effective distance there, so those fall back
        // to the v17.5 nominal-MV linear mapping.
        val samples = AnalysisSession.windSamples.sortedBy { it.timeS }
        val spreadM =
            if (samples.isEmpty()) 0.0
            else samples.maxOf { it.downrangeM } - samples.minOf { it.downrangeM }
        val points = if (spreadM > 1.0) {
            samples.map { UnitsManager.displayDistance(it.downrangeM) to UnitsManager.displaySpeed(it.crosswindMps) }
        } else {
            val mvMps = AnalysisSession.muzzleVelocityMps.takeIf { it > 0.0 }
                ?: com.rfsat.vtb.profiles.ProfileRepository(this).getBullet().muzzleVelocityMps
            samples.map { UnitsManager.displayDistance(it.timeS * mvMps) to UnitsManager.displaySpeed(it.crosswindMps) }
        }
        binding.windChart.setSeries(points)
        binding.windChart.title = "Crosswind vs. distance ($dU / $sU, +right)"

        // v16.0: wind transfer — the measured wind is a property of the air,
        // so it can drive a correction for ANY saved rifle/bullet/scope set.
        if (samples.isNotEmpty()) {
            binding.btnApplyToSet.visibility = View.VISIBLE
            binding.btnApplyToSet.setOnClickListener { promptWindTransfer() }
        }

        // v19.0 usability row.
        binding.btnSpeak.text = if (ttsEnabled()) "Speak: on" else "Speak: off"
        binding.btnSpeak.setOnClickListener { toggleTts() }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnExportCsv.setOnClickListener { exportCsv() }
        maybeSpeakAdjustment()
    }

    // ---- Spoken corrections (v19.0): eyes stay on the scope, hands on
    // the rifle — the correction is read out instead of read off. ----

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    private fun ttsEnabled(): Boolean =
        getSharedPreferences("vtb_tts", MODE_PRIVATE).getBoolean("enabled", false)

    private fun toggleTts() {
        val now = !ttsEnabled()
        getSharedPreferences("vtb_tts", MODE_PRIVATE).edit().putBoolean("enabled", now).apply()
        binding.btnSpeak.text = if (now) "Speak: on" else "Speak: off"
        if (now) maybeSpeakAdjustment()
        else tts?.stop()
    }

    private fun maybeSpeakAdjustment() {
        if (!ttsEnabled()) return
        val adj = AnalysisSession.adjustment ?: return
        if (!adj.valid) return
        val phrase = buildString {
            append("${kotlin.math.abs(adj.windageClicks)} clicks ${adj.windageDirection.lowercase()}, ")
            append("${kotlin.math.abs(adj.elevationClicks)} clicks ${adj.elevationDirection.lowercase()}")
        }
        val speak = {
            tts?.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vtb_adj")
        }
        if (ttsReady) { speak(); return }
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.language = java.util.Locale.US
                    ttsReady = true
                    runOnUiThread { speak() }
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    // ---- Shot history (v19.0) ----

    private fun showHistory() {
        val entries = AnalysisSession.history(this).asReversed()
        if (entries.isEmpty()) {
            notifyUser("No shots recorded yet — history fills as analyses complete.")
            return
        }
        val um = UnitsManager
        val fmtDate = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val text = entries.joinToString("\n") { e ->
            val dir = if (e.crosswindMps >= 0) "\u2192" else "\u2190"
            val tr = if (e.tracer) " T" else ""
            "%s  %4.0f %s  %4.1f %s %s  %dclk %s / %dclk %s%s".format(
                fmtDate.format(java.util.Date(e.timestampMs)),
                um.displayDistance(e.targetDistanceYd * 0.9144), um.distanceUnitLabel(),
                um.displaySpeed(kotlin.math.abs(e.crosswindMps)), um.speedUnitLabel(), dir,
                kotlin.math.abs(e.windageClicks), e.windageDirection.take(1),
                kotlin.math.abs(e.elevationClicks), e.elevationDirection.take(1), tr
            )
        }
        val tv = android.widget.TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(32, 16, 32, 0)
            setText(text)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Shot history (newest first)")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                AnalysisSession.clearHistory(this)
                notifyUser("Shot history cleared.")
            }
            .show()
    }

    // ---- CSV export (v19.0): wind samples of the current analysis,
    // shared as text so it lands in email/Drive/Sheets without any
    // file-provider plumbing. ----

    private fun exportCsv() {
        val samples = AnalysisSession.windSamples
        if (samples.isEmpty()) {
            notifyUser("No analysis data to export.")
            return
        }
        val csv = buildString {
            append("time_s,downrange_m,crosswind_mps,vertical_mps,confidence\n")
            for (w in samples) {
                append("%.3f,%.1f,%.3f,%.3f,%.3f\n".format(
                    w.timeS, w.downrangeM, w.crosswindMps, w.verticalWindMps, w.confidence))
            }
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "VTB wind samples")
            putExtra(android.content.Intent.EXTRA_TEXT, csv)
        }
        startActivity(android.content.Intent.createChooser(intent, "Export wind data"))
    }

    /** Pick a saved profile set + engagement distance, recompute the scope
     *  correction from THIS analysis' wind samples with that set's
     *  ballistics, zero and click unit. The original analysis on screen is
     *  untouched — results show in a dialog, clearly marked as transferred. */
    private fun promptWindTransfer() {
        val repo = com.rfsat.vtb.profiles.ProfileRepository(this)
        val sets = repo.getSets()
        if (sets.isEmpty()) {
            android.widget.notifyUser("No saved profile sets — create them in Profiles (\"Save as set\").")
            return
        }
        val um = UnitsManager
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@ResultsActivity, android.R.layout.simple_spinner_dropdown_item, sets.map { it.name })
        }
        val distLabel = android.widget.TextView(this).apply {
            text = "Engagement distance (${um.distanceUnitLabel()})"
            textSize = 12f
        }
        val distInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format("%.0f", um.displayDistance(AnalysisSession.targetDistanceYd * 0.9144)))
        }
        container.addView(spinner); container.addView(distLabel); container.addView(distInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Apply measured wind to…")
            .setView(container)
            .setPositiveButton("Compute") { _, _ ->
                val set = sets.getOrNull(spinner.selectedItemPosition) ?: return@setPositiveButton
                val distM = distInput.text.toString().toDoubleOrNull()?.let { um.inputDistanceToMeters(it) }
                    ?: AnalysisSession.targetDistanceYd * 0.9144
                showTransferredAdjustment(set, distM)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTransferredAdjustment(set: com.rfsat.vtb.profiles.ProfileSet, targetDistanceM: Double) {
        val adj = AdjustmentCalculator.computeAdjustment(
            set.bullet, set.rifle, set.scope,
            com.rfsat.vtb.environment.EnvironmentManager.current.atmosphere,
            targetDistanceYd = targetDistanceM / 0.9144,
            windSamples = AnalysisSession.windSamples
        )
        val um = UnitsManager
        val msg = StringBuilder()
        if (!adj.valid) {
            msg.append("The simulated trajectory for this set never reached ")
                .append("%.0f %s — check its muzzle velocity, BC and zero.".format(
                    um.displayDistance(targetDistanceM), um.distanceUnitLabel()))
        } else {
            msg.append("Windage: ${adj.windageClicks} clicks ${adj.windageDirection} " +
                "(%.2f ${adj.scopeUnitLabel})\n".format(kotlin.math.abs(adj.windageScopeUnits)))
            msg.append("Elevation: ${adj.elevationClicks} clicks ${adj.elevationDirection} " +
                "(%.2f ${adj.scopeUnitLabel})\n\n".format(kotlin.math.abs(adj.elevationScopeUnits)))
            msg.append("Wind used: %.1f %s cross · confidence %d%%\n\n".format(
                um.displaySpeed(kotlin.math.abs(adj.estimatedCrosswindMps)),
                um.speedUnitLabel(), (adj.windConfidence * 100).toInt()))
            // Transfer honesty: what carries over and what doesn't.
            msg.append("\u26A0 Transferred wind: valid for shots moments after the " +
                "measurement (wind is gusty), and measured along the ORIGINAL " +
                "shot's path — a different bearing or a much longer range may " +
                "see different air.\n")
            for (w in adj.warnings) msg.append("\u26A0 $w\n")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Correction for \"${set.name}\"")
            .setMessage(msg.toString().trimEnd())
            .setPositiveButton("Close", null)
            .show()
    }

    /** Camera calibration this analysis used, e.g. " · 46.1° @3.0× → 15.9°".
     *  Empty for payloads saved before v13.0 (fields deserialize to 0). */
    private fun cameraSuffix(): String {
        val tracer = if (AnalysisSession.tracerMode) " · TRACER" else ""
        if (AnalysisSession.baseFovDeg <= 0.0 || AnalysisSession.cameraZoom <= 0.0) return tracer
        val base = fmt1(AnalysisSession.baseFovDeg)
        val zoom = fmt1(AnalysisSession.cameraZoom)
        return (if (AnalysisSession.cameraZoom == 1.0)
            " · FOV $base°"
        else
            " · FOV $base° @${zoom}× → ${fmt1(AnalysisSession.effectiveFovDeg)}°") + tracer
    }

    private fun fmt1(v: Double) = String.format("%.1f", v)
    private fun fmt2(v: Double) = String.format("%.2f", v)
}

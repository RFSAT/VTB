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
                "Target: ${fmt1(UnitsManager.displayDistance(AnalysisSession.targetDistanceYd * 0.9144))} " +
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
            "Target: ${fmt1(UnitsManager.displayDistance(AnalysisSession.targetDistanceYd * 0.9144))} $dU" +
            cameraSuffix()

        binding.tvRowWind.text =
            "Wind: ${fmt1(UnitsManager.displaySpeed(abs(adjustment.estimatedCrosswindMps)))} $sU " +
            (if (adjustment.estimatedCrosswindMps >= 0) "\u2192" else "\u2190") + // →  L-to-R / ← R-to-L
            " · conf ${(adjustment.windConfidence * 100).toInt()}%"

        val latM = adjustment.impactOffsetMAtTarget.z
        val vertM = adjustment.impactOffsetMAtTarget.y
        binding.tvRowImpact.text =
            "Impact: ${fmt1(UnitsManager.displayOffset(abs(latM)))} $oU ${if (latM >= 0) "R" else "L"} · " +
            "${fmt1(UnitsManager.displayOffset(abs(vertM)))} $oU ${if (vertM >= 0) "high" else "low"}"

        if (adjustment.warnings.isNotEmpty()) {
            binding.tvWarnings.visibility = View.VISIBLE
            binding.tvWarnings.text =
                adjustment.warnings.joinToString("\n") { "\u26A0 $it" }
        }

        // Chart x-axis (v15.0): DISTANCE where it's physical, time otherwise.
        //  TRACER — each sample is the bullet at a known downrange x, so
        //    crosswind-vs-distance is the natural (and requested) axis.
        //  VAPOR — the estimator assigns every drift sample the same
        //    effective distance (the trail centroid, ~half the range): a
        //    distance axis would collapse to a vertical line, so the drift
        //    timeline keeps the time axis there.
        val samples = AnalysisSession.windSamples
        val distSpreadM =
            if (samples.isEmpty()) 0.0
            else samples.maxOf { it.downrangeM } - samples.minOf { it.downrangeM }
        if (distSpreadM > 1.0) {
            binding.windChart.setSeries(
                samples.sortedBy { it.downrangeM }
                    .map { UnitsManager.displayDistance(it.downrangeM) to UnitsManager.displaySpeed(it.crosswindMps) }
            )
            binding.windChart.title = "Crosswind vs. distance ($dU / $sU, +right)"
        } else {
            binding.windChart.setSeries(
                samples.map { it.timeS to UnitsManager.displaySpeed(it.crosswindMps) }
            )
            binding.windChart.title = "Crosswind vs. s after shot ($sU, +right)"
        }

        // v16.0: wind transfer — the measured wind is a property of the air,
        // so it can drive a correction for ANY saved rifle/bullet/scope set.
        if (samples.isNotEmpty()) {
            binding.btnApplyToSet.visibility = View.VISIBLE
            binding.btnApplyToSet.setOnClickListener { promptWindTransfer() }
        }
    }

    /** Pick a saved profile set + engagement distance, recompute the scope
     *  correction from THIS analysis' wind samples with that set's
     *  ballistics, zero and click unit. The original analysis on screen is
     *  untouched — results show in a dialog, clearly marked as transferred. */
    private fun promptWindTransfer() {
        val repo = com.rfsat.vtb.profiles.ProfileRepository(this)
        val sets = repo.getSets()
        if (sets.isEmpty()) {
            android.widget.Toast.makeText(this,
                "No saved profile sets — create them in Profiles (\"Save as set\").",
                android.widget.Toast.LENGTH_LONG).show()
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

        android.app.AlertDialog.Builder(this)
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
            com.rfsat.vtb.ballistics.Atmosphere(),
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
        android.app.AlertDialog.Builder(this)
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

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
                UnitsManager.distanceUnitLabel()
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
            "Target: ${fmt1(UnitsManager.displayDistance(AnalysisSession.targetDistanceYd * 0.9144))} $dU"

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

        binding.windChart.setSeries(
            AnalysisSession.windSamples.map { it.timeS to UnitsManager.displaySpeed(it.crosswindMps) }
        )
        binding.windChart.title = "Crosswind vs. s after shot ($sU, +right)"
    }

    private fun fmt1(v: Double) = String.format("%.1f", v)
    private fun fmt2(v: Double) = String.format("%.2f", v)
}

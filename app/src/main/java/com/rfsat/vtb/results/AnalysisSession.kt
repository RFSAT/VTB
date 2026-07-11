package com.rfsat.vtb.results

import android.content.Context
import com.google.gson.Gson
import com.rfsat.vtb.wind.WindSample

/**
 * Holder for the last capture's results. Persisted to SharedPreferences as
 * JSON (v10.1) so the Results toolbar item shows the last analysed data
 * even after the app is closed and reopened — not just for the process
 * lifetime. One shot's worth of data at a time is enough for the workflow:
 * capture -> analyze -> results. (A multi-shot history would move to Room.)
 */
object AnalysisSession {
    private const val PREFS = "vtb_last_analysis"
    private const val KEY = "payload"
    private val gson = Gson()

    var windSamples: List<WindSample> = emptyList()
    var adjustment: ScopeAdjustment? = null
    var targetDistanceYd: Double = 100.0
    // Camera calibration this analysis was computed with (v13.0) — recorded
    // per analysis so saved results show the zoom/FOV they were derived
    // from. 0.0 = payload from an older version (fields absent in Gson).
    var baseFovDeg: Double = 0.0
    var cameraZoom: Double = 0.0
    var effectiveFovDeg: Double = 0.0
    /** true when this analysis tracked a tracer round (v14.0). */
    var tracerMode: Boolean = false
    /** Nominal (specification) muzzle velocity of the bullet this analysis
     *  used, m/s (v17.5) — the chart maps its time axis to distance as
     *  t x this value. Recorded per analysis so switching the active
     *  profile later can't silently rescale a stored chart. 0 = payload
     *  from an older version (Results falls back to the active profile). */
    var muzzleVelocityMps: Double = 0.0

    /** Everything the Results screen needs, in one Gson-friendly bundle. */
    private data class Payload(
        val windSamples: List<WindSample>,
        val adjustment: ScopeAdjustment,
        val targetDistanceYd: Double,
        val baseFovDeg: Double = 0.0,
        val cameraZoom: Double = 0.0,
        val effectiveFovDeg: Double = 0.0,
        val tracerMode: Boolean = false,
        val muzzleVelocityMps: Double = 0.0
    )

    /** Call after a successful analysis to survive app restarts. */
    fun persist(context: Context) {
        val adj = adjustment ?: return
        val json = gson.toJson(Payload(windSamples, adj, targetDistanceYd, baseFovDeg, cameraZoom, effectiveFovDeg, tracerMode, muzzleVelocityMps))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    /**
     * v16.2: warning TEXTS are stored inside the payload, so an analysis
     * saved by an older APK replays its old wording after an upgrade —
     * which made the v16.1 warning rewrite look unimplemented on a device
     * with a stored result. Rewrite known legacy strings to the current
     * short forms on restore; unknown strings pass through untouched.
     */
    private fun migrateLegacyWarnings(adj: ScopeAdjustment): ScopeAdjustment {
        if (adj.warnings.isEmpty()) return adj
        val pct = Regex("""(\d+)%""")
        val migrated = adj.warnings.map { w ->
            when {
                w.startsWith("TESTING MODE: confidence") ->
                    "Confidence ${pct.find(w)?.groupValues?.get(1) ?: "?"}% below threshold (5%)."
                w.startsWith("Low tracking confidence") ->
                    "Low confidence — use wind estimate with caution."
                else -> w
            }
        }
        return if (migrated == adj.warnings) adj else adj.copy(warnings = migrated)
    }

    /** One line of the shot history (v19.0) — the compact per-analysis
     *  record kept alongside the full last-analysis payload. */
    data class HistoryEntry(
        val timestampMs: Long,
        val targetDistanceYd: Double,
        val crosswindMps: Double,
        val verticalWindMps: Double,
        val confidence: Double,
        val windageClicks: Int,
        val windageDirection: String,
        val elevationClicks: Int,
        val elevationDirection: String,
        val tracer: Boolean = false
    )

    private const val HISTORY_KEY = "history"
    private const val HISTORY_CAP = 50

    fun appendHistory(context: Context) {
        val adj = adjustment ?: return
        if (!adj.valid) return
        val entry = HistoryEntry(
            System.currentTimeMillis(), targetDistanceYd,
            adj.estimatedCrosswindMps, adj.estimatedVerticalWindMps, adj.windConfidence,
            adj.windageClicks, adj.windageDirection, adj.elevationClicks, adj.elevationDirection,
            tracerMode
        )
        val list = (history(context) + entry).takeLast(HISTORY_CAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(HISTORY_KEY, gson.toJson(list)).apply()
    }

    fun history(context: Context): List<HistoryEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<HistoryEntry>>(
                json,
                com.google.gson.reflect.TypeToken.getParameterized(List::class.java, HistoryEntry::class.java).type
            )
        }.getOrNull() ?: emptyList()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(HISTORY_KEY).apply()
    }

    /** Call once at app start; no-op if nothing stored or already loaded. */
    fun restore(context: Context) {
        if (adjustment != null) return
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        // runCatching: a payload written by an older app version may not
        // match the current data classes — treat it as absent, don't crash.
        runCatching { gson.fromJson(json, Payload::class.java) }.getOrNull()?.let {
            windSamples = it.windSamples
            adjustment = it.adjustment.let(::migrateLegacyWarnings)
            targetDistanceYd = it.targetDistanceYd
            baseFovDeg = it.baseFovDeg
            cameraZoom = it.cameraZoom
            effectiveFovDeg = it.effectiveFovDeg
            tracerMode = it.tracerMode
            muzzleVelocityMps = it.muzzleVelocityMps
        }
    }
}

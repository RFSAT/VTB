package com.rfsat.vtb.backup

import android.content.Context
import com.google.gson.Gson
import com.rfsat.vtb.log.Logger

/**
 * v1.20.30 — whole-app backup and restore in one file.
 *
 * Everything the app remembers lives in SharedPreferences inside its private
 * data sandbox. A change of applicationId (com.rfsat.vtb -> com.VTBC for the
 * Play listing) makes the new build a DIFFERENT app to Android: it installs
 * alongside the old one with an empty sandbox, so calibrations, custom sets
 * and tuned profiles would otherwise be lost. The per-section CSV export
 * covers individual profiles only — not profile sets, drop/wind calibration,
 * units, capture fields or history. This does cover all of it.
 *
 * Format: a single JSON document holding every store as key -> {type, value}.
 * Values are written as STRINGS with an explicit type tag rather than as raw
 * JSON numbers, because JSON has one number type and a round trip would
 * otherwise turn every Int into a Double and corrupt putInt/putLong keys.
 */
object AppBackup {

    private const val TAG = "AppBackup"
    const val FORMAT = 1

    /**
     * Every preference store the app writes. Four of these are only ever
     * referenced through private constants (theme, units, last analysis,
     * environment), so this list is maintained deliberately — adding a new
     * getSharedPreferences() call anywhere means adding its name here.
     *
     * vtb_crash is deliberately EXCLUDED: it holds a recorded stack trace,
     * and restoring one would pop the crash-report dialog on a fresh install
     * for a crash that never happened there.
     */
    val STORES: List<String> = listOf(
        "vtb_profiles",        // rifle/bullet/scope, profile sets, active set, seed flag
        "vtb_capture_fields",  // distance, FOV, zoom, fps, stream URL, capture source
        "vtb_field_units",     // per-parameter unit choices
        "vtb_wind_cal",        // wind-scale calibration
        "vtb_tts",             // spoken corrections
        "vtb_prefs",           // log-tab visibility and similar UI preferences
        "vtb_theme",           // display mode
        "vtb_units",           // app-wide measurement units
        "vtb_last_analysis",   // last analysis payload + 50-entry history
        "vtb_environment"      // last weather with sources and timestamp
    )

    private data class Entry(
        val t: String? = null,
        val v: String? = null,
        val set: List<String>? = null
    )

    /** Gson builds this via Unsafe, so every field must tolerate being absent. */
    private data class Backup(
        val format: Int = 0,
        val app: String? = null,
        val versionName: String? = null,
        val createdMs: Long = 0L,
        val stores: Map<String, Map<String, Entry>>? = null
    )

    // ---------------------------------------------------------------- export

    fun export(ctx: Context): String {
        val stores = LinkedHashMap<String, Map<String, Entry>>()
        var keys = 0
        for (name in STORES) {
            val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = LinkedHashMap<String, Entry>()
            for ((k, v) in prefs.all) {
                val e = when (v) {
                    is String -> Entry("s", v)
                    is Int -> Entry("i", v.toString())
                    is Long -> Entry("l", v.toString())
                    is Float -> Entry("f", v.toString())
                    is Boolean -> Entry("b", v.toString())
                    is Set<*> -> Entry("ss", null, v.filterIsInstance<String>())
                    else -> null
                }
                if (e != null) { entries[k] = e; keys++ }
            }
            stores[name] = entries
        }
        Logger.i(TAG, "Backup created: ${stores.size} stores, $keys keys")
        return Gson().toJson(
            Backup(
                format = FORMAT,
                app = "VTB",
                versionName = com.rfsat.vtb.BuildConfig.VERSION_NAME,
                createdMs = System.currentTimeMillis(),
                stores = stores
            )
        )
    }

    // --------------------------------------------------------------- inspect

    /** What a backup file contains, for the confirmation prompt. */
    data class Summary(val versionName: String, val createdMs: Long, val stores: Int, val keys: Int)

    fun inspect(json: String): Result<Summary> = runCatching {
        val b = parse(json)
        val stores = b.stores!!
        Summary(
            versionName = b.versionName ?: "unknown",
            createdMs = b.createdMs,
            stores = stores.count { it.key in STORES },
            keys = stores.filterKeys { it in STORES }.values.sumOf { it.size }
        )
    }

    // --------------------------------------------------------------- restore

    /**
     * Replaces the contents of every known store with the backup's. Returns
     * the number of keys written. Uses commit() rather than apply(): the app
     * is restarted immediately afterwards, so the write must have landed.
     */
    fun restore(ctx: Context, json: String): Result<Int> = runCatching {
        val b = parse(json)
        var written = 0
        for ((name, entries) in b.stores!!) {
            if (name !in STORES) continue // ignore anything foreign or retired
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            ed.clear()
            for ((k, e) in entries) {
                when (e.t) {
                    "s" -> ed.putString(k, e.v)
                    "i" -> e.v?.toIntOrNull()?.let { ed.putInt(k, it) }
                    "l" -> e.v?.toLongOrNull()?.let { ed.putLong(k, it) }
                    "f" -> e.v?.toFloatOrNull()?.let { ed.putFloat(k, it) }
                    "b" -> ed.putBoolean(k, e.v.toBoolean())
                    "ss" -> ed.putStringSet(k, (e.set ?: emptyList()).toSet())
                    else -> continue
                }
                written++
            }
            ed.commit()
        }
        Logger.i(TAG, "Backup restored: $written keys from a ${b.versionName} backup")
        written
    }

    /**
     * v1.20.33: re-read every in-memory singleton from the freshly restored
     * preferences, so the app does NOT have to be killed after a restore.
     *
     * These four are the only objects that cache preference state for the
     * life of the process:
     *   ThemeManager / UnitsManager  — cache their setting in a field, filled
     *                                  by init() at process start
     *   EnvironmentManager           — holds the working atmosphere
     *   AnalysisSession              — holds the last analysis and history
     * ProfileRepository re-reads its store on every call, and FieldUnits'
     * canonical cache belongs to an individual field/spinner binding, so
     * both are refreshed simply by rebuilding the activities.
     *
     * Each call is guarded: a restore that has already succeeded must not be
     * undone by one uncooperative singleton.
     */
    fun reloadInMemoryState(ctx: Context) {
        runCatching { com.rfsat.vtb.ui.ThemeManager.init(ctx) }
            .onFailure { Logger.w(TAG, "theme reload: ${it.message}") }
        runCatching { com.rfsat.vtb.ui.UnitsManager.init(ctx) }
            .onFailure { Logger.w(TAG, "units reload: ${it.message}") }
        runCatching { com.rfsat.vtb.environment.EnvironmentManager.restore(ctx) }
            .onFailure { Logger.w(TAG, "environment reload: ${it.message}") }
        runCatching { com.rfsat.vtb.results.AnalysisSession.restore(ctx) }
            .onFailure { Logger.w(TAG, "analysis reload: ${it.message}") }
        Logger.i(TAG, "In-memory state reloaded after restore")
    }

    private fun parse(json: String): Backup {
        val b = try {
            Gson().fromJson(json, Backup::class.java)
        } catch (t: Throwable) {
            throw IllegalArgumentException("not a valid backup file")
        } ?: throw IllegalArgumentException("empty file")
        if (b.app != null && b.app != "VTB") throw IllegalArgumentException("not a VTB backup file")
        if (b.format > FORMAT) {
            throw IllegalArgumentException("written by a newer version of VTB (format ${b.format})")
        }
        if (b.stores == null) throw IllegalArgumentException("no settings found in this file")
        return b
    }
}

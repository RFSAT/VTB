package com.rfsat.vtb.profiles

import android.content.Context
import com.google.gson.Gson

/**
 * Simple JSON-in-SharedPreferences store for the currently active
 * rifle/bullet/scope profile. Good enough for a single-user field tool;
 * swap for Room if multi-profile libraries are wanted later.
 */
class ProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("vtb_profiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getRifle(): RifleProfile {
        val parsed = prefs.getString(KEY_RIFLE, null)
            ?.let { runCatching { gson.fromJson(it, RifleProfile::class.java) }.getOrNull() }
            ?: return RifleProfile.DEFAULT
        // MIGRATION (v9.0): zero distance moved from yards to metres (default
        // 100 m). Gson leaves the new field 0.0 on old JSON (it bypasses
        // Kotlin constructor defaults), so 0.0 => migrate from the legacy
        // yards value, or fall back to the 100 m default.
        @Suppress("DEPRECATION")
        if (parsed.zeroDistanceM <= 0.0) {
            val migrated = parsed.copy(
                zeroDistanceM = if (parsed.zeroDistanceYards > 0.0) parsed.zeroDistanceYards * 0.9144
                                else RifleProfile.DEFAULT.zeroDistanceM
            )
            saveRifle(migrated)
            return migrated
        }
        return parsed
    }

    fun saveRifle(profile: RifleProfile) {
        prefs.edit().putString(KEY_RIFLE, gson.toJson(profile)).apply()
    }

    fun getBullet(): BulletProfile =
        prefs.getString(KEY_BULLET, null)?.let { gson.fromJson(it, BulletProfile::class.java) }
            ?: BulletProfile.DEFAULT

    fun saveBullet(profile: BulletProfile) {
        prefs.edit().putString(KEY_BULLET, gson.toJson(profile)).apply()
    }

    fun getScope(): ScopeProfile {
        val json = prefs.getString(KEY_SCOPE, null) ?: return ScopeProfile.DEFAULT
        val parsed = runCatching { gson.fromJson(json, ScopeProfile::class.java) }.getOrNull()
            ?: return ScopeProfile.DEFAULT
        // MIGRATION: profiles saved before v4.0 predate the optical fields —
        // Gson leaves those as 0.0/null on old JSON, which silently overrode
        // the new Continental 5-30x56 default (this is why v4.0 appeared to
        // "not include" the new scope). Detect a stale/damaged profile and
        // replace it with the current default once.
        @Suppress("SENSELESS_COMPARISON")
        val stale = parsed.name == null || parsed.name.isBlank() ||
            parsed.name.contains("placeholder", ignoreCase = true) ||
            (parsed.clickUnit as ClickUnit?) == null ||
            parsed.zoomMax <= 0.0 || parsed.heightAboveBarrelIn <= 0.0
        if (stale) {
            saveScope(ScopeProfile.DEFAULT)
            return ScopeProfile.DEFAULT
        }
        // MIGRATION (v10.0): type ID dropped from the default scope's name —
        // rename saved copies so the preset spinner still matches them.
        if (parsed.name.contains("VCT-34FFP")) {
            val renamed = parsed.copy(name = ScopeProfile.DEFAULT.name)
            saveScope(renamed)
            return renamed
        }
        return parsed
    }

    fun saveScope(profile: ScopeProfile) {
        prefs.edit().putString(KEY_SCOPE, gson.toJson(profile)).apply()
    }

    fun resetToDefaults() {
        // Only the ACTIVE profiles reset — saved profile sets (v16.0) are a
        // library the user built up; a full prefs.clear() would wipe them.
        prefs.edit().remove(KEY_RIFLE).remove(KEY_BULLET).remove(KEY_SCOPE).apply()
    }

    // ---- Named profile sets (v16.0) ----
    // A snapshot of rifle+bullet+scope under one name, so wind measured
    // with one rig can be re-applied to compute corrections for another.

    fun getSets(): List<ProfileSet> {
        val json = prefs.getString(KEY_SETS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<ProfileSet>>(
                json,
                com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ProfileSet::class.java).type
            )
        }.getOrNull()?.filter { it.name.isNotBlank() }
            // Guard against pre-v9 zero semantics sneaking in via hand-edited JSON.
            ?.map { if (it.rifle.zeroDistanceM <= 0.0) it.copy(rifle = it.rifle.copy(zeroDistanceM = RifleProfile.DEFAULT.zeroDistanceM)) else it }
            ?: emptyList()
    }

    /** Insert or replace by name. */
    fun saveSet(set: ProfileSet) {
        val updated = getSets().filter { it.name != set.name } + set
        prefs.edit().putString(KEY_SETS, gson.toJson(updated)).apply()
    }

    fun deleteSet(name: String) {
        prefs.edit().putString(KEY_SETS, gson.toJson(getSets().filter { it.name != name })).apply()
    }

    companion object {
        private const val KEY_RIFLE = "rifle_profile"
        private const val KEY_BULLET = "bullet_profile"
        private const val KEY_SCOPE = "scope_profile"
        private const val KEY_SETS = "profile_sets"
    }
}

/** A named rifle+bullet+scope combination (v16.0). */
data class ProfileSet(
    val name: String,
    val rifle: RifleProfile,
    val bullet: BulletProfile,
    val scope: ScopeProfile
)

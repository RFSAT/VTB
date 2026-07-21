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
    /**
     * v20.22: seed ready-made profile sets on first run (once; deleting them
     * later does not resurrect them). Built from the catalogues so the data
     * matches what a manual catalogue pick would produce. The user's actual
     * equipment combinations ship as the defaults.
     */
    fun seedDefaultSetsIfEmpty() {
        if (prefs.getBoolean(KEY_SETS_SEEDED, false) || getSets().isNotEmpty()) return
        fun scope(name: String) = ScopeCatalog.entries
            .firstOrNull { "${it.brand} ${it.model}" == name }?.toScopeProfile()
        fun bullet(mfr: String, product: String) = AmmoCatalog.entries
            .firstOrNull { it.manufacturer == mfr && it.product == product }?.toBulletProfile()
        fun rifle(name: String, zeroM: Double? = null) = RifleCatalog.entries
            .firstOrNull { "${it.brand} ${it.model}" == name }?.toRifleProfile()
            ?.let { if (zeroM != null) it.copy(zeroDistanceM = zeroM) else it }

        val seeds = listOfNotNull(
            ProfileSet(
                name = ".22LR — Ruger + Continental",
                rifle = rifle("Ruger Precision Rimfire .22LR", zeroM = 200.0) ?: return,
                bullet = bullet("Federal", "Gold Medal Target") ?: return,
                scope = scope("Vector Optics Continental 5-30x56") ?: return
            ),
            ProfileSet(
                name = "AEA Element — X-Sight LTV",
                rifle = rifle("AEA Element .22") ?: return,
                bullet = bullet("EDgun", ".22 RN 16gr") ?: return,
                scope = scope("ATN X-Sight LTV 5-15x") ?: return
            ),
            ProfileSet(
                name = "AEA Element — X-Sight 5 LRF",
                rifle = rifle("AEA Element .22") ?: return,
                bullet = bullet("EDgun", ".22 RN 16gr") ?: return,
                scope = scope("ATN X-Sight 5 LRF 5-25x") ?: return
            )
        )
        prefs.edit()
            .putString(KEY_SETS, gson.toJson(seeds))
            .putBoolean(KEY_SETS_SEEDED, true)
            .apply()
        // v1.20.26: the first set is the DEFAULT — applied as the active
        // selection on a fresh install so the app starts on a complete rig.
        seeds.firstOrNull()?.let { d ->
            saveRifle(d.rifle); saveBullet(d.bullet); saveScope(d.scope)
            setActiveSetName(d.name)
        }
    }

    /**
     * v1.20.24 one-time fix-up: earlier seeds mislabelled the user's pellet
     * as an "AEA" bullet (AEA is the rifle maker; the pellets are EDgun).
     * Rename it in any stored set and in the active bullet profile.
     */
    fun migrateSeededBulletBrand() {
        val wrong = "AEA Element .22 RN 16gr"
        val fixedName = "EDgun .22 RN 16gr"
        var changed = false
        val sets = getSets().map { set ->
            if (set.bullet.name == wrong) { changed = true; set.copy(bullet = set.bullet.copy(name = fixedName)) } else set
        }
        if (changed) prefs.edit().putString(KEY_SETS, gson.toJson(sets)).apply()
        if (getBullet().name == wrong) saveBullet(getBullet().copy(name = fixedName))
    }

    /**
     * v1.20.25 one-time fix-up: v20.23 marked ALL ATN entries stream-capable,
     * but the LTV line has no Wi-Fi (Obsidian LT core; SD-card recording
     * only). Clear the flag on any stored LTV scope — active profile and
     * inside saved sets — so the Capture source selector hides correctly.
     */
    /**
     * v1.20.26 one-time fix-up: the default set's bullet changed from CCI
     * Standard Velocity to Federal Gold Medal Target (user request). Update
     * the stored seeded set only if it still carries the original CCI load
     * (a customised bullet is left alone).
     */
    fun migrateDefaultSetBullet() {
        val fed = AmmoCatalog.entries
            .firstOrNull { it.manufacturer == "Federal" && it.product == "Gold Medal Target" }
            ?.toBulletProfile() ?: return
        var changed = false
        val sets = getSets().map { set ->
            if (set.name == ".22LR — Ruger + Continental" && set.bullet.name.startsWith("CCI Standard Velocity")) {
                changed = true; set.copy(bullet = fed)
            } else set
        }
        if (changed) prefs.edit().putString(KEY_SETS, gson.toJson(sets)).apply()
    }

    fun migrateLtvStreamFlag() {
        fun fix(sc: ScopeProfile): ScopeProfile =
            if (sc.streamCapable && sc.name.contains("X-Sight LTV")) sc.copy(streamCapable = false) else sc
        var changed = false
        val sets = getSets().map { set ->
            val f = fix(set.scope)
            if (f !== set.scope) { changed = true; set.copy(scope = f) } else set
        }
        if (changed) prefs.edit().putString(KEY_SETS, gson.toJson(sets)).apply()
        val active = getScope()
        val fixedActive = fix(active)
        if (fixedActive !== active) saveScope(fixedActive)
    }

    fun saveSet(set: ProfileSet) {
        val updated = getSets().filter { it.name != set.name } + set
        prefs.edit().putString(KEY_SETS, gson.toJson(updated)).apply()
    }

    fun deleteSet(name: String) {
        prefs.edit().putString(KEY_SETS, gson.toJson(getSets().filter { it.name != name })).apply()
        if (getActiveSetName() == name) clearActiveSetName()
    }

    // v20.10: remember which saved set is active, so Home can name it and
    // Settings can restore the spinner to it. Cleared whenever the active
    // profiles are edited directly (they no longer match the set snapshot).
    fun getActiveSetName(): String? = prefs.getString(KEY_ACTIVE_SET, null)?.ifBlank { null }
    fun setActiveSetName(name: String) = prefs.edit().putString(KEY_ACTIVE_SET, name).apply()
    fun clearActiveSetName() = prefs.edit().remove(KEY_ACTIVE_SET).apply()

    companion object {
        private const val KEY_RIFLE = "rifle_profile"
        private const val KEY_BULLET = "bullet_profile"
        private const val KEY_SCOPE = "scope_profile"
        private const val KEY_SETS = "profile_sets"
        private const val KEY_ACTIVE_SET = "active_set_name"
        private const val KEY_SETS_SEEDED = "sets_seeded"
    }
}

/** A named rifle+bullet+scope combination (v16.0). */
data class ProfileSet(
    val name: String,
    val rifle: RifleProfile,
    val bullet: BulletProfile,
    val scope: ScopeProfile
)

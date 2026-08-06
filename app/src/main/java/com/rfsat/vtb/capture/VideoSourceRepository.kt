package com.rfsat.vtb.capture

import android.content.Context

/**
 * v1.20.39 — the source of capture video, chosen in Settings and independent
 * of the scope profile.
 *
 * Until now, live streaming was gated on the ACTIVE SCOPE carrying a
 * streamCapable flag, and the source list was built from that scope's name.
 * That only fits a scope that is itself a streamer (ATN X-Sight). It cannot
 * represent a scope-mounted CAMERA such as a Tactacam 5.0, which streams over
 * its own Wi-Fi (rtsp://192.168.1.1:554) and can sit on ANY rifle regardless
 * of the optic. Video source is therefore its own setting, not a scope
 * property.
 *
 * The phone camera is always source 0 and always present. Beyond it the user
 * keeps a list of named RTSP sources (Tactacam, an ATN scope, a generic ONVIF
 * camera, ...). The list and the current selection live in their own
 * SharedPreferences store so AppBackup carries them.
 */
object VideoSourceRepository {

    private const val PREFS = "vtb_video_sources"
    private const val KEY_SELECTED = "selected_id"
    private const val KEY_URLS = "url_"        // url_<id>
    private const val KEY_NAMES = "name_"      // name_<id>
    private const val KEY_IDS = "ids"          // comma-separated custom ids

    /** The always-present phone camera; id 0 is reserved for it. */
    const val PHONE_CAMERA_ID = "phone"

    data class Source(
        val id: String,
        val name: String,
        /** RTSP URL (host or host+path); empty for the phone camera. */
        val url: String,
        val isPhone: Boolean
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun phone() = Source(PHONE_CAMERA_ID, "Phone camera", "", true)

    /** Custom-source ids in insertion order. */
    private fun ids(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_IDS, "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** All sources: the phone camera first, then every saved RTSP source. */
    fun all(ctx: Context): List<Source> {
        val p = prefs(ctx)
        val custom = ids(ctx).map { id ->
            Source(
                id = id,
                name = p.getString("$KEY_NAMES$id", "RTSP source") ?: "RTSP source",
                url = p.getString("$KEY_URLS$id", "") ?: "",
                isPhone = false
            )
        }
        return listOf(phone()) + custom
    }

    fun selectedId(ctx: Context): String =
        prefs(ctx).getString(KEY_SELECTED, PHONE_CAMERA_ID) ?: PHONE_CAMERA_ID

    fun selected(ctx: Context): Source =
        all(ctx).firstOrNull { it.id == selectedId(ctx) } ?: phone()

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    /**
     * Add or update a custom RTSP source. A blank name or url is rejected
     * (returns null). Returns the saved Source.
     */
    fun put(ctx: Context, id: String?, name: String, url: String): Source? {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty()) return null
        val p = prefs(ctx)
        val realId = id ?: "src${System.currentTimeMillis()}"
        val order = ids(ctx).toMutableList()
        if (realId !in order) order.add(realId)
        p.edit()
            .putString(KEY_URLS + realId, cleanUrl)
            .putString(KEY_NAMES + realId, cleanName)
            .putString(KEY_IDS, order.joinToString(","))
            .apply()
        return Source(realId, cleanName, cleanUrl, false)
    }

    fun remove(ctx: Context, id: String) {
        if (id == PHONE_CAMERA_ID) return
        val p = prefs(ctx)
        val order = ids(ctx).toMutableList().apply { remove(id) }
        p.edit()
            .remove(KEY_URLS + id)
            .remove(KEY_NAMES + id)
            .putString(KEY_IDS, order.joinToString(","))
            .apply()
        if (selectedId(ctx) == id) select(ctx, PHONE_CAMERA_ID)
    }

    /**
     * Seed the common scope-camera defaults once, so a new user sees ready
     * examples (Tactacam 5.0, generic ATN scope) rather than a blank list.
     * Both use the standard 192.168.1.1:554 hotspot address; the recorder
     * probes stream paths automatically.
     */
    fun seedIfEmpty(ctx: Context) {
        if (ids(ctx).isNotEmpty()) return
        if (prefs(ctx).getBoolean("seeded", false)) return
        put(ctx, "tactacam", "Tactacam 5.0", "rtsp://192.168.1.1:554")
        put(ctx, "atn", "ATN scope (Wi-Fi)", "rtsp://192.168.1.1:554")
        prefs(ctx).edit().putBoolean("seeded", true).apply()
    }
}

package com.myassistant.app.admin

import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.os.Bundle
import org.json.JSONArray

/**
 * Website rules for Chrome and Edge, given to the browsers the way companies do it (managed
 * configuration), which only the device owner can set and the browser itself can't turn off.
 * Blocked sites are for good (no unblock); the switches can be changed.
 */
object BrowserPolicy {
    val BROWSERS = listOf(
        "com.android.chrome" to "Chrome",
        "com.microsoft.emmx" to "Edge",
        "com.microsoft.emmx.canary" to "Edge Canary",
    )

    data class Preset(val title: String, val sites: List<String>)

    /** Ready lists to block in one go. A domain also covers its subdomains (m., www.). */
    val PRESETS = listOf(
        Preset("ريلز وشورتس", listOf(
            "youtube.com/shorts", "instagram.com/reels", "instagram.com/reel", "facebook.com/reel", "facebook.com/reels",
            "tiktok.com", "snapchat.com/spotlight", "likee.video",
        )),
        Preset("دراما قصيرة", listOf(
            "dramabox.com", "dramaboxdb.com", "dramaboxapp.com", "reelshort.com", "netshort.com", "goodshort.com", "shortmax.com",
        )),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("browser_policy", Context.MODE_PRIVATE)

    fun sites(ctx: Context): List<String> = prefs(ctx).getStringSet("sites", emptySet()).orEmpty().sorted()
    fun noIncognito(ctx: Context) = prefs(ctx).getBoolean("no_incognito", false)
    fun safeSearch(ctx: Context) = prefs(ctx).getBoolean("safe_search", false)
    fun youtubeRestricted(ctx: Context) = prefs(ctx).getBoolean("yt_restrict", false)

    /** "https://www.TikTok.com/" -> "tiktok.com"; null when it isn't a site. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val host = s.substringBefore('/')
        if ('.' !in host || host.startsWith('.') || host.endsWith('.')) return null
        return s
    }

    /** Adds sites to the block list (for good) and applies it. Returns how many were new. */
    fun block(ctx: Context, items: List<String>): Int {
        val have = prefs(ctx).getStringSet("sites", emptySet()).orEmpty()
        val add = items.mapNotNull { normalize(it) }.filter { it !in have }
        if (add.isEmpty()) return 0
        prefs(ctx).edit().putStringSet("sites", have + add).commit()
        apply(ctx)
        return add.size
    }

    fun setSwitch(ctx: Context, key: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(key, on).commit()
        apply(ctx)
    }

    fun isInstalled(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** True when the browser has our rules right now. */
    fun applied(ctx: Context, pkg: String): Boolean = DeviceOwner.isOwner(ctx) && runCatching {
        val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        !dpm.getApplicationRestrictions(DeviceOwner.admin(ctx), pkg).isEmpty
    }.getOrDefault(false)

    /** Gives every supported browser the current rules. Needs device owner. */
    fun apply(ctx: Context): Boolean {
        if (!DeviceOwner.isOwner(ctx)) return false
        val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = DeviceOwner.admin(ctx)
        val list = sites(ctx)
        val rm = ctx.getSystemService(RestrictionsManager::class.java)
        var ok = true
        for ((pkg, _) in BROWSERS) {
            // Read the browser's own settings list (when installed) to give each value in the type it expects.
            val schema = runCatching { rm.getManifestRestrictions(pkg) }.getOrNull().orEmpty().associateBy { it.key }
            val b = Bundle()
            fun putList(key: String, v: List<String>) {
                if (schema[key]?.type == RestrictionEntry.TYPE_MULTI_SELECT) b.putStringArray(key, v.toTypedArray())
                else b.putString(key, JSONArray(v).toString())
            }
            fun putInt(key: String, v: Int) {
                when (schema[key]?.type) {
                    RestrictionEntry.TYPE_CHOICE, RestrictionEntry.TYPE_STRING -> b.putString(key, v.toString())
                    else -> b.putInt(key, v)
                }
            }
            fun putBool(key: String, v: Boolean) {
                when (schema[key]?.type) {
                    RestrictionEntry.TYPE_STRING, RestrictionEntry.TYPE_CHOICE -> b.putString(key, v.toString())
                    else -> b.putBoolean(key, v)
                }
            }
            if (list.isNotEmpty()) putList("URLBlocklist", list)
            if (noIncognito(ctx)) {
                putInt("IncognitoModeAvailability", 1)
                if (pkg.startsWith("com.microsoft.")) putInt("InPrivateModeAvailability", 1)
            }
            if (safeSearch(ctx)) putBool("ForceGoogleSafeSearch", true)
            if (youtubeRestricted(ctx)) putInt("ForceYouTubeRestrict", 1)
            ok = ok and runCatching { dpm.setApplicationRestrictions(admin, pkg, b) }.isSuccess
        }
        return ok
    }
}

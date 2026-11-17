package com.myassistant.app.control

import android.content.Context

/**
 * Names for the different accounts/copies of the same app (e.g. WhatsApp + its Twin copy, each with two
 * accounts). A raw account key looks like "u0|Ahmed" — u<n> is the Android user (twin apps run as another
 * user), the rest is the account's own name when the app tells us. The user can give each one a name.
 */
object Accounts {
    val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private fun k(pkg: String, account: String) = "$pkg#$account"

    fun customName(ctx: Context, pkg: String, account: String): String? = prefs(ctx).getString(k(pkg, account), null)

    fun rename(ctx: Context, pkg: String, account: String, name: String) {
        prefs(ctx).edit().apply { if (name.isBlank()) remove(k(pkg, account)) else putString(k(pkg, account), name.trim()) }.apply()
    }

    /** Readable label: the user's name for it, else "WhatsApp (توأم) · Ahmed". */
    fun label(ctx: Context, pkg: String, app: String, account: String): String {
        customName(ctx, pkg, account)?.let { return "$app · $it" }
        val user = account.substringBefore('|')
        val self = account.substringAfter('|', "")
        val twin = user.isNotEmpty() && user != "u0"
        return app + (if (twin) " (توأم)" else "") + (if (self.isNotBlank()) " · $self" else "")
    }

    fun label(ctx: Context, n: SavedNotification) = label(ctx, n.pkg, n.app, n.account)

    /** Do several accounts of this app exist? (then we show which account each message came to). */
    fun isMulti(ctx: Context, pkg: String): Boolean =
        NotificationStore.get(ctx).accounts().count { it.first.first == pkg } > 1

    /** Finds raw account keys whose label matches the user's words ("الشغل", "التوأم", "Ahmed"). */
    fun match(ctx: Context, words: String): List<Pair<String, String>> {
        val w = words.trim()
        if (w.isEmpty()) return emptyList()
        return NotificationStore.get(ctx).accounts()
            .filter { (pa, acc, _) -> label(ctx, pa.first, pa.second, acc).contains(w, ignoreCase = true) || acc.contains(w, true) }
            .map { it.first.first to it.second }
    }
}

package com.myassistant.app.admin

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/** Sections of the /blockapps page. */
enum class AppCat(val label: String) {
    SOCIAL("سوشيال ميديا"),
    LIVE("لايف ستريم"),
    REELS("ريلز وفيديوهات قصيرة"),
    DRAMA("دراما قصيرة"),
    BROWSER("متصفحات"),
    MUSIC("أغاني"),
    GAMES("ألعاب"),
    OTHER("تانية"),
}

/** Puts each app in a section: the user's own choice first, then known apps, then Android's own app category. */
object AppCategories {
    private val PKGS = mapOf(
        AppCat.SOCIAL to setOf(
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.instagram.android", "com.instagram.lite",
            "com.instagram.barcelona", "com.snapchat.android", "com.twitter.android", "com.pinterest", "com.reddit.frontpage",
            "com.tumblr", "com.bd.nproject",
        ),
        AppCat.LIVE to setOf(
            "sg.bigo.live", "sg.bigo.live.lite", "com.sgiggle.production", "com.asiainno.uplive", "com.cmcm.live", "com.mico", "com.yy.hiyo",
        ),
        AppCat.REELS to setOf(
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.tiktok.lite",
            "video.like", "video.like.lite", "com.kwai.video", "com.kwai.bulldog", "co.triller.droid",
        ),
        AppCat.DRAMA to setOf("com.storymatrix.drama", "com.deepvision.shortdrama", "com.netshort.abroad"),
        AppCat.MUSIC to setOf(
            "com.anghami", "com.spotify.music", "com.spotify.lite", "com.soundcloud.android", "com.google.android.apps.youtube.music",
            "deezer.android.app",
        ),
    )

    private val NAMES = mapOf(
        AppCat.SOCIAL to listOf("Facebook", "Instagram", "Snapchat", "Threads", "Messenger", "Twitter", "Pinterest", "Reddit", "Lemon8"),
        AppCat.LIVE to listOf(
            "Live", "Bigo", "Tango", "Uplive", "LiveMe", "MICO", "Hago", "StreamKar", "Poppo", "BuzzCast", "Soulchill", "Kumu",
            "Ligo", "Yalla", "Chamet", "Tumile", "MeYo",
        ),
        AppCat.REELS to listOf("TikTok", "Likee", "Kwai", "Snack Video", "Moj", "Josh", "Triller", "Chingari", "Zili", "Vigo", "Reels"),
        AppCat.DRAMA to listOf(
            "Drama", "ReelShort", "ShortMax", "GoodShort", "FlexTV", "MoboReels", "Playlet", "ShortTV", "StardustTV", "Kalos",
            "TopShort", "FreeReels", "NetShort",
        ),
        AppCat.MUSIC to listOf("Anghami", "Spotify", "SoundCloud", "Deezer", "أنغامي", "سبوتيفاي"),
    )

    /** Whole-word match, so "Live" doesn't match "Olive". */
    private fun hasWord(text: String, word: String) =
        Regex("(^|[^\\p{L}])" + Regex.escape(word), RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_sections", Context.MODE_PRIVATE)

    /** The user moved this app to another section. */
    fun move(ctx: Context, key: String, cat: AppCat) = prefs(ctx).edit().putString(key, cat.name).apply()

    fun of(ctx: Context, pkg: String?, name: String, browsers: Set<String>): AppCat {
        val key = pkg ?: name
        prefs(ctx).getString(key, null)?.let { saved -> AppCat.entries.firstOrNull { it.name == saved } }?.let { return it }
        if (pkg != null) PKGS.entries.firstOrNull { pkg in it.value }?.let { return it.key }
        // Drama before live/reels: "Drama Reels" style names are dramas.
        for (c in listOf(AppCat.DRAMA, AppCat.LIVE, AppCat.REELS, AppCat.SOCIAL, AppCat.MUSIC)) {
            if (NAMES[c].orEmpty().any { hasWord(name, it) }) return c
        }
        if (pkg == null) return AppCat.OTHER
        if (pkg in browsers) return AppCat.BROWSER
        if (Build.VERSION.SDK_INT >= 26) {
            val c = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
            when (c) {
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCat.SOCIAL
                ApplicationInfo.CATEGORY_GAME -> return AppCat.GAMES
                ApplicationInfo.CATEGORY_AUDIO -> return AppCat.MUSIC
            }
        }
        return AppCat.OTHER
    }
}

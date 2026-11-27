package com.myassistant.app.admin

import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Needed for "device owner" (مالك الجهاز). Enabled once with: adb shell dpm set-device-owner com.myassistant.app/.admin.AdminReceiver */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        DeviceOwner.onBecameOwner(context)
    }
}

/** A blocked app: a package name when we know it, and the name to spot it in the store/installer. */
data class BlockedApp(val name: String, val pkg: String?)

/**
 * Full control when the app is the phone's device owner: block installing chosen apps (removed silently if they
 * get in), protect the assistant from being uninstalled, grant its own permissions, suspend apps, lock, reboot.
 * Blocking is permanent: there is no unblock and no way to give up control from the app.
 */
object DeviceOwner {
    const val ADB_COMMAND = "adb shell dpm set-device-owner com.myassistant.app/.admin.AdminReceiver"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(ctx: Context) = runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    // ---------- block list ----------

    fun blocked(ctx: Context): List<BlockedApp> = prefs(ctx).getStringSet("blocked", emptySet()).orEmpty()
        .map { val p = it.split('\u0001'); BlockedApp(p[0], p.getOrNull(1)?.ifBlank { null }) }
        .sortedBy { it.name }

    private fun save(ctx: Context, list: List<BlockedApp>) {
        // stored as "name\u0001pkg"; entries are unique per package (or per name when no package)
        prefs(ctx).edit().putStringSet("blocked", list.map { it.name + '\u0001' + (it.pkg ?: "") }.toSet()).apply()
    }

    /** Finds an installed app by its name or package. */
    fun findInstalled(ctx: Context, query: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val q = query.trim()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
        return apps.firstOrNull { it.second.equals(q, true) }
            ?: apps.firstOrNull { it.first.equals(q, true) }
            ?: apps.firstOrNull { it.first.contains(q, true) }
    }

    /** Popular apps: package → names the stores show, plus Arabic/English spellings people use. */
    private val KNOWN = mapOf(
        // Facebook / Meta
        "com.facebook.katana" to listOf("Facebook", "فيسبوك", "فيس بوك", "فيس"),
        "com.facebook.lite" to listOf("Facebook Lite", "فيسبوك لايت", "فيس لايت"),
        "com.facebook.orca" to listOf("Messenger", "ماسنجر", "مسنجر"),
        "com.instagram.android" to listOf("Instagram", "انستجرام", "إنستجرام", "انستا"),
        "com.instagram.lite" to listOf("Instagram Lite", "انستجرام لايت", "انستا لايت"),
        "com.instagram.barcelona" to listOf("Threads", "ثريدز"),
        // TikTok
        "com.zhiliaoapp.musically" to listOf("TikTok", "تيك توك", "تيكتوك"),
        "com.ss.android.ugc.trill" to listOf("TikTok", "تيك توك"),
        "com.zhiliaoapp.musically.go" to listOf("TikTok Lite", "تيك توك لايت", "تيكتوك لايت"),
        "com.ss.android.ugc.tiktok.lite" to listOf("TikTok Lite", "تيك توك لايت"),
        // Others
        "com.snapchat.android" to listOf("Snapchat", "سناب شات", "سناب"),
        "com.anghami" to listOf("Anghami", "أنغامي", "انغامي"),
        "com.spotify.music" to listOf("Spotify", "سبوتيفاي", "سبوتفاي"),
        "com.spotify.lite" to listOf("Spotify Lite", "سبوتيفاي لايت"),
        // Live streaming (Bigo Live and the like)
        "sg.bigo.live" to listOf("BIGO LIVE", "Bigo Live", "بيجو لايف", "بيجو"),
        "sg.bigo.live.lite" to listOf("BIGO LIVE Lite", "Bigo Live Lite", "بيجو لايف لايت"),
        "video.like" to listOf("Likee", "لايكي"),
        "video.like.lite" to listOf("Likee Lite", "لايكي لايت"),
        "com.sgiggle.production" to listOf("Tango", "Tango Live", "تانجو"),
        "com.asiainno.uplive" to listOf("Uplive", "أب لايف", "اب لايف"),
        "com.cmcm.live" to listOf("LiveMe", "Live.me", "لايف مي"),
        "com.mico" to listOf("MICO", "ميكو"),
        "com.yy.hiyo" to listOf("Hago", "هاجو"),
        // Short dramas / reels
        "com.storymatrix.drama" to listOf("DramaBox", "Drama Box", "دراما بوكس", "دراما بوكس"),
        "com.deepvision.shortdrama" to listOf("Drama Pops", "دراما بوبس"),
        "com.netshort.abroad" to listOf("NetShort", "نت شورت"),
        // Not requested but common
        "com.twitter.android" to listOf("X", "Twitter", "تويتر"),
        "com.google.android.youtube" to listOf("YouTube", "يوتيوب"),
        "org.telegram.messenger" to listOf("Telegram", "تيليجرام", "تليجرام"),
        "com.tencent.ig" to listOf("PUBG MOBILE", "ببجي", "PUBG"),
    )

    /** Live-streaming apps found only by their store name (package not known for sure). */
    private val LIVE_NAMES = listOf("StreamKar", "Poppo Live", "BuzzCast", "Soulchill", "Kumu", "Ligo", "Yalla Live", "Chamet", "Tumile", "MeYo")

    /** Short-drama and reels apps the user asked to block (2nd list, added once). */
    private val REELS_PKGS = listOf("com.storymatrix.drama", "com.deepvision.shortdrama", "com.netshort.abroad")
    private val REELS_NAMES = listOf(
        "ReelShort", "ShortMax", "GoodShort", "FlexTV", "DramaWave", "MoboReels", "Playlet", "ShortTV", "StardustTV",
        "Kalos TV", "DramaShorts", "My Drama", "TopShort", "iDrama", "FreeReels", "Kwai", "Snack Video", "Moj", "Josh",
        "Triller", "Lemon8", "Chingari", "Zili", "Vigo Video",
    )

    /** The user's own list (blocked from the first run of this version). */
    private val DEFAULT_BLOCKED = listOf(
        "com.facebook.katana", "com.facebook.lite",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.tiktok.lite",
        "com.snapchat.android", "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
        "com.anghami", "com.spotify.music", "com.spotify.lite",
        "sg.bigo.live", "sg.bigo.live.lite", "video.like", "video.like.lite", "com.sgiggle.production",
        "com.asiainno.uplive", "com.cmcm.live", "com.mico", "com.yy.hiyo",
    )

    /** Adds the default lists once each (keeps anything the user removed later removed). */
    fun seedDefaults(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("seeded_v1", false)) {
            seed(ctx, DEFAULT_BLOCKED, LIVE_NAMES)
            p.edit().putBoolean("seeded_v1", true).apply()
        }
        if (!p.getBoolean("seeded_v2_reels", false)) {
            seed(ctx, REELS_PKGS, REELS_NAMES)
            p.edit().putBoolean("seeded_v2_reels", true).apply()
        }
    }

    private fun seed(ctx: Context, pkgs: List<String>, names: List<String>) {
        val have = blocked(ctx)
        val add = pkgs.filter { pk -> have.none { it.pkg == pk } }.map { BlockedApp(KNOWN[it]?.first() ?: it, it) } +
            names.filter { n -> have.none { it.name.equals(n, true) } }.map { BlockedApp(it, null) }
        // Two TikTok packages share a name: keep the name unique per package.
        save(ctx, (have + add).distinctBy { it.pkg ?: it.name })
        enforce(ctx)
    }

    private fun norm(s: String) = s.trim().lowercase().replace("إ", "ا").replace("أ", "ا").replace(" ", "")

    private fun knownPkg(name: String): String? = KNOWN.entries.firstOrNull { e -> e.value.any { norm(it) == norm(name) } }?.key

    /** All names to look for on store screens for this blocked app (store title first). */
    fun storeNames(b: BlockedApp): List<String> =
        (((b.pkg ?: knownPkg(b.name))?.let { KNOWN[it] }.orEmpty()) + b.name).filter { it.length >= 2 }.distinct()

    /** Adds an app to the block list and removes it now if it's installed. Returns what happened. */
    fun block(ctx: Context, nameOrPkg: String, pkgHint: String? = null): String {
        val found = findInstalled(ctx, pkgHint ?: nameOrPkg) ?: knownPkg(nameOrPkg)?.let { findInstalled(ctx, it) }
        val pkg = pkgHint?.takeIf { it.contains('.') } ?: found?.second ?: knownPkg(nameOrPkg) ?: nameOrPkg.takeIf { it.contains('.') && ' ' !in it }
        if (pkg == ctx.packageName) return "مينفعش أمنع نفسي 🙂"
        val name = found?.first ?: pkg?.let { KNOWN[it]?.first() } ?: nameOrPkg.trim()
        val list = blocked(ctx).filterNot { if (pkg != null) it.pkg == pkg else it.name.equals(name, true) } + BlockedApp(name, pkg)
        save(ctx, list)
        val removed = if (pkg != null && installed(ctx, pkg)) remove(ctx, pkg) else null
        return "$name اتمنع" + if (removed != null) " واتشال" else ""
    }

    private fun installed(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    }.getOrDefault(false)

    /** Removes an app: silently when we're device owner, otherwise opens the uninstall screen (the screen service confirms it). */
    fun remove(ctx: Context, pkg: String): Boolean {
        if (isOwner(ctx)) {
            runCatching { dpm(ctx).setApplicationHidden(admin(ctx), pkg, true) }
            val ok = runCatching {
                val pi = PendingIntent.getBroadcast(
                    ctx, pkg.hashCode(), Intent(ACTION_REMOVED).setPackage(ctx.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                ctx.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        pendingUninstall = pkg
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Package we asked the system to uninstall (the screen service presses OK on the confirm dialog). */
    @Volatile var pendingUninstall: String? = null

    private const val ACTION_REMOVED = "com.myassistant.app.APP_REMOVED"

    /** Checks every blocked package and removes any that is installed. */
    fun enforce(ctx: Context) {
        blocked(ctx).mapNotNull { it.pkg }.filter { installed(ctx, it) }.forEach { remove(ctx, it) }
        if (browsersOnly(ctx)) forbiddenBrowsers(ctx).forEach { remove(ctx, it) }
    }

    // ---------- browsers: only Chrome, Edge and the phone's own ----------

    /** Browsers that stay allowed besides the phone's built-in (system) ones. */
    private val ALLOWED_BROWSERS = setOf("com.android.chrome", "com.microsoft.emmx", "com.google.android.googlequicksearchbox")

    /** Store names of other browsers, so their install button gets covered in the store. */
    private val BROWSER_NAMES = listOf(
        "Firefox", "Firefox Focus", "Firefox Nightly", "Firefox Beta", "Opera", "Opera Mini", "Opera GX", "Opera Touch",
        "Brave", "DuckDuckGo", "Samsung Internet", "UC Browser", "UC Mini", "Vivaldi", "Tor Browser", "Yandex", "Yandex Browser",
        "Kiwi Browser", "Mi Browser", "Puffin", "Dolphin", "Via Browser", "Ecosia", "Aloha", "Phoenix Browser", "Mint Browser",
        "Chrome Beta", "Chrome Dev", "Chrome Canary", "Edge Beta", "Edge Dev", "Edge Canary", "Mull", "Cromite", "Bromite",
        "Hermit", "Lemur Browser", "Quetta", "Maxthon", "Avast Secure Browser", "AVG Secure Browser", "Aloha Browser",
    )

    /** Always on: other browsers are never allowed. */
    @Suppress("UNUSED_PARAMETER")
    fun browsersOnly(ctx: Context) = true

    /** Apps that can open any web link = browsers. */
    fun browserPackages(ctx: Context): Set<String> = runCatching {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://nova-browser-check.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun isSystemApp(ctx: Context, pkg: String) = runCatching {
        val f = ctx.packageManager.getApplicationInfo(pkg, 0).flags
        f and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 || f and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }.getOrDefault(true)

    fun isForbiddenBrowser(ctx: Context, pkg: String): Boolean =
        pkg != ctx.packageName && pkg !in ALLOWED_BROWSERS && !isSystemApp(ctx, pkg) && pkg in browserPackages(ctx)

    fun forbiddenBrowsers(ctx: Context) = browserPackages(ctx).filter { isForbiddenBrowser(ctx, it) }

    /** Names that also match store titles that only start with them ("Firefox Fast & Private Browser"). */
    fun isPrefixName(name: String) = BROWSER_NAMES.any { it.equals(name, true) }

    /** What the store guard looks for: the block list + other browsers when that rule is on. */
    fun storeBlocked(ctx: Context): List<BlockedApp> =
        blocked(ctx) + if (browsersOnly(ctx)) BROWSER_NAMES.map { BlockedApp(it, null) } else emptyList()

    /** Called when an app gets installed. */
    fun onInstalled(ctx: Context, pkg: String) {
        if (browsersOnly(ctx) && isForbiddenBrowser(ctx, pkg)) {
            remove(ctx, pkg)
            ContextCompat.getMainExecutor(ctx).execute {
                Toast.makeText(ctx, "🚫 مسموح بس Chrome وEdge ومتصفح الموبايل — اتشال", Toast.LENGTH_LONG).show()
            }
            runCatching { com.myassistant.app.reading.Reading.offerInstead(ctx) }
            return
        }
        val pm = ctx.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val list = blocked(ctx)
        val hit = list.firstOrNull { it.pkg == pkg || (it.pkg == null && ((label != null && label.equals(it.name, true)) || knownPkg(it.name) == pkg)) } ?: return
        if (hit.pkg == null) save(ctx, list - hit + hit.copy(pkg = pkg)) // learn the package for next time
        remove(ctx, pkg)
        ContextCompat.getMainExecutor(ctx).execute { Toast.makeText(ctx, "🚫 ${hit.name} ممنوع — اتشال", Toast.LENGTH_LONG).show() }
        runCatching { com.myassistant.app.reading.Reading.offerInstead(ctx) }
    }

    /** Listens for installs while the app's services are running. */
    fun registerInstallWatcher(ctx: Context): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                runCatching { onInstalled(c.applicationContext, pkg) }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        return r
    }

    /** Text on screen that belongs to a blocked app in the store / installer, if any. */
    val STORE_PKGS = setOf(
        "com.android.vending", "com.hihonor.appmarket", "com.huawei.appmarket", "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.hihonor.packageinstaller", "com.huawei.packageinstaller",
        "com.apkpure.aegon", "com.uptodown", "com.aurora.store",
    )

    // ---------- device owner powers ----------

    fun onBecameOwner(ctx: Context) {
        runCatching { protectSelf(ctx) }
        runCatching { enforce(ctx) }
    }

    /** The assistant can't be uninstalled, and gets all the permissions it asks for. */
    fun protectSelf(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        d.setUninstallBlocked(a, ctx.packageName, true)
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        for (p in requested) {
            if (p == "android.permission.ACCESS_BACKGROUND_LOCATION" && Build.VERSION.SDK_INT < 29) continue
            runCatching { d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        }
    }

    /** Stops the app from being opened (until unsuspended). */
    fun suspend(ctx: Context, pkg: String, on: Boolean): Boolean =
        isOwner(ctx) && runCatching { dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), on).isEmpty() }.getOrDefault(false)

    // ---------- notifications of other apps ----------

    fun mutedApps(ctx: Context): Set<String> = prefs(ctx).getStringSet("muted", emptySet()).orEmpty()

    /**
     * Turns an app's notifications off (or back on) without opening Settings. As device owner on Android 13+ this
     * takes away the app's notification permission (silent, survives reboots); otherwise Nova's notification
     * reader removes them as they arrive.
     */
    fun muteNotifications(ctx: Context, pkg: String, mute: Boolean): String {
        val set = mutedApps(ctx).toMutableSet().apply { if (mute) add(pkg) else remove(pkg) }
        prefs(ctx).edit().putStringSet("muted", set).apply()
        var system = false
        if (isOwner(ctx) && Build.VERSION.SDK_INT >= 33) {
            system = runCatching {
                dpm(ctx).setPermissionGrantState(
                    admin(ctx), pkg, android.Manifest.permission.POST_NOTIFICATIONS,
                    if (mute) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED else DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrDefault(false)
            if (!mute && system) runCatching {
                dpm(ctx).setPermissionGrantState(admin(ctx), pkg, android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            }
        }
        return if (system) "system" else "listener"
    }

    fun lockNow(ctx: Context) = runCatching { dpm(ctx).lockNow() }.isSuccess

    fun reboot(ctx: Context) = isOwner(ctx) && runCatching { dpm(ctx).reboot(admin(ctx)) }.isSuccess

}

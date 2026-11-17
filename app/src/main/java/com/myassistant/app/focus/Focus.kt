package com.myassistant.app.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.myassistant.app.admin.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A focus rule: these apps are blocked between [from] and [to] (e.g. 00:00–08:00), and/or after
 * [limitMin] minutes of use per day. Times are "HH:mm"; a window may cross midnight.
 */
data class FocusRule(
    val id: Long,
    val name: String,
    val apps: List<String>,
    val labels: List<String>,
    val from: String?,
    val to: String?,
    val limitMin: Int,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("apps", JSONArray(apps)).put("labels", JSONArray(labels))
        .put("from", from ?: "").put("to", to ?: "").put("limit", limitMin)

    companion object {
        fun from(j: JSONObject) = FocusRule(
            j.getLong("id"), j.optString("name"),
            j.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getString(it) } },
            j.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            j.optString("from").ifBlank { null }, j.optString("to").ifBlank { null }, j.optInt("limit"),
        )
    }
}

object Focus {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun rules(ctx: Context): List<FocusRule> = runCatching {
        val a = JSONArray(prefs(ctx).getString("rules", "[]"))
        (0 until a.length()).map { FocusRule.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<FocusRule>) {
        prefs(ctx).edit().putString("rules", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** Apps that must never be blocked. */
    private fun protected(ctx: Context) = setOf(
        ctx.packageName, "com.android.settings", "com.android.phone", "com.android.dialer", "com.google.android.dialer",
        "com.hihonor.contacts", "com.android.contacts", "com.android.systemui",
    )

    fun minutes(t: String?): Int? = t?.let {
        Regex("^(\\d{1,2}):(\\d{2})$").find(it.trim())?.destructured?.let { (h, m) -> h.toInt() * 60 + m.toInt() }?.takeIf { v -> v in 0..1440 }
    }

    /** Adds a rule. [apps] are names as the user says them. Returns a message. */
    fun add(ctx: Context, name: String, apps: List<String>, from: String?, to: String?, limitMin: Int): String {
        val found = apps.map { it to DeviceOwner.findInstalled(ctx, it) }
        val missing = found.filter { it.second == null }.map { it.first }
        val ok = found.mapNotNull { it.second }.filter { it.second !in protected(ctx) }.distinctBy { it.second }
        if (ok.isEmpty()) return "مش لاقي التطبيقات دي على الموبايل: ${missing.joinToString("، ")}"
        val hasWindow = minutes(from) != null && minutes(to) != null
        if (!hasWindow && limitMin <= 0) return "محتاج ميعاد (من/لحد بصيغة HH:mm) أو حد دقايق في اليوم"
        val rule = FocusRule(System.currentTimeMillis(), name.ifBlank { "تركيز" }, ok.map { it.second }, ok.map { it.first },
            if (hasWindow) from!!.trim() else null, if (hasWindow) to!!.trim() else null, limitMin.coerceAtLeast(0))
        save(ctx, rules(ctx) + rule)
        apply(ctx)
        return buildString {
            append("✅ ${rule.name}: ${rule.labels.joinToString("، ")}")
            if (hasWindow) append(" — مقفولين من ${rule.from} لحد ${rule.to}")
            if (rule.limitMin > 0) append(" — ${rule.limitMin} دقيقة في اليوم")
            if (missing.isNotEmpty()) append(". مش لاقي: ${missing.joinToString("، ")}")
            if (!DeviceOwner.isOwner(ctx)) append(". (من غير مالك الجهاز: هقفلها بالتحكم في الشاشة)")
        }
    }

    fun remove(ctx: Context, nameOrId: String): String {
        val q = nameOrId.trim()
        val before = rules(ctx)
        val after = before.filterNot { it.name.contains(q, true) || it.id.toString() == q || it.labels.any { l -> l.equals(q, true) } }
        if (after.size == before.size) return "مش لاقي قاعدة اسمها \"$q\""
        save(ctx, after)
        apply(ctx)
        return "✅ اتشالت"
    }

    private fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun inWindow(r: FocusRule, now: Int): Boolean {
        val f = minutes(r.from) ?: return false
        val t = minutes(r.to) ?: return false
        return if (f <= t) now in f until t else now >= f || now < t
    }

    private fun startOfDay() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Minutes each app was on screen today. */
    fun usageToday(ctx: Context, pkgs: Collection<String>): Map<String, Int> = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val stats = usm.queryAndAggregateUsageStats(startOfDay(), System.currentTimeMillis())
        pkgs.associateWith { ((stats[it]?.totalTimeInForeground ?: 0L) / 60_000L).toInt() }
    }.getOrDefault(emptyMap())

    /** Which apps are blocked right now, and why. */
    fun blockedNow(ctx: Context): Map<String, String> {
        val now = nowMinutes()
        val out = LinkedHashMap<String, String>()
        val rules = rules(ctx)
        val usage = usageToday(ctx, rules.filter { it.limitMin > 0 }.flatMap { it.apps })
        for (r in rules) {
            if (inWindow(r, now)) r.apps.forEach { out.putIfAbsent(it, "${r.name}: لحد ${r.to}") }
            if (r.limitMin > 0) {
                val used = r.apps.sumOf { usage[it] ?: 0 }
                if (used >= r.limitMin) r.apps.forEach { out.putIfAbsent(it, "${r.name}: خلّصت ${r.limitMin} دقيقة النهارده") }
            }
        }
        return out
    }

    @Volatile private var cache: Pair<Long, Map<String, String>>? = null

    /** Cached for 20 s — used by the screen service on every app switch. */
    fun blockedCached(ctx: Context): Map<String, String> {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.first < 20_000) return c.second
        return blockedNow(ctx).also { cache = System.currentTimeMillis() to it }
    }

    /** Suspends / un-suspends apps (device owner) and schedules the next change. */
    fun apply(ctx: Context) {
        cache = null
        val want = blockedNow(ctx).keys
        val prev = prefs(ctx).getStringSet("suspended", emptySet()).orEmpty()
        if (DeviceOwner.isOwner(ctx)) {
            (want - prev).forEach { DeviceOwner.suspend(ctx, it, true) }
            (prev - want).forEach { DeviceOwner.suspend(ctx, it, false) }
            prefs(ctx).edit().putStringSet("suspended", want).apply()
        } else if (prev.isNotEmpty()) {
            prefs(ctx).edit().putStringSet("suspended", emptySet()).apply()
        }
        scheduleNext(ctx)
    }

    /** Next moment something may change: any window start/end, or midnight (daily limits reset). */
    private fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 9090, Intent(ctx, FocusReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val rules = rules(ctx)
        if (rules.isEmpty()) { am.cancel(pi); return }
        val now = nowMinutes()
        val points = (rules.flatMap { listOfNotNull(minutes(it.from), minutes(it.to)) } + 0).map { it % 1440 }.distinct()
        val delta = points.map { p -> ((p - now) % 1440 + 1440) % 1440 }.map { if (it == 0) 1440 else it }.min()
        val at = Calendar.getInstance().apply { set(Calendar.SECOND, 2); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, delta) }.timeInMillis
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = goAsync()
        Thread { try { Focus.apply(ctx) } finally { p.finish() } }.start()
    }
}

package com.myassistant.app.tools

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Screen time per app — the same data Digital Wellbeing shows — read in the background. */
class ScreenTimeTool : Tool {
    override val name = "screen_time"
    override val title = "وقت الشاشة"
    override val description =
        "Phone usage like Digital Wellbeing, without opening anything: total screen time, time per app, " +
            "how many times each app was opened, and unlocks. Period: today, yesterday, or week."
    override val parameters = Schema.obj(
        "period" to Schema.string("today | yesterday | week"),
        "app" to Schema.string("Optional: one app name to focus on"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!hasUsageAccess(ctx.app)) {
            return@withContext "Usage access is OFF. Tell the user: → الصلاحيات → بيانات الاستخدام."
        }
        val period = args.str("period").ifEmpty { "today" }
        val (start, end, days) = range(period)
        val usage = collect(ctx.app, start, end)
        val pm = ctx.app.packageManager
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

        val apps = usage.perApp.entries
            .map { (pkg, a) -> Triple(label(pkg), a.ms, a.opens) }
            .filter { it.second >= 60_000 }
            .sortedByDescending { it.second }
        val focus = args.str("app").lowercase()
        buildString {
            appendLine("Period: $period")
            appendLine("Total screen time: ${fmt(usage.totalMs)}" + if (days > 1) " (≈ ${fmt(usage.totalMs / days)} per day)" else "")
            if (usage.unlocks > 0) appendLine("Unlocks: ${usage.unlocks}")
            if (focus.isNotEmpty()) {
                val hit = apps.filter { it.first.lowercase().contains(focus) }
                if (hit.isEmpty()) appendLine("${args.str("app")}: no use in this period")
                hit.forEach { appendLine("${it.first}: ${fmt(it.second)}, opened ${it.third} times") }
            } else {
                appendLine("Top apps:")
                apps.take(12).forEach { appendLine("- ${it.first}: ${fmt(it.second)} (opened ${it.third}×)") }
            }
        }
    }

    private class AppUse(var ms: Long = 0, var opens: Int = 0)
    private class Usage(val perApp: Map<String, AppUse>, val totalMs: Long, val unlocks: Int)

    private fun collect(ctx: Context, start: Long, end: Long): Usage {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val launcher = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val ignore = setOfNotNull(launcher, "com.android.systemui")

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        val per = HashMap<String, AppUse>()
        var fgPkg: String? = null
        var fgStart = 0L
        var unlocks = 0

        fun close(at: Long) {
            val p = fgPkg ?: return
            if (p !in ignore) per.getOrPut(p) { AppUse() }.ms += (at - fgStart).coerceAtLeast(0)
            fgPkg = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (fgPkg != e.packageName) {
                        close(e.timeStamp)
                        fgPkg = e.packageName
                        fgStart = e.timeStamp
                        if (e.packageName !in ignore) per.getOrPut(e.packageName) { AppUse() }.opens++
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (e.packageName == fgPkg) close(e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(e.timeStamp)
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
            }
        }
        close(minOf(end, System.currentTimeMillis()))
        return Usage(per, per.values.sumOf { it.ms }, unlocks)
    }

    private fun range(period: String): Triple<Long, Long, Int> {
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when (period) {
            "yesterday" -> Triple(midnight - 86_400_000L, midnight, 1)
            "week" -> Triple(midnight - 6 * 86_400_000L, now, 7)
            else -> Triple(midnight, now, 1)
        }
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}

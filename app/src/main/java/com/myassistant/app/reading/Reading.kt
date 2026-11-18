package com.myassistant.app.reading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.myassistant.app.places.PlaceTracker
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reading habit: 15 minutes in Honor Docs before sleep, counted automatically from app usage at night
 * (9 PM → 3 AM). Nova nudges once at bedtime, and offers reading instead whenever something gets blocked.
 */
object Reading {
    const val GOAL_MIN = 15
    private const val NIGHT_FROM_HOUR = 21
    private const val BEDTIME_HOUR = 22
    private const val BEDTIME_MIN = 30
    private const val CHANNEL = "reading"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reading", Context.MODE_PRIVATE)

    /** Honor Docs (found by package or by its name, so it works whatever the exact package is). */
    fun appPackage(ctx: Context): String? {
        prefs(ctx).getString("pkg", null)?.let { p -> if (installed(ctx, p)) return p }
        val known = listOf("com.hihonor.docs", "com.huawei.docs", "com.hihonor.hnoffice", "cn.wps.moffice_eng")
        val found = known.firstOrNull { installed(ctx, it) }
            ?: listOf("Honor Docs", "Docs", "المستندات", "مستندات", "مستندات Honor").firstNotNullOfOrNull { n ->
                com.myassistant.app.admin.DeviceOwner.findInstalled(ctx, n)?.second
            }
        found?.let { prefs(ctx).edit().putString("pkg", it).apply() }
        return found
    }

    private fun installed(ctx: Context, p: String) = runCatching { ctx.packageManager.getApplicationInfo(p, 0); true }.getOrDefault(false)

    /** Start of the night that [t] belongs to: 9 PM (times before 3 AM belong to the previous evening). */
    private fun nightStart(t: Long = System.currentTimeMillis()): Long {
        val dayStart = PlaceTracker.dayStart(t) // 3 AM
        return Calendar.getInstance().apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, NIGHT_FROM_HOUR)
        }.timeInMillis
    }

    private fun nightKey(start: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start))

    /** Minutes Docs was on screen between [from] and [to]. */
    fun minutesBetween(ctx: Context, from: Long, to: Long): Int {
        val pkg = appPackage(ctx) ?: return 0
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return 0
        val ev = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return 0
        val e = UsageEvents.Event()
        var openedAt = -1L
        var total = 0L
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (openedAt < 0) openedAt = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (openedAt >= 0) { total += e.timeStamp - openedAt; openedAt = -1 }
            }
        }
        if (openedAt >= 0) total += to - openedAt
        return (total / 60_000L).toInt()
    }

    /** Tonight's reading minutes (0 before 9 PM). Saves the result so history survives. */
    fun tonight(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val start = nightStart(now)
        if (now < start) return 0
        val m = minutesBetween(ctx, start, minOf(now, start + 6 * 3_600_000L))
        val key = nightKey(start)
        val p = prefs(ctx)
        if (m > p.getInt("n_$key", 0)) p.edit().putInt("n_$key", m).apply()
        return m
    }

    /** Minutes read on the night that started [daysAgo] nights ago (from the saved history). */
    fun night(ctx: Context, daysAgo: Int): Int {
        if (daysAgo == 0) return tonight(ctx)
        val start = nightStart() - daysAgo * 86_400_000L
        val key = nightKey(start)
        val saved = prefs(ctx).getInt("n_$key", -1)
        if (saved >= 0) return saved
        // not saved (app wasn't asked that night): try the usage history
        return minutesBetween(ctx, start, start + 6 * 3_600_000L).also { prefs(ctx).edit().putInt("n_$key", it).apply() }
    }

    /** Nights in a row that reached the goal (tonight counts if already reached). */
    fun streak(ctx: Context): Int {
        var n = if (tonight(ctx) >= GOAL_MIN) 1 else 0
        for (d in 1..60) { if (night(ctx, d) >= GOAL_MIN) n++ else break }
        return n
    }

    private fun openIntent(ctx: Context): PendingIntent? {
        val launch = appPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return null
        return PendingIntent.getActivity(ctx, 8801, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(ctx: Context, title: String, text: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "القراءة", NotificationManager.IMPORTANCE_HIGH))
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setAutoCancel(true)
        text?.let { b.setContentText(it) }
        openIntent(ctx)?.let { b.setContentIntent(it); b.addAction(0, "افتح الكتاب", it) }
        runCatching { NotificationManagerCompat.from(ctx).notify(8800, b.build()) }
    }

    /** Something was just blocked: offer reading instead (at most every 10 minutes). */
    fun offerInstead(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastOffer", 0) < 10 * 60_000L) return
        p.edit().putLong("lastOffer", now).apply()
        val left = (GOAL_MIN - tonight(ctx)).coerceAtLeast(0)
        notify(ctx, "بدل كده؟", if (left > 0) "$left دقيقة قراية" else "شوية قراية")
    }

    /** Called when the phone is unlocked: once a night after 10:30 PM, if tonight's goal isn't reached yet. */
    fun onUnlock(ctx: Context) {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY); val m = now.get(Calendar.MINUTE)
        val bedtime = (h > BEDTIME_HOUR || (h == BEDTIME_HOUR && m >= BEDTIME_MIN)) || h < 3
        if (!bedtime) return
        val key = nightKey(nightStart())
        val p = prefs(ctx)
        if (p.getString("nudged", null) == key) return
        val read = tonight(ctx)
        if (read >= GOAL_MIN) return
        p.edit().putString("nudged", key).apply()
        notify(ctx, "قبل ما تنام", "${GOAL_MIN - read} دقيقة قراية")
    }

    /** For Nova's context. */
    fun contextLine(ctx: Context): String =
        "goal $GOAL_MIN min in Honor Docs before sleep; tonight ${tonight(ctx)} min; streak ${streak(ctx)} nights"
}

class ReadingReportTool : Tool {
    override val name = "reading_report"
    override val title = "القراءة"
    override val description =
        "The user's reading habit: minutes read in Honor Docs each night (counted automatically, 9 PM–3 AM), " +
            "tonight vs the ${Reading.GOAL_MIN}-minute goal, the streak, and the last N nights."
    override val parameters = Schema.obj("nights" to Schema.integer("How many past nights to list (default 7)"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val n = (args.int("nights") ?: 7).coerceIn(1, 30)
        buildString {
            appendLine(Reading.contextLine(ctx.app))
            for (d in 1 until n) appendLine("$d nights ago: ${Reading.night(ctx.app, d)} min")
        }
    }
}

package com.myassistant.app.prayer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.myassistant.app.places.PlaceTracker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** How a prayer went. */
object PrayerStatus {
    const val ON_TIME = "on_time"   // confirmed within its time
    const val MOSQUE = "mosque"     // was at a saved mosque at prayer time
    const val LATE = "late"         // confirmed after its time ended
    const val UNSURE = "unsure"     // lock ended by itself, no confirmation
    fun ar(s: String?) = when (s) {
        ON_TIME -> "في وقتها"; MOSQUE -> "في الجامع"; LATE -> "متأخرة"; UNSURE -> "مش متأكد"; else -> "لسه"
    }
}

/** Log of prayers + habits (kept on the phone, no screen — Nova reads it). Blocking. */
class HabitStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "habits.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prayers (day TEXT NOT NULL, prayer TEXT NOT NULL, status TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(day, prayer))")
        db.execSQL("CREATE TABLE habits (id INTEGER PRIMARY KEY AUTOINCREMENT, habit TEXT NOT NULL, amount REAL NOT NULL, unit TEXT, at INTEGER NOT NULL, note TEXT)")
        db.execSQL("CREATE INDEX habits_at ON habits(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun mark(day: String, p: Prayer, status: String, at: Long = System.currentTimeMillis()) {
        // a better status never gets downgraded (e.g. mosque → unsure)
        val old = status(day, p)
        val rank = listOf(PrayerStatus.UNSURE, PrayerStatus.LATE, PrayerStatus.ON_TIME, PrayerStatus.MOSQUE)
        if (old != null && rank.indexOf(old) > rank.indexOf(status)) return
        writableDatabase.insertWithOnConflict("prayers", null, ContentValues().apply {
            put("day", day); put("prayer", p.key); put("status", status); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun status(day: String, p: Prayer): String? =
        readableDatabase.rawQuery("SELECT status FROM prayers WHERE day = ? AND prayer = ?", arrayOf(day, p.key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun prayers(fromDay: String, toDay: String): List<Triple<String, Prayer, String>> =
        readableDatabase.rawQuery("SELECT day, prayer, status FROM prayers WHERE day >= ? AND day <= ? ORDER BY day", arrayOf(fromDay, toDay)).use { c ->
            buildList { while (c.moveToNext()) Prayer.of(c.getString(1))?.let { add(Triple(c.getString(0), it, c.getString(2))) } }
        }

    fun log(habit: String, amount: Double, unit: String?, note: String?, at: Long = System.currentTimeMillis()) =
        writableDatabase.insert("habits", null, ContentValues().apply {
            put("habit", habit.trim().lowercase()); put("amount", amount); put("unit", unit); put("at", at); put("note", note)
        })

    /** (habit, total, unit, entries) between [from, to). */
    fun totals(from: Long, to: Long, habit: String? = null): List<Triple<String, Double, String?>> =
        readableDatabase.rawQuery(
            "SELECT habit, SUM(amount), MAX(unit) FROM habits WHERE at >= ? AND at < ?" + (if (habit != null) " AND habit = ?" else "") + " GROUP BY habit",
            listOfNotNull(from.toString(), to.toString(), habit?.trim()?.lowercase()).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getDouble(1), c.getString(2))) } }

    fun habits(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT habit FROM habits", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    companion object {
        @Volatile private var instance: HabitStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: HabitStore(ctx.applicationContext).also { instance = it } }
    }
}

/**
 * At each adhan: lock the phone (Nova's prayer screen) until you confirm you prayed.
 * - min 7 / max 20 minutes (Friday Dhuhr: 25 / 60)
 * - at a saved mosque → counted as prayed in the mosque, no lock
 * - driving → waits until you stop
 * - screen off (sleeping, phone in pocket) → locks when you next unlock the phone, if the prayer's time hasn't ended
 * - one reminder halfway through the prayer's time if it isn't confirmed
 */
object Prayers {
    private const val CHANNEL = "prayer"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE)
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun dayKey(t: Long) = dayFmt.format(Date(t))

    /** Where to compute times for: last tracked position, else Cairo. */
    private fun latLng(ctx: Context): Pair<Double, Double> = PlaceTracker.lastFix(ctx) ?: (30.0444 to 31.2357)

    fun times(ctx: Context, daysFromToday: Int = 0): DayTimes {
        val (lat, lng) = latLng(ctx)
        return PrayerTimes.forDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, daysFromToday) }, lat, lng)
    }

    private fun isFriday(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

    fun minMax(p: Prayer, adhan: Long): Pair<Int, Int> = if (p == Prayer.DHUHR && isFriday(adhan)) 25 to 60 else 7 to 20

    /** The prayer whose time we're in now (adhan, end), if any. */
    fun current(ctx: Context, now: Long = System.currentTimeMillis()): Triple<Prayer, Long, Long>? {
        for (off in listOf(0, -1)) {
            val d = times(ctx, off)
            val nextFajr = times(ctx, off + 1).fajr
            for (p in Prayer.entries) {
                val a = d.at(p); val e = d.end(p, nextFajr)
                if (now in a until e) return Triple(p, a, e)
            }
        }
        return null
    }

    // ---------- alarms ----------

    private fun pi(ctx: Context, action: String, p: Prayer, adhan: Long, end: Long, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, PrayerReceiver::class.java).setAction(action).putExtra("p", p.key).putExtra("adhan", adhan).putExtra("end", end),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Schedules the next adhan (each adhan schedules the one after it). */
    fun scheduleNext(ctx: Context) {
        val now = System.currentTimeMillis()
        for (off in 0..1) {
            val d = times(ctx, off)
            val nextFajr = times(ctx, off + 1).fajr
            val p = Prayer.entries.firstOrNull { d.at(it) > now + 5_000 } ?: continue
            alarm(ctx, d.at(p), pi(ctx, ACTION_ADHAN, p, d.at(p), d.end(p, nextFajr), 7100))
            return
        }
    }

    fun onAdhan(ctx: Context, p: Prayer, adhan: Long, end: Long) {
        scheduleNext(ctx)
        val day = dayKey(adhan)
        val store = HabitStore.get(ctx)
        // halfway reminder
        alarm(ctx, adhan + (end - adhan) / 2, pi(ctx, ACTION_HALF, p, adhan, end, 7101))
        if (store.status(day, p) != null) return
        if (PlaceTracker.isMosque(PlaceTracker.currentPlace(ctx))) { store.mark(day, p, PrayerStatus.MOSQUE); return }
        tryLock(ctx, p, adhan, end, deferred = false)
    }

    /** Locks now if we can; otherwise waits (driving → check again in 3 min; screen off → lock on next unlock). */
    fun tryLock(ctx: Context, p: Prayer, adhan: Long, end: Long, deferred: Boolean) {
        val now = System.currentTimeMillis()
        if (now >= end) { clearPending(ctx); return }
        if (HabitStore.get(ctx).status(dayKey(adhan), p) != null) { clearPending(ctx); return }
        if (PlaceTracker.movingFast(ctx)) {
            alarm(ctx, now + 3 * 60_000L, pi(ctx, ACTION_RECHECK, p, adhan, end, 7102))
            return
        }
        val interactive = ctx.getSystemService(PowerManager::class.java).isInteractive
        if (!interactive) {
            prefs(ctx).edit().putString("pending", p.key).putLong("pAdhan", adhan).putLong("pEnd", end).apply()
            return
        }
        clearPending(ctx)
        val (min, max) = minMax(p, adhan)
        PrayerLockActivity.start(ctx, p, adhan, end, if (deferred) 0 else min, max)
    }

    private fun clearPending(ctx: Context) = prefs(ctx).edit().remove("pending").apply()

    /** The phone was just unlocked: show a waiting prayer lock, if any. */
    fun onUserPresent(ctx: Context) {
        val pr = prefs(ctx)
        val p = Prayer.of(pr.getString("pending", null) ?: return) ?: return
        tryLock(ctx, p, pr.getLong("pAdhan", 0), pr.getLong("pEnd", 0), deferred = true)
    }

    fun onHalf(ctx: Context, p: Prayer, adhan: Long) {
        if (HabitStore.get(ctx).status(dayKey(adhan), p) != null) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "الصلاة", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("لسه مصليتش ${p.ar}")
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(7200 + p.ordinal, n) }
    }

    /** "I prayed X" from chat or the lock screen. */
    fun confirm(ctx: Context, p: Prayer, now: Long = System.currentTimeMillis()): String {
        // which day's prayer: today's if its adhan already came, else yesterday's (e.g. Isha after midnight)
        val today = times(ctx, 0)
        val adhan = if (today.at(p) <= now) today.at(p) else times(ctx, -1).at(p)
        val end = if (today.at(p) <= now) today.end(p, times(ctx, 1).fajr) else times(ctx, -1).end(p, today.fajr)
        val status = if (now < end) PrayerStatus.ON_TIME else PrayerStatus.LATE
        HabitStore.get(ctx).mark(dayKey(adhan), p, status, now)
        return PrayerStatus.ar(status)
    }

    /** One line for Nova's context: today's prayers and the next one. */
    fun contextLine(ctx: Context): String {
        val now = System.currentTimeMillis()
        val d = times(ctx, 0)
        val day = dayKey(now)
        val store = HabitStore.get(ctx)
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val parts = Prayer.entries.map { p ->
            "${p.ar} ${tf.format(Date(d.at(p)))}: " + if (d.at(p) > now) "لسه مدخلش" else PrayerStatus.ar(store.status(day, p))
        }
        return parts.joinToString(" · ")
    }

    const val ACTION_ADHAN = "com.myassistant.app.ADHAN"
    const val ACTION_HALF = "com.myassistant.app.PRAYER_HALF"
    const val ACTION_RECHECK = "com.myassistant.app.PRAYER_RECHECK"
}

class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val p = Prayer.of(intent.getStringExtra("p") ?: "")
                val adhan = intent.getLongExtra("adhan", 0); val end = intent.getLongExtra("end", 0)
                when (intent.action) {
                    Prayers.ACTION_ADHAN -> if (p != null) Prayers.onAdhan(ctx, p, adhan, end)
                    Prayers.ACTION_RECHECK -> if (p != null) Prayers.tryLock(ctx, p, adhan, end, deferred = false)
                    Prayers.ACTION_HALF -> if (p != null) Prayers.onHalf(ctx, p, adhan)
                    else -> Prayers.scheduleNext(ctx)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

package com.myassistant.app.reminders

import android.Manifest
import android.annotation.SuppressLint
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
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.myassistant.app.MainActivity
import java.util.Calendar

data class Reminder(
    val id: Long,
    val text: String,
    /** Time-based: when it fires next (0 for place reminders). */
    val at: Long,
    /** none | daily | weekly | monthly */
    val repeat: String,
    /** Place reminders: saved place name, coordinates, and arrive|leave. */
    val place: String?,
    val lat: Double?,
    val lng: Double?,
    val event: String?,
    val done: Boolean,
) {
    val isPlace get() = place != null
}

data class SavedPlace(val name: String, val lat: Double, val lng: Double)

/** Reminders and saved places, stored on the phone. Blocking — use from IO. */
class ReminderStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "reminders.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, at INTEGER NOT NULL DEFAULT 0, " +
                "repeat TEXT NOT NULL DEFAULT 'none', place TEXT, lat REAL, lng REAL, event TEXT, done INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE places (name TEXT PRIMARY KEY, lat REAL NOT NULL, lng REAL NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(text: String, at: Long, repeat: String, place: SavedPlace?, event: String?): Long =
        writableDatabase.insert("reminders", null, ContentValues().apply {
            put("text", text); put("at", at); put("repeat", repeat)
            place?.let { put("place", it.name); put("lat", it.lat); put("lng", it.lng); put("event", event ?: "arrive") }
        })

    fun get(id: Long): Reminder? = query("id = ?", arrayOf(id.toString())).firstOrNull()
    fun active(): List<Reminder> = query("done = 0", null)
    fun all(): List<Reminder> = query(null, null)

    fun setNext(id: Long, at: Long) = writableDatabase.execSQL("UPDATE reminders SET at = ? WHERE id = ?", arrayOf<Any>(at, id))
    fun markDone(id: Long) = writableDatabase.execSQL("UPDATE reminders SET done = 1 WHERE id = ?", arrayOf<Any>(id))
    fun delete(id: Long) = writableDatabase.execSQL("DELETE FROM reminders WHERE id = ?", arrayOf<Any>(id))

    fun savePlace(p: SavedPlace) {
        writableDatabase.insertWithOnConflict("places", null, ContentValues().apply {
            put("name", p.name.trim()); put("lat", p.lat); put("lng", p.lng)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun place(name: String): SavedPlace? {
        val n = name.trim().removePrefix("ال")
        return places().firstOrNull { it.name.equals(name.trim(), true) }
            ?: places().firstOrNull { it.name.removePrefix("ال").equals(n, true) || it.name.contains(n, true) }
    }

    fun places(): List<SavedPlace> = readableDatabase.rawQuery("SELECT name, lat, lng FROM places ORDER BY name", null).use { c ->
        buildList { while (c.moveToNext()) add(SavedPlace(c.getString(0), c.getDouble(1), c.getDouble(2))) }
    }

    fun deletePlace(name: String) = writableDatabase.execSQL("DELETE FROM places WHERE name = ?", arrayOf<Any>(name))

    private fun query(where: String?, args: Array<String>?): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT id, text, at, repeat, place, lat, lng, event, done FROM reminders" +
                (where?.let { " WHERE $it" } ?: "") + " ORDER BY done, at",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Reminder(
                        c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4),
                        if (c.isNull(5)) null else c.getDouble(5), if (c.isNull(6)) null else c.getDouble(6),
                        c.getString(7), c.getInt(8) == 1,
                    ),
                )
            }
        }

    companion object {
        @Volatile private var instance: ReminderStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ReminderStore(ctx.applicationContext).also { instance = it } }
    }
}

object ReminderScheduler {
    const val RADIUS_M = 150f
    private const val CHANNEL = "reminders"

    private fun pending(ctx: Context, id: Long, place: Boolean): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(if (place) "place" else "time").putExtra("id", id)
        val mutability = if (place && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, (id * 2 + if (place) 1 else 0).toInt(), i, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    @SuppressLint("MissingPermission")
    fun schedule(ctx: Context, r: Reminder) {
        if (r.done) return
        if (r.isPlace) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            runCatching { lm.addProximityAlert(r.lat!!, r.lng!!, RADIUS_M, -1, pending(ctx, r.id, true)) }
        } else {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = pending(ctx, r.id, false)
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }

    @SuppressLint("MissingPermission")
    fun cancel(ctx: Context, r: Reminder) {
        if (r.isPlace) runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeProximityAlert(pending(ctx, r.id, true)) }
        else ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, r.id, false))
    }

    fun rescheduleAll(ctx: Context) {
        val store = ReminderStore.get(ctx)
        val now = System.currentTimeMillis()
        for (r in store.active()) {
            if (!r.isPlace && r.at < now) {
                if (r.repeat == "none") { notify(ctx, r); store.markDone(r.id); continue }
                val next = next(r.at, r.repeat, now)
                store.setNext(r.id, next)
                schedule(ctx, r.copy(at = next))
            } else {
                schedule(ctx, r)
            }
        }
    }

    /** Next occurrence after [after]. */
    fun next(at: Long, repeat: String, after: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        do {
            when (repeat) {
                "daily" -> c.add(Calendar.DAY_OF_MONTH, 1)
                "weekly" -> c.add(Calendar.WEEK_OF_YEAR, 1)
                "monthly" -> c.add(Calendar.MONTH, 1)
                else -> return at
            }
        } while (c.timeInMillis <= after)
        return c.timeInMillis
    }

    fun notify(ctx: Context, r: Reminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "التذكيرات", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (r.isPlace) "⏰ " + (if (r.event == "leave") "وإنت خارج من ${r.place}" else "وصلت ${r.place}") else "⏰ تذكير"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(10_000 + r.id.toInt(), n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                handle(ctx, intent)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(ctx: Context, intent: Intent) {
        val store = ReminderStore.get(ctx)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ReminderScheduler.rescheduleAll(ctx)
                runCatching { com.myassistant.app.health.Health.schedule(ctx) }
                runCatching { com.myassistant.app.mail.DailyReport.schedule(ctx) }
                runCatching { com.myassistant.app.focus.Focus.apply(ctx) }
            }
            "time" -> {
                val r = store.get(intent.getLongExtra("id", -1)) ?: return
                if (r.done) return
                ReminderScheduler.notify(ctx, r)
                if (r.repeat == "none") store.markDone(r.id) else {
                    val next = ReminderScheduler.next(r.at, r.repeat, System.currentTimeMillis())
                    store.setNext(r.id, next)
                    ReminderScheduler.schedule(ctx, r.copy(at = next))
                }
            }
            "place" -> {
                val r = store.get(intent.getLongExtra("id", -1)) ?: return
                if (r.done) return
                val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
                if ((r.event == "leave") == entering) return
                ReminderScheduler.notify(ctx, r)
                if (r.repeat == "none") {
                    store.markDone(r.id)
                    ReminderScheduler.cancel(ctx, r)
                }
            }
        }
    }
}

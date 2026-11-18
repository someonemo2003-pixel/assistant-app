package com.myassistant.app.places

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myassistant.app.reminders.ReminderStore
import com.myassistant.app.reminders.SavedPlace
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One stay: at a saved place (name) or at an unnamed spot (name = null) for 15+ minutes. */
data class Visit(val id: Long, val place: String?, val lat: Double, val lng: Double, val arrive: Long, val last: Long, val left: Long?, val addr: String?)

/** Visits, kept on the phone. Blocking — use from IO. */
class VisitStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "visits.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE visits (id INTEGER PRIMARY KEY AUTOINCREMENT, place TEXT, lat REAL NOT NULL, lng REAL NOT NULL, " +
                "arrive INTEGER NOT NULL, last INTEGER NOT NULL, left INTEGER, addr TEXT)",
        )
        db.execSQL("CREATE INDEX visits_arrive ON visits(arrive)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun open(): Visit? = query("left IS NULL", null).lastOrNull()

    fun start(place: String?, lat: Double, lng: Double, arrive: Long): Long =
        writableDatabase.insert("visits", null, ContentValues().apply {
            put("place", place); put("lat", lat); put("lng", lng); put("arrive", arrive); put("last", arrive)
        })

    fun touch(id: Long, t: Long) = writableDatabase.execSQL("UPDATE visits SET last = ? WHERE id = ?", arrayOf<Any>(t, id))
    fun close(id: Long, t: Long) = writableDatabase.execSQL("UPDATE visits SET left = ? WHERE id = ?", arrayOf<Any>(t, id))
    fun setAddr(id: Long, a: String) = writableDatabase.execSQL("UPDATE visits SET addr = ? WHERE id = ?", arrayOf<Any>(a, id))
    fun rename(id: Long, place: String) = writableDatabase.execSQL("UPDATE visits SET place = ? WHERE id = ?", arrayOf<Any>(place, id))

    /** Visits that overlap [from, to). */
    fun between(from: Long, to: Long): List<Visit> =
        query("arrive < ? AND IFNULL(left, last) >= ?", arrayOf(to.toString(), from.toString()))

    fun clear() = writableDatabase.execSQL("DELETE FROM visits")

    private fun query(where: String, args: Array<String>?): List<Visit> =
        readableDatabase.rawQuery("SELECT id, place, lat, lng, arrive, last, left, addr FROM visits WHERE $where ORDER BY arrive", args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Visit(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getLong(4), c.getLong(5),
                        if (c.isNull(6)) null else c.getLong(6), c.getString(7)),
                )
            }
        }

    companion object {
        @Volatile private var instance: VisitStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: VisitStore(ctx.applicationContext).also { instance = it } }
    }
}

/**
 * Knows where the user is through the day: saved places (البيت، الشغل…) by name, and any other spot where they
 * stayed 15+ minutes. Days start at 3 AM, so a late night out still counts as the same day.
 */
object PlaceTracker {
    const val DEFAULT_RADIUS = 100
    private const val STAY_MS = 15 * 60_000L
    private const val SAME_SPOT_M = 120f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("place_tracker", Context.MODE_PRIVATE)

    fun dist(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float =
        FloatArray(1).also { Location.distanceBetween(aLat, aLng, bLat, bLng, it) }[0]

    /** Start of the "day" that [t] belongs to: 3 AM (before 3 AM counts as the previous day). */
    fun dayStart(t: Long = System.currentTimeMillis(), daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = t
        add(Calendar.HOUR_OF_DAY, -3)
        set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis

    /** The saved place this fix is in, if any (place radius + a little of the fix's own uncertainty). */
    fun matchPlace(ctx: Context, lat: Double, lng: Double, accuracy: Float): SavedPlace? =
        ReminderStore.get(ctx).places()
            .map { it to dist(lat, lng, it.lat, it.lng) }
            .filter { (p, d) -> d <= p.radius + minOf(accuracy, 60f) }
            .minByOrNull { it.second }?.first

    /** Called with each location fix (every 5 min by day, 30 min early morning, 1 h at night). */
    @Synchronized
    fun onFix(ctx: Context, lat: Double, lng: Double, accuracy: Float, t: Long = System.currentTimeMillis()) {
        if (accuracy > 250f) return // too vague to say anything
        val p = prefs(ctx)
        // remember the last two fixes (for "is the user driving?")
        p.edit().putFloat("pLat", p.getFloat("lLat", lat.toFloat())).putFloat("pLng", p.getFloat("lLng", lng.toFloat()))
            .putLong("pT", p.getLong("lT", t)).putFloat("lLat", lat.toFloat()).putFloat("lLng", lng.toFloat()).putLong("lT", t).apply()
        val store = VisitStore.get(ctx)

        // Are we staying still? Keep an anchor; it moves when we're > SAME_SPOT_M away from it.
        var aLat = p.getFloat("aLat", Float.NaN).toDouble()
        var aLng = p.getFloat("aLng", Float.NaN).toDouble()
        var since = p.getLong("since", t)
        if (aLat.isNaN() || dist(aLat, aLng, lat, lng) > SAME_SPOT_M) { aLat = lat; aLng = lng; since = t }
        p.edit().putFloat("aLat", aLat.toFloat()).putFloat("aLng", aLng.toFloat()).putLong("since", since).apply()

        val place = matchPlace(ctx, lat, lng, accuracy)
        val open = store.open()

        // Still in the same place → just note we're here.
        if (open != null) {
            val same = if (open.place != null) place?.name == open.place
            else place == null && dist(open.lat, open.lng, lat, lng) <= SAME_SPOT_M * 1.5f
            if (same) { store.touch(open.id, t); p.edit().remove("out").apply(); return }
            // Leaving a saved place needs two fixes in a row outside it (GPS can jump).
            if (open.place != null && place == null) {
                val out = p.getInt("out", 0) + 1
                if (out < 2 && accuracy > 30f) { p.edit().putInt("out", out).apply(); return }
            }
            store.close(open.id, open.last)
            p.edit().remove("out").apply()
        }
        when {
            place != null -> store.start(place.name, place.lat, place.lng, t)
            t - since >= STAY_MS -> store.start(null, aLat, aLng, since)
        }
    }

    /** Last known position from tracking (or null). */
    fun lastFix(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        val lat = p.getFloat("lLat", Float.NaN)
        return if (lat.isNaN()) null else lat.toDouble() to p.getFloat("lLng", 0f).toDouble()
    }

    /** Moving faster than ~20 km/h between the last two fixes (taken within the last 10 minutes)? */
    fun movingFast(ctx: Context, speedMps: Float? = null): Boolean {
        if (speedMps != null && speedMps > 5.5f) return true
        val p = prefs(ctx)
        val lT = p.getLong("lT", 0); val pT = p.getLong("pT", 0)
        if (System.currentTimeMillis() - lT > 10 * 60_000L || lT - pT < 30_000L) return false
        val d = dist(p.getFloat("pLat", 0f).toDouble(), p.getFloat("pLng", 0f).toDouble(), p.getFloat("lLat", 0f).toDouble(), p.getFloat("lLng", 0f).toDouble())
        return d / ((lT - pT) / 1000f) > 5.5f
    }

    fun isMosque(name: String?) = name != null && listOf("جامع", "مسجد", "mosque", "masjid").any { name.contains(it, true) }

    /** The saved place the user is at right now (from tracking), if any. */
    fun currentPlace(ctx: Context): String? = VisitStore.get(ctx).open()?.takeIf {
        System.currentTimeMillis() - it.last < 20 * 60_000L
    }?.place

    /** Names a spot: "سجل المكان ده باسم X". Also renames today's visit if we're there now. */
    fun savePlace(ctx: Context, name: String, lat: Double, lng: Double, radius: Int) {
        ReminderStore.get(ctx).savePlace(SavedPlace(name.trim(), lat, lng, radius.coerceIn(30, 1000)))
        val store = VisitStore.get(ctx)
        store.open()?.let { v -> if (v.place == null && dist(v.lat, v.lng, lat, lng) <= radius + 60) store.rename(v.id, name.trim()) }
    }

    private val tf get() = SimpleDateFormat("HH:mm", Locale.US)

    private fun label(ctx: Context, v: Visit): String {
        if (v.place != null) return v.place
        val a = v.addr ?: runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale("ar")).getFromLocation(v.lat, v.lng, 1)?.firstOrNull()?.let { ad ->
                listOfNotNull(ad.thoroughfare, ad.subLocality ?: ad.locality).joinToString("، ").ifBlank { ad.getAddressLine(0) }
            }
        }.getOrNull()?.also { VisitStore.get(ctx).setAddr(v.id, it) }
        return "مكان مش متسجل" + (a?.let { " ($it)" } ?: "")
    }

    /** "03:00–08:40 البيت → 09:10–17:30 الشغل → من 19:20 البيت (دلوقتي)". Blocking. */
    fun timeline(ctx: Context, daysAgo: Int = 0): String {
        val from = dayStart(daysAgo = daysAgo)
        val to = from + 24 * 3_600_000L
        val visits = VisitStore.get(ctx).between(from, to)
        if (visits.isEmpty()) return ""
        return visits.joinToString(" → ") { v ->
            val start = tf.format(Date(maxOf(v.arrive, from)))
            val end = v.left?.let { tf.format(Date(minOf(it, to))) }
            (if (end == null) "من $start" else "$start–$end") + " " + label(ctx, v) + if (end == null && daysAgo == 0) " (دلوقتي)" else ""
        }
    }

    /** Short line for the assistant's context: where the user is now and where they've been today. Blocking. */
    fun contextLine(ctx: Context): String? {
        val places = ReminderStore.get(ctx).places()
        val today = runCatching { timeline(ctx) }.getOrDefault("")
        if (places.isEmpty() && today.isEmpty()) return null
        return buildString {
            if (places.isNotEmpty()) append("Saved places: ").append(places.joinToString("، ") { it.name }).append(". ")
            append("Today since 3 AM: ").append(today.ifEmpty { "no stays yet" })
        }
    }

    fun canTrack(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (!canTrack(ctx)) return
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PlaceService::class.java)) }
    }
}

/** Runs all day, balanced power (Wi-Fi/cell, GPS only when needed): every hour at night, 30 min in the early morning, 5 min the rest of the day. */
class PlaceService : Service(), LocationListener {
    override fun onBind(intent: Intent?): IBinder? = null
    private var started = false

    private val handler by lazy { android.os.Handler(Looper.getMainLooper()) }
    private val reschedule = Runnable { requestUpdates() }
    private var currentEvery = 0L
    private var unlockWatcher: android.content.BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTE_ID, note(), type) }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RATE) { started = true; requestUpdates(); return START_STICKY }
        if (started) return START_STICKY
        started = true
        requestUpdates()
        unlockWatcher = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Thread {
                    runCatching { com.myassistant.app.prayer.Prayers.onUserPresent(applicationContext) }
                    runCatching { com.myassistant.app.reading.Reading.onUnlock(applicationContext) }
                }.start()
            }
        }
        runCatching { ContextCompat.registerReceiver(this, unlockWatcher, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED) }
        return START_STICKY
    }

    /** (Re)asks for fixes at the rate for this time of day, and again when the next period starts. */
    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(this) }
        val every = intervalNow()
        currentEvery = every
        val main = if (Build.VERSION.SDK_INT >= 31 && runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false))
            LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching { lm.requestLocationUpdates(main, every, 0f, this, Looper.getMainLooper()) }
        // Free fixes other apps already asked for — only in the daytime period, so nights stay quiet.
        if (every <= 5 * 60_000L) runCatching { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 0f, this, Looper.getMainLooper()) }
        // Switch rate at 7 AM / 10 AM / midnight. An alarm wakes us even in deep sleep (the handler is a backup).
        handler.removeCallbacks(reschedule)
        handler.postDelayed(reschedule, msToNextPeriod() + 5_000L)
        runCatching {
            val am = getSystemService(android.app.AlarmManager::class.java)
            val pi = android.app.PendingIntent.getForegroundService(
                this, 6162, Intent(this, PlaceService::class.java).setAction(ACTION_RATE),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val at = System.currentTimeMillis() + msToNextPeriod() + 5_000L
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (intervalNow() != currentEvery) handler.post { requestUpdates() } // the rate period changed while asleep
        val acc = if (loc.hasAccuracy()) loc.accuracy else 100f
        Thread { runCatching { PlaceTracker.onFix(applicationContext, loc.latitude, loc.longitude, acc, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()) } }.start()
    }

    override fun onDestroy() {
        unlockWatcher?.let { runCatching { unregisterReceiver(it) } }
        handler.removeCallbacks(reschedule)
        runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
        super.onDestroy()
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nova", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nova")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "nova_bg"
        private const val NOTE_ID = 6161
        private const val ACTION_RATE = "rate"

        /** 12 AM–7 AM: every hour · 7–10 AM: every 30 min · 10 AM–12 AM: every 5 min. */
        fun intervalAt(hour: Int): Long = when (hour) {
            in 0 until 7 -> 60 * 60_000L
            in 7 until 10 -> 30 * 60_000L
            else -> 5 * 60_000L
        }

        fun intervalNow(): Long = intervalAt(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))

        /** Milliseconds until the next change of rate (7 AM, 10 AM or midnight). */
        fun msToNextPeriod(): Long {
            val now = java.util.Calendar.getInstance()
            val h = now.get(java.util.Calendar.HOUR_OF_DAY)
            val next = (now.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                when {
                    h < 7 -> set(java.util.Calendar.HOUR_OF_DAY, 7)
                    h < 10 -> set(java.util.Calendar.HOUR_OF_DAY, 10)
                    else -> { set(java.util.Calendar.HOUR_OF_DAY, 0); add(java.util.Calendar.DAY_OF_MONTH, 1) }
                }
            }
            return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        }
    }
}

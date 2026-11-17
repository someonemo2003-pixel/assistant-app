package com.myassistant.app.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location

data class GeoPoint(val time: Long, val lat: Double, val lng: Double, val accuracy: Float, val session: Long?)
data class Walk(val id: Long, val start: Long, val end: Long?, val meters: Double, val steps: Int)
data class DaySteps(val dayStart: Long, val steps: Int)

/**
 * Steps and places, stored only on this phone. Blocking — use from IO.
 */
class ActivityStore private constructor(context: Context) : SQLiteOpenHelper(context, "activity.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, acc REAL NOT NULL, session INTEGER)")
        db.execSQL("CREATE INDEX points_time ON points(time)")
        db.execSQL("CREATE TABLE step_samples (time INTEGER PRIMARY KEY, counter INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE walks (id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER NOT NULL, end_time INTEGER, meters REAL NOT NULL DEFAULT 0, steps INTEGER NOT NULL DEFAULT 0, start_counter INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- places ----------

    fun addPoint(loc: Location, session: Long?) {
        writableDatabase.insert("points", null, ContentValues().apply {
            put("time", loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("acc", if (loc.hasAccuracy()) loc.accuracy else 50f)
            session?.let { put("session", it) }
        })
    }

    fun points(from: Long, to: Long, session: Long? = null): List<GeoPoint> {
        val (where, args) = if (session != null) "session = ?" to arrayOf(session.toString())
        else "time >= ? AND time < ?" to arrayOf(from.toString(), to.toString())
        return readableDatabase.rawQuery("SELECT time, lat, lng, acc, session FROM points WHERE $where ORDER BY time", args).use { c ->
            buildList {
                while (c.moveToNext()) add(GeoPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getFloat(3), if (c.isNull(4)) null else c.getLong(4)))
            }
        }
    }

    fun clearPlaces() {
        writableDatabase.execSQL("DELETE FROM points")
        writableDatabase.execSQL("DELETE FROM walks")
    }

    // ---------- steps ----------

    /** The phone's step sensor counts since the last restart; we keep samples and add up the increases. */
    fun addStepSample(counter: Long) {
        writableDatabase.insertWithOnConflict("step_samples", null, ContentValues().apply {
            put("time", System.currentTimeMillis()); put("counter", counter)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun stepsBetween(from: Long, to: Long): Int {
        // Include the last sample before the range so the first increase is counted.
        val samples = readableDatabase.rawQuery(
            "SELECT time, counter FROM step_samples WHERE time >= (SELECT IFNULL(MAX(time), 0) FROM step_samples WHERE time < ?) AND time < ? ORDER BY time",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
        var total = 0L
        for (i in 1 until samples.size) {
            val d = samples[i].second - samples[i - 1].second
            // A drop means the phone restarted: the new value is all new steps.
            total += if (d >= 0) d else samples[i].second
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ---------- walks ----------

    fun startWalk(counter: Long?): Long = writableDatabase.insert("walks", null, ContentValues().apply {
        put("start", System.currentTimeMillis()); counter?.let { put("start_counter", it) }
    })

    fun finishWalk(id: Long, meters: Double, steps: Int) {
        writableDatabase.execSQL(
            "UPDATE walks SET end_time = ?, meters = ?, steps = ? WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), meters, steps, id),
        )
    }

    fun updateWalkDistance(id: Long, meters: Double) {
        writableDatabase.execSQL("UPDATE walks SET meters = ? WHERE id = ?", arrayOf<Any>(meters, id))
    }

    fun walkStartCounter(id: Long): Long? =
        readableDatabase.rawQuery("SELECT start_counter FROM walks WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun walks(limit: Int = 20): List<Walk> =
        readableDatabase.rawQuery("SELECT id, start, end_time, meters, steps FROM walks ORDER BY start DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Walk(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getDouble(3), c.getInt(4))) }
        }

    companion object {
        @Volatile private var instance: ActivityStore? = null
        fun get(ctx: Context): ActivityStore =
            instance ?: synchronized(this) { instance ?: ActivityStore(ctx.applicationContext).also { instance = it } }

        /** Total path length in metres, ignoring GPS jitter. */
        fun distance(points: List<GeoPoint>): Double {
            var d = 0.0
            var last: GeoPoint? = null
            val out = FloatArray(1)
            for (p in points) {
                if (p.accuracy > 60f) continue
                val l = last
                if (l != null) {
                    Location.distanceBetween(l.lat, l.lng, p.lat, p.lng, out)
                    if (out[0] >= 4f && out[0] < 5000f) d += out[0] else if (out[0] < 4f) continue
                }
                last = p
            }
            return d
        }
    }
}

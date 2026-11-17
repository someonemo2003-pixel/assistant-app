package com.myassistant.app.health

import android.Manifest
import android.location.Geocoder
import android.location.Location
import android.os.Build
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the map screen should show (set by the show_route tool, read by the UI). */
data class MapRequest(val from: Long, val to: Long, val walkId: Long?, val title: String)

object MapRequests {
    private val _request = MutableStateFlow<MapRequest?>(null)
    val request: StateFlow<MapRequest?> = _request.asStateFlow()
    fun show(r: MapRequest) { _request.value = r }
    fun consume() { _request.value = null }
}

private fun dayStart(offsetDays: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, -offsetDays)
}.timeInMillis

/** "today" | "yesterday" | "yyyy-MM-dd" → [start, end) */
internal fun dayRange(day: String): Pair<Long, Long>? = when (day.trim().lowercase()) {
    "", "today", "النهارده" -> dayStart(0) to System.currentTimeMillis()
    "yesterday", "امبارح" -> dayStart(1) to dayStart(0)
    else -> runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.trim())!!.time
        d to d + 86_400_000L
    }.getOrNull()
}

class StepsTool : Tool {
    override val name = "steps"
    override val title = "الخطوات"
    override val description =
        "Steps walked (counted by the phone itself, no other app needed): today, yesterday, or the last 7 days per day, " +
            "with estimated distance and calories."
    override val parameters = Schema.obj("period" to Schema.string("today | yesterday | week"))
    override val permissions = if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        Health.sampleSteps(ctx.app)
        val store = ActivityStore.get(ctx.app)
        fun line(label: String, steps: Int) =
            "$label: $steps steps (≈ ${String.format(Locale.US, "%.1f", steps * 0.75 / 1000)} km, ≈ ${(steps * 0.04).toInt()} kcal)"
        return withContext(Dispatchers.IO) {
            when (args.str("period")) {
                "yesterday" -> line("Yesterday", store.stepsBetween(dayStart(1), dayStart(0)))
                "week" -> {
                    val fmt = SimpleDateFormat("EEE MM-dd", Locale.US)
                    val days = (6 downTo 0).map { i ->
                        val from = dayStart(i)
                        val to = if (i == 0) System.currentTimeMillis() else dayStart(i - 1)
                        fmt.format(Date(from)) to store.stepsBetween(from, to)
                    }
                    days.joinToString("\n") { line(it.first, it.second) } + "\nTotal: ${days.sumOf { it.second }} steps"
                }
                else -> line("Today", store.stepsBetween(dayStart(0), System.currentTimeMillis())) +
                    "\n(Counting started when the step permission was given; earlier days may be empty.)"
            }
        }
    }
}

class WalkTool : Tool {
    override val name = "walk_tracking"
    override val title = "تتبّع المشي"
    override val description =
        "Start or stop precise GPS tracking of a walk/run (distance, route, steps). action: start | stop | status."
    override val parameters = Schema.obj("action" to Schema.string("start | stop | status"), required = listOf("action"))
    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = when (args.str("action")) {
        "start" -> if (WalkService.running.value) "Already tracking" else { WalkService.start(ctx.app); "Walk tracking started" }
        "stop" -> {
            if (!WalkService.running.value) "Not tracking right now"
            else {
                val id = WalkService.current
                WalkService.stop(ctx.app)
                kotlinx.coroutines.delay(800)
                val w = withContext(Dispatchers.IO) { ActivityStore.get(ctx.app).walks(5).firstOrNull { it.id == id } }
                if (w != null) {
                    MapRequests.show(MapRequest(w.start, w.end ?: System.currentTimeMillis(), w.id, "المشوار"))
                    "Walk saved: ${String.format(Locale.US, "%.2f", w.meters / 1000)} km, ${w.steps} steps, " +
                        "${((w.end ?: System.currentTimeMillis()) - w.start) / 60000} min. The map is now shown to the user."
                } else "Walk stopped"
            }
        }
        else -> if (WalkService.running.value) "Tracking a walk right now" else "Not tracking"
    }
}

class ShowRouteTool : Tool {
    override val name = "show_route"
    override val title = "خريطة"
    override val description =
        "Show the user a map of where they went: a day (today/yesterday/yyyy-MM-dd), optionally a time window, or the last tracked walk."
    override val parameters = Schema.obj(
        "day" to Schema.string("today | yesterday | yyyy-MM-dd | last_walk"),
        "from_hour" to Schema.integer("Optional start hour 0-23"),
        "to_hour" to Schema.integer("Optional end hour 0-23"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val store = ActivityStore.get(ctx.app)
        if (args.str("day") == "last_walk") {
            val w = withContext(Dispatchers.IO) { store.walks(1).firstOrNull() } ?: return "No tracked walks yet"
            MapRequests.show(MapRequest(w.start, w.end ?: System.currentTimeMillis(), w.id, "آخر مشوار"))
            return "Showing the last walk on the map (${String.format(Locale.US, "%.2f", w.meters / 1000)} km)."
        }
        var (from, to) = dayRange(args.str("day")) ?: return "Bad day; use today, yesterday or yyyy-MM-dd"
        val dayBegin = from
        if (args.has("from_hour")) from = dayBegin + args.optInt("from_hour") * 3_600_000L
        if (args.has("to_hour")) to = dayBegin + args.optInt("to_hour") * 3_600_000L
        val pts = withContext(Dispatchers.IO) { store.points(from, to) }
        if (pts.isEmpty()) return "No location history for that time (all-day tracking may be off or 'allow all the time' location not granted)."
        MapRequests.show(MapRequest(from, to, null, args.str("day").ifEmpty { "النهارده" }))
        return "Showing the map: ${pts.size} points, ≈ ${String.format(Locale.US, "%.1f", ActivityStore.distance(pts) / 1000)} km moved."
    }
}

class PlacesTool : Tool {
    override val name = "places_visited"
    override val title = "الأماكن"
    override val description =
        "Where the user was and for how long on a day (from the phone's own location history): list of stops with times and addresses."
    override val parameters = Schema.obj("day" to Schema.string("today | yesterday | yyyy-MM-dd"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val (from, to) = dayRange(args.str("day")) ?: return@withContext "Bad day"
        val pts = ActivityStore.get(ctx.app).points(from, to).filter { it.accuracy <= 150f }
        if (pts.isEmpty()) return@withContext "No location history for that day"
        val stays = stays(pts)
        if (stays.isEmpty()) return@withContext "Moving all day, no long stops"
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val geo = runCatching { Geocoder(ctx.app, Locale("ar")) }.getOrNull()
        stays.joinToString("\n") { s ->
            @Suppress("DEPRECATION")
            val addr = runCatching { geo?.getFromLocation(s.lat, s.lng, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
            "${fmt.format(Date(s.start))}–${fmt.format(Date(s.end))} (${(s.end - s.start) / 60000} min): " +
                (addr ?: "%.5f, %.5f".format(Locale.US, s.lat, s.lng))
        }
    }

    private data class Stay(val start: Long, val end: Long, val lat: Double, val lng: Double)

    /** Groups points that stay within ~150 m for 10+ minutes. */
    private fun stays(points: List<GeoPoint>): List<Stay> {
        val out = mutableListOf<Stay>()
        var group = mutableListOf(points.first())
        val d = FloatArray(1)
        for (p in points.drop(1)) {
            val c = group.first()
            Location.distanceBetween(c.lat, c.lng, p.lat, p.lng, d)
            if (d[0] <= 150f) group += p else {
                flush(group, out); group = mutableListOf(p)
            }
        }
        flush(group, out)
        return out
    }

    private fun flush(g: List<GeoPoint>, out: MutableList<Stay>) {
        if (g.last().time - g.first().time >= 10 * 60_000L) {
            out += Stay(g.first().time, g.last().time, g.map { it.lat }.average(), g.map { it.lng }.average())
        }
    }
}

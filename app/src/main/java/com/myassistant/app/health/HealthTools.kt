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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
                    val pts = withContext(Dispatchers.IO) { ActivityStore.get(ctx.app).points(0, 0, w.id) }
                    val shown = GoogleMaps.openRoute(ctx.app, pts)
                    "Walk saved: ${String.format(Locale.US, "%.2f", w.meters / 1000)} km, ${w.steps} steps, " +
                        "${((w.end ?: System.currentTimeMillis()) - w.start) / 60000} min." +
                        if (shown) " The route is now open in Google Maps." else ""
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
        "Open Google Maps with the route the user went: a day (today/yesterday/yyyy-MM-dd), optionally a time window, or the last tracked walk."
    override val parameters = Schema.obj(
        "day" to Schema.string("today | yesterday | yyyy-MM-dd | last_walk"),
        "from_hour" to Schema.integer("Optional start hour 0-23"),
        "to_hour" to Schema.integer("Optional end hour 0-23"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val store = ActivityStore.get(ctx.app)
        if (args.str("day") == "last_walk") {
            val w = withContext(Dispatchers.IO) { store.walks(1).firstOrNull() } ?: return "No tracked walks yet"
            val pts = withContext(Dispatchers.IO) { store.points(0, 0, w.id) }
            if (!GoogleMaps.openRoute(ctx.app, pts)) return "That walk has no saved points"
            return "Opened the last walk in Google Maps (${String.format(Locale.US, "%.2f", w.meters / 1000)} km)."
        }
        var (from, to) = dayRange(args.str("day")) ?: return "Bad day; use today, yesterday or yyyy-MM-dd"
        val dayBegin = from
        if (args.has("from_hour")) from = dayBegin + args.optInt("from_hour") * 3_600_000L
        if (args.has("to_hour")) to = dayBegin + args.optInt("to_hour") * 3_600_000L
        val pts = withContext(Dispatchers.IO) { store.points(from, to) }
        if (pts.isEmpty()) return "No location history for that time (all-day tracking may be off or 'allow all the time' location not granted)."
        GoogleMaps.openRoute(ctx.app, pts, if (ActivityStore.distance(pts) > 5_000) "driving" else "walking")
        return "Opened the route in Google Maps: ${pts.size} points, ≈ ${String.format(Locale.US, "%.1f", ActivityStore.distance(pts) / 1000)} km moved."
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


class NavigateTool : Tool {
    override val name = "navigate"
    override val title = "الطريق"
    override val description =
        "Open Google Maps with the way to a place from where the user is now (a name or address, e.g. 'Cairo Festival City' " +
            "or a saved place's address). start=true begins turn-by-turn navigation right away."
    override val parameters = Schema.obj(
        "destination" to Schema.string("Place name or address"),
        "mode" to Schema.string("driving | walking | transit | bicycling | two-wheeler (default driving)"),
        "start" to Schema.string("'true' to start navigation now, else just show the route"),
        required = listOf("destination"),
    )
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val dest = args.str("destination").ifBlank { return "Missing destination" }
        // A saved place ("البيت") → its coordinates.
        val saved = withContext(Dispatchers.IO) {
            com.myassistant.app.reminders.ReminderStore.get(ctx.app).places().firstOrNull { it.name.equals(dest.trim(), true) }
        }
        val target = saved?.let { String.format(Locale.US, "%.6f,%.6f", it.lat, it.lng) } ?: dest
        val ok = GoogleMaps.directions(ctx.app, target, args.str("mode").ifBlank { "driving" }, args.str("start") == "true")
        return if (ok) "Opened Google Maps to $dest" else "Couldn't open Google Maps"
    }
}

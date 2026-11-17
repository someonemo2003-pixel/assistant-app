package com.myassistant.app.health

import android.Manifest
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
                    "Walk saved: ${String.format(Locale.US, "%.2f", w.meters / 1000)} km, ${w.steps} steps, " +
                        "${((w.end ?: System.currentTimeMillis()) - w.start) / 60000} min."
                } else "Walk stopped"
            }
        }
        else -> if (WalkService.running.value) "Tracking a walk right now" else "Not tracking"
    }
}

class PlacesTool : Tool {
    override val name = "places_visited"
    override val title = "الأماكن"
    override val description =
        "Where the user went on a day and when: saved places by name (البيت، الشغل…) and other spots where they stayed " +
            "15+ minutes. A day runs from 3 AM to 3 AM."
    override val parameters = Schema.obj("day" to Schema.string("today | yesterday | number of days ago (0-30)"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val d = when (val v = args.str("day").lowercase()) {
            "", "today", "النهارده" -> 0
            "yesterday", "امبارح" -> 1
            else -> v.toIntOrNull()?.coerceIn(0, 30) ?: 0
        }
        com.myassistant.app.places.PlaceTracker.timeline(ctx.app, d).ifEmpty { "No places logged for that day" }
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

package com.myassistant.app.reminders

import com.myassistant.app.health.Health
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddReminderTool : Tool {
    override val name = "add_reminder"
    override val title = "تذكير"
    override val description =
        "Create a reminder that notifies the user even if the app is closed. Either at a time ('at' as yyyy-MM-dd HH:mm in " +
            "local time, or 'in_minutes'), optionally repeating; or at a saved place (place name + when: arrive|leave)."
    override val parameters = Schema.obj(
        "text" to Schema.string("What to remind about, in the user's words"),
        "at" to Schema.string("Local date-time 'yyyy-MM-dd HH:mm'"),
        "in_minutes" to Schema.integer("Or: minutes from now"),
        "repeat" to Schema.string("none | daily | weekly | monthly"),
        "place" to Schema.string("Saved place name (e.g. البيت, الشغل) for a place reminder"),
        "when" to Schema.string("arrive | leave (place reminders)"),
        required = listOf("text"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val store = ReminderStore.get(ctx.app)
        val text = args.str("text").ifEmpty { return@withContext "Missing text" }
        val repeat = args.str("repeat").lowercase().takeIf { it in setOf("daily", "weekly", "monthly") } ?: "none"
        val placeName = args.str("place")
        if (placeName.isNotEmpty()) {
            val place = store.place(placeName)
                ?: return@withContext "Place \"$placeName\" is not saved. Saved: ${store.places().joinToString { it.name }.ifEmpty { "none" }}. " +
                    "Ask the user to say 'save my location as $placeName' while they are there (save_place)."
            val event = if (args.str("when") == "leave") "leave" else "arrive"
            val id = store.add(text, 0, repeat, place, event)
            ReminderScheduler.schedule(ctx.app, store.get(id)!!)
            val bg = android.os.Build.VERSION.SDK_INT < 29 ||
                Health.granted(ctx.app, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return@withContext "Place reminder #$id set: when you ${if (event == "leave") "leave" else "arrive at"} ${place.name}" +
                if (!bg) ". NOTE: needs 'location all the time' permission to work when the app is closed." else ""
        }
        val at = args.int("in_minutes")?.let { System.currentTimeMillis() + it * 60_000L }
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(args.str("at"))!!.time }.getOrNull()
            ?: return@withContext "Need 'at' (yyyy-MM-dd HH:mm) or 'in_minutes'"
        if (at < System.currentTimeMillis() - 60_000 && repeat == "none") return@withContext "That time is in the past"
        val id = store.add(text, at, repeat, null, null)
        ReminderScheduler.schedule(ctx.app, store.get(id)!!)
        val exact = if (ReminderScheduler.canExact(ctx.app)) "" else " (may be a few minutes late: enable 'exact alarms' in Permissions)"
        "Reminder #$id set for ${SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US).format(Date(at))}" +
            (if (repeat != "none") ", repeats $repeat" else "") + exact
    }
}

class ListRemindersTool : Tool {
    override val name = "list_reminders"
    override val title = "التذكيرات"
    override val description = "List the user's reminders (upcoming by default) and saved places."
    override val parameters = Schema.obj("include_done" to Schema.string("'true' to include finished ones"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val store = ReminderStore.get(ctx.app)
        val list = if (args.str("include_done") == "true") store.all() else store.active()
        val fmt = SimpleDateFormat("EEE MM-dd HH:mm", Locale.US)
        val rs = if (list.isEmpty()) "No reminders" else list.joinToString("\n") { r ->
            "#${r.id} " + (if (r.done) "✓ " else "") +
                (if (r.isPlace) "[${r.event} ${r.place}]" else "[${fmt.format(Date(r.at))}${if (r.repeat != "none") ", ${r.repeat}" else ""}]") +
                " ${r.text}"
        }
        rs + "\nSaved places: " + store.places().joinToString { it.name }.ifEmpty { "none" }
    }
}

class CancelReminderTool : Tool {
    override val name = "cancel_reminder"
    override val title = "إلغاء تذكير"
    override val description = "Cancel a reminder by its #id (from list_reminders)."
    override val parameters = Schema.obj("id" to Schema.integer("Reminder id"), required = listOf("id"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val store = ReminderStore.get(ctx.app)
        val r = args.int("id")?.toLong()?.let { store.get(it) } ?: return@withContext "No such reminder"
        ReminderScheduler.cancel(ctx.app, r)
        store.delete(r.id)
        "Reminder #${r.id} cancelled"
    }
}

class SavePlaceTool : Tool {
    override val name = "save_place"
    override val title = "حفظ مكان"
    override val description =
        "Save where the user is NOW under a name (\"سجل المكان ده باسم الشغل\"). Nova then knows whenever the user is there " +
            "(daily places log) and it can be used for place reminders. Optional radius in metres for big places " +
            "(default 100; e.g. 300 for a university or a mall)."
    override val parameters = Schema.obj(
        "name" to Schema.string("Place name"),
        "radius" to Schema.integer("Optional size in metres (default 100)"),
        required = listOf("name"),
    )
    override val permissions = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val name = args.str("name").ifEmpty { return "Missing name" }
        val loc = Health.currentLocation(ctx.app) ?: return "Could not get the current location (turn on location/GPS)"
        if (loc.accuracy > 150f) return "Location is too vague right now (±${loc.accuracy.toInt()} m). Ask the user to turn on GPS / Wi-Fi and try again."
        val radius = (args.int("radius") ?: com.myassistant.app.places.PlaceTracker.DEFAULT_RADIUS).coerceIn(30, 1000)
        withContext(Dispatchers.IO) {
            com.myassistant.app.places.PlaceTracker.savePlace(ctx.app, name, loc.latitude, loc.longitude, radius)
            // Keep it in memory too, under places.
            val mem = ctx.memory
            if (mem.search(name, 20).none { it.category == com.myassistant.app.memory.MemoryCategories.PLACES && it.text.contains(name) }) {
                mem.add("مكان محفوظ: $name", com.myassistant.app.memory.MemoryCategories.PLACES)
            }
        }
        return "Saved \"$name\" (±${loc.accuracy.toInt()} m, radius $radius m)"
    }
}

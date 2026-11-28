package com.myassistant.app.gtasks

import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import com.myassistant.app.ui.UiRequests
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Runs a Google Tasks call; when not connected, opens the connect screen and says so. */
private suspend fun guarded(block: suspend () -> String): String = try {
    block()
} catch (_: GoogleTasks.NotConnected) {
    UiRequests.open("tasks")
    "Google Tasks isn't connected. I opened the connect screen; tell the user to tap 'اربط' once and try again."
} catch (e: GoogleTasks.ApiError) {
    "Google Tasks error: ${e.message}"
} catch (e: java.io.IOException) {
    "No internet: ${e.message}"
}

class TasksListTool : Tool {
    override val name = "tasks_list"
    override val title = "مهام جوجل"
    override val description =
        "List the user's open tasks in Google Tasks (optionally one list by name, or 'lists' to name the lists). " +
            "Use for 'what are my tasks', 'إيه مهامي', 'ورايا إيه'."
    override val parameters = Schema.obj(
        "list" to Schema.string("List name, empty = all lists"),
        "what" to Schema.string("tasks | lists (default tasks)"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = guarded {
        val app = ctx.app
        if (args.str("what").lowercase() == "lists") {
            return@guarded "Lists: " + GoogleTasks.lists(app).joinToString(", ") { it.title }
        }
        val lists = if (args.str("list").isBlank()) GoogleTasks.lists(app) else listOf(GoogleTasks.pickList(app, args.str("list")))
        val out = StringBuilder()
        var n = 0
        for (l in lists) {
            val ts = GoogleTasks.tasks(app, l)
            if (ts.isEmpty()) continue
            out.append("[${l.title}]\n")
            ts.sortedWith(compareBy({ it.due ?: "9999" }, { it.title })).forEach {
                n++
                out.append("- ${it.title}").append(it.due?.let { d -> " (due $d)" } ?: "")
                    .append(if (it.notes.isNotBlank()) " — ${it.notes.take(80)}" else "").append('\n')
            }
        }
        if (n == 0) "No open tasks." else "$n open tasks:\n$out"
    }
}

class TasksAddTool : Tool {
    override val name = "tasks_add"
    override val title = "مهمة جديدة"
    override val learnable = true
    override val description =
        "Add a task to Google Tasks. Optional due date (YYYY-MM-DD, or today/tomorrow) and time (HH:MM, 24h): Google Tasks keeps " +
            "only the day, so with a time Nova also sets its own reminder at that time. Optional list name."
    override val parameters = Schema.obj(
        "title" to Schema.string("The task, short"),
        "notes" to Schema.string("Details (optional)"),
        "due" to Schema.string("YYYY-MM-DD | today | tomorrow (optional)"),
        "time" to Schema.string("HH:MM 24h (optional)"),
        "list" to Schema.string("List name (optional, default list)"),
        required = listOf("title"),
    )

    override fun describe(args: JSONObject) = "مهمة: ${args.str("title")}"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = guarded {
        val app = ctx.app
        val title = args.str("title").ifBlank { return@guarded "Missing title" }
        val day = parseDay(args.str("due"))
        val time = args.str("time").takeIf { Regex("""\d{1,2}:\d{2}""").matches(it) }
        val list = GoogleTasks.pickList(app, args.str("list"))
        val effectiveDay = day ?: if (time != null) fmt.format(Calendar.getInstance().time) else null
        GoogleTasks.add(app, list, title, args.str("notes"), effectiveDay)
        var extra = ""
        if (time != null && effectiveDay != null) {
            val at = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$effectiveDay $time")!!.time }.getOrNull()
            if (at != null && at > System.currentTimeMillis()) {
                val store = com.myassistant.app.reminders.ReminderStore.get(app)
                val id = store.add(title, at, "none", null, null)
                store.get(id)?.let { com.myassistant.app.reminders.ReminderScheduler.schedule(app, it) }
                extra = ", reminder set for $effectiveDay $time"
            }
        }
        "Added to '${list.title}': $title" + (effectiveDay?.let { " (due $it)" } ?: "") + extra
    }
}

class TasksDoneTool : Tool {
    override val name = "tasks_done"
    override val title = "خلّص مهمة"
    override val description = "Mark a Google Tasks task as done, found by (part of) its title."
    override val parameters = Schema.obj("title" to Schema.string("Task title or part of it"), required = listOf("title"))
    override fun describe(args: JSONObject) = "خلّص: ${args.str("title")}"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = guarded {
        val found = GoogleTasks.find(ctx.app, args.str("title"))
        when {
            found.isEmpty() -> "No open task matches '${args.str("title")}'."
            found.size > 1 -> "Several tasks match, ask which one: " + found.joinToString(" | ") { it.title }
            else -> { GoogleTasks.complete(ctx.app, found.first()); "Done: ${found.first().title}" }
        }
    }
}

private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

internal fun parseDay(s: String): String? {
    val t = s.trim().lowercase()
    if (t.isEmpty()) return null
    val c = Calendar.getInstance()
    return when (t) {
        "today", "النهارده", "اليوم" -> fmt.format(c.time)
        "tomorrow", "بكرة", "بكره", "غدا" -> { c.add(Calendar.DAY_OF_YEAR, 1); fmt.format(c.time) }
        else -> t.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
    }
}

package com.myassistant.app.focus

import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FocusAddTool : Tool {
    override val name = "focus_add"
    override val title = "وضع التركيز"
    override val description =
        "Block apps on a schedule and/or after a daily time limit, e.g. 'close Facebook and TikTok from 12am to 8am' " +
            "(from=00:00, to=08:00) or 'Instagram one hour a day' (daily_minutes=60). Apps stay installed but can't be opened."
    override val parameters = Schema.obj(
        "name" to Schema.string("Short name for the rule, e.g. نوم, شغل, سوشيال"),
        "apps" to Schema.string("Comma-separated app names as the user said them"),
        "from" to Schema.string("Optional start time HH:mm (24h)"),
        "to" to Schema.string("Optional end time HH:mm (24h); may be after midnight"),
        "daily_minutes" to Schema.integer("Optional minutes allowed per day (0 = no limit)"),
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        Focus.add(
            ctx.app, args.str("name"), args.str("apps").split(',', '،').map { it.trim() }.filter { it.isNotEmpty() },
            args.str("from").ifBlank { null }, args.str("to").ifBlank { null }, args.int("daily_minutes") ?: 0,
        )
    }
}

class FocusListTool : Tool {
    override val name = "focus_list"
    override val title = "قواعد التركيز"
    override val description = "List focus rules, today's usage of limited apps, and which apps are blocked right now."
    override val parameters = Schema.obj()
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        val rules = Focus.rules(ctx.app)
        if (rules.isEmpty()) return@withContext "No focus rules"
        val usage = Focus.usageToday(ctx.app, rules.flatMap { it.apps })
        val blocked = Focus.blockedNow(ctx.app)
        buildString {
            rules.forEach { r ->
                append("- ${r.name}: ${r.labels.joinToString(", ")}")
                if (r.from != null) append(" | blocked ${r.from}–${r.to}")
                if (r.limitMin > 0) append(" | limit ${r.limitMin} min/day, used ${r.apps.sumOf { usage[it] ?: 0 }}")
                appendLine()
            }
            append("Blocked now: ").append(if (blocked.isEmpty()) "nothing" else blocked.values.distinct().joinToString("; "))
        }
    }
}

class FocusRemoveTool : Tool {
    override val name = "focus_remove"
    override val title = "إلغاء قاعدة تركيز"
    override val description = "Delete a focus rule."
    override val parameters = Schema.obj("rule" to Schema.string("Rule name or an app in it"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withContext(Dispatchers.IO) {
        Focus.remove(ctx.app, args.str("rule"))
    }
}

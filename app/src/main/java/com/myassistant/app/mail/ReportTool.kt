package com.myassistant.app.mail

import com.myassistant.app.control.Accounts
import com.myassistant.app.control.NotificationStore
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** On-demand report from chat: "تقرير الجيميل بس", "تقرير واتس التوأم", "تقرير آخر ٣ ساعات". */
class ReportTool : Tool {
    override val name = "make_report"
    override val title = "تقرير"
    override val description =
        "Collect data for a report the user asks for. Pick only the parts they asked about: 'Gmail report' → email; " +
            "'WhatsApp report' → whatsapp; full/daily report → all. Can filter to one account (e.g. the WhatsApp twin " +
            "copy, a named WhatsApp account, or one email address). Then write the report from the returned data."
    override val parameters = Schema.obj(
        "sources" to Schema.string("Comma list of: email, whatsapp, messages (other chat apps/SMS), calendar, reminders, money, health. Or 'all'."),
        "hours" to Schema.integer("How far back, default 24 (max 168)"),
        "account" to Schema.string("Optional: only this account — words from its name, e.g. 'توأم', 'الشغل', or part of an email address"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val raw = args.str("sources").lowercase()
        val sources = if (raw.isBlank() || "all" in raw) DailyReport.SOURCES.keys
        else raw.split(',', ' ', '،').map { it.trim() }.filter { it in DailyReport.SOURCES }.toSet().ifEmpty { DailyReport.SOURCES.keys }
        val hours = (args.int("hours") ?: 24).coerceIn(1, 168)
        val data = DailyReport.collect(ctx.app, sources, hours, args.str("account"))
        val known = withContext(Dispatchers.IO) {
            NotificationStore.get(ctx.app).accounts().filter { it.first.first in Accounts.WHATSAPP }
                .joinToString(", ") { Accounts.label(ctx.app, it.first.first, it.first.second, it.second) }
        }
        if (data.isBlank()) return "No data for that report. Known WhatsApp accounts: ${known.ifEmpty { "none yet" }}"
        return "Write the report now, following this style:\n${DailyReport.STYLE}\n\nKnown WhatsApp accounts: $known\n\nDATA:\n${data.take(16_000)}"
    }
}

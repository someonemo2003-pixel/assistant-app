package com.myassistant.app.prayer

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

private fun periodRange(period: String): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    when (period.trim().lowercase()) {
        "yesterday", "امبارح" -> { start.add(Calendar.DAY_OF_MONTH, -1); return start.timeInMillis to start.timeInMillis + 86_400_000L }
        "week", "الأسبوع", "الاسبوع" -> start.add(Calendar.DAY_OF_MONTH, -6)
        "month", "الشهر" -> start.add(Calendar.DAY_OF_MONTH, -29)
    }
    return start.timeInMillis to now
}

class PrayedTool : Tool {
    override val name = "prayed"
    override val title = "صليت"
    override val description =
        "The user says they prayed (\"صليت العصر\"). Records it as on time if its time hasn't ended, else late. " +
            "If they don't name the prayer, use the current/last one."
    override val parameters = Schema.obj("prayer" to Schema.string("fajr | dhuhr | asr | maghrib | isha (Arabic names OK)"))
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val p = Prayer.of(args.str("prayer")) ?: Prayers.current(ctx.app)?.first
            ?: return@withContext "Which prayer?"
        "${p.ar}: " + Prayers.confirm(ctx.app, p)
    }
}

class PrayerReportTool : Tool {
    override val name = "prayer_report"
    override val title = "الصلاة"
    override val description =
        "Today's prayer times and which prayers the user prayed (on time / in the mosque / late / unsure / not yet), " +
            "or a summary for the week or month."
    override val parameters = Schema.obj("period" to Schema.string("today | yesterday | week | month"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val period = args.str("period").ifBlank { "today" }
        if (period == "today") return@withContext Prayers.contextLine(ctx.app)
        val (from, to) = periodRange(period)
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rows = HabitStore.get(ctx.app).prayers(df.format(Date(from)), df.format(Date(to)))
        val days = ((to - from) / 86_400_000L + 1).toInt()
        val total = days * 5
        val by = rows.groupingBy { it.third }.eachCount()
        val missedFajr = days - rows.count { it.second == Prayer.FAJR }
        buildString {
            append("$period: ${rows.size}/$total prayers recorded. ")
            append(by.entries.joinToString(", ") { "${PrayerStatus.ar(it.key)}: ${it.value}" })
            append(". Not recorded: ${total - rows.size} (Fajr not recorded: $missedFajr).")
        }
    }
}

class LogHabitTool : Tool {
    override val name = "log_habit"
    override val title = "عادة"
    override val description =
        "Record a habit the user did: e.g. quran (pages), study (minutes), sleep (hours), water (cups), exercise (minutes), " +
            "or any other habit. Keep habit names short and consistent in English (quran, study, sleep, water, exercise…)."
    override val parameters = Schema.obj(
        "habit" to Schema.string("quran | study | sleep | water | exercise | … "),
        "amount" to Schema.number("How much"),
        "unit" to Schema.string("pages | minutes | hours | cups | …"),
        "note" to Schema.string("Optional, e.g. the subject studied"),
        required = listOf("habit", "amount"),
    )
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val h = args.str("habit").ifBlank { return@withContext "Missing habit" }
        val amount = args.optDouble("amount", 1.0)
        HabitStore.get(ctx.app).log(h, amount, args.str("unit").ifBlank { null }, args.str("note").ifBlank { null })
        val (from, to) = periodRange("today")
        val t = HabitStore.get(ctx.app).totals(from, to, h).firstOrNull()
        "Logged. Today $h: ${t?.second?.let { "%.1f".format(Locale.US, it) }} ${t?.third ?: ""}"
    }
}

class HabitReportTool : Tool {
    override val name = "habit_report"
    override val title = "العادات"
    override val description =
        "Totals of the user's habits (quran, study, sleep, water, exercise…) for today, yesterday, the week or the month, " +
            "plus steps walked. Use it to answer 'how much did I study this week' etc."
    override val parameters = Schema.obj(
        "period" to Schema.string("today | yesterday | week | month"),
        "habit" to Schema.string("Optional: one habit"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val (from, to) = periodRange(args.str("period").ifBlank { "today" })
        val rows = HabitStore.get(ctx.app).totals(from, to, args.str("habit").ifBlank { null })
        val steps = runCatching { com.myassistant.app.health.ActivityStore.get(ctx.app).stepsBetween(from, to) }.getOrDefault(0)
        buildString {
            if (rows.isEmpty()) append("No habits logged. ")
            rows.forEach { append("${it.first}: ${"%.1f".format(Locale.US, it.second)} ${it.third ?: ""}\n") }
            append("steps: $steps")
        }
    }
}

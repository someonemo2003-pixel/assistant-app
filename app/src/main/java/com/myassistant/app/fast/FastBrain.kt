package com.myassistant.app.fast

import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.core.ToolCall
import com.myassistant.app.core.newId
import com.myassistant.app.prayer.Prayer
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.ToolRegistry
import com.myassistant.app.tools.has
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The instant, offline brain. Handles simple requests in milliseconds using the same tools
 * as the AI, and returns null for anything it doesn't fully understand (→ cloud AI).
 */
class FastBrain(private val tools: ToolRegistry, private val ctx: ToolContext) {

    /** What the fast brain did last (so "لا مش ده" can undo the link). */
    private data class LastAction(val text: String, val experienceId: Long?, val how: String, val at: Long)
    @Volatile private var last: LastAction? = null

    suspend fun tryHandle(text: String): List<ChatMessage>? {
        if (BrainLog.isBlocked(ctx.app, text)) return null // the user said a fast answer to this was wrong
        val out = handle(text)
        if (out != null) {
            val how = lastHow
            last = LastAction(text, lastExperienceId, how, System.currentTimeMillis())
            BrainLog.add(ctx.app, "fast", text, how)
        }
        lastExperienceId = null; lastHow = "rule"
        return out
    }

    /**
     * "لا مش ده" right after a fast answer: forget that link and let the big brain handle it.
     * Returns true if a recent fast action was undone.
     */
    suspend fun onCorrection(userText: String): Boolean {
        val l = last ?: return false
        if (System.currentTimeMillis() - l.at > 3 * 60_000L || !isCorrection(userText)) return false
        last = null
        withContext(Dispatchers.IO) {
            // exact phrase learned wrongly → delete it; matched by meaning/template → just block this phrase
            if (l.how == "exact" && l.experienceId != null) ctx.experience.delete(l.experienceId)
            BrainLog.block(ctx.app, l.text)
            BrainLog.add(ctx.app, "corrected", l.text, l.how)
        }
        return true
    }

    private fun isCorrection(t: String): Boolean {
        val n = FastParser.normalize(t)
        if (n.length > 30) return false
        return listOf("لا مش ده", "مش ده", "لا مش كده", "مش كده", "غلط", "لا غلط", "مش ده اللي", "انا مقولتش", "لا لا").any { n.startsWith(it) } || n == "لا"
    }

    @Volatile private var lastExperienceId: Long? = null
    @Volatile private var lastHow = "rule"

    private suspend fun handle(text: String): List<ChatMessage>? {
        // Shortcuts the user taught come first, then built-in rules, then things learned from the AI.
        fromExperience(text, userOnly = true)?.let { return it }
        val intent = FastParser.parse(text)
            ?: return fromExperience(text, userOnly = false) ?: fromTemplate(text) ?: fromMeaning(text)
        return when (intent) {
            is LocalIntent.OpenCodeTask -> {
                val tool = tools["opencode_plan"] ?: return null
                val args = JSONObject().put("task", intent.task)
                val r = tool.execute(args, ctx)
                val plan = r.removePrefix("PLAN (nothing was changed yet):").trim()
                val failed = com.myassistant.app.terminal.OpenCode.lastPlanAt == 0L
                actionMessages(
                    "opencode_plan", args, r,
                    if (failed) "OpenCode مقدرش يعمل خطة:\n$plan"
                    else "خطة OpenCode (لسه ماتغيّرش حاجة):\n\n$plan\n\nقول «نفّذ» علشان يبدأ، أو اكتبله تعديلك.",
                )
            }
            is LocalIntent.OpenCodeGo -> {
                if (!com.myassistant.app.terminal.OpenCode.hasFreshPlan()) return null
                val tool = tools["opencode_do"] ?: return null
                val args = JSONObject().put("summary", "الخطة اللي OpenCode عملها")
                val r = tool.execute(args, ctx)
                com.myassistant.app.terminal.OpenCode.lastPlanAt = 0L
                actionMessages("opencode_do", args, r, "OpenCode خلّص:\n\n" + r.substringAfter('\n').trim().takeLast(3000))
            }
            is LocalIntent.AboutMe -> {
                val items = withContext(Dispatchers.IO) { ctx.memory.recent(2000) }
                listOf(reply(if (items.isEmpty()) "لسه معرفش عنك حاجة. احكيلي عن نفسك وأنا هفتكر." else aboutMe(items)))
            }
            is LocalIntent.AskTime -> listOf(reply("الساعة دلوقتي ${clock(nowHour(), nowMinute())}"))
            is LocalIntent.AskDate -> listOf(reply("النهارده ${SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar", "EG")).format(Date())}"))
            is LocalIntent.Alarm -> run(
                "set_alarm", JSONObject().put("hour", intent.hour).put("minute", intent.minute),
                ok = { it.startsWith("Alarm set") },
                success = "تمام، ضبطتلك منبه ${clock(intent.hour, intent.minute)} ⏰",
                failure = "معرفتش أضبط المنبه، تطبيق الساعة مستجابش.",
            )
            is LocalIntent.Timer -> run(
                "set_timer", JSONObject().put("seconds", intent.seconds),
                ok = { it.startsWith("Timer started") },
                success = "اشتغل مؤقت ${duration(intent.seconds)} ⏱️",
                failure = "معرفتش أشغّل المؤقت.",
            )
            is LocalIntent.Remember -> run(
                "remember", JSONObject().put("fact", intent.fact),
                ok = { it == "Saved" },
                success = "حفظتها ✓",
                failure = "معرفتش أحفظها.",
            )
            is LocalIntent.OpenApp -> openApp(intent.names)
            is LocalIntent.Steps -> {
                val tool = tools["steps"] ?: return null
                if (!ctx.has(tool.permissions)) return null
                val args = JSONObject().put("period", intent.period)
                val r = tool.execute(args, ctx)
                val m = Regex("(\\d+) steps \\(≈ ([\\d.]+) km").findAll(r).lastOrNull() ?: return null
                val label = when (intent.period) { "yesterday" -> "امبارح"; "week" -> "الأسبوع ده"; else -> "النهارده" }
                val total = if (intent.period == "week") Regex("Total: (\\d+)").find(r)?.groupValues?.get(1) ?: m.groupValues[1] else m.groupValues[1]
                actionMessages("steps", args, r, "مشيت $label $total خطوة 👣" + if (intent.period != "week") " (حوالي ${m.groupValues[2]} كم)" else "")
            }
            is LocalIntent.Walk -> {
                val tool = tools["walk_tracking"] ?: return null
                if (!ctx.has(tool.permissions)) return null
                val args = JSONObject().put("action", if (intent.start) "start" else "stop")
                val r = tool.execute(args, ctx)
                val text = when {
                    r.startsWith("Walk tracking started") -> "بدأت أتتبّع مشيك 🚶 — قولّي \"وقّف المشي\" لما تخلص"
                    r.startsWith("Walk saved") -> Regex("([\\d.]+) km, (\\d+) steps, (\\d+) min").find(r)?.let {
                        "خلصت ✓ مشيت ${it.groupValues[1]} كم، ${it.groupValues[2]} خطوة في ${it.groupValues[3]} دقيقة 🗺️"
                    } ?: "وقّفت التتبّع ✓"
                    r.startsWith("Already") -> "أنا بتتبّعك فعلاً 🚶"
                    else -> "مفيش تتبّع شغال دلوقتي"
                }
                actionMessages("walk_tracking", args, r, text)
            }
            is LocalIntent.Places -> {
                val tool = tools["places_visited"] ?: return null
                val args = JSONObject().put("day", intent.day)
                val r = tool.execute(args, ctx)
                actionMessages("places_visited", args, r, if (r.startsWith("No places")) "مفيش أماكن متسجّلة لليوم ده." else r)
            }
            is LocalIntent.Prayed -> {
                val tool = tools["prayed"] ?: return null
                val args = JSONObject().apply { intent.prayer?.let { put("prayer", it) } }
                val r = tool.execute(args, ctx)
                if (r == "Which prayer?") return null
                actionMessages("prayed", args, r, "تقبل الله ✓ (" + r + ")")
            }
            is LocalIntent.PrayerTime -> listOf(reply(prayerTimeText(intent)))
            is LocalIntent.Habit -> {
                val tool = tools["log_habit"] ?: return null
                val args = JSONObject().put("habit", intent.habit).put("amount", intent.amount).put("unit", intent.unit)
                val r = tool.execute(args, ctx)
                actionMessages("log_habit", args, r, "سجلتها ✓")
            }
            is LocalIntent.SavePlace -> {
                val tool = tools["save_place"] ?: return null
                if (!ctx.has(tool.permissions)) return null
                val args = JSONObject().put("name", intent.name)
                val r = tool.execute(args, ctx)
                actionMessages(
                    "save_place", args, r,
                    when {
                        r.startsWith("Saved") -> "سجلت المكان ده باسم \"${intent.name}\" ✓"
                        r.startsWith("Location is too vague") -> "الموقع مش دقيق دلوقتي — افتح الواي فاي أو الـ GPS وقولّي تاني."
                        else -> "معرفتش أجيب مكانك دلوقتي، اتأكد إن الموقع شغال."
                    },
                )
            }
            is LocalIntent.Silent -> {
                val tool = tools["set_volume"] ?: return null
                val pct = if (intent.on) 0 else 70
                val args = JSONObject().put("stream", "ring").put("percent", pct)
                val r = tool.execute(args, ctx)
                runCatching { tool.execute(JSONObject().put("stream", "notification").put("percent", pct), ctx) }
                actionMessages("set_volume", args, r, if (intent.on) "الموبايل بقى صامت 🔕" else "رجّعت الصوت 🔔")
            }
            is LocalIntent.Battery -> {
                val bm = ctx.app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                listOf(reply("البطارية $level%" + if (bm.isCharging) " وبتشحن ⚡" else ""))
            }
            is LocalIntent.LockPhone -> run(
                "lock_phone", JSONObject(), ok = { it.startsWith("🔒") }, success = "قفلت الشاشة 🔒", failure = "معرفتش أقفل الشاشة.",
            )
            is LocalIntent.CheckUpdate -> {
                val tool = tools["check_update"] ?: return null
                val r = tool.execute(JSONObject(), ctx)
                actionMessages("check_update", JSONObject(), r, r)
            }
            is LocalIntent.Flashlight -> run(
                "flashlight", JSONObject().put("on", intent.on.toString()),
                ok = { it.startsWith("Flashlight o") },
                success = if (intent.on) "ولّعت الكشاف 🔦" else "طفيت الكشاف",
                failure = "معرفتش أتحكم في الكشاف.",
            )
        }
    }

    /** Replays something learned from the big brain or taught by the user. */
    private suspend fun fromExperience(text: String, userOnly: Boolean): List<ChatMessage>? {
        val exp = withContext(Dispatchers.IO) { ctx.experience.find(text) } ?: return null
        if (userOnly && exp.source != "user") return null
        val tool = tools[exp.tool]?.takeIf { it.learnable } ?: return null
        val args = runCatching { JSONObject(exp.args) }.getOrNull() ?: return null
        val result = tool.execute(args, ctx)
        val failed = listOf("Error", "Missing", "Invalid", "No ", "Cannot", "App not found").any { result.startsWith(it) }
        if (failed) return null // let the big brain try instead
        withContext(Dispatchers.IO) { ctx.experience.markUsed(exp.id) }
        lastExperienceId = exp.id; lastHow = "exact"
        return actionMessages(exp.tool, args, result, exp.reply)
    }

    /**
     * Same request with one word swapped, where that word is what the action used:
     * learned "كلم أحمد" (contact=أحمد) + "كلم محمد" → call محمد. No AI, no meaning model needed.
     */
    private suspend fun fromTemplate(text: String): List<ChatMessage>? {
        val all = withContext(Dispatchers.IO) { ctx.experience.all() }
        val candidates = all.mapNotNull { e -> Templates.adapt(e.phrase, e.args, text, requireChange = true)?.let { e to it } }
            .sortedByDescending { it.first.uses }
        val (exp, argsJson) = candidates.firstOrNull() ?: return null
        // two different learned actions fit → not sure, let the big brain decide
        if (candidates.drop(1).any { it.first.tool != exp.tool }) return null
        return replay(exp, argsJson, "template", text)
    }

    /** Same request said differently: matched by the on-device meaning model. Never replays with a different name/number. */
    private suspend fun fromMeaning(text: String): List<ChatMessage>? {
        val exp = ctx.recall.similarExperience(text) ?: return null
        val argsJson = Templates.adapt(exp.phrase, exp.args, text, requireChange = false) ?: return null
        return replay(exp, argsJson, "meaning", text)
    }

    private suspend fun replay(exp: Experience, argsJson: String, how: String, text: String): List<ChatMessage>? {
        val tool = tools[exp.tool]?.takeIf { it.learnable } ?: return null
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val result = tool.execute(args, ctx)
        val failed = listOf("Error", "Missing", "Invalid", "No ", "Cannot", "App not found").any { result.startsWith(it) }
        if (failed) return null
        withContext(Dispatchers.IO) { ctx.experience.markUsed(exp.id) }
        lastExperienceId = exp.id; lastHow = how
        val reply = if (argsJson == exp.args) exp.reply else Templates.adaptReply(exp.reply, exp.phrase, text)
        return actionMessages(exp.tool, args, result, reply)
    }

    private fun prayerTimeText(i: LocalIntent.PrayerTime): String {
        val times = com.myassistant.app.prayer.Prayers.times(ctx.app, 0)
        val tf = SimpleDateFormat("h:mm", Locale("ar", "EG"))
        val now = System.currentTimeMillis()
        if (i.prayer == null) {
            if (i.remaining) {
                val next = Prayer.entries.firstOrNull { times.at(it) > now }
                val at = next?.let { times.at(it) } ?: com.myassistant.app.prayer.Prayers.times(ctx.app, 1).fajr
                return "فاضل ${minutesText(at - now)} على ${(next ?: Prayer.FAJR).ar}"
            }
            return Prayer.entries.joinToString("\n") { "${it.ar}: ${tf.format(Date(times.at(it)))}" }
        }
        val p = Prayer.of(i.prayer) ?: return "مش فاهم أنهي صلاة"
        var at = times.at(p)
        if (at < now) at = com.myassistant.app.prayer.Prayers.times(ctx.app, 1).at(p)
        return if (i.remaining) "فاضل ${minutesText(at - now)} على ${p.ar}" else "${p.ar} الساعة ${tf.format(Date(at))}"
    }

    private fun minutesText(ms: Long): String {
        val m = (ms / 60_000L).toInt()
        val h = m / 60
        return when {
            h == 0 -> "$m دقيقة"
            m % 60 == 0 -> if (h == 1) "ساعة" else "$h ساعات"
            else -> (if (h == 1) "ساعة" else "$h ساعات") + " و${m % 60} دقيقة"
        }
    }

    private suspend fun openApp(names: List<String>): List<ChatMessage>? {
        val tool = tools["open_app"] ?: return null
        for (name in names) {
            val args = JSONObject().put("app_name", name)
            val result = tool.execute(args, ctx)
            if (result.startsWith("Opened")) {
                return actionMessages("open_app", args, result, "فتحتلك ${result.removePrefix("Opened ").trim()} ✓")
            }
        }
        return null // not sure which app → let the AI figure it out
    }

    private suspend fun run(
        toolName: String,
        args: JSONObject,
        ok: (String) -> Boolean,
        success: String,
        failure: String,
    ): List<ChatMessage>? {
        val tool = tools[toolName] ?: return null
        val result = tool.execute(args, ctx)
        return actionMessages(toolName, args, result, if (ok(result)) success else failure)
    }

    /** Same shape the AI produces (call → result → reply), so the conversation stays consistent. */
    private fun actionMessages(toolName: String, args: JSONObject, result: String, text: String): List<ChatMessage> {
        val call = ToolCall("local-" + newId(), toolName, args.toString())
        return listOf(
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call), providerName = NAME),
            ChatMessage(role = Role.TOOL, text = result, toolCallId = call.id, toolName = toolName),
            reply(text),
        )
    }

    /** What Nova remembers, grouped by folder (newest first, a few per folder). */
    private fun aboutMe(items: List<com.myassistant.app.memory.MemoryItem>): String {
        val cats = com.myassistant.app.memory.MemoryCategories
        val byCat = items.groupBy { cats.normalize(it.category) }
        return buildString {
            append("ده اللي أعرفه عنك (").append(items.size).append(" حاجة):")
            for (key in cats.all.keys) {
                val list = byCat[key] ?: continue
                val label = cats.label(key).substringAfter(' ')
                append("\n\n").append(label).append(":")
                list.take(8).forEach { append("\n• ").append(it.text) }
                if (list.size > 8) append("\n… و").append(list.size - 8).append(" كمان")
            }
            append("\n\nعايز تعدّل أو تمسح حاجة؟ قولّي.")
        }
    }

    private fun reply(text: String) = ChatMessage(role = Role.ASSISTANT, text = text, providerName = NAME)

    private fun nowHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun nowMinute() = Calendar.getInstance().get(Calendar.MINUTE)

    companion object {
        const val NAME = "⚡ سريع"

        fun clock(hour: Int, minute: Int): String {
            val h12 = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val period = when (hour) {
                in 0..3 -> "بالليل"
                in 4..11 -> "الصبح"
                in 12..14 -> "الضهر"
                in 15..17 -> "العصر"
                else -> "بالليل"
            }
            return String.format(Locale.US, "%d:%02d %s", h12, minute, period)
        }

        fun duration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return buildList {
                if (h > 0) add(if (h == 1) "ساعة" else "$h ساعات")
                if (m > 0) add(if (m == 1) "دقيقة" else "$m دقايق")
                if (s > 0) add("$s ثانية")
            }.joinToString(" و ")
        }
    }
}

package com.myassistant.app.fast

import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.core.ToolCall
import com.myassistant.app.core.newId
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.ToolRegistry
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

    suspend fun tryHandle(text: String): List<ChatMessage>? {
        // Shortcuts the user taught come first, then built-in rules, then things learned from the AI.
        fromExperience(text, userOnly = true)?.let { return it }
        val intent = FastParser.parse(text) ?: return fromExperience(text, userOnly = false)
        return when (intent) {
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
        return actionMessages(exp.tool, args, result, exp.reply)
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

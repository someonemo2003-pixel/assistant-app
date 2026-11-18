package com.myassistant.app.settings

import com.myassistant.app.AssistantApp
import com.myassistant.app.mail.DailyReport
import com.myassistant.app.semantic.ModelState
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import com.myassistant.app.ui.UiRequests
import com.myassistant.app.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** There is no settings page: everything is read and changed from chat through these tools. */
private fun graph(ctx: ToolContext) = (ctx.app.applicationContext as AssistantApp).graph

class AppStatusTool : Tool {
    override val name = "app_settings"
    override val title = "إعدادات Nova"
    override val description =
        "Nova's own settings and status: version, auto-update, AI providers (name, model, on/off, has key), chat/action brain, " +
            "voice replies, hands-free, learning, auto memory, daily Gmail report, meaning model, device owner."
    override val parameters = Schema.obj()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val g = graph(ctx)
        val s = ctx.settings
        val prov = g.providers.configs.value.joinToString("\n") { c ->
            "  - ${c.name} (id ${c.id}) model=${c.model} ${if (c.enabled) "on" else "off"} key=${if (g.providers.hasKey(c.id)) "yes" else "no"}"
        }
        fun brain(id: String) = g.providers.configs.value.firstOrNull { it.id == id }?.name ?: "default order"
        return buildString {
            appendLine("version: ${Updater.current(ctx.app)}, auto_update: ${Updater.autoOn(ctx.app)}")
            appendLine("providers (tried in this order):\n$prov")
            appendLine("chat_brain: ${brain(s.chatBrain.value)}, action_brain: ${brain(s.actionBrain.value)}")
            appendLine("speak_replies: ${s.speakReplies.value}, hands_free: ${s.handsFree.value}")
            appendLine("learn_from_ai: ${s.learnFromAi.value}, auto_memory: ${s.autoMemory.value}")
            appendLine("daily_report: ${DailyReport.enabled(ctx.app)} at " + String.format(Locale.US, "%02d:%02d", DailyReport.hour(ctx.app), DailyReport.minute(ctx.app)))
            appendLine("meaning_model: ${when (val m = g.meaning.state.value) { is ModelState.Ready -> "ready"; is ModelState.NotDownloaded -> "not downloaded"; is ModelState.Downloading -> "downloading"; is ModelState.Failed -> "failed: ${m.message}"; else -> "loading" }}")
            appendLine("device_owner: ${com.myassistant.app.admin.DeviceOwner.isOwner(ctx.app)}")
        }
    }
}

class ChangeSettingTool : Tool {
    override val name = "change_setting"
    override val title = "تغيير إعداد"
    override val description =
        "Change one of Nova's settings. name: speak_replies | hands_free | learn_from_ai | auto_memory | auto_update (value on/off); " +
            "daily_report (on/off) | report_time (HH:mm); chat_brain | action_brain (provider name, or 'default'); " +
            "provider_on | provider_off (provider name); provider_model (value 'ProviderName=model-id'); " +
            "meaning_model (download/delete)."
    override val parameters = Schema.obj(
        "name" to Schema.string("Setting name"),
        "value" to Schema.string("New value"),
        required = listOf("name"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val g = graph(ctx)
        val s = ctx.settings
        val name = args.str("name").lowercase()
        val v = args.str("value")
        val on = v.lowercase() in setOf("on", "true", "yes", "1", "شغال", "اه", "أيوه")
        fun provider(n: String) = g.providers.configs.value.firstOrNull { it.name.equals(n.trim(), true) || it.id.equals(n.trim(), true) }
            ?: g.providers.configs.value.firstOrNull { it.name.contains(n.trim(), true) }
        return when (name) {
            "speak_replies" -> { s.setSpeakReplies(on); "OK" }
            "hands_free" -> { s.setHandsFree(on); "OK" }
            "learn_from_ai" -> { s.setLearnFromAi(on); "OK" }
            "auto_memory" -> { s.setAutoMemory(on); "OK" }
            "auto_update" -> { Updater.setAuto(ctx.app, on); "OK" }
            "daily_report" -> { DailyReport.configure(ctx.app, on, DailyReport.hour(ctx.app), DailyReport.minute(ctx.app)); "OK" }
            "report_time" -> {
                val m = Regex("(\\d{1,2}):(\\d{2})").find(v) ?: return "Use HH:mm"
                DailyReport.configure(ctx.app, true, m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].toInt().coerceIn(0, 59)); "OK"
            }
            "chat_brain", "action_brain" -> {
                val id = if (v.isBlank() || v.equals("default", true)) "" else provider(v)?.id ?: return "No provider named \"$v\""
                if (name == "chat_brain") s.setChatBrain(id) else s.setActionBrain(id); "OK"
            }
            "provider_on", "provider_off" -> {
                val c = provider(v) ?: return "No provider named \"$v\""
                g.providers.upsert(c.copy(enabled = name == "provider_on")); "OK"
            }
            "provider_model" -> {
                val (n, model) = v.split('=', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                val c = provider(n) ?: return "No provider named \"$n\""
                if (model.isBlank()) return "Missing model"
                g.providers.upsert(c.copy(model = model.trim())); "OK"
            }
            "meaning_model" -> {
                if (v.lowercase().startsWith("del")) withContext(Dispatchers.IO) { g.meaning.delete() }
                else g.appScope.launch { g.meaning.download(); runCatching { g.recall.backfill() } }
                "OK (downloading in the background)"
            }
            else -> "Unknown setting \"$name\""
        }
    }
}

class CheckUpdateTool : Tool {
    override val name = "check_update"
    override val title = "تحديث Nova"
    override val description = "Check GitHub for a newer version of Nova and install it (silently as device owner)."
    override val parameters = Schema.obj()
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = Updater.checkAndInstall(ctx.app)
}

class OpenSetupTool : Tool {
    override val name = "open_setup"
    override val title = "فتح شاشة"
    override val description =
        "Open a small screen in Nova when something can't be done by chat: 'providers' (add an AI provider / paste an API key — " +
            "keys must NEVER be typed in chat), 'gmail' (Gmail address + app password), 'backup' (save/restore a backup file), " +
            "'permissions', 'learned' (commands Nova learned), 'data' (memory, expenses, reminders), 'chats'."
    override val parameters = Schema.obj("section" to Schema.string("providers | gmail | backup | permissions | learned | data | chats"), required = listOf("section"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val s = args.str("section").lowercase().ifBlank { "providers" }
        UiRequests.open(s)
        return "Opened $s"
    }
}

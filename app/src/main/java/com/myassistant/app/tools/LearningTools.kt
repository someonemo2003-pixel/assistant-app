package com.myassistant.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets the user teach the fast brain a personal shortcut: "لما أقول X اعمل Y". */
class TeachShortcutTool(private val actions: Map<String, String>) : Tool {
    override val name = "teach_shortcut"
    override val title = "تعليم اختصار"
    override val description =
        "Save a personal shortcut so the phrase runs an action instantly next time, offline. " +
            "Use when the user says things like 'when I say X do Y' / 'لما أقول X اعمل Y'. " +
            "Available actions: " + actions.entries.joinToString { "${it.key} (${it.value})" } + "."
    override val parameters = Schema.obj(
        "phrase" to Schema.string("The exact trigger phrase the user will say"),
        "action" to Schema.string("One of: " + actions.keys.joinToString()),
        "args_json" to Schema.string("The action's arguments as a JSON object string, e.g. {\"hour\":7,\"minute\":0}"),
        "reply" to Schema.string("Short confirmation to show when the shortcut runs, in the user's language"),
        required = listOf("phrase", "action", "args_json"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val phrase = args.str("phrase")
        val action = args.str("action")
        if (phrase.isEmpty()) return "Missing phrase"
        if (action !in actions) return "Unknown action. Use one of: ${actions.keys.joinToString()}"
        val actionArgs = runCatching { JSONObject(args.str("args_json").ifEmpty { "{}" }) }.getOrNull()
            ?: return "args_json is not valid JSON"
        val reply = args.str("reply").ifEmpty { "تم ✓" }
        val ok = withContext(Dispatchers.IO) { ctx.experience.learn(phrase, action, actionArgs.toString(), reply, "user") }
        return if (ok) "Shortcut saved: \"$phrase\" → $action" else "Could not save the shortcut"
    }
}

/** Lets the big brain look things up in past conversations stored on the phone. */
class SearchChatsTool : Tool {
    override val name = "search_chats"
    override val title = "بحث في الشاتات"
    override val description = "Search the user's past conversations (stored on the phone) for messages about a topic."
    override val parameters = Schema.obj(
        "query" to Schema.string("Keywords to search for"),
        required = listOf("query"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val hits = withContext(Dispatchers.IO) {
            ctx.chats.search(args.str("query"), 8, excludeConv = ctx.currentConversation)
        }
        if (hits.isEmpty()) return "No matching messages in past chats"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return hits.joinToString("\n") { h ->
            val who = if (h.role.name == "USER") "user" else "assistant"
            "[${fmt.format(Date(h.createdAt))} · ${h.conversationTitle}] $who: ${h.text.take(300)}"
        }
    }
}

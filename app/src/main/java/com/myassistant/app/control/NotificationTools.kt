package com.myassistant.app.control

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

private const val OFF = "Notification access is OFF. Tell the user: ⚙️ → الصلاحيات → قراءة الإشعارات."

private fun line(n: SavedNotification, fmt: SimpleDateFormat, app: String = n.app): String {
    val where = if (n.conversation.isNotBlank() && n.conversation != n.sender) " in \"${n.conversation}\"" else ""
    val kind = if (n.isEmail) "📧 " else ""
    return "[${fmt.format(Date(n.time))}] $kind$app · ${n.sender}$where: ${n.text}"
}

class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val title = "الإشعارات"
    override val description =
        "Search the user's saved notifications: messages (WhatsApp, Messenger, Telegram, SMS...), emails (Gmail and other " +
            "email apps — sender, subject and preview), and app alerts. Filter by person, app, or words; by time; or emails only."
    override val parameters = Schema.obj(
        "filter" to Schema.string("Optional: person, group, app name, or words to look for"),
        "hours" to Schema.integer("How far back, in hours (default 24, max 720)"),
        "type" to Schema.string("all | messages | emails"),
        "limit" to Schema.integer("Max items, default 25"),
        "account" to Schema.string("Optional: only one account/copy of an app, e.g. 'توأم' (WhatsApp twin) or a name the user gave it"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!NotificationReader.isEnabled(ctx.app)) return@withContext OFF
        val hours = (args.int("hours") ?: 24).coerceIn(1, 720)
        val type = args.str("type")
        val store = NotificationStore.get(ctx.app)
        val accs = args.str("account").takeIf { it.isNotBlank() }?.let { Accounts.match(ctx.app, it) }
        fun q(pkgs: List<String> = emptyList(), account: String? = null) = store.query(
            sinceMs = System.currentTimeMillis() - hours * 3_600_000L,
            filter = args.str("filter"),
            onlyEmail = type == "emails",
            onlyMessages = type == "messages",
            limit = (args.int("limit") ?: 25).coerceIn(1, 100),
            pkgs = pkgs, account = account,
        )
        if (accs != null && accs.isEmpty()) return@withContext "No account matches \"${args.str("account")}\""
        val list = accs?.flatMap { (p, a) -> q(listOf(p), a) }?.sortedByDescending { it.time } ?: q()
        if (list.isEmpty()) return@withContext "Nothing found in the last $hours hours"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        list.joinToString("\n") { line(it, fmt, Accounts.label(ctx.app, it)) }
    }
}

class MissedMessagesTool : Tool {
    override val name = "missed_messages"
    override val title = "اللي فاتك"
    override val description =
        "Collect messages and emails received in the last N hours (default 12), grouped by app and conversation, so you can " +
            "summarise what the user missed and who is waiting for a reply."
    override val parameters = Schema.obj("hours" to Schema.integer("How far back, default 12"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!NotificationReader.isEnabled(ctx.app)) return@withContext OFF
        val hours = (args.int("hours") ?: 12).coerceIn(1, 168)
        val since = System.currentTimeMillis() - hours * 3_600_000L
        val store = NotificationStore.get(ctx.app)
        val items = (store.query(since, onlyMessages = true, limit = 300) + store.query(since, onlyEmail = true, limit = 100))
            .distinctBy { it.id }
        if (items.isEmpty()) return@withContext "No messages or emails in the last $hours hours"
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        buildString {
            appendLine("${items.size} items in the last $hours hours:")
            items.groupBy { Accounts.label(ctx.app, it) to (it.conversation.ifBlank { it.sender }) }
                .entries.sortedByDescending { e -> e.value.maxOf { it.time } }
                .take(30)
                .forEach { (k, v) ->
                    appendLine("== ${k.first} · ${k.second} (${v.size}) ==")
                    v.sortedBy { it.time }.takeLast(8).forEach { appendLine("  ${fmt.format(Date(it.time))} ${it.sender}: ${it.text.take(300)}") }
                }
        }
    }
}

class ReplyNotificationTool : Tool {
    override val name = "reply_message"
    override val title = "رد على رسالة"
    override val description =
        "Send a reply to someone: first through the notification (instant, app stays closed); if the notification is gone, " +
            "it sends by itself through WhatsApp (opens the chat and presses Send). 'to' is the sender/group name or a phone number."
    override val parameters = Schema.obj(
        "to" to Schema.string("Sender or group name as shown in the notification"),
        "message" to Schema.string("Reply text"),
        "app" to Schema.string("Optional app name, e.g. WhatsApp"),
        required = listOf("to", "message"),
    )
    override val requiresConfirmation = true
    override fun describe(args: JSONObject) = "رد على ${args.str("to")}: \"${args.str("message").take(80)}\""

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val reader = NotificationReader.current
            ?: return WhatsAppSender.send(ctx, args.str("to"), args.str("message"))
        val to = args.str("to").lowercase()
        val app = args.str("app").lowercase()
        val candidates = reader.live().filter { sbn ->
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().lowercase()
            val conv = ex.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty().lowercase()
            val label = runCatching {
                ctx.app.packageManager.getApplicationLabel(ctx.app.packageManager.getApplicationInfo(sbn.packageName, 0)).toString().lowercase()
            }.getOrDefault(sbn.packageName)
            (title.contains(to) || conv.contains(to)) && (app.isEmpty() || label.contains(app)) &&
                sbn.notification.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
        }
        val target = candidates.maxByOrNull { it.postTime }
        if (target != null && reader.reply(target, args.str("message"))) return "Reply sent to ${args.str("to")}"
        // Notification gone: do it inside the app ourselves instead of asking the user.
        val isWhatsApp = app.isEmpty() || app.contains("whats") || app.contains("واتس")
        if (isWhatsApp) return WhatsAppSender.send(ctx, args.str("to"), args.str("message"))
        return "The notification is gone. Do it yourself now with open_app ${args.str("app")} + read_screen + do_steps " +
            "(search the chat, type the message, tap send). Do NOT ask the user to do it."
    }
}

class ClearNotificationsTool : Tool {
    override val name = "clear_notifications"
    override val title = "تنضيف الإشعارات"
    override val description =
        "Dismiss notifications from the shade, or mark chats as read. Filter by app or words; empty filter = all."
    override val parameters = Schema.obj(
        "filter" to Schema.string("Optional app name or words"),
        "mark_read" to Schema.string("'true' to mark messages as read (where the app supports it) instead of just dismissing"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val reader = NotificationReader.current ?: return OFF
        val f = args.str("filter").lowercase()
        val pm = ctx.app.packageManager
        val targets = reader.live().filter { sbn ->
            if (f.isEmpty()) return@filter true
            val ex = sbn.notification.extras
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            listOf(label, ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty(), ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty())
                .any { it.lowercase().contains(f) }
        }
        if (targets.isEmpty()) return "No matching notifications"
        var done = 0
        for (t in targets) {
            val ok = if (args.str("mark_read") == "true") reader.markRead(t) || reader.dismiss(t) else reader.dismiss(t)
            if (ok) done++
        }
        return "Done for $done of ${targets.size} notifications"
    }
}

class NotificationAlertTool : Tool {
    override val name = "notification_alert"
    override val title = "تنبيه خاص"
    override val description =
        "Manage special alerts: when a new message/email/notification matches a person or keyword, the user gets a loud alert. " +
            "action: add (needs match), list, or remove (needs id)."
    override val parameters = Schema.obj(
        "action" to Schema.string("add | list | remove"),
        "match" to Schema.string("Person name or keyword to watch for"),
        "app" to Schema.string("Optional app to limit it to"),
        "id" to Schema.integer("Rule id to remove"),
        required = listOf("action"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val store = NotificationStore.get(ctx.app)
        when (args.str("action")) {
            "add" -> {
                val m = args.str("match").ifEmpty { return@withContext "Missing match" }
                val id = store.addAlert(m, args.str("app"))
                "Alert #$id added for \"$m\"" + (args.str("app").takeIf { it.isNotEmpty() }?.let { " in $it" } ?: "") +
                    if (!NotificationReader.isEnabled(ctx.app)) ". NOTE: notification access is OFF, enable it for alerts to work." else ""
            }
            "remove" -> {
                val id = args.int("id")?.toLong() ?: return@withContext "Missing id"
                store.removeAlert(id)
                "Alert #$id removed"
            }
            else -> store.alerts().takeIf { it.isNotEmpty() }?.joinToString("\n") { "#${it.id}: \"${it.match}\"" + if (it.app.isNotEmpty()) " (${it.app})" else "" }
                ?: "No alerts set"
        }
    }
}

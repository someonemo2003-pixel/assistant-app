package com.myassistant.app.control

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SeenNotification(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

/** Keeps the recent notifications (WhatsApp messages, emails, ...) so the assistant can read them. */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications?.forEach { remember(it) } }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { remember(it) }
    }

    private fun remember(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(recent) {
            recent.removeAll { it.pkg == sbn.packageName && it.title == title && it.text == text }
            recent.addFirst(SeenNotification(app, sbn.packageName, title, text.take(500), sbn.postTime))
            while (recent.size > 200) recent.removeLast()
        }
    }

    companion object {
        @Volatile var connected = false
            private set
        private val recent = ArrayDeque<SeenNotification>()

        fun snapshot(): List<SeenNotification> = synchronized(recent) { recent.toList() }
    }
}

class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val title = "الإشعارات"
    override val description =
        "Read the user's recent notifications (messages from WhatsApp/Messenger/SMS, emails, app alerts). " +
            "Optionally filter by app name or sender."
    override val parameters = Schema.obj(
        "filter" to Schema.string("Optional app name or sender to filter by"),
        "limit" to Schema.integer("How many, default 15"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        if (!NotificationReader.connected && NotificationReader.snapshot().isEmpty()) {
            return "Notification access is OFF. Tell the user: ⚙️ → الصلاحيات → قراءة الإشعارات."
        }
        val f = args.str("filter").lowercase()
        val list = NotificationReader.snapshot()
            .filter { f.isEmpty() || it.app.lowercase().contains(f) || it.title.lowercase().contains(f) || it.pkg.contains(f) }
            .take((args.int("limit") ?: 15).coerceIn(1, 50))
        if (list.isEmpty()) return "No matching notifications"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return list.joinToString("\n") { "[${fmt.format(Date(it.time))}] ${it.app} · ${it.title}: ${it.text}" }
    }
}

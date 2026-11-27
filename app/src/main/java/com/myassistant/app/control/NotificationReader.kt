package com.myassistant.app.control

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myassistant.app.MainActivity
import java.lang.ref.WeakReference

/**
 * Reads every notification (WhatsApp, Messenger, Telegram, SMS, Gmail and other email apps, ...),
 * saves it on the phone, raises the user's custom alerts, and can reply / dismiss / mark as read.
 */
class NotificationReader : NotificationListenerService() {

    private val store by lazy { NotificationStore.get(this) }

    override fun onListenerConnected() {
        instance = WeakReference(this)
        runCatching { activeNotifications?.forEach { handle(it, alert = false) } }
    }

    override fun onListenerDisconnected() {
        if (instance?.get() === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { runCatching { handle(it, alert = true) } }
    }

    private fun handle(sbn: StatusBarNotification, alert: Boolean) {
        if (sbn.packageName == packageName) return
        // muted by the user → remove it right away (backup for when the system-level switch isn't available)
        if (alert && sbn.packageName in com.myassistant.app.admin.DeviceOwner.mutedApps(this)) {
            runCatching { cancelNotification(sbn.key) }
            return
        }
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val app = appLabel(sbn.packageName)
        val isEmail = n.category == Notification.CATEGORY_EMAIL || sbn.packageName in EMAIL_APPS
        val fresh = mutableListOf<SavedNotification>()

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val account = accountOf(sbn, extras, style)
        if (style != null && style.messages.isNotEmpty()) {
            val conv = style.conversationTitle?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            for (m in style.messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sender = m.person?.name?.toString() ?: conv
                val item = SavedNotification(0, sbn.key, sbn.packageName, app, conv, sender, text.take(1500), m.timestamp, isEmail, true, account)
                if (store.save(item)) fresh += item
            }
        } else {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            val texts = if (lines.isNotEmpty() && body.isEmpty()) lines else listOf(body)
            val isMessage = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGE_APPS
            for (t in texts) {
                if (title.isEmpty() && t.isEmpty()) continue
                val item = SavedNotification(0, sbn.key, sbn.packageName, app, sub, title, t.take(1500), sbn.postTime, isEmail, isMessage, account)
                if (store.save(item)) {
                    fresh += item
                    if (!isMessage && !isEmail) {
                        runCatching { com.myassistant.app.finance.ExpenseSync.fromNotification(this, app, sbn.packageName, "$title $t", sbn.postTime) }
                    }
                }
            }
        }
        if (alert && fresh.isNotEmpty()) checkAlerts(fresh)
    }

    /** "u<user>" (twin copies run as another Android user) + "|<the account's own name>" when the app tells us. */
    private fun accountOf(sbn: StatusBarNotification, extras: Bundle, style: NotificationCompat.MessagingStyle?): String {
        val user = "u" + sbn.user.hashCode()
        if (sbn.packageName !in MESSAGE_APPS) return user
        val generic = setOf("you", "me", "أنت", "انت", "أنا", "انا")
        val candidates = listOf(
            style?.user?.name?.toString(),
            extras.getCharSequence("android.selfDisplayName")?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
        val self = candidates.map { it?.trim().orEmpty() }.firstOrNull { c ->
            c.isNotEmpty() && c.lowercase() !in generic && c.length <= 40 &&
                !Regex("\\d+\\s*(new )?(messages?|رسائل|رسالة|رسايل|محادثات|chats?)", RegexOption.IGNORE_CASE).containsMatchIn(c)
        }
        return if (self != null) "$user|$self" else user
    }

    private fun checkAlerts(items: List<SavedNotification>) {
        val rules = store.alerts()
        if (rules.isEmpty()) return
        for (item in items) {
            val rule = rules.firstOrNull { r ->
                (r.app.isEmpty() || item.app.contains(r.app, ignoreCase = true)) &&
                    listOf(item.sender, item.conversation, item.text).any { it.contains(r.match, ignoreCase = true) }
            } ?: continue
            postAlert("${item.sender.ifEmpty { item.app }} (${item.app})", item.text, rule.match)
        }
    }

    private fun postAlert(title: String, text: String, rule: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "تنبيهات مهمة", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("تنبيه: $rule")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((System.nanoTime() % Int.MAX_VALUE).toInt(), notif) }
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---------- actions on live notifications ----------

    fun live(): List<StatusBarNotification> = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        .filter { it.packageName != packageName }

    /** Replies through the notification's own "Reply" box. */
    fun reply(sbn: StatusBarNotification, message: String): Boolean {
        val action = sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return false
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, message) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrDefault(false)
    }

    fun markRead(sbn: StatusBarNotification): Boolean {
        val action = sbn.notification.actions?.firstOrNull { a ->
            (Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                a.title?.toString()?.lowercase()?.let { t -> "read" in t || "مقروء" in t || "قراءة" in t } == true
        } ?: return false
        return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
    }

    fun dismiss(sbn: StatusBarNotification) = runCatching { cancelNotification(sbn.key) }.isSuccess

    companion object {
        private const val ALERT_CHANNEL = "alerts"
        @Volatile private var instance: WeakReference<NotificationReader>? = null
        val current: NotificationReader? get() = instance?.get()

        val EMAIL_APPS = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.samsung.android.email.provider", "com.android.email", "com.huawei.email", "com.hihonor.email",
            "me.bluemail.mail", "ch.protonmail.android", "com.readdle.spark", "com.easilydo.mail", "com.google.android.apps.inbox",
        )
        val MESSAGE_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger", "com.instagram.android",
            "com.google.android.apps.messaging", "com.android.mms", "com.hihonor.mms", "com.huawei.message",
            "com.viber.voip", "com.snapchat.android", "com.discord", "com.Slack", "com.microsoft.teams",
        )

        fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}

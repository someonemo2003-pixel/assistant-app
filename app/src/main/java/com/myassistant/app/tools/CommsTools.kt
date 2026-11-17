package com.myassistant.app.tools

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.myassistant.app.memory.ArabicText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun normName(s: String) = ArabicText.words(s).joinToString(" ")

/** Looks up contacts by (part of) name, Arabic-aware. */
internal suspend fun findContacts(ctx: ToolContext, query: String, limit: Int = 8): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        val q = normName(query)
        val out = mutableListOf<Pair<String, String>>()
        ctx.app.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = normName(name)
                if (q.isEmpty() || n.contains(q) || q.split(' ').all { w -> n.contains(w) }) out += name to number
            }
        }
        out.distinctBy { it.second.filter(Char::isDigit).takeLast(9) }.sortedBy { it.first.length }.take(limit)
    }

class FindContactTool : Tool {
    override val name = "find_contact"
    override val title = "جهات الاتصال"
    override val description = "Find a contact's phone number by name (works with Arabic or English names)."
    override val parameters = Schema.obj("name" to Schema.string("Contact name or part of it"), required = listOf("name"))
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val list = findContacts(ctx, args.str("name"))
        return if (list.isEmpty()) "No contact matches \"${args.str("name")}\"" else list.joinToString("\n") { "${it.first}: ${it.second}" }
    }
}

class CallTool : Tool {
    override val name = "call"
    override val title = "مكالمة"
    override val description = "Phone call a number or a contact name. If several contacts match, ask the user which one first."
    override val parameters = Schema.obj(
        "number" to Schema.string("Phone number (preferred if known)"),
        "contact" to Schema.string("Or contact name"),
    )
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.CALL_PHONE)
    override fun describe(args: JSONObject) = "اتصال بـ ${args.str("contact").ifEmpty { args.str("number") }}"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val number = args.str("number").ifEmpty {
            val m = if (ctx.has(listOf(Manifest.permission.READ_CONTACTS))) findContacts(ctx, args.str("contact")) else emptyList()
            when {
                m.isEmpty() -> return "No number for \"${args.str("contact")}\""
                m.size > 1 && m.map { it.first }.distinct().size > 1 -> return "Several matches, ask the user: " + m.joinToString { "${it.first} (${it.second})" }
                else -> m.first().second
            }
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
        return if (ctx.launch(intent)) "Calling $number" else "Could not start the call"
    }
}

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val title = "إرسال SMS"
    override val description = "Send an SMS directly (no extra tap). Use a number, or a contact name."
    override val parameters = Schema.obj(
        "message" to Schema.string("Message text"),
        "number" to Schema.string("Phone number"),
        "contact" to Schema.string("Or contact name"),
        required = listOf("message"),
    )
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.SEND_SMS)
    override fun describe(args: JSONObject) =
        "إرسال SMS لـ ${args.str("contact").ifEmpty { args.str("number") }}: \"${args.str("message").take(80)}\""

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val number = args.str("number").ifEmpty {
            val m = if (ctx.has(listOf(Manifest.permission.READ_CONTACTS))) findContacts(ctx, args.str("contact")) else emptyList()
            if (m.size != 1) return if (m.isEmpty()) "No number found" else "Several matches, ask the user: " + m.joinToString { "${it.first} (${it.second})" }
            m.first().second
        }
        return withContext(Dispatchers.IO) {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.app.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(args.str("message"))
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS sent to $number"
        }
    }
}

class ReadSmsTool : Tool {
    override val name = "read_sms"
    override val title = "قراءة الرسايل"
    override val description = "Read recent SMS messages in the inbox (e.g. bank codes, delivery updates). Optional sender filter."
    override val parameters = Schema.obj(
        "from" to Schema.string("Optional sender name/number filter"),
        "limit" to Schema.integer("How many, default 10"),
    )
    override val permissions = listOf(Manifest.permission.READ_SMS)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val f = args.str("from").lowercase()
        val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val out = mutableListOf<String>()
        ctx.app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER,
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val from = c.getString(0).orEmpty()
                if (f.isNotEmpty() && !from.lowercase().contains(f)) continue
                out += "[${fmt.format(Date(c.getLong(2)))}] $from: ${c.getString(1).orEmpty().take(400)}"
            }
        }
        if (out.isEmpty()) "No messages" else out.joinToString("\n")
    }
}

class RecentCallsTool : Tool {
    override val name = "recent_calls"
    override val title = "سجل المكالمات"
    override val description = "List recent calls (missed, incoming, outgoing)."
    override val parameters = Schema.obj("limit" to Schema.integer("How many, default 10"))
    override val permissions = listOf(Manifest.permission.READ_CALL_LOG)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val out = mutableListOf<String>()
        ctx.app.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DEFAULT_SORT_ORDER,
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }
                val who = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1).orEmpty()
                out += "[${fmt.format(Date(c.getLong(3)))}] $type · $who (${c.getLong(4)}s)"
            }
        }
        if (out.isEmpty()) "No calls" else out.joinToString("\n")
    }
}

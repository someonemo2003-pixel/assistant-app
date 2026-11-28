package com.myassistant.app.mail

import android.util.Base64
import androidx.core.text.HtmlCompat
import com.myassistant.app.ai.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Gmail through Google's Gmail API with the Google sign-in (no App Password). Blocking: use from IO.
 * Gmail message ids are hex strings; they're shown to the model as numbers (the same "uid" the IMAP
 * path uses), converted both ways without loss.
 */
class GmailApi(private val token: String) : MailBackend {
    class ApiError(msg: String) : Exception(msg)

    private val api = "https://gmail.googleapis.com/gmail/v1/users/me"

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(api + path).header("Authorization", "Bearer $token").method(method, rb).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${resp.code}")
                throw ApiError(msg)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun toUid(id: String): Long = java.lang.Long.parseUnsignedLong(id, 16)
    private fun toId(uid: Long): String = java.lang.Long.toHexString(uid)

    private val me: String by lazy { runCatching { call("GET", "/profile").optString("emailAddress") }.getOrDefault("").lowercase() }

    override fun test(): Int = call("GET", "/labels/INBOX").optInt("messagesTotal", 0)

    private fun ids(q: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var page: String? = null
        do {
            val o = call("GET", "/messages?q=${enc(q)}&maxResults=${minOf(limit - out.size, 500)}" + (page?.let { "&pageToken=$it" } ?: ""))
            val arr = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i).getString("id")
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null && out.size < limit)
        return out.take(limit)
    }

    override fun recent(sinceMs: Long, unreadOnly: Boolean, query: String, limit: Int): List<MailItem> {
        val q = buildString {
            append("in:inbox after:").append(sinceMs / 1000)
            if (unreadOnly) append(" is:unread")
            if (query.isNotBlank()) append(' ').append(query.trim())
        }
        val headers = listOf("From", "Subject", "To", "Cc", "Delivered-To", "X-Forwarded-To", "X-Original-To", "X-Forwarded-For")
            .joinToString("") { "&metadataHeaders=$it" }
        return ids(q, limit).map { id ->
            val m = call("GET", "/messages/$id?format=metadata$headers")
            item(m)
        }.sortedByDescending { it.date }
    }

    private fun headerMap(payload: JSONObject?): Map<String, List<String>> {
        val arr = payload?.optJSONArray("headers") ?: return emptyMap()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .groupBy({ it.optString("name").lowercase() }, { it.optString("value") })
    }

    private fun item(m: JSONObject): MailItem {
        val h = headerMap(m.optJSONObject("payload"))
        val labels = m.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return MailItem(
            uid = toUid(m.getString("id")),
            from = h["from"]?.firstOrNull().orEmpty(),
            account = originalAccount(h),
            subject = h["subject"]?.firstOrNull().orEmpty(),
            date = m.optString("internalDate").toLongOrNull() ?: 0L,
            unread = "UNREAD" in labels,
            snippet = HtmlCompat.fromHtml(m.optString("snippet"), HtmlCompat.FROM_HTML_MODE_COMPACT).toString().take(300),
        )
    }

    /** Which of the user's accounts the mail was really sent to (headers survive auto-forwarding). */
    private fun originalAccount(h: Map<String, List<String>>): String {
        val mine = me
        fun addrs(key: String) = h[key].orEmpty().flatMap { it.split(',', ' ') }
            .map { it.trim().trim('<', '>', '"').lowercase() }.filter { it.contains('@') && it != mine }
        return (addrs("x-forwarded-to") + addrs("x-original-to") + addrs("delivered-to") + addrs("x-forwarded-for") + addrs("to") + addrs("cc"))
            .firstOrNull() ?: mine
    }

    override fun read(uid: Long): String {
        val m = call("GET", "/messages/${toId(uid)}?format=full")
        val it = item(m)
        val attachments = mutableListOf<String>()
        val body = textOf(m.optJSONObject("payload"), attachments).trim()
        return buildString {
            appendLine("From: ${it.from}")
            appendLine("Sent to account: ${it.account}")
            appendLine("Subject: ${it.subject}")
            appendLine("Date: ${Date(it.date)}")
            if (attachments.isNotEmpty()) appendLine("Attachments: ${attachments.joinToString()}")
            appendLine()
            append(body.take(8000))
        }
    }

    private fun decode(data: String): String =
        String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    private fun textOf(p: JSONObject?, attachments: MutableList<String>): String {
        if (p == null) return ""
        val mime = p.optString("mimeType")
        val name = p.optString("filename")
        if (name.isNotBlank()) { attachments += name; return "" }
        val data = p.optJSONObject("body")?.optString("data").orEmpty()
        val parts = p.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        return when {
            mime == "text/plain" -> if (data.isNotBlank()) decode(data) else ""
            mime == "text/html" -> if (data.isNotBlank()) HtmlCompat.fromHtml(decode(data), HtmlCompat.FROM_HTML_MODE_COMPACT).toString() else ""
            mime == "multipart/alternative" ->
                (parts.firstOrNull { it.optString("mimeType") == "text/plain" } ?: parts.firstOrNull())?.let { textOf(it, attachments) }.orEmpty()
            mime.startsWith("multipart/") -> parts.joinToString("\n") { textOf(it, attachments) }
            else -> ""
        }
    }

    override fun markRead(uids: List<Long>?, allUnread: Boolean): Int {
        val list = if (allUnread) ids("in:inbox is:unread", 1000) else uids.orEmpty().map { toId(it) }
        list.chunked(1000).forEach { chunk ->
            call("POST", "/messages/batchModify", JSONObject().put("ids", JSONArray(chunk)).put("removeLabelIds", JSONArray(listOf("UNREAD"))))
        }
        return list.size
    }

    /** Builds the email; a reply stays in the same thread. Returns it with the thread id (if any). */
    private fun compose(to: String, subject: String, body: String, replyToUid: Long?): Pair<MimeMessage, String?> {
        val msg = MimeMessage(Session.getInstance(Properties()))
        var subj = subject
        var recipients = to
        var thread: String? = null
        if (replyToUid != null) {
            val o = call("GET", "/messages/${toId(replyToUid)}?format=metadata&metadataHeaders=From&metadataHeaders=Reply-To" +
                "&metadataHeaders=Subject&metadataHeaders=Message-ID&metadataHeaders=References")
            thread = o.optString("threadId").ifBlank { null }
            val h = headerMap(o.optJSONObject("payload"))
            if (recipients.isBlank()) recipients = (h["reply-to"] ?: h["from"])?.firstOrNull().orEmpty()
            if (subj.isBlank()) subj = h["subject"]?.firstOrNull()?.let { if (it.startsWith("Re:", true)) it else "Re: $it" }.orEmpty()
            (h["message-id"]?.firstOrNull())?.let { id ->
                msg.setHeader("In-Reply-To", id)
                msg.setHeader("References", (h["references"]?.firstOrNull()?.let { "$it " } ?: "") + id)
            }
        }
        if (me.isNotBlank()) msg.setFrom(InternetAddress(me))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients))
        msg.setSubject(subj, "UTF-8")
        msg.setText(body, "UTF-8")
        msg.sentDate = Date()
        return msg to thread
    }

    private fun raw(msg: MimeMessage): String {
        val out = ByteArrayOutputStream()
        msg.writeTo(out)
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    override fun send(to: String, subject: String, body: String, replyToUid: Long?): String {
        val (msg, thread) = compose(to, subject, body, replyToUid)
        val o = JSONObject().put("raw", raw(msg))
        thread?.let { o.put("threadId", it) }
        call("POST", "/messages/send", o)
        return "Sent to ${msg.allRecipients?.joinToString()}"
    }

    override fun draft(to: String, subject: String, body: String, replyToUid: Long?): String {
        val (msg, thread) = compose(to, subject, body, replyToUid)
        val m = JSONObject().put("raw", raw(msg))
        thread?.let { m.put("threadId", it) }
        call("POST", "/drafts", JSONObject().put("message", m))
        return "Draft saved in Gmail (Drafts)"
    }
}

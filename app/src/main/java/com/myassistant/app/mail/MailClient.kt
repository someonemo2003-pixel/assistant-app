package com.myassistant.app.mail

import android.content.Context
import androidx.core.text.HtmlCompat
import com.myassistant.app.security.SecretStore
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

data class MailItem(
    val uid: Long,
    val from: String,
    /** The account it was originally sent to (before forwarding to the hub). */
    val account: String,
    val subject: String,
    val date: Long,
    val unread: Boolean,
    val snippet: String,
)

/** Gmail address + app password (encrypted) for the "hub" inbox. */
class MailConfig(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("mail", Context.MODE_PRIVATE)
    private val _email = MutableStateFlow(prefs.getString("email", "").orEmpty())
    val email: StateFlow<String> = _email.asStateFlow()

    fun password(): String? = secrets.get("gmail_app_password")
    fun hasPassword() = secrets.has("gmail_app_password")
    val configured get() = _email.value.isNotBlank() && hasPassword()

    fun save(email: String, appPassword: String?) {
        prefs.edit().putString("email", email.trim()).apply()
        _email.value = email.trim()
        if (!appPassword.isNullOrBlank()) secrets.put("gmail_app_password", appPassword.replace(" ", "").trim())
    }

    fun clear() {
        prefs.edit().remove("email").apply()
        _email.value = ""
        secrets.put("gmail_app_password", null)
    }

    fun client(): MailClient? {
        val p = password() ?: return null
        if (_email.value.isBlank()) return null
        return MailClient(_email.value, p)
    }
}

/** Talks to Gmail directly over IMAP/SMTP (no app is opened). All calls are blocking — use from IO. */
class MailClient(private val address: String, private val appPassword: String) {

    private val props = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", "imap.gmail.com")
        put("mail.imaps.port", "993")
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "30000")
        put("mail.smtp.host", "smtp.gmail.com")
        put("mail.smtp.port", "465")
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "30000")
    }
    private val session: Session = Session.getInstance(props)

    private fun <T> inbox(write: Boolean = false, block: (IMAPFolder) -> T): T {
        val store = session.getStore("imaps") as IMAPStore
        store.connect("imap.gmail.com", address, appPassword)
        try {
            val f = store.getFolder("INBOX") as IMAPFolder
            f.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            try {
                return block(f)
            } finally {
                runCatching { f.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    /** Throws with a readable message if login fails. */
    fun test(): Int = inbox { it.messageCount }

    fun recent(sinceMs: Long, unreadOnly: Boolean = false, query: String = "", limit: Int = 30): List<MailItem> = inbox { f ->
        var term: SearchTerm = ReceivedDateTerm(ComparisonTerm.GE, Date(sinceMs))
        if (unreadOnly) term = AndTerm(term, FlagTerm(Flags(Flags.Flag.SEEN), false))
        if (query.isNotBlank()) {
            term = AndTerm(term, OrTerm(arrayOf(FromStringTerm(query), SubjectTerm(query), BodyTerm(query))))
        }
        val msgs = f.search(term).sortedByDescending { it.receivedDate ?: it.sentDate ?: Date(0) }.take(limit).toTypedArray()
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
            add("Delivered-To"); add("X-Forwarded-To"); add("X-Original-To"); add("X-Forwarded-For")
        }
        f.fetch(msgs, fp)
        msgs.mapIndexed { i, m -> toItem(f, m, withSnippet = i < 25) }
    }

    fun read(uid: Long): String = inbox { f ->
        val m = f.getMessageByUID(uid) ?: return@inbox "Email not found"
        val item = toItem(f, m, withSnippet = false)
        val attachments = mutableListOf<String>()
        val body = textOf(m, attachments).trim()
        buildString {
            appendLine("From: ${item.from}")
            appendLine("Sent to account: ${item.account}")
            appendLine("Subject: ${item.subject}")
            appendLine("Date: ${Date(item.date)}")
            if (attachments.isNotEmpty()) appendLine("Attachments: ${attachments.joinToString()}")
            appendLine()
            append(body.take(8000))
        }
    }

    fun markRead(uids: List<Long>?, allUnread: Boolean): Int = inbox(write = true) { f ->
        val msgs: Array<Message> = if (allUnread) f.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
        else uids.orEmpty().mapNotNull { f.getMessageByUID(it) }.toTypedArray()
        if (msgs.isNotEmpty()) f.setFlags(msgs, Flags(Flags.Flag.SEEN), true)
        msgs.size
    }

    /** Builds a message; replying keeps the thread together. */
    private fun compose(to: String, subject: String, body: String, replyToUid: Long?): MimeMessage {
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(address))
        var subj = subject
        var recipients = to
        if (replyToUid != null) {
            inbox { f ->
                (f.getMessageByUID(replyToUid) as? MimeMessage)?.let { orig ->
                    if (recipients.isBlank()) recipients = (orig.replyTo ?: orig.from)?.joinToString { (it as? InternetAddress)?.address ?: it.toString() }.orEmpty()
                    if (subj.isBlank()) subj = orig.subject?.let { if (it.startsWith("Re:", true)) it else "Re: $it" }.orEmpty()
                    orig.messageID?.let { id ->
                        msg.setHeader("In-Reply-To", id)
                        msg.setHeader("References", (orig.getHeader("References")?.firstOrNull()?.let { "$it " } ?: "") + id)
                    }
                }
            }
        }
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients))
        msg.setSubject(subj, "UTF-8")
        msg.setText(body, "UTF-8")
        msg.sentDate = Date()
        return msg
    }

    fun send(to: String, subject: String, body: String, replyToUid: Long?): String {
        val msg = compose(to, subject, body, replyToUid)
        Transport.send(msg, address, appPassword)
        return "Sent to ${msg.allRecipients?.joinToString()}"
    }

    /** Saves to Gmail's Drafts folder so the user can review and send it from Gmail. */
    fun draft(to: String, subject: String, body: String, replyToUid: Long?): String {
        val msg = compose(to, subject, body, replyToUid)
        val store = session.getStore("imaps") as IMAPStore
        store.connect("imap.gmail.com", address, appPassword)
        try {
            val drafts = store.defaultFolder.list("*").map { it as IMAPFolder }
                .firstOrNull { f -> f.attributes.any { it.equals("\\Drafts", true) } }
                ?: store.getFolder("[Gmail]/Drafts") as IMAPFolder
            msg.setFlag(Flags.Flag.DRAFT, true)
            drafts.appendMessages(arrayOf(msg))
            return "Draft saved in Gmail (${drafts.name})"
        } finally {
            runCatching { store.close() }
        }
    }

    // ---------- helpers ----------

    private fun toItem(f: IMAPFolder, m: Message, withSnippet: Boolean): MailItem {
        val from = m.from?.firstOrNull()?.let(::pretty).orEmpty()
        return MailItem(
            uid = f.getUID(m),
            from = from,
            account = originalAccount(m),
            subject = m.subject.orEmpty(),
            date = (m.receivedDate ?: m.sentDate ?: Date()).time,
            unread = !m.isSet(Flags.Flag.SEEN),
            snippet = if (withSnippet) runCatching { textOf(m, mutableListOf()).replace(Regex("\\s+"), " ").trim().take(300) }.getOrDefault("") else "",
        )
    }

    /** Which of the user's accounts the mail was really sent to (headers survive auto-forwarding). */
    private fun originalAccount(m: Message): String {
        val me = address.lowercase()
        val fromHeaders = listOf("X-Forwarded-To", "X-Original-To", "Delivered-To", "X-Forwarded-For")
            .flatMap { h -> runCatching { m.getHeader(h)?.toList() }.getOrNull().orEmpty() }
            .flatMap { it.split(',', ' ') }
            .map { it.trim().trim('<', '>').lowercase() }
            .filter { it.contains('@') && it != me }
        val recipients = runCatching {
            (m.getRecipients(Message.RecipientType.TO).orEmpty() + m.getRecipients(Message.RecipientType.CC).orEmpty())
                .mapNotNull { (it as? InternetAddress)?.address?.lowercase() }
                .filter { it != me }
        }.getOrDefault(emptyList())
        return fromHeaders.firstOrNull() ?: recipients.firstOrNull() ?: me
    }

    private fun pretty(a: Address): String = (a as? InternetAddress)?.let {
        if (!it.personal.isNullOrBlank()) "${it.personal} <${it.address}>" else it.address
    } ?: a.toString()

    private fun textOf(p: Part, attachments: MutableList<String>): String {
        if (Part.ATTACHMENT.equals(p.disposition, true) || (!p.fileName.isNullOrBlank() && !p.isMimeType("text/*"))) {
            p.fileName?.let { attachments += it }
            return ""
        }
        return when {
            p.isMimeType("text/plain") -> p.content?.toString().orEmpty()
            p.isMimeType("text/html") -> HtmlCompat.fromHtml(p.content?.toString().orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                parts.firstOrNull { it.isMimeType("text/plain") }?.let { textOf(it, attachments) }
                    ?: parts.firstOrNull()?.let { textOf(it, attachments) }.orEmpty()
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                (0 until mp.count).joinToString("\n") { textOf(mp.getBodyPart(it), attachments) }
            }
            p.isMimeType("message/rfc822") -> (p.content as? Part)?.let { textOf(it, attachments) }.orEmpty()
            else -> ""
        }
    }
}

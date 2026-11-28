package com.myassistant.app.mail

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

private const val NOT_SET = "Gmail is not connected. Open setup section 'gmail' (open_setup) and tell the user to tap 'اربط بحساب جوجل'."

internal fun formatList(items: List<MailItem>): String {
    if (items.isEmpty()) return "No emails"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    return items.joinToString("\n") { m ->
        (if (m.unread) "● " else "  ") + "[uid ${m.uid}] ${fmt.format(Date(m.date))} · to ${m.account} · from ${m.from}: ${m.subject}" +
            if (m.snippet.isNotBlank()) "\n     ${m.snippet}" else ""
    }
}

private suspend fun <T> mail(ctx: ToolContext, block: (MailBackend) -> T): Any? {
    val c = ctx.mail?.backend(ctx.app) ?: return NOT_SET
    return withContext(Dispatchers.IO) {
        try {
            block(c)
        } catch (e: GmailApi.ApiError) {
            "Gmail error: ${e.message}"
        } catch (e: javax.mail.AuthenticationFailedException) {
            "Gmail login failed: check the email and App Password in → Gmail."
        } catch (e: Exception) {
            "Email error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

class CheckEmailTool : Tool {
    override val name = "check_email"
    override val title = "الإيميل"
    override val description =
        "List emails from the user's Gmail hub inbox (all their accounts forward here). Each line shows which original " +
            "account it was sent to ('to …'), sender, subject and a preview. Use for 'any new emails?', summaries, reports."
    override val parameters = Schema.obj(
        "hours" to Schema.integer("How far back, default 24 (max 720)"),
        "unread_only" to Schema.string("'true' for unread only"),
        "account" to Schema.string("Optional: only emails sent to this account (part of the address)"),
        "limit" to Schema.integer("Max emails, default 30"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val hours = (args.int("hours") ?: 24).coerceIn(1, 720)
        val acc = args.str("account").lowercase()
        val r = mail(ctx) { c ->
            c.recent(System.currentTimeMillis() - hours * 3_600_000L, args.str("unread_only") == "true", limit = (args.int("limit") ?: 30).coerceIn(1, 60))
                .filter { acc.isEmpty() || it.account.contains(acc) }
        }
        @Suppress("UNCHECKED_CAST")
        return (r as? List<MailItem>)?.let { formatList(it) } ?: r.toString()
    }
}

class SearchEmailTool : Tool {
    override val name = "search_email"
    override val title = "بحث في الإيميل"
    override val description = "Search the Gmail hub for emails by sender, subject or words in the body."
    override val parameters = Schema.obj(
        "query" to Schema.string("Words / sender to look for"),
        "days" to Schema.integer("How far back in days, default 30"),
        required = listOf("query"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val days = (args.int("days") ?: 30).coerceIn(1, 365)
        val r = mail(ctx) { c -> c.recent(System.currentTimeMillis() - days * 86_400_000L, query = args.str("query"), limit = 25) }
        @Suppress("UNCHECKED_CAST")
        return (r as? List<MailItem>)?.let { formatList(it) } ?: r.toString()
    }
}

class ReadEmailTool : Tool {
    override val name = "read_email"
    override val title = "قراءة إيميل"
    override val description = "Read one full email by its uid (from check_email/search_email)."
    override val parameters = Schema.obj("uid" to Schema.integer("Email uid"), required = listOf("uid"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val uid = args.int("uid")?.toLong() ?: return "Missing uid"
        return mail(ctx) { it.read(uid) }.toString()
    }
}

class MarkEmailReadTool : Tool {
    override val name = "mark_email_read"
    override val title = "تعليم كمقروء"
    override val description = "Mark emails as read in Gmail: specific uids (comma-separated) or all unread."
    override val parameters = Schema.obj(
        "uids" to Schema.string("Comma-separated uids, or empty"),
        "all_unread" to Schema.string("'true' to mark ALL unread emails as read"),
    )
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val all = args.str("all_unread") == "true"
        val uids = args.str("uids").split(',', ' ').mapNotNull { it.trim().toLongOrNull() }
        if (!all && uids.isEmpty()) return "Nothing to mark"
        return mail(ctx) { c -> "Marked ${c.markRead(uids, all)} emails as read" }.toString()
    }
}

class DraftEmailTool : Tool {
    override val name = "draft_email"
    override val title = "مسودة إيميل"
    override val description =
        "Write an email or a reply and save it in Gmail Drafts for the user to review (nothing is sent). " +
            "For a reply pass reply_to_uid (to/subject can then be empty)."
    override val parameters = Schema.obj(
        "to" to Schema.string("Recipient email(s)"),
        "subject" to Schema.string("Subject"),
        "body" to Schema.string("Email text"),
        "reply_to_uid" to Schema.integer("Optional uid of the email being answered"),
        required = listOf("body"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String =
        mail(ctx) { it.draft(args.str("to"), args.str("subject"), args.str("body"), args.int("reply_to_uid")?.toLong()) }.toString()
}

class SendEmailTool : Tool {
    override val name = "send_email"
    override val title = "إرسال إيميل"
    override val description =
        "Send an email (or a reply with reply_to_uid) from the Gmail hub. The user is always asked to approve first."
    override val parameters = Schema.obj(
        "to" to Schema.string("Recipient email(s)"),
        "subject" to Schema.string("Subject"),
        "body" to Schema.string("Email text"),
        "reply_to_uid" to Schema.integer("Optional uid of the email being answered"),
        required = listOf("body"),
    )
    override val requiresConfirmation = true
    override fun describe(args: JSONObject) =
        "إرسال إيميل ${args.str("to").ifEmpty { "(رد)" }}: \"${args.str("subject").ifEmpty { args.str("body") }.take(70)}\""

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String =
        mail(ctx) { it.send(args.str("to"), args.str("subject"), args.str("body"), args.int("reply_to_uid")?.toLong()) }.toString()
}

package com.myassistant.app.control

import android.Manifest
import android.content.Intent
import android.net.Uri
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.findContacts
import com.myassistant.app.tools.has
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a WhatsApp message by itself when there's no notification to reply to:
 * 1) known number → open the chat with the text already written, then press Send;
 * 2) otherwise (e.g. a group) → search the chat inside WhatsApp, type, and send.
 */
object WhatsAppSender {

    suspend fun send(ctx: ToolContext, to: String, message: String): String {
        val s = ScreenService.current ?: return SCREEN_OFF
        val pm = ctx.app.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: return "WhatsApp is not installed"

        val number = numberFor(ctx, to)
        if (number is Lookup.Ambiguous) return "Several contacts match \"$to\": ${number.names.joinToString()}. Ask the user which one."

        return withContext(Dispatchers.Default) {
            if (number is Lookup.Found) {
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=${number.digits}&text=" + Uri.encode(message))
                if (ctx.launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) {
                    val sendBtn = s.waitFor("id:$pkg:id/send", 7000)
                    if (sendBtn != null && s.tap(sendBtn)) {
                        s.waitIdle()
                        return@withContext "Sent on WhatsApp to $to"
                    }
                }
            }
            viaSearch(ctx, s, pkg, to, message)
        }
    }

    private suspend fun viaSearch(ctx: ToolContext, s: ScreenService, pkg: String, to: String, message: String): String {
        val launch = ctx.app.packageManager.getLaunchIntentForPackage(pkg) ?: return "Cannot open WhatsApp"
        ctx.launch(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        s.waitIdle(minMs = 400, quietMs = 300, maxMs = 3000)
        // If WhatsApp reopened inside a chat, go back to the chat list first.
        repeat(2) { if (s.find("id:$pkg:id/entry") != null) { s.global("back"); s.waitIdle() } }
        val search = s.waitFor("id:$pkg:id/menuitem_search", 2500) ?: s.waitFor("id:$pkg:id/my_search_bar", 1500)
            ?: s.waitFor("text:Search", 1000) ?: s.waitFor("text:بحث", 1000)
            ?: return FALLBACK
        s.tap(search)
        s.waitIdle()
        if (!s.type(null, to, append = false)) return FALLBACK
        s.waitIdle(minMs = 300, quietMs = 300, maxMs = 2000)
        val hit = s.waitFor("text:$to", 2500) ?: return "Chat \"$to\" not found in WhatsApp"
        s.tap(hit)
        val entry = s.waitFor("id:$pkg:id/entry", 3000) ?: return FALLBACK
        if (!s.type(entry, message, append = false)) return FALLBACK
        val sendBtn = s.waitFor("id:$pkg:id/send", 2000) ?: return FALLBACK
        if (!s.tap(sendBtn)) return FALLBACK
        s.waitIdle()
        return "Sent on WhatsApp to $to"
    }

    private sealed interface Lookup {
        data class Found(val digits: String) : Lookup
        data class Ambiguous(val names: List<String>) : Lookup
        data object None : Lookup
    }

    private suspend fun numberFor(ctx: ToolContext, to: String): Lookup {
        val direct = to.filter { it.isDigit() }
        if (direct.length >= 8 && to.count { it.isLetter() } == 0) return Lookup.Found(intl(direct))
        if (!ctx.has(listOf(Manifest.permission.READ_CONTACTS))) return Lookup.None
        val matches = findContacts(ctx, to)
        if (matches.isEmpty()) return Lookup.None
        val exact = matches.filter { it.first.equals(to.trim(), ignoreCase = true) }
        val pick = exact.ifEmpty { matches }
        val names = pick.map { it.first }.distinct()
        if (names.size > 1) return Lookup.Ambiguous(names.take(5))
        return Lookup.Found(intl(pick.first().second.filter { it.isDigit() || it == '+' }))
    }

    /** WhatsApp links need the international form: Egyptian 01xxxxxxxxx → 201xxxxxxxxx. */
    private fun intl(raw: String): String {
        val d = raw.removePrefix("+").removePrefix("00").filter { it.isDigit() }
        return if (d.length == 11 && d.startsWith("01")) "2$d" else d
    }

    private const val FALLBACK =
        "Automatic WhatsApp send didn't finish. Continue yourself with read_screen + do_steps (do NOT ask the user to do it)."
}

/** "ابعت لأحمد على واتساب إني جاي" — sends directly, fast, without the AI clicking around. */
class SendWhatsAppTool : com.myassistant.app.tools.Tool {
    override val name = "send_whatsapp"
    override val title = "واتساب"
    override val description =
        "Send a WhatsApp message to a contact name, phone number, or group name. Opens the chat with the text and presses Send by itself."
    override val parameters = com.myassistant.app.tools.Schema.obj(
        "to" to com.myassistant.app.tools.Schema.string("Contact name, phone number, or group name"),
        "message" to com.myassistant.app.tools.Schema.string("Message text"),
        required = listOf("to", "message"),
    )
    override val requiresConfirmation = true
    override fun describe(args: org.json.JSONObject) =
        "واتساب لـ ${args.optString("to")}: \"${args.optString("message").take(80)}\""

    override suspend fun execute(args: org.json.JSONObject, ctx: ToolContext): String =
        WhatsAppSender.send(ctx, args.optString("to").trim(), args.optString("message").trim())
}

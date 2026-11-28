package com.myassistant.app.ui

/** Commands typed in the chat that open one of Nova's pages. */
object SlashCommands {
    data class Command(val command: String, val title: String)

    val all = listOf(
        Command("/blockapps", "البرامج المحظورة"),
        Command("/extension", "المتصفح والمواقع"),
        Command("/update", "شوف فيه تحديث"),
    )

    /** Commands that start with what the user typed (only while the message starts with "/"). */
    fun matching(input: String): List<Command> {
        val t = input.trim().lowercase()
        if (!t.startsWith("/") || ' ' in t) return emptyList()
        return all.filter { it.command.startsWith(t) }
    }
}

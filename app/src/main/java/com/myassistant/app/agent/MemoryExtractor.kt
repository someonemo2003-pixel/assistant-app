package com.myassistant.app.agent

import com.myassistant.app.ai.ProviderRouter
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.memory.ArabicText
import com.myassistant.app.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When a conversation ends, asks the big brain (one request) which lasting facts about the user
 * it contained, and stores the new ones in local memory.
 */
class MemoryExtractor(private val router: ProviderRouter, private val memory: MemoryStore) {

    /** Returns how many new facts were saved. */
    suspend fun extract(messages: List<ChatMessage>): Int {
        val lines = messages
            .filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.text.isNotBlank() }
            .takeLast(40)
            .joinToString("\n") { (if (it.role == Role.USER) "User: " else "Assistant: ") + it.text.take(500) }
            .takeLast(8000)
        if (messages.count { it.role == Role.USER } < 2 || lines.isBlank()) return 0

        val known = withContext(Dispatchers.IO) { memory.recent(60) }.joinToString("\n") { "- ${it.text}" }
        val system = buildString {
            appendLine("You extract lasting personal facts about the user from a conversation.")
            appendLine("Keep only durable facts the user stated: their name, people in their life, preferences,")
            appendLine("work, routines, important dates, ongoing plans. Skip small talk, one-off requests, and anything")
            appendLine("already known. Never include passwords, card numbers, or ID numbers.")
            appendLine("Write each fact as one short sentence in the user's language, one per line, starting with '- '.")
            appendLine("If there is nothing new, reply exactly: NONE")
            if (known.isNotBlank()) {
                appendLine()
                appendLine("Already known:")
                appendLine(known)
            }
        }
        val reply = router.complete(system, listOf(ChatMessage(role = Role.USER, text = lines)), emptyList())
        val facts = reply.text.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("• ") }
            .map { it.drop(2).trim() }
            .filter { it.length in 4..300 }
            .take(10)

        return withContext(Dispatchers.IO) {
            val existing = memory.recent(2000).map { ArabicText.normalizeForIndex(it.text) }.toHashSet()
            var saved = 0
            for (f in facts) {
                if (existing.add(ArabicText.normalizeForIndex(f))) {
                    memory.add(f)
                    saved++
                }
            }
            saved
        }
    }
}

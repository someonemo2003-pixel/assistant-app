package com.myassistant.app.agent

import com.myassistant.app.ai.ProviderRouter
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.fast.FastBrain
import com.myassistant.app.memory.MemoryStore
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The agent loop: ask the model → run any tools it calls → give it the results → repeat
 * until it answers in plain text.
 */
class Agent(
    private val router: ProviderRouter,
    private val tools: ToolRegistry,
    private val memory: MemoryStore,
    private val toolContext: ToolContext,
) {

    private val fastBrain = FastBrain(tools, toolContext)

    suspend fun run(
        history: MutableList<ChatMessage>,
        emit: suspend (ChatMessage) -> Unit,
        confirm: suspend (String) -> Boolean,
    ) {
        val lastUser = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()

        // 1) Instant local brain for simple commands (no internet, no quota).
        if (history.lastOrNull()?.role == Role.USER) {
            fastBrain.tryHandle(lastUser)?.let { msgs ->
                msgs.forEach { history += it; emit(it) }
                return
            }
        }

        // 2) Cloud AI for everything else.
        val system = systemPrompt(lastUser)

        repeat(MAX_STEPS) {
            val reply = router.complete(system, trimmed(history), tools.specs())
            history += reply
            emit(reply)
            if (reply.toolCalls.isEmpty()) return

            for (call in reply.toolCalls) {
                val tool = tools[call.name]
                val result = if (tool == null) {
                    "Unknown tool: ${call.name}"
                } else {
                    try {
                        val args = runCatching { JSONObject(call.argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                        if (tool.requiresConfirmation && !confirm(tool.describe(args))) {
                            "The user declined this action"
                        } else {
                            tool.execute(args, toolContext)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                }
                val msg = ChatMessage(role = Role.TOOL, text = result, toolCallId = call.id, toolName = call.name)
                history += msg
                emit(msg)
            }
        }
    }

    private suspend fun systemPrompt(userText: String): String {
        val memories = withContext(Dispatchers.IO) {
            if (memory.count() <= ALWAYS_INCLUDE) memory.recent(ALWAYS_INCLUDE) else memory.search(userText, 8)
        }
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return buildString {
            appendLine("You are \"مساعدي\" (My Assistant), a personal assistant living on the user's Android phone.")
            appendLine("- Reply in the user's language. For Arabic, use friendly, short Egyptian Arabic.")
            appendLine("- Prefer doing things with your tools over explaining how to do them.")
            appendLine("- When the user shares a lasting fact about themselves, save it with the remember tool.")
            appendLine("- Never invent tool results; report what actually happened. Keep answers concise.")
            appendLine("- Current time: $now (${TimeZone.getDefault().id}).")
            if (memories.isNotEmpty()) {
                appendLine()
                appendLine("Things you know about the user (from memory):")
                memories.forEach { appendLine("- ${it.text}") }
            }
        }
    }

    /** Keeps recent context only, always cutting at the start of a user turn. */
    private fun trimmed(history: List<ChatMessage>): List<ChatMessage> {
        if (history.size <= MAX_CONTEXT) return history
        val userStarts = history.indices.filter { history[it].role == Role.USER }
        val start = userStarts.firstOrNull { history.size - it <= MAX_CONTEXT } ?: userStarts.lastOrNull() ?: 0
        return history.subList(start, history.size)
    }

    private companion object {
        const val MAX_STEPS = 6
        const val MAX_CONTEXT = 40
        const val ALWAYS_INCLUDE = 30
    }
}

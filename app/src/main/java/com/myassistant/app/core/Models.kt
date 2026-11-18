package com.myassistant.app.core

import java.util.UUID

enum class Role { USER, ASSISTANT, TOOL }

data class ToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/**
 * One turn of the conversation, in a provider-neutral shape.
 * Every provider translates to/from this, so adding a provider never touches the rest of the app.
 */
data class ChatMessage(
    val id: String = newId(),
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** For TOOL messages: which call this answers, and the tool's name. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Provider-specific raw payload (e.g. Gemini parts with thought signatures), keyed by provider kind. */
    val raw: Map<String, String> = emptyMap(),
    /** Which provider produced this reply (shown in the UI). */
    val providerName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/** Key in [ChatMessage.raw] holding the model's own thinking text, when it shares it. */
const val THINKING_KEY = "thinking"

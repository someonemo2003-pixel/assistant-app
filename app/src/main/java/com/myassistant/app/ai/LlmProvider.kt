package com.myassistant.app.ai

import com.myassistant.app.core.ChatMessage
import org.json.JSONObject

/** A tool as the model sees it: name, description and a JSON-schema for its arguments. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

class ProviderException(message: String) : Exception(message)

/**
 * The "brain" contract. Gemini, Groq, OpenRouter, a local model... each is one class implementing this.
 */
interface LlmProvider {
    val name: String

    /** Returns the assistant's next turn: text and/or tool calls. */
    suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage

    /** Model ids available with this key (used by the settings screen). */
    suspend fun listModels(): List<String>
}

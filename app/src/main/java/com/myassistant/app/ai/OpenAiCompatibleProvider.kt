package com.myassistant.app.ai

import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.core.ToolCall
import com.myassistant.app.core.newId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Works with any service that speaks the OpenAI "chat/completions" format:
 * Groq, OpenRouter, OpenAI, DeepSeek, Together, Mistral, a local Ollama server...
 */
class OpenAiCompatibleProvider(
    override val name: String,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    private val base = baseUrl.trim().trimEnd('/')

    private val headers: Map<String, String>
        get() = mapOf(
            "Authorization" to "Bearer $apiKey",
            "HTTP-Referer" to "https://github.com/someonemo2003-pixel/assistant-app",
            "X-Title" to "My Assistant",
        )

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in history) {
            when (m.role) {
                Role.USER -> messages.put(JSONObject().put("role", "user").put("content", m.text))
                Role.ASSISTANT -> {
                    val o = JSONObject().put("role", "assistant").put("content", m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        m.toolCalls.forEach { c ->
                            calls.put(
                                JSONObject().put("id", c.id).put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", c.name).put("arguments", c.argsJson),
                                ),
                            )
                        }
                        o.put("tool_calls", calls)
                    }
                    messages.put(o)
                }
                Role.TOOL -> messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId ?: "").put("content", m.text),
                )
            }
        }

        val body = JSONObject().put("model", model).put("messages", messages)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach { t ->
                arr.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters),
                    ),
                )
            }
            body.put("tools", arr)
        }

        val res = Http.postJson("$base/chat/completions", headers, body)
        val choices = res.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw ProviderException("رد فارغ")
        val msg = choices.getJSONObject(0).getJSONObject("message")
        val text = if (msg.isNull("content")) "" else msg.optString("content")

        val calls = mutableListOf<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: continue
                val args = when (val a = fn.opt("arguments")) {
                    is String -> a.ifBlank { "{}" }
                    is JSONObject -> a.toString()
                    else -> "{}"
                }
                calls += ToolCall(c.optString("id").ifBlank { newId() }, fn.optString("name"), args)
            }
        }
        return ChatMessage(role = Role.ASSISTANT, text = text.trim(), toolCalls = calls)
    }

    override suspend fun listModels(): List<String> {
        val data = Http.getJson("$base/models", headers).optJSONArray("data") ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
            .sorted()
    }
}

package com.myassistant.app.ai

import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.core.ToolCall
import com.myassistant.app.core.newId
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini via its native REST API (free tier from aistudio.google.com). */
class GeminiProvider(
    override val name: String,
    baseUrl: String,
    private val apiKey: String,
    model: String,
) : LlmProvider {

    companion object {
        const val RAW_KEY = "gemini"
        /** Ids we invented ourselves (Gemini didn't send one) must not be echoed back. */
        private const val LOCAL_ID = "local-"
    }

    private val base = baseUrl.trim().trimEnd('/')
    private val model = model.trim().removePrefix("models/")
    private val headers get() = mapOf("x-goog-api-key" to apiKey)

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        val contents = JSONArray()
        var openToolTurn: JSONObject? = null

        for (m in history) {
            when (m.role) {
                Role.USER -> {
                    contents.put(userContent(JSONObject().put("text", m.text)))
                    openToolTurn = null
                }
                Role.ASSISTANT -> {
                    // Re-send Gemini's own content verbatim when we have it (keeps thought signatures valid).
                    contents.put(m.raw[RAW_KEY]?.let { JSONObject(it) } ?: modelContent(m))
                    openToolTurn = null
                }
                Role.TOOL -> {
                    val fr = JSONObject()
                        .put("name", m.toolName ?: "tool")
                        .put("response", JSONObject().put("result", m.text))
                    m.toolCallId?.takeIf { !it.startsWith(LOCAL_ID) }?.let { fr.put("id", it) }
                    val part = JSONObject().put("functionResponse", fr)
                    val turn = openToolTurn
                    if (turn != null) {
                        turn.getJSONArray("parts").put(part)
                    } else {
                        val c = userContent(part)
                        contents.put(c)
                        openToolTurn = c
                    }
                }
            }
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { t ->
                decls.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }

        val res = Http.postJson("$base/models/$model:generateContent", headers, body)
        val candidates = res.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = res.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw ProviderException("لم يرجع Gemini أي رد" + if (reason.isNotBlank()) " ($reason)" else "")
        }
        val first = candidates.getJSONObject(0)
        val content = first.optJSONObject("content")
            ?: throw ProviderException("رد فارغ من Gemini (${first.optString("finishReason")})")
        if (!content.has("role")) content.put("role", "model")

        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought")) continue
            p.optJSONObject("functionCall")?.let { fc ->
                calls += ToolCall(
                    id = fc.optString("id").ifBlank { LOCAL_ID + newId() },
                    name = fc.optString("name"),
                    argsJson = (fc.optJSONObject("args") ?: JSONObject()).toString(),
                )
            }
            if (p.has("text")) text.append(p.optString("text"))
        }
        return ChatMessage(
            role = Role.ASSISTANT,
            text = text.toString().trim(),
            toolCalls = calls,
            raw = mapOf(RAW_KEY to content.toString()),
        )
    }

    override suspend fun listModels(): List<String> {
        val models = Http.getJson("$base/models?pageSize=200", headers).optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val m = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canChat = methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (canChat) m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } else null
        }.sorted()
    }

    private fun userContent(part: JSONObject) =
        JSONObject().put("role", "user").put("parts", JSONArray().put(part))

    private fun modelContent(m: ChatMessage): JSONObject {
        val parts = JSONArray()
        if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
        m.toolCalls.forEach { c ->
            val fc = JSONObject().put("name", c.name).put("args", runCatching { JSONObject(c.argsJson) }.getOrDefault(JSONObject()))
            if (!c.id.startsWith(LOCAL_ID)) fc.put("id", c.id)
            parts.put(JSONObject().put("functionCall", fc))
        }
        if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
        return JSONObject().put("role", "model").put("parts", parts)
    }
}

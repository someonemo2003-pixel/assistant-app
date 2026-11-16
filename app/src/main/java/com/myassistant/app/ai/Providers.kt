package com.myassistant.app.ai

import android.content.Context
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ProviderType { GEMINI, OPENAI_COMPATIBLE }

data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** Where to get a (free) key — shown as a link in settings. */
    val keyUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("baseUrl", baseUrl)
        .put("model", model).put("enabled", enabled).put("builtIn", builtIn).put("keyUrl", keyUrl ?: "")

    companion object {
        fun fromJson(o: JSONObject) = ProviderConfig(
            id = o.getString("id"),
            type = runCatching { ProviderType.valueOf(o.getString("type")) }.getOrDefault(ProviderType.OPENAI_COMPATIBLE),
            name = o.optString("name"),
            baseUrl = o.optString("baseUrl"),
            model = o.optString("model"),
            enabled = o.optBoolean("enabled", true),
            builtIn = o.optBoolean("builtIn", false),
            keyUrl = o.optString("keyUrl").ifBlank { null },
        )
    }
}

/** Ready-made providers. Add a line here to ship a new built-in one. */
object ProviderPresets {
    val defaults = listOf(
        ProviderConfig(
            id = "gemini", type = ProviderType.GEMINI, name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-flash-latest", builtIn = true,
            keyUrl = "https://aistudio.google.com/apikey",
        ),
        ProviderConfig(
            id = "groq", type = ProviderType.OPENAI_COMPATIBLE, name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile", builtIn = true,
            keyUrl = "https://console.groq.com/keys",
        ),
        ProviderConfig(
            id = "openrouter", type = ProviderType.OPENAI_COMPATIBLE, name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "meta-llama/llama-3.3-70b-instruct:free", builtIn = true,
            keyUrl = "https://openrouter.ai/keys",
        ),
    )
}

object ProviderFactory {
    fun create(config: ProviderConfig, apiKey: String): LlmProvider = when (config.type) {
        ProviderType.GEMINI -> GeminiProvider(config.name, config.baseUrl, apiKey, config.model)
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(config.name, config.baseUrl, apiKey, config.model)
    }
}

/** Provider list (order = fallback order) persisted on the phone; keys kept in [SecretStore]. */
class ProviderStore(context: Context, private val secrets: SecretStore) {

    private val prefs = context.getSharedPreferences("providers", Context.MODE_PRIVATE)
    private val _configs = MutableStateFlow(load())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    /** Bumps whenever a key changes so the UI can refresh "key saved" badges. */
    private val _keysVersion = MutableStateFlow(0)
    val keysVersion: StateFlow<Int> = _keysVersion.asStateFlow()

    fun hasKey(id: String) = secrets.has(keyName(id))
    fun apiKey(id: String) = secrets.get(keyName(id))

    fun setApiKey(id: String, key: String?) {
        secrets.put(keyName(id), key?.trim())
        _keysVersion.value++
    }

    fun upsert(config: ProviderConfig) {
        val list = _configs.value.toMutableList()
        val i = list.indexOfFirst { it.id == config.id }
        if (i >= 0) list[i] = config else list += config
        save(list)
    }

    fun remove(id: String) {
        save(_configs.value.filterNot { it.id == id })
        setApiKey(id, null)
    }

    fun move(id: String, delta: Int) {
        val list = _configs.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    /** Enabled providers that have a key, in fallback order. */
    fun activeProviders(): List<LlmProvider> = _configs.value
        .filter { it.enabled }
        .mapNotNull { c -> apiKey(c.id)?.takeIf { it.isNotBlank() }?.let { ProviderFactory.create(c, it) } }

    private fun keyName(id: String) = "api_key_$id"

    private fun load(): List<ProviderConfig> {
        val stored = prefs.getString("list", null) ?: return ProviderPresets.defaults
        return try {
            val arr = JSONArray(stored)
            (0 until arr.length()).map { ProviderConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            ProviderPresets.defaults
        }
    }

    private fun save(list: List<ProviderConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
        _configs.value = list
    }
}

class NoProvidersException : Exception("مفيش مزوّد شغال. افتح الإعدادات وحط مفتاح API واحد على الأقل.")

class AllProvidersFailedException(val errors: List<String>) :
    Exception("كل المزوّدين فشلوا:\n" + errors.joinToString("\n"))

/** Tries each provider in order; if one fails (limit reached, offline, error) moves to the next. */
class ProviderRouter(private val providers: () -> List<LlmProvider>) {

    suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        val list = providers()
        if (list.isEmpty()) throw NoProvidersException()
        val errors = mutableListOf<String>()
        for (p in list) {
            try {
                return p.complete(system, history, tools).copy(providerName = p.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "• ${p.name}: ${e.message}"
            }
        }
        throw AllProvidersFailedException(errors)
    }
}

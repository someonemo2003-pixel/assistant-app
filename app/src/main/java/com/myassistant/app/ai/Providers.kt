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

/** Ready-made providers, in the default fallback order. Add a line here to ship a new built-in one. */
object ProviderPresets {
    const val NVIDIA_URL = "https://integrate.api.nvidia.com/v1"
    const val KIMI_MODEL = "moonshotai/kimi-k3.1-pro"
    const val NEMOTRON_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"
    const val GROQ_MODEL = "openai/gpt-oss-120b"

    val defaults = listOf(
        // Smartest free model: chat, the Gmail report, the nightly teacher.
        ProviderConfig(
            id = "nvidia-kimi", type = ProviderType.OPENAI_COMPATIBLE, name = "NVIDIA Kimi",
            baseUrl = NVIDIA_URL, model = KIMI_MODEL, builtIn = true,
            keyUrl = "https://build.nvidia.com/settings/api-keys",
        ),
        // Fast and still strong; uses the same NVIDIA key.
        ProviderConfig(
            id = "nvidia-nemotron", type = ProviderType.OPENAI_COMPATIBLE, name = "Nemotron 3 Ultra",
            baseUrl = NVIDIA_URL, model = NEMOTRON_MODEL, builtIn = true,
            keyUrl = "https://build.nvidia.com/settings/api-keys",
        ),
        // Fastest: phone actions.
        ProviderConfig(
            id = "groq", type = ProviderType.OPENAI_COMPATIBLE, name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = GROQ_MODEL, builtIn = true,
            keyUrl = "https://console.groq.com/keys",
        ),
        ProviderConfig(
            id = "gemini", type = ProviderType.GEMINI, name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-flash-latest", builtIn = true,
            keyUrl = "https://aistudio.google.com/apikey",
        ),
        ProviderConfig(
            id = "openrouter", type = ProviderType.OPENAI_COMPATIBLE, name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openrouter/free", builtIn = true,
            keyUrl = "https://openrouter.ai/keys",
        ),
    )

    fun isNvidia(c: ProviderConfig) = "integrate.api.nvidia.com" in c.baseUrl
    fun isKimi(c: ProviderConfig) = isNvidia(c) && (c.model.startsWith("moonshotai/") || c.name.contains("kimi", true))
    fun isNemotron(c: ProviderConfig) = isNvidia(c) && (c.model.contains("nemotron") || c.name.contains("nemotron", true))
    fun isGroq(c: ProviderConfig) = "api.groq.com" in c.baseUrl

    /** Default order: Kimi → Nemotron → Groq → Gemini → OpenRouter → anything else. */
    fun rank(c: ProviderConfig) = when {
        isKimi(c) -> 0
        isNemotron(c) -> 1
        isGroq(c) -> 2
        c.type == ProviderType.GEMINI -> 3
        "openrouter.ai" in c.baseUrl -> 4
        else -> 5
    }
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

    fun hasKey(id: String) = apiKey(id)?.isNotBlank() == true

    /** The provider's own key, or the key of another provider on the same service (e.g. one NVIDIA key for both). */
    fun apiKey(id: String): String? {
        secrets.get(keyName(id))?.takeIf { it.isNotBlank() }?.let { return it }
        val me = _configs.value.firstOrNull { it.id == id } ?: return null
        return _configs.value.filter { it.id != id && it.baseUrl.trimEnd('/') == me.baseUrl.trimEnd('/') }
            .firstNotNullOfOrNull { secrets.get(keyName(it.id))?.takeIf { k -> k.isNotBlank() } }
    }

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

    /** Enabled providers that have a key, in fallback order, with their ids. */
    fun activeProviders(): List<Pair<String, LlmProvider>> = _configs.value
        .filter { it.enabled }
        .mapNotNull { c -> apiKey(c.id)?.takeIf { it.isNotBlank() }?.let { c.id to ProviderFactory.create(c, it) } }

    private fun keyName(id: String) = "api_key_$id"

    /**
     * One-time setup of the recommended defaults on an existing install: fixes NVIDIA model names, adds the
     * missing built-ins, puts them in the recommended order, and returns (chat brain id, action brain id).
     */
    fun applyDefaultsOnce(): Pair<String?, String?>? {
        if (prefs.getBoolean("defaults_v2", false)) return null
        var list = _configs.value.map { c ->
            when {
                ProviderPresets.isKimi(c) && !c.model.startsWith("moonshotai/kimi") -> c.copy(model = ProviderPresets.KIMI_MODEL)
                ProviderPresets.isKimi(c) && c.model.endsWith("/") -> c.copy(model = ProviderPresets.KIMI_MODEL)
                ProviderPresets.isNemotron(c) && (c.model.endsWith("/") || !c.model.contains("nemotron")) -> c.copy(model = ProviderPresets.NEMOTRON_MODEL)
                ProviderPresets.isGroq(c) && c.builtIn -> c.copy(model = ProviderPresets.GROQ_MODEL)
                else -> c
            }
        }
        // add the built-ins that aren't there yet (an existing NVIDIA provider already covers Kimi/Nemotron)
        for (p in ProviderPresets.defaults) {
            val covered = list.any { it.id == p.id } ||
                (ProviderPresets.isKimi(p) && list.any { ProviderPresets.isKimi(it) }) ||
                (ProviderPresets.isNemotron(p) && list.any { ProviderPresets.isNemotron(it) })
            if (!covered) list = list + p
        }
        list = list.sortedBy { ProviderPresets.rank(it) }
        save(list)
        prefs.edit().putBoolean("defaults_v2", true).apply()
        return list.firstOrNull { ProviderPresets.isKimi(it) }?.id to list.firstOrNull { ProviderPresets.isGroq(it) }?.id
    }

    private fun load(): List<ProviderConfig> {
        val stored = prefs.getString("list", null) ?: return ProviderPresets.defaults.also { prefs.edit().putBoolean("defaults_v2", true).apply() }
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
class ProviderRouter(private val providers: () -> List<Pair<String, LlmProvider>>) {

    /** [preferred] = provider id to try first (e.g. the one chosen for chatting or for phone actions). */
    suspend fun complete(
        system: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        preferred: String? = null,
        onAttempt: (name: String, previousFailed: String?) -> Unit = { _, _ -> },
    ): ChatMessage {
        val all = providers()
        val list = (all.filter { it.first == preferred } + all.filterNot { it.first == preferred }).map { it.second }
        if (list.isEmpty()) throw NoProvidersException()
        val errors = mutableListOf<String>()
        var failed: String? = null
        for (p in list) {
            try {
                onAttempt(p.name, failed)
                return p.complete(system, history, tools).copy(providerName = p.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "• ${p.name}: ${e.message}"
                failed = p.name
            }
        }
        throw AllProvidersFailedException(errors)
    }
}

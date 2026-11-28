package com.myassistant.app.web

import android.content.Context
import com.myassistant.app.AssistantApp
import com.myassistant.app.ai.Http
import com.myassistant.app.ai.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Web search: Gemini with Google Search when a Gemini key is saved, otherwise DuckDuckGo's plain results page. */
object WebSearch {
    suspend fun search(ctx: Context, query: String): String {
        gemini(ctx, query)?.let { return it }
        return duck(ctx, query)
    }

    private suspend fun gemini(ctx: Context, query: String): String? {
        val graph = (ctx.applicationContext as? AssistantApp)?.graph ?: return null
        val cfg = graph.providers.configs.value.firstOrNull { it.type == ProviderType.GEMINI && it.enabled && graph.providers.hasKey(it.id) }
            ?: return null
        val key = graph.providers.apiKey(cfg.id) ?: return null
        val model = cfg.model.removePrefix("models/").ifBlank { "gemini-flash-latest" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
                "Search the web and answer briefly with the facts found (dates, numbers, names). Query: $query")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return runCatching {
            val o = Http.postJson("${cfg.baseUrl.trimEnd('/')}/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
            val cand = o.getJSONArray("candidates").getJSONObject(0)
            val parts = cand.getJSONObject("content").getJSONArray("parts")
            val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            val sources = chunks?.let { a ->
                (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("web") }
                    .joinToString("\n") { "- ${it.optString("title")}: ${it.optString("uri")}" }
            }.orEmpty()
            if (text.isBlank()) null else "SEARCH RESULT (Google, via Gemini):\n$text" + if (sources.isNotBlank()) "\n\nSources:\n$sources" else ""
        }.getOrNull()
    }

    private suspend fun duck(ctx: Context, query: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131 Mobile Safari/537.36")
            .build()
        val html = runCatching { Http.client.newCall(req).execute().use { it.body?.string().orEmpty() } }.getOrDefault("")
        val links = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""").findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""").findAll(html).map { strip(it.groupValues[1]) }.toList()
        if (links.isEmpty()) {
            // Fall back to reading a results page in Nova's browser.
            return@withContext runCatching {
                HiddenBrowser.open(ctx, "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            }.getOrElse { "Search failed: ${it.message}" }
        }
        "SEARCH RESULTS (untrusted; open one with web_open to read it):\n" + links.take(8).mapIndexed { i, m ->
            var url = m.groupValues[1].replace("&amp;", "&")
            Regex("""uddg=([^&]+)""").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            "${i + 1}. ${strip(m.groupValues[2])}\n   $url" + (snippets.getOrNull(i)?.let { "\n   $it" } ?: "")
        }.joinToString("\n")
    }

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").trim()
}

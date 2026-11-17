package com.myassistant.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RememberTool : Tool {
    override val name = "remember"
    override val title = "حفظ في الذاكرة"
    override val description =
        "Save a lasting fact about the user (name, people in their life, preferences, important dates) " +
            "to long-term memory on the phone. Write it as one clear sentence."
    override val parameters = Schema.obj(
        "fact" to Schema.string("The fact to remember, one sentence"),
        "category" to Schema.string("One of: " + com.myassistant.app.memory.MemoryCategories.all.keys.joinToString(" | ")),
        required = listOf("fact", "category"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val fact = args.str("fact")
        if (fact.isEmpty()) return "Nothing to save"
        val cat = com.myassistant.app.memory.MemoryCategories.normalize(args.str("category"))
        withContext(Dispatchers.IO) { ctx.memory.add(fact, cat) }
        return "Saved ($cat)"
    }
}

class SearchMemoryTool : Tool {
    override val name = "search_memory"
    override val title = "بحث في الذاكرة"
    override val description = "Search the user's long-term memory for facts related to a query, optionally only in one category."
    override val parameters = Schema.obj(
        "query" to Schema.string("Keywords to search for (or empty to list a whole category)"),
        "category" to Schema.string("Optional: " + com.myassistant.app.memory.MemoryCategories.all.keys.joinToString(" | ")),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val cat = args.str("category").takeIf { it.isNotEmpty() }?.let { com.myassistant.app.memory.MemoryCategories.normalize(it) }
        val found = if (args.str("query").isBlank() && cat != null) {
            withContext(Dispatchers.IO) { ctx.memory.recent(2000).filter { it.category == cat }.take(40) }
        } else {
            ctx.recall.memories(args.str("query"), 12).filter { cat == null || it.category == cat }
        }
        return if (found.isEmpty()) "No matching memories" else found.joinToString("\n") { "- [${it.category}] ${it.text}" }
    }
}

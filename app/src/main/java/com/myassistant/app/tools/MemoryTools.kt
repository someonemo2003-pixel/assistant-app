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
        required = listOf("fact"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val fact = args.str("fact")
        if (fact.isEmpty()) return "Nothing to save"
        withContext(Dispatchers.IO) { ctx.memory.add(fact) }
        return "Saved"
    }
}

class SearchMemoryTool : Tool {
    override val name = "search_memory"
    override val title = "بحث في الذاكرة"
    override val description = "Search the user's long-term memory for facts related to a query."
    override val parameters = Schema.obj(
        "query" to Schema.string("Keywords to search for"),
        required = listOf("query"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val found = withContext(Dispatchers.IO) { ctx.memory.search(args.str("query"), 8) }
        return if (found.isEmpty()) "No matching memories" else found.joinToString("\n") { "- ${it.text}" }
    }
}

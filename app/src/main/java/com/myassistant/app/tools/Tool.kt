package com.myassistant.app.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.myassistant.app.ai.ToolSpec
import com.myassistant.app.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One capability of the assistant. To add a new one:
 *  1. create a class implementing Tool (one file),
 *  2. add it to the list in AppGraph.
 * The model discovers it automatically from [name], [description] and [parameters].
 */
interface Tool {
    /** Machine name the model calls, e.g. "set_alarm". */
    val name: String
    /** Short Arabic label shown in the chat, e.g. "منبه". */
    val title: String
    /** Tells the model what the tool does and when to use it. */
    val description: String
    /** JSON-schema of the arguments (build it with [Schema]). */
    val parameters: JSONObject
    /** If true, the user must approve before it runs (sending, deleting, paying...). */
    val requiresConfirmation: Boolean get() = false

    /** Human sentence shown in the approval dialog. */
    fun describe(args: JSONObject): String = title

    /** Do the work and return a short result for the model. */
    suspend fun execute(args: JSONObject, ctx: ToolContext): String
}

class ToolContext(val app: Context, val memory: MemoryStore) {
    /** Starts an activity from anywhere; returns false if nothing on the phone can handle it. */
    suspend fun launch(intent: Intent): Boolean = withContext(Dispatchers.Main) {
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

class ToolRegistry(val all: List<Tool>) {
    private val byName = all.associateBy { it.name }
    operator fun get(name: String): Tool? = byName[name]
    fun specs(): List<ToolSpec> = all.map { ToolSpec(it.name, it.description, it.parameters) }
}

/** Tiny helper to write argument schemas that both Gemini and OpenAI-style APIs accept. */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (k, v) -> properties.put(k, v) }
        val o = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) o.put("required", JSONArray(required))
        return o
    }

    fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)
}

fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key).trim()
fun JSONObject.int(key: String): Int? = if (isNull(key)) null else when (val v = opt(key)) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

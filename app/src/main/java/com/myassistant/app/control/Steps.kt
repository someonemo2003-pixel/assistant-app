package com.myassistant.app.control

import android.view.accessibility.AccessibilityNodeInfo
import com.myassistant.app.tools.OpenAppTool
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes screen steps quickly ("tap id:…", "type: …", "open_app Gmail" …), waiting only until the
 * screen settles. In live mode it records each step by a stable selector (view id / text) so the
 * whole task can be replayed later without the AI.
 */
class StepRunner(private val s: ScreenService, private val ctx: ToolContext) {

    private val openApp = OpenAppTool()

    /** Runs steps in order, stopping at the first failure. Returns a short log (starts with "Error:" on failure). */
    suspend fun runAll(steps: List<String>, replay: Boolean): String {
        val log = StringBuilder()
        for ((i, step) in steps.withIndex()) {
            val (ok, canonical, note) = runCatching { runOne(step, replay) }
                .getOrElse { Triple(false, null, it.message ?: "error") }
            if (!ok) {
                if (!replay) ctx.record(null)
                return "Error: step ${i + 1} \"$step\" failed: $note\n$log"
            }
            if (!replay) ctx.record(canonical)
            log.append("✓ ").append(step).append('\n')
        }
        return log.toString().trim()
    }

    private suspend fun runOne(step: String, replay: Boolean): Triple<Boolean, String?, String> {
        val verb = step.substringBefore(' ').lowercase()
        val rest = step.substringAfter(' ', "").trim()
        return when (verb) {
            "open_app" -> {
                val r = withContext(Dispatchers.Main) { openApp.execute(JSONObject().put("app_name", rest), ctx) }
                if (!r.startsWith("Opened")) return Triple(false, null, r)
                s.waitIdle(minMs = 350, quietMs = 300, maxMs = 3000)
                Triple(true, "open_app $rest", r)
            }
            "tap", "long_press" -> {
                val (node, selector) = locate(rest, replay) ?: return Triple(false, null, "element not found")
                if (!s.tap(node, long = verb == "long_press")) return Triple(false, null, "tap did not work")
                s.waitIdle()
                Triple(true, "$verb $selector", "")
            }
            "type" -> {
                val target = step.removePrefix(step.substringBefore(' ')).substringBefore(": ", "").trim().removePrefix(":").trim()
                val text = if (step.contains(": ")) step.substringAfter(": ") else rest.removePrefix(":").trim()
                val located = if (target.isEmpty()) null else locate(target, replay)
                if (target.isNotEmpty() && located == null) return Triple(false, null, "field not found")
                if (!s.type(located?.first, text, append = false)) return Triple(false, null, "no text field")
                s.waitIdle(minMs = 80, quietMs = 150, maxMs = 800)
                Triple(true, "type ${located?.second.orEmpty()}: $text", "")
            }
            "scroll" -> {
                val down = rest != "up"
                if (!s.scroll(forward = down)) return Triple(false, null, "nothing to scroll")
                s.waitIdle()
                Triple(true, "scroll ${if (down) "down" else "up"}", "")
            }
            "back", "home", "recents", "notifications", "quick_settings", "screenshot", "lock_screen" -> {
                if (!s.global(verb)) return Triple(false, null, "button not available")
                s.waitIdle()
                Triple(true, verb, "")
            }
            "wait" -> {
                delay((rest.toLongOrNull() ?: 500).coerceIn(50, 3000))
                Triple(true, step, "")
            }
            else -> Triple(false, null, "unknown step (use open_app, tap, long_press, type, scroll, back, home, wait)")
        }
    }

    /** Finds an element by number (latest screen), "id:…", or "text:…"; returns it with a stable selector. */
    private suspend fun locate(target: String, replay: Boolean): Pair<AccessibilityNodeInfo, String>? {
        target.toIntOrNull()?.let { n ->
            if (replay) return null
            val node = s.element(n)?.takeIf { it.refresh() } ?: return null
            return node to (s.selectorOf(node) ?: return null)
        }
        val sel = when {
            target.startsWith("id:") || target.startsWith("text:") -> target
            else -> "text:$target"
        }
        val node = if (replay) s.waitFor(sel) else s.find(sel) ?: s.waitFor(sel, 1500)
        return node?.let { it to sel }
    }
}

/** Replays a recorded/learned sequence of screen steps without the AI. */
class RunRecipeTool : Tool {
    override val name = "run_recipe"
    override val title = "خطوات محفوظة"
    override val description = "Replay a saved sequence of screen steps (used by learned shortcuts)."
    override val parameters = Schema.obj("steps" to Schema.string("JSON array of steps"), required = listOf("steps"))
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val s = ScreenService.current ?: return SCREEN_OFF
        val steps = parseSteps(args.opt("steps"))
        if (steps.isEmpty()) return "Error: empty recipe"
        val log = withContext(Dispatchers.Default) { StepRunner(s, ctx).runAll(steps, replay = true) }
        return if (log.startsWith("Error")) log else "Recipe done (${steps.size} steps)"
    }

    companion object {
        fun parseSteps(v: Any?): List<String> = when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> runCatching { JSONArray(v) }.map { a -> (0 until a.length()).map { a.optString(it) } }
                .getOrElse { v.lines() }
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** "احفظ الخطوات دي باسم …" — saves the steps of the last screen task as a personal shortcut. */
class SaveStepsTool : Tool {
    override val name = "save_last_steps"
    override val title = "حفظ الخطوات"
    override val description =
        "Save the screen steps of the previous task as a shortcut phrase, so saying that phrase replays them instantly."
    override val parameters = Schema.obj(
        "phrase" to Schema.string("The phrase the user will say"),
        "reply" to Schema.string("Short confirmation to show when it runs"),
        required = listOf("phrase"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val steps = ctx.lastRecipe ?: return "No recorded steps yet. Do the task once first."
        val ok = withContext(Dispatchers.IO) {
            ctx.experience.learn(
                args.str("phrase"), "run_recipe", JSONObject().put("steps", JSONArray(steps)).toString(),
                args.str("reply").ifEmpty { "تم ✓" }, "user",
            )
        }
        return if (ok) "Saved ${steps.size} steps as \"${args.str("phrase")}\"" else "Could not save"
    }
}

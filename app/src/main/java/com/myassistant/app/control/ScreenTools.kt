package com.myassistant.app.control

import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val SCREEN_OFF =
    "Screen control is OFF. Tell the user to enable it: ⚙️ → الصلاحيات → التحكم في الشاشة (Accessibility)."

/** Screen tools share one approval per request (see Agent) — the user approves "control the screen" once. */
abstract class ScreenTool : Tool {
    override val group: String get() = "screen"
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = ctx.settings.askBeforeScreen.value

    protected suspend fun withService(block: suspend (ScreenService) -> String): String {
        val s = ScreenService.current ?: return SCREEN_OFF
        return withContext(Dispatchers.Default) { block(s) }
    }
}

class ReadScreenTool : ScreenTool() {
    override val name = "read_screen"
    override val title = "قراءة الشاشة"
    override val description =
        "Read what's on the phone screen (any app) as a numbered list of elements with their view ids (#id). " +
            "Use before tapping. Prefer do_steps to perform several actions at once."
    override val parameters = Schema.obj()
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = false
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { it.readScreen() }
}

class DoStepsTool : ScreenTool() {
    override val name = "do_steps"
    override val title = "تنفيذ خطوات"
    override val description =
        "Do one or MORE screen actions in a single call (fast). One step per line:\n" +
            "open_app NAME | tap N | tap id:FULL_ID | tap text:LABEL | long_press N | type N: TEXT | type: TEXT (focused field) | " +
            "scroll down | scroll up | back | home | recents | notifications | wait MS\n" +
            "N is an element number from the latest screen. Plan as many steps as you safely can, e.g. " +
            "\"long_press 3\\ntap text:Select all\\ntap id:com.google.android.gm:id/mark_read\". " +
            "Stops at the first failing step. Returns the resulting screen."
    override val parameters = Schema.obj(
        "steps" to Schema.string("Steps, one per line"),
        required = listOf("steps"),
    )
    override fun describe(args: JSONObject) = args.str("steps").lines().firstOrNull().orEmpty().take(60)

    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val steps = args.str("steps").lines().map { it.trim() }.filter { it.isNotEmpty() }.take(15)
        if (steps.isEmpty()) return@withService "No steps"
        val log = StepRunner(s, ctx).runAll(steps, replay = false)
        log + "\n\n" + s.readScreen()
    }
}

/** Kept for models that prefer single actions; all route through the same fast step engine. */
class TapTool : ScreenTool() {
    override val name = "tap"
    override val title = "دوسة"
    override val description = "Tap one element (by number from read_screen, or by visible text). Prefer do_steps for several actions."
    override val parameters = Schema.obj(
        "element" to Schema.integer("Element number from read_screen"),
        "text" to Schema.string("Or: visible text of the element"),
        "long_press" to Schema.string("'true' for a long press"),
    )
    override fun describe(args: JSONObject) = "الدوس على " + args.str("text").ifEmpty { "عنصر ${args.int("element") ?: "?"}" }

    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val target = args.int("element")?.toString() ?: "text:" + args.str("text")
        val verb = if (args.str("long_press") == "true") "long_press" else "tap"
        StepRunner(s, ctx).runAll(listOf("$verb $target"), replay = false) + "\n\n" + s.readScreen()
    }
}

class TypeTextTool : ScreenTool() {
    override val name = "type_text"
    override val title = "كتابة"
    override val description = "Type text into a field (by element number, or the focused/first field)."
    override val parameters = Schema.obj(
        "text" to Schema.string("Text to type"),
        "element" to Schema.integer("Optional element number of the field"),
        required = listOf("text"),
    )
    override fun describe(args: JSONObject) = "كتابة: \"${args.str("text").take(60)}\""

    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val step = (args.int("element")?.let { "type $it: " } ?: "type: ") + args.str("text")
        StepRunner(s, ctx).runAll(listOf(step), replay = false) + "\n\n" + s.readScreen()
    }
}

class ScrollTool : ScreenTool() {
    override val name = "scroll"
    override val title = "تقليب"
    override val description = "Scroll the screen down (to see more) or up."
    override val parameters = Schema.obj("direction" to Schema.string("'down' or 'up'"), required = listOf("direction"))
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = false
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        StepRunner(s, ctx).runAll(listOf("scroll " + args.str("direction").ifEmpty { "down" }), replay = false) + "\n\n" + s.readScreen()
    }
}

class PhoneButtonTool : ScreenTool() {
    override val name = "press_button"
    override val title = "زرار الموبايل"
    override val description = "Press a system button: back, home, recents, notifications, quick_settings, lock_screen, screenshot."
    override val parameters = Schema.obj(
        "button" to Schema.string("back | home | recents | notifications | quick_settings | lock_screen | screenshot"),
        required = listOf("button"),
    )
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = args.str("button") == "lock_screen"
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        StepRunner(s, ctx).runAll(listOf(args.str("button")), replay = false) + "\n\n" + s.readScreen()
    }
}

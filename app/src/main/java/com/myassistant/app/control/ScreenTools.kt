package com.myassistant.app.control

import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.int
import com.myassistant.app.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val NOT_ENABLED =
    "Screen control is OFF. Tell the user to enable it: ⚙️ → الصلاحيات → التحكم في الشاشة (Accessibility)."

/** Screen tools share one approval per request (see Agent) — the user approves "control the screen" once. */
abstract class ScreenTool : Tool {
    override val group: String get() = "screen"
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = ctx.settings.askBeforeScreen.value

    protected suspend fun withService(block: suspend (ScreenService) -> String): String {
        val s = ScreenService.current ?: return NOT_ENABLED
        return withContext(Dispatchers.Default) { block(s) }
    }

    /** After acting, wait for the UI to settle and return what's on screen now. */
    protected suspend fun after(s: ScreenService, what: String): String {
        delay(900)
        return "$what\n\n" + s.readScreen()
    }
}

class ReadScreenTool : ScreenTool() {
    override val name = "read_screen"
    override val title = "قراءة الشاشة"
    override val description =
        "Read what's currently on the phone screen (any app): visible texts, buttons and fields, numbered. " +
            "Use before tapping or typing."
    override val parameters = Schema.obj()
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = false
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { it.readScreen() }
}

class TapTool : ScreenTool() {
    override val name = "tap"
    override val title = "دوسة"
    override val description =
        "Tap an element on screen, by its number from read_screen, or by its visible text. Returns the new screen."
    override val parameters = Schema.obj(
        "element" to Schema.integer("Element number from read_screen"),
        "text" to Schema.string("Or: visible text/label of the element to tap"),
        "long_press" to Schema.string("'true' for a long press"),
    )
    override fun describe(args: JSONObject) = "الدوس على " + (args.str("text").ifEmpty { "عنصر رقم ${args.int("element") ?: "?"}" })

    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val node = args.int("element")?.let { s.element(it) } ?: args.str("text").takeIf { it.isNotEmpty() }?.let { s.findByText(it) }
            ?: return@withService "Element not found. Call read_screen and use a number from the list."
        val label = s.label(node)
        if (s.tap(node, long = args.str("long_press") == "true")) after(s, "Tapped \"$label\".") else "Tap failed on \"$label\""
    }
}

class TypeTextTool : ScreenTool() {
    override val name = "type_text"
    override val title = "كتابة"
    override val description =
        "Type text into a field on screen (by element number, or the focused/first text field). Returns the new screen."
    override val parameters = Schema.obj(
        "text" to Schema.string("Text to type"),
        "element" to Schema.integer("Optional element number of the text field"),
        "append" to Schema.string("'true' to add to existing text instead of replacing it"),
        required = listOf("text"),
    )
    override fun describe(args: JSONObject) = "كتابة: \"${args.str("text").take(60)}\""

    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val node = args.int("element")?.let { s.element(it) }
        if (s.type(node, args.str("text"), append = args.str("append") == "true")) after(s, "Typed.") else "No text field found to type into."
    }
}

class ScrollTool : ScreenTool() {
    override val name = "scroll"
    override val title = "تقليب"
    override val description = "Scroll the screen down (to see more) or up. Returns the new screen."
    override val parameters = Schema.obj(
        "direction" to Schema.string("'down' or 'up'"),
        required = listOf("direction"),
    )
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = false
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        if (s.scroll(forward = args.str("direction") != "up")) after(s, "Scrolled.") else "Nothing to scroll."
    }
}

class PhoneButtonTool : ScreenTool() {
    override val name = "press_button"
    override val title = "زرار الموبايل"
    override val description =
        "Press a system button: back, home, recents, notifications (pull down shade), quick_settings, lock_screen, screenshot."
    override val parameters = Schema.obj(
        "button" to Schema.string("back | home | recents | notifications | quick_settings | lock_screen | screenshot"),
        required = listOf("button"),
    )
    override fun needsConfirmation(args: JSONObject, ctx: ToolContext) = args.str("button") == "lock_screen"
    override suspend fun execute(args: JSONObject, ctx: ToolContext) = withService { s ->
        val b = args.str("button")
        if (s.global(b)) after(s, "Pressed $b.") else "Could not press $b"
    }
}

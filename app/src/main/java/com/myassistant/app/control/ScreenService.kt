package com.myassistant.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * The assistant's eyes and hands: reads what's on screen and taps/types/scrolls in any app.
 * Enabled by the user in Settings → Accessibility.
 */
class ScreenService : AccessibilityService() {

    /** Elements from the last screen read, so the AI can refer to them by number. */
    private var lastElements: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    /** Time of the last UI change anywhere on screen (used to know when the screen has settled). */
    @Volatile private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    /** Waits until the screen stops changing (much faster than a fixed delay). */
    suspend fun waitIdle(minMs: Long = 120, quietMs: Long = 220, maxMs: Long = 1500) {
        val start = SystemClock.uptimeMillis()
        delay(minMs)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(60)
        }
    }

    /** A stable way to find this element again later: its view id, or its text. */
    fun selectorOf(node: AccessibilityNodeInfo): String? {
        val id = node.viewIdResourceName
        if (!id.isNullOrEmpty()) {
            val same = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id).orEmpty()
            if (same.size == 1) return "id:$id"
        }
        val label = label(node)
        return if (label.isNotEmpty()) "text:$label" else id?.let { "id:$it" }
    }

    /** Finds an element by "id:..." or "text:..." (fast, native lookup). */
    fun find(selector: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return when {
            selector.startsWith("id:") ->
                root.findAccessibilityNodeInfosByViewId(selector.removePrefix("id:")).firstOrNull { it.isVisibleToUser }
            selector.startsWith("text:") -> {
                val t = selector.removePrefix("text:").trim()
                val hits = root.findAccessibilityNodeInfosByText(t).filter { it.isVisibleToUser }
                hits.firstOrNull { label(it).equals(t, ignoreCase = true) } ?: hits.firstOrNull()
            }
            else -> null
        }
    }

    /** Polls for an element to appear (e.g. right after opening an app). */
    suspend fun waitFor(selector: String, timeoutMs: Long = 3500): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            find(selector)?.let { return it }
            delay(120)
        }
        return null
    }
    override fun onInterrupt() = Unit

    // ---------- reading ----------

    fun readScreen(maxElements: Int = 90): String {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            if (isEmpty()) windows.mapNotNullTo(this) { it.root }
        }
        if (roots.isEmpty()) return "Screen not available (the phone may be locked)."
        val elements = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        val pkg = roots.first().packageName?.toString().orEmpty()
        for (root in roots) collect(root, elements, lines, 0, maxElements)
        lastElements = elements
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        return buildString {
            appendLine("Screen: $app ($pkg). Elements [n]:")
            lines.forEach { appendLine(it) }
            if (elements.size >= maxElements) appendLine("…(more elements not shown; scroll to see more)")
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        lines: MutableList<String>,
        depth: Int,
        max: Int,
    ) {
        if (out.size >= max || depth > 40) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
        val label = listOf(text, desc, hint).firstOrNull { it.isNotEmpty() }.orEmpty()
        val interesting = label.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        if (interesting && (label.isNotEmpty() || node.isEditable || node.isScrollable)) {
            out += node
            val flags = buildList {
                if (node.isClickable || node.isLongClickable || parentClickable(node)) add("tap")
                if (node.isEditable) add("type")
                if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "on" else "off")
                if (!node.isEnabled) add("disabled")
            }.joinToString(",")
            val id = node.viewIdResourceName?.substringAfter(":id/")?.let { " #$it" }.orEmpty()
            lines += "[${out.size}] \"${label.take(60).replace('\n', ' ')}\"" + (if (flags.isNotEmpty()) " ($flags)" else "") + id
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, lines, depth + 1, max) }
        }
    }

    private fun parentClickable(n: AccessibilityNodeInfo): Boolean {
        var p = n.parent
        var hops = 0
        while (p != null && hops < 3) {
            if (p.isClickable) return true
            p = p.parent
            hops++
        }
        return false
    }

    // ---------- acting ----------

    fun element(index: Int): AccessibilityNodeInfo? = lastElements.getOrNull(index - 1)

    /** Finds an element on the current screen whose text/description contains [text]. */
    fun findByText(text: String): AccessibilityNodeInfo? {
        val want = text.trim().lowercase()
        if (want.isEmpty()) return null
        readScreen()
        return lastElements.firstOrNull { label(it).lowercase() == want }
            ?: lastElements.firstOrNull { label(it).lowercase().contains(want) }
    }

    fun label(n: AccessibilityNodeInfo) =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    suspend fun tap(node: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if ((if (long) n.isLongClickable else n.isClickable) && n.performAction(action)) return true
            n = n.parent
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        return gestureTap(r.exactCenterX(), r.exactCenterY(), if (long) 700 else 60)
    }

    fun type(node: AccessibilityNodeInfo?, text: String, append: Boolean): Boolean {
        val target = node?.takeIf { it.isEditable }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: run { readScreen(); lastElements.firstOrNull { it.isEditable } }
            ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val current = if (append) target.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun scroll(forward: Boolean): Boolean {
        readScreen()
        val scrollable = lastElements.firstOrNull { it.isScrollable }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable != null && scrollable.performAction(action)) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (forward) dm.heightPixels * 0.75f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.75f
        return gesture(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 350)
    }

    fun global(action: String): Boolean = when (action) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }

    private suspend fun gestureTap(x: Float, y: Float, durationMs: Long) =
        gesture(Path().apply { moveTo(x, y) }, durationMs)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
        }, null)
        if (!ok) return false
        return withTimeoutOrNull(3000) { done.await() } ?: false
    }

    companion object {
        @Volatile private var instance: WeakReference<ScreenService>? = null
        val current: ScreenService? get() = instance?.get()
    }
}

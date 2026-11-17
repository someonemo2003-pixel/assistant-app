package com.myassistant.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ---------- reading ----------

    fun readScreen(maxElements: Int = 150): String {
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
            appendLine("App on screen: $app ($pkg)")
            appendLine("Elements (use the number in [ ] to tap or type):")
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
            val kind = node.className?.toString()?.substringAfterLast('.').orEmpty()
            val flags = buildList {
                if (node.isClickable || node.isLongClickable) add("tap")
                if (node.isEditable) add("type")
                if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isSelected) add("selected")
                if (!node.isEnabled) add("disabled")
            }.joinToString(",")
            lines += "[${out.size}] $kind \"${label.take(80).replace('\n', ' ')}\"" + if (flags.isNotEmpty()) " ($flags)" else ""
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, lines, depth + 1, max) }
        }
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

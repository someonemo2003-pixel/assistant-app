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

    private var installWatcher: android.content.BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        installWatcher = runCatching { com.myassistant.app.admin.DeviceOwner.registerInstallWatcher(this) }.getOrNull()
        runCatching { com.myassistant.app.admin.DeviceOwner.enforce(applicationContext) }
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        installWatcher?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private var lastGuardAt = 0L

    /** Blocked apps: leave their store/installer page; confirm uninstalls we asked for; protect ourselves. */
    private var lastFocusAt = 0L

    /** Focus mode: leave apps that are blocked right now (backup for when we're not device owner). */
    private fun focusGuard(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusAt < 1500) return
        val blocked = com.myassistant.app.focus.Focus.blockedCached(this)
        val why = blocked[pkg] ?: return
        lastFocusAt = now
        // A limit may have just been reached: make it stick (suspend) when we can.
        if (com.myassistant.app.admin.DeviceOwner.isOwner(this)) Thread { com.myassistant.app.focus.Focus.apply(applicationContext) }.start()
        performGlobalAction(GLOBAL_ACTION_HOME)
        toast("⏳ $why")
    }

    private fun guard(event: AccessibilityEvent) {
        runCatching { focusGuard(event) }
        val pkg = event.packageName?.toString() ?: return
        val isStore = pkg in com.myassistant.app.admin.DeviceOwner.STORE_PKGS || "packageinstaller" in pkg || "permissioncontroller" in pkg
        if (!isStore) {
            if (pkg != "com.android.systemui" && pkg != packageName) clearCovers()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastGuardAt < 300) {
            // Run once more after things settle, so covers follow scrolling.
            if (!recheckPosted) {
                recheckPosted = true
                handler.postDelayed({
                    recheckPosted = false
                    runCatching {
                        val r = rootInActiveWindow
                        val p = r?.packageName?.toString()
                        if (r != null && p != null && p in com.myassistant.app.admin.DeviceOwner.STORE_PKGS) {
                            lastGuardAt = SystemClock.uptimeMillis()
                            storeGuard(r, com.myassistant.app.admin.DeviceOwner.storeBlocked(this))
                        }
                    }
                }, 350)
            }
            return
        }
        lastGuardAt = now
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        // One label per node (text, else description) so a button isn't counted twice.
        val labels = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || texts.size > 600) return
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            t?.let { texts += it }
            d?.let { texts += it }
            (t ?: d)?.trim()?.takeIf { it.isNotEmpty() }?.let { labels += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val all = texts.joinToString("\n")

        // 1) An uninstall we started (blocked app): press the confirm button.
        val pending = com.myassistant.app.admin.DeviceOwner.pendingUninstall
        if (pending != null && ("packageinstaller" in pkg || "permissioncontroller" in pkg)) {
            val ok = listOf("OK", "موافق", "حسنًا", "حسناً", "إلغاء التثبيت", "Uninstall", "حذف", "إزالة")
            for (w in ok) {
                val btn = root.findAccessibilityNodeInfosByText(w).firstOrNull { it.isClickable && it.className?.contains("Button") == true }
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    com.myassistant.app.admin.DeviceOwner.pendingUninstall = null
                    return
                }
            }
            return
        }

        // 2) Someone trying to uninstall the assistant while a PIN is set.
        if (("packageinstaller" in pkg || "permissioncontroller" in pkg) && com.myassistant.app.admin.DeviceOwner.hasPin(this) &&
            (all.contains("Nova") || all.contains("مساعدي")) && Regex("إلغاء التثبيت|uninstall|حذف التطبيق|إزالة التطبيق|إلغاء تثبيت", RegexOption.IGNORE_CASE).containsMatchIn(all)
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            toast("🔒 Nova محمي — شيل الحماية من الإعدادات بالرقم السري")
            return
        }

        val blockedApps = com.myassistant.app.admin.DeviceOwner.storeBlocked(this)
        if (blockedApps.isEmpty()) { clearCovers(); return }

        // 3) The system installer (APK files): just leave it.
        if ("packageinstaller" in pkg) {
            val trimmed = texts.map { it.trim() }
            val hit = blockedApps.firstOrNull { b -> b.name.length >= 3 && trimmed.any { it.equals(b.name, true) } } ?: return
            performGlobalAction(GLOBAL_ACTION_BACK)
            toast("🚫 ${hit.name} ممنوع على الموبايل ده")
            return
        }

        // 4) Stores: cover only the install button that belongs to a blocked app, and cancel a download if one started.
        storeGuard(root, blockedApps)
    }

    private val handler by lazy { android.os.Handler(mainLooper) }
    @Volatile private var recheckPosted = false
    private val covers = ArrayList<android.view.View>()

    private val installWords = listOf("تثبيت", "Install", "تنزيل", "Get", "تحديث", "Update")
    private val cancelWords = listOf("إلغاء", "Cancel")

    private fun nodeLabel(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** Nodes on screen whose own label is exactly one of [words]. */
    private fun exact(root: AccessibilityNodeInfo, words: List<String>): List<AccessibilityNodeInfo> =
        words.flatMap { w -> root.findAccessibilityNodeInfosByText(w).filter { nodeLabel(it).equals(w, true) } }
            .distinctBy { val r = Rect(); it.getBoundsInScreen(r); r.toShortString() }

    private fun isTitle(label: String, name: String) =
        label.equals(name, true) || listOf(":", " -", " –", " |", " (").any { label.startsWith(name + it, true) } ||
            (com.myassistant.app.admin.DeviceOwner.isPrefixName(name) && label.startsWith("$name ", true) &&
                (label.contains("brows", true) || label.contains("متصفح") || label.contains("web", true)))

    /**
     * The card/page a button belongs to: climb up from the button until a parent also shows the blocked app's name.
     * If on the way the parent already holds more than 2 install buttons, the button belongs to some other app.
     */
    private fun ownerCard(btn: AccessibilityNodeInfo, names: List<String>): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = btn.parent
        while (n != null) {
            if (exact(n, installWords).size > 2) return null
            val hasName = names.any { name -> n!!.findAccessibilityNodeInfosByText(name).any { isTitle(nodeLabel(it), name) } }
            if (hasName) return n
            n = n.parent
        }
        return null
    }

    private fun storeGuard(root: AccessibilityNodeInfo, blocked: List<com.myassistant.app.admin.BlockedApp>) {
        val buttons = exact(root, installWords)
        val cancels = exact(root, cancelWords)
        val toCover = ArrayList<Rect>()
        var hitName: String? = null
        val seen = ArrayList<String>()
        for (b in blocked) {
            val names = com.myassistant.app.admin.DeviceOwner.storeNames(b)
            val onScreen = names.filter { name -> root.findAccessibilityNodeInfosByText(name).any { isTitle(nodeLabel(it), name) } }
            if (onScreen.isEmpty()) continue
            seen += b.name
            for (btn in buttons) {
                ownerCard(btn, onScreen) ?: continue
                val r = Rect(); btn.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) { toCover += r; hitName = b.name }
            }
            // A download already started: press its Cancel.
            for (c in cancels) {
                if (ownerCard(c, onScreen) != null) {
                    val target = generateSequence(c) { it.parent }.take(3).firstOrNull { it.isClickable }
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) toast("🚫 ${b.name} ممنوع — لغيت التحميل")
                }
            }
        }
        com.myassistant.app.admin.DeviceOwner.lastStoreCheck =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                " · ${root.packageName} · أزرار تثبيت: ${buttons.size} (${buttons.joinToString { nodeLabel(it) }})" +
                " · ممنوع ظاهر: ${seen.ifEmpty { listOf("لا") }.joinToString()} · اتغطى: ${toCover.size}"
        handler.post { showCovers(toCover, hitName) }
    }

    private fun clearCovers() = handler.post {
        val wm = getSystemService(android.view.WindowManager::class.java)
        covers.forEach { runCatching { wm.removeView(it) } }
        covers.clear()
    }

    /** Draws a "blocked" label over each button; it swallows taps so the button can't be pressed. */
    private fun showCovers(rects: List<Rect>, name: String?) {
        val wm = getSystemService(android.view.WindowManager::class.java)
        covers.forEach { runCatching { wm.removeView(it) } }
        covers.clear()
        for (r in rects) {
            val v = android.widget.TextView(this).apply {
                text = "🚫 ممنوع"
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#E5DC2626")); cornerRadius = r.height() / 2f
                }
                setOnClickListener { toast("🚫 ${name ?: "البرنامج ده"} ممنوع على الموبايل ده") }
            }
            val lp = android.view.WindowManager.LayoutParams(
                r.width(), r.height(), r.left, r.top,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT }
            if (runCatching { wm.addView(v, lp) }.isSuccess) covers += v
        }
    }

    private fun toast(t: String) = android.os.Handler(mainLooper).post {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Time of the last UI change anywhere on screen (used to know when the screen has settled). */
    @Volatile private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
        if (event != null) runCatching { guard(event) }
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

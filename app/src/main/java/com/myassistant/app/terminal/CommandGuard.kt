package com.myassistant.app.terminal

/**
 * Sorts a terminal command before Nova runs it.
 *  READ      – only looks at things: runs right away (planning is read-only).
 *  CHANGE    – changes something: the user approves the plan first.
 *  ROOT      – tries to root the phone (would void the warranty): always refused.
 *  PROTECTED – tries to undo Nova's own control (device owner, blocked apps): always refused.
 * Pure Kotlin so it is unit-tested.
 */
object CommandGuard {
    enum class Level { READ, CHANGE, ROOT, PROTECTED }

    data class Verdict(val level: Level, val reason: String = "")

    private val ROOT = listOf(
        Regex("""(^|[\s;&|(`'"])(su|tsu|sudo)(\s|$|[;&|)'"])"""),
        Regex("""magisk|kernelsu|\bksud?\b|apatch|supersu|\bkingroot\b|zygisk""", RegexOption.IGNORE_CASE),
        Regex("""\bfastboot\b|flashing\s+unlock|oem\s+unlock|unlock[_ -]?bootloader""", RegexOption.IGNORE_CASE),
    )

    private val PROTECTED = listOf(
        Regex("""\b(pm|am|cmd|appops|settings|monkey)\b.*com\.myassistant\.app"""),
        Regex("""\bdpm\b|remove-active-admin|(set|clear)-device-owner|clear-device-owner-app"""),
        Regex("""\bunsuspend\b"""),
    )

    private val READ_CMDS = setOf(
        "ls", "cat", "pwd", "echo", "printf", "head", "tail", "grep", "egrep", "rg", "find", "tree", "less", "more",
        "which", "type", "whoami", "id", "uname", "df", "du", "free", "ps", "uptime", "date", "env", "printenv",
        "wc", "file", "stat", "sort", "uniq", "diff", "cut", "awk", "sed", "jq", "basename", "dirname", "realpath",
        "termux-info", "nproc", "lscpu", "true", "cd", "history",
    )
    private val VERSION_ONLY = setOf("python", "python3", "node", "npm", "pip", "java", "opencode", "proot-distro", "bash", "git", "pkg", "apt")
    private val GIT_READ = setOf("status", "log", "diff", "show", "branch", "remote", "rev-parse", "ls-files", "blame")
    private val PKG_READ = setOf("list", "list-installed", "search", "show", "info", "files")

    fun check(command: String): Verdict {
        val c = command.trim()
        if (c.isEmpty()) return Verdict(Level.CHANGE, "empty")
        ROOT.firstOrNull { it.containsMatchIn(c) }?.let { return Verdict(Level.ROOT, "root") }
        PROTECTED.firstOrNull { it.containsMatchIn(c) }?.let { return Verdict(Level.PROTECTED, "nova") }
        return if (isReadOnly(c)) Verdict(Level.READ) else Verdict(Level.CHANGE)
    }

    /** True when a free-text task asks to root the phone. */
    fun mentionsRoot(text: String): Boolean =
        ROOT.any { it.containsMatchIn(text) } || Regex("""\broot(ing)?\s+(the\s+)?phone|روت""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun isReadOnly(c: String): Boolean {
        // Hidden sub-commands or writing to files are never "read only".
        if (c.contains("`") || c.contains("$(") || c.contains("<(") || c.contains(">(")) return false
        val noSafe = c.replace(Regex("""\d?>\s*/dev/null|2>&1"""), "")
        if (noSafe.contains('>')) return false
        val parts = noSafe.split(Regex("""&&|\|\||;|\||\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { segmentIsRead(it) }
    }

    private fun segmentIsRead(seg: String): Boolean {
        val words = seg.split(Regex("""\s+""")).dropWhile { Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""").matches(it) }
        val cmd = words.firstOrNull()?.substringAfterLast('/') ?: return false
        val rest = words.drop(1)
        return when {
            cmd == "find" -> rest.none { it in setOf("-delete", "-exec", "-execdir", "-ok", "-fprint", "-fprintf", "-fls") }
            cmd == "sed" -> rest.none { it.startsWith("-i") || it == "--in-place" }
            cmd == "awk" -> !seg.contains("system(")
            cmd == "git" -> rest.firstOrNull() in GIT_READ || rest.firstOrNull() in setOf("--version", "version")
            cmd == "pkg" || cmd == "apt" || cmd == "dpkg" -> rest.firstOrNull() in PKG_READ || rest.firstOrNull() in setOf("-l", "-L", "--list", "--version")
            cmd in READ_CMDS -> true
            cmd in VERSION_ONLY -> rest.size == 1 && rest[0] in setOf("--version", "-v", "-V", "version")
            else -> false
        }
    }
}

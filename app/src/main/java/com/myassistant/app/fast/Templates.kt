package com.myassistant.app.fast

/**
 * Turns a learned example into a template: the words of the phrase that also appear in the action's
 * arguments are slots ("كلم أحمد" → call {contact: أحمد}). Pure logic, unit-tested.
 */
object Templates {
    private data class W(val orig: String, val norm: String)

    private fun words(text: String): List<W> = text.trim().split(Regex("\\s+"))
        .map { o -> W(o.trim('.', '!', '؟', '?', '،', ','), FastParser.normalize(o).trim()) }
        .filter { it.norm.isNotEmpty() && it.norm !in FastParser.politeWords }

    /** The part of a word that the args use: the word itself, or without a leading ل/ب/و ("لأحمد" → "أحمد"). */
    private fun core(w: W, argsNorm: String): Pair<String, String>? {
        if (w.norm.length >= 2 && argsNorm.contains(w.norm)) return w.orig to w.norm
        if (w.norm.length > 3 && w.norm[0] in "لبو" && argsNorm.contains(w.norm.substring(1))) return w.orig.substring(1) to w.norm.substring(1)
        return null
    }

    private fun stripLike(w: W, like: W): String =
        if (like.norm.length > 3 && like.norm[0] in "لبو" && w.norm.length > 1 && w.norm[0] == like.norm[0]) w.orig.substring(1) else w.orig

    /** A regex that finds [norm] in the raw args whatever the spelling (أ/ا/إ, ة/ه, ى/ي). */
    private fun loose(norm: String): Regex = Regex(norm.map {
        when (it) {
            'ا' -> "[اأإآٱ]"; 'ه' -> "[هة]"; 'ي' -> "[يى]"
            else -> Regex.escape(it.toString())
        }
    }.joinToString(""), RegexOption.IGNORE_CASE)

    /**
     * Args to use for [text] given a learned ([phrase] → [args]), or null when it isn't safe:
     * - every slot of the example still appears in [text] → same args (only if ![requireChange])
     * - exactly one word differs and it's a slot → args with that value swapped
     * - anything else (a different name/number we can't place) → null
     */
    fun adapt(phrase: String, args: String, text: String, requireChange: Boolean): String? {
        val pw = words(phrase)
        val tw = words(text)
        if (pw.isEmpty() || tw.isEmpty()) return null
        val argsNorm = FastParser.normalize(args)
        val slots = pw.mapIndexedNotNull { i, w -> core(w, argsNorm)?.let { i to it } }.toMap()
        val textNorms = tw.map { it.norm }.toSet()

        if (!requireChange) {
            val allThere = slots.values.all { (_, n) -> textNorms.any { it == n || it.endsWith(n) } }
            if (allThere) return args
        }
        if (pw.size != tw.size) return null
        val diffs = pw.indices.filter { pw[it].norm != tw[it].norm }
        if (diffs.size != 1) return null
        val i = diffs.single()
        val (_, slotNorm) = slots[i] ?: return null
        val newValue = stripLike(tw[i], pw[i])
        if (newValue.isBlank()) return null
        if (newValue.any { it == '"' || it == '\\' }) return null
        val replaced = loose(slotNorm).replace(args, Regex.escapeReplacement(newValue))
        return replaced.takeIf { it != args }
    }

    /** Swaps the changed word in the learned reply too ("كلمت أحمد" → "كلمت محمد"), or a neutral reply. */
    fun adaptReply(reply: String, phrase: String, text: String): String {
        val pw = words(phrase); val tw = words(text)
        if (pw.size == tw.size) {
            val diffs = pw.indices.filter { pw[it].norm != tw[it].norm }
            if (diffs.size == 1) {
                val i = diffs.single()
                val from = loose(pw[i].norm.let { if (it.length > 3 && it[0] in "لبو" && !FastParser.normalize(reply).contains(it)) it.substring(1) else it })
                val to = stripLike(tw[i], pw[i]).let { if (FastParser.normalize(reply).contains(pw[i].norm)) tw[i].orig else it }
                val r = from.replace(reply, Regex.escapeReplacement(to))
                if (r != reply) return r
            }
        }
        return "تمام ✓"
    }
}

package com.myassistant.app.semantic

import com.myassistant.app.data.ChatHit
import com.myassistant.app.data.ChatStore
import com.myassistant.app.fast.Experience
import com.myassistant.app.fast.ExperienceStore
import com.myassistant.app.fast.FastParser
import com.myassistant.app.memory.MemoryItem
import com.myassistant.app.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds things by meaning (when the on-device model is ready) and by keywords (always),
 * and keeps fingerprints up to date in the background.
 */
class Recall(
    private val model: MeaningModel,
    private val memory: MemoryStore,
    private val chats: ChatStore,
    private val experience: ExperienceStore,
) {

    // ---------- memory ----------

    suspend fun memories(query: String, limit: Int = 8): List<MemoryItem> {
        val keyword = withContext(Dispatchers.IO) { memory.search(query, limit) }
        val q = model.embed(query, EmbedKind.QUERY) ?: return keyword
        val byMeaning = withContext(Dispatchers.IO) { memory.withVectors() }
            .map { (m, v) -> Vectors.dot(q, Vectors.fromBytes(v)) to m }
            .filter { it.first >= MEMORY_MIN }
            .sortedByDescending { it.first }
            .take(limit)
            .map { it.second }
        return (byMeaning + keyword).distinctBy { it.id }.take(limit)
    }

    // ---------- past chats ----------

    suspend fun chats(query: String, limit: Int = 8, excludeConv: Long?): List<ChatHit> {
        val keyword = withContext(Dispatchers.IO) { chats.search(query, limit, excludeConv) }
        val q = model.embed(query, EmbedKind.QUERY) ?: return keyword
        val byMeaning = withContext(Dispatchers.IO) {
            chats.searchByVector({ Vectors.dot(q, Vectors.fromBytes(it)) }, limit, excludeConv)
        }.filter { it.first >= CHAT_MIN }.map { it.second }
        return (byMeaning + keyword).distinctBy { it.text + it.createdAt }.take(limit)
    }

    // ---------- learned phrases ----------

    /**
     * The learned phrase closest in meaning, only if it's clearly the same request
     * (high similarity, same numbers, and no close competitor that does something else).
     */
    suspend fun similarExperience(text: String): Experience? {
        val q = model.embed(text, EmbedKind.SIMILARITY) ?: return null
        val nums = FastParser.numbers(text).sorted()
        val ranked = withContext(Dispatchers.IO) { experience.withVectors() }
            .map { (e, v) -> Vectors.dot(q, Vectors.fromBytes(v)) to e }
            .sortedByDescending { it.first }
        val (bestScore, best) = ranked.firstOrNull() ?: return null
        if (bestScore < EXPERIENCE_MIN) return null
        if (FastParser.numbers(best.phrase).sorted() != nums) return null
        val rival = ranked.drop(1).firstOrNull { (_, e) -> e.tool != best.tool || e.args != best.args }
        if (rival != null && bestScore - rival.first < EXPERIENCE_MARGIN) return null
        return best
    }

    // ---------- background indexing ----------

    /** Fingerprints everything that doesn't have one yet. Returns how many were added. */
    suspend fun backfill(maxItems: Int = 2000): Int {
        if (!model.isReady) return 0
        var added = 0
        for (e in withContext(Dispatchers.IO) { experience.missingVectors() }) {
            val v = model.embed(e.phrase, EmbedKind.SIMILARITY) ?: return added
            withContext(Dispatchers.IO) { experience.setVector(e.id, Vectors.toBytes(v)) }
            added++
        }
        for (m in withContext(Dispatchers.IO) { memory.missingVectors() }) {
            val v = model.embed(m.text, EmbedKind.DOCUMENT) ?: return added
            withContext(Dispatchers.IO) { memory.setVector(m.id, Vectors.toBytes(v)) }
            added++
        }
        while (added < maxItems) {
            val batch = withContext(Dispatchers.IO) { chats.missingVectors(100) }
            if (batch.isEmpty()) break
            for ((id, conv, text) in batch) {
                val v = model.embed(text, EmbedKind.DOCUMENT) ?: return added
                withContext(Dispatchers.IO) { chats.setVector(id, conv, Vectors.toBytes(v)) }
                added++
            }
        }
        return added
    }

    private companion object {
        const val EXPERIENCE_MIN = 0.80f
        const val EXPERIENCE_MARGIN = 0.04f
        const val MEMORY_MIN = 0.30f
        const val CHAT_MIN = 0.35f
    }
}

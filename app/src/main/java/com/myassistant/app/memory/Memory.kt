package com.myassistant.app.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class MemoryItem(val id: Long, val text: String, val createdAt: Long, val category: String = MemoryCategories.GENERAL)

/** Folders for what the assistant knows about you, so things aren't all mixed together. */
object MemoryCategories {
    const val GENERAL = "general"
    const val PLACES = "places"
    /** key → Arabic label with icon. Order = display order. */
    val all = linkedMapOf(
        "personal" to "عني",
        "people" to "ناس",
        "work" to "شغل ودراسة",
        "health" to "صحة",
        "money" to "فلوس",
        "places" to "أماكن",
        "dates" to "مناسبات ومواعيد",
        "preferences" to "تفضيلات",
        GENERAL to "عام",
    )

    fun normalize(key: String?): String = key?.trim()?.lowercase()?.takeIf { it in all } ?: GENERAL
    fun label(key: String) = all[key] ?: all.getValue(GENERAL)
}

/**
 * Long-term memory, stored only on the phone.
 * Today: full-text search (SQLite FTS4). Later this interface can be backed by on-device
 * embeddings for search-by-meaning without changing anything else.
 * All methods are blocking — call them off the main thread.
 */
interface MemoryStore {
    fun add(text: String, category: String = MemoryCategories.GENERAL): Long
    fun setCategory(id: Long, category: String) = Unit
    fun search(query: String, limit: Int = 6): List<MemoryItem>
    fun recent(limit: Int = 500): List<MemoryItem>
    fun delete(id: Long)
    fun count(): Int

    // Meaning fingerprints (optional; filled in by the on-device model).
    fun missingVectors(limit: Int = 500): List<MemoryItem> = emptyList()
    fun setVector(id: Long, vec: ByteArray) = Unit
    fun withVectors(): List<Pair<MemoryItem, ByteArray>> = emptyList()
}

class SqliteMemoryStore(context: Context) :
    SQLiteOpenHelper(context, "memory.db", null, 3), MemoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE memories USING fts4(" +
                "text, norm, created_at, notindexed=text, notindexed=created_at, tokenize=unicode61)",
        )
        db.execSQL(VEC_TABLE)
        db.execSQL(META_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(VEC_TABLE)
        if (oldVersion < 3) db.execSQL(META_TABLE)
    }

    override fun setCategory(id: Long, category: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO memory_meta (id, category) VALUES (?, ?)",
            arrayOf<Any>(id, MemoryCategories.normalize(category)),
        )
    }

    private fun categoryOf(ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            "SELECT id, category FROM memory_meta WHERE id IN (${ids.joinToString(",")})", null,
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) } }
    }

    private fun withCategories(items: List<MemoryItem>): List<MemoryItem> {
        val cats = categoryOf(items.map { it.id })
        return items.map { it.copy(category = cats[it.id] ?: MemoryCategories.GENERAL) }
    }

    override fun missingVectors(limit: Int): List<MemoryItem> =
        readableDatabase.rawQuery(
            "SELECT docid, text, created_at FROM memories WHERE docid NOT IN (SELECT id FROM memory_vec) LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(MemoryItem(c.getLong(0), c.getString(1), c.getLong(2))) } }

    override fun setVector(id: Long, vec: ByteArray) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO memory_vec (id, vec) VALUES (?, ?)", arrayOf<Any>(id, vec))
    }

    override fun withVectors(): List<Pair<MemoryItem, ByteArray>> {
        val vecs = readableDatabase.rawQuery("SELECT id, vec FROM memory_vec", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getBlob(1)) }
        }
        if (vecs.isEmpty()) return emptyList()
        return recent(100_000).mapNotNull { m -> vecs[m.id]?.let { m to it } }
    }

    override fun add(text: String, category: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1
        val values = ContentValues().apply {
            put("text", clean)
            put("norm", ArabicText.normalizeForIndex(clean))
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("memories", null, values)
        if (id > 0) setCategory(id, category)
        return id
    }

    override fun search(query: String, limit: Int): List<MemoryItem> {
        val terms = ArabicText.terms(query)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "$it*" }
        val found = readableDatabase.rawQuery(
            "SELECT docid, text, created_at, norm FROM memories WHERE norm MATCH ? ORDER BY docid DESC LIMIT 200",
            arrayOf(match),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val norm = c.getString(3).orEmpty()
                    val score = terms.count { t -> norm.split(' ').any { it.startsWith(t) } }
                    add(score to MemoryItem(c.getLong(0), c.getString(1), c.getLong(2)))
                }
            }
        }
        return withCategories(found.sortedByDescending { it.first }.take(limit).map { it.second })
    }

    override fun recent(limit: Int): List<MemoryItem> =
        readableDatabase.rawQuery(
            "SELECT docid, text, created_at FROM memories ORDER BY docid DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(MemoryItem(c.getLong(0), c.getString(1), c.getLong(2))) }
        }.let { withCategories(it) }

    override fun delete(id: Long) {
        writableDatabase.execSQL("DELETE FROM memories WHERE docid = ?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM memory_vec WHERE id = ?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM memory_meta WHERE id = ?", arrayOf(id))
    }

    private companion object {
        const val VEC_TABLE = "CREATE TABLE IF NOT EXISTS memory_vec (id INTEGER PRIMARY KEY, vec BLOB NOT NULL)"
        const val META_TABLE = "CREATE TABLE IF NOT EXISTS memory_meta (id INTEGER PRIMARY KEY, category TEXT NOT NULL)"
    }

    override fun count(): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM memories", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}

/** Light Arabic normalisation so "أحمد" finds "احمد" and "الدكتور" finds "دكتور". */
object ArabicText {
    private val diacritics = Regex("[\\u064B-\\u0652\\u0640]")
    private val prefixes = listOf("وال", "بال", "فال", "كال", "لل", "ال")

    fun normalizeWord(raw: String): String {
        var w = raw.lowercase()
            .replace(diacritics, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        for (p in prefixes) {
            if (w.startsWith(p) && w.length - p.length >= 2) {
                w = w.removePrefix(p)
                break
            }
        }
        return w
    }

    fun words(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.map { normalizeWord(it) }.filter { it.isNotBlank() }

    fun normalizeForIndex(text: String): String = words(text).joinToString(" ")

    private val stopWords = setOf(
        "في", "من", "علي", "عن", "الي", "انا", "انت", "هو", "هي", "ده", "دي", "اللي", "مع", "او", "و",
        "the", "a", "an", "is", "to", "of", "and", "my", "me", "i",
    )

    fun terms(query: String): List<String> =
        words(query).filter { it.length >= 2 && it !in stopWords }.distinct().take(12)
}

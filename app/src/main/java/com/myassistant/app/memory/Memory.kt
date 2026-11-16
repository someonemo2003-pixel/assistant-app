package com.myassistant.app.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class MemoryItem(val id: Long, val text: String, val createdAt: Long)

/**
 * Long-term memory, stored only on the phone.
 * Today: full-text search (SQLite FTS4). Later this interface can be backed by on-device
 * embeddings for search-by-meaning without changing anything else.
 * All methods are blocking — call them off the main thread.
 */
interface MemoryStore {
    fun add(text: String): Long
    fun search(query: String, limit: Int = 6): List<MemoryItem>
    fun recent(limit: Int = 500): List<MemoryItem>
    fun delete(id: Long)
    fun count(): Int
}

class SqliteMemoryStore(context: Context) :
    SQLiteOpenHelper(context, "memory.db", null, 1), MemoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE memories USING fts4(" +
                "text, norm, created_at, notindexed=text, notindexed=created_at, tokenize=unicode61)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun add(text: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1
        val values = ContentValues().apply {
            put("text", clean)
            put("norm", ArabicText.normalizeForIndex(clean))
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("memories", null, values)
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
        return found.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    override fun recent(limit: Int): List<MemoryItem> =
        readableDatabase.rawQuery(
            "SELECT docid, text, created_at FROM memories ORDER BY docid DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(MemoryItem(c.getLong(0), c.getString(1), c.getLong(2))) }
        }

    override fun delete(id: Long) {
        writableDatabase.execSQL("DELETE FROM memories WHERE docid = ?", arrayOf(id))
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

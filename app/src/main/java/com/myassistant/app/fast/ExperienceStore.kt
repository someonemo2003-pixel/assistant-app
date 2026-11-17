package com.myassistant.app.fast

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One thing the fast brain has learned: "when the user says [phrase], run [tool] with [args]". */
data class Experience(
    val id: Long,
    val phrase: String,
    val tool: String,
    val args: String,
    val reply: String,
    /** "ai" = learned from the big brain, "user" = taught by the user. */
    val source: String,
    val uses: Int,
    val createdAt: Long,
)

/**
 * The fast brain's notebook. Grows as the big brain handles new phrasings and as the user
 * teaches shortcuts. Today it matches by normalised phrase; the meaning-based model (phase B)
 * plugs in on top of [all]. Blocking calls — use from Dispatchers.IO.
 */
class ExperienceStore(context: Context) : SQLiteOpenHelper(context, "experience.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE experience (id INTEGER PRIMARY KEY AUTOINCREMENT, phrase TEXT NOT NULL, " +
                "key TEXT NOT NULL UNIQUE, tool TEXT NOT NULL, args TEXT NOT NULL, reply TEXT NOT NULL, " +
                "source TEXT NOT NULL, uses INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, vec BLOB)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE experience ADD COLUMN vec BLOB")
    }

    // ---- meaning fingerprints (filled in by the on-device model) ----

    fun missingVectors(limit: Int = 500): List<Experience> =
        readableDatabase.rawQuery("$SELECT WHERE vec IS NULL LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    fun setVector(id: Long, vec: ByteArray) {
        writableDatabase.execSQL("UPDATE experience SET vec = ? WHERE id = ?", arrayOf<Any>(vec, id))
    }

    fun withVectors(): List<Pair<Experience, ByteArray>> =
        readableDatabase.rawQuery("SELECT id, phrase, tool, args, reply, source, uses, created_at, vec FROM experience WHERE vec IS NOT NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(read(c) to c.getBlob(8)) }
        }

    /** Saves (or replaces) what to do for this phrase. Returns false if the phrase is empty. */
    fun learn(phrase: String, tool: String, args: String, reply: String, source: String): Boolean {
        val key = FastParser.phraseKey(phrase)
        if (key.isEmpty()) return false
        // A user-taught shortcut is never overwritten by something learned automatically.
        if (source == "ai" && find(phrase)?.source == "user") return false
        writableDatabase.insertWithOnConflict("experience", null, ContentValues().apply {
            putNull("vec")
            put("phrase", phrase.trim())
            put("key", key)
            put("tool", tool)
            put("args", args)
            put("reply", reply)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    fun find(phrase: String): Experience? {
        val key = FastParser.phraseKey(phrase)
        if (key.isEmpty()) return null
        return readableDatabase.rawQuery("$SELECT WHERE key = ? LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) read(c) else null
        }
    }

    fun all(): List<Experience> =
        readableDatabase.rawQuery("$SELECT ORDER BY created_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    fun markUsed(id: Long) {
        writableDatabase.execSQL("UPDATE experience SET uses = uses + 1 WHERE id = ?", arrayOf<Any>(id))
    }

    fun delete(id: Long) {
        writableDatabase.execSQL("DELETE FROM experience WHERE id = ?", arrayOf<Any>(id))
    }

    private fun read(c: android.database.Cursor) = Experience(
        id = c.getLong(0), phrase = c.getString(1), tool = c.getString(2), args = c.getString(3),
        reply = c.getString(4), source = c.getString(5), uses = c.getInt(6), createdAt = c.getLong(7),
    )

    private companion object {
        const val SELECT = "SELECT id, phrase, tool, args, reply, source, uses, created_at FROM experience"
    }
}

package com.myassistant.app.fast

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * What happened with each request — handled instantly, corrected by the user, sent to the big brain (and how it went).
 * The nightly teacher reads this to find what to teach the fast brain. Kept 14 days. Blocking.
 */
class BrainLog private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "brainlog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, detail TEXT)")
        db.execSQL("CREATE TABLE blocked (key TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class Entry(val at: Long, val kind: String, val text: String, val detail: String?)

    fun since(t: Long): List<Entry> =
        readableDatabase.rawQuery("SELECT at, kind, text, detail FROM log WHERE at >= ? ORDER BY at", arrayOf(t.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) }
        }

    companion object {
        @Volatile private var instance: BrainLog? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: BrainLog(ctx.applicationContext).also { instance = it } }

        /** kind: fast | corrected | ai | ai_failed | taught */
        fun add(ctx: Context, kind: String, text: String, detail: String?) = runCatching {
            val db = get(ctx).writableDatabase
            db.insert("log", null, ContentValues().apply {
                put("at", System.currentTimeMillis()); put("kind", kind); put("text", text.take(300)); put("detail", detail?.take(1500))
            })
            db.execSQL("DELETE FROM log WHERE at < ?", arrayOf<Any>(System.currentTimeMillis() - 14 * 86_400_000L))
        }

        fun block(ctx: Context, text: String) = runCatching {
            get(ctx).writableDatabase.insertWithOnConflict("blocked", null, ContentValues().apply {
                put("key", FastParser.phraseKey(text)); put("at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun unblock(ctx: Context, text: String) = runCatching {
            get(ctx).writableDatabase.execSQL("DELETE FROM blocked WHERE key = ?", arrayOf<Any>(FastParser.phraseKey(text)))
        }

        fun isBlocked(ctx: Context, text: String): Boolean = runCatching {
            get(ctx).readableDatabase.rawQuery("SELECT 1 FROM blocked WHERE key = ?", arrayOf(FastParser.phraseKey(text))).use { it.moveToFirst() }
        }.getOrDefault(false)
    }
}

package com.myassistant.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.core.ToolCall
import com.myassistant.app.memory.ArabicText
import org.json.JSONArray
import org.json.JSONObject

data class Conversation(val id: Long, val title: String, val updatedAt: Long, val extracted: Boolean)

data class ChatHit(val conversationTitle: String, val role: Role, val text: String, val createdAt: Long)

/**
 * All conversations, saved only on the phone. Also full-text searchable so the assistant can
 * look things up in past chats. Blocking calls — use from Dispatchers.IO.
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, extracted INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, conv_id INTEGER NOT NULL, role TEXT NOT NULL, " +
                "text TEXT NOT NULL, tool_calls TEXT, tool_call_id TEXT, tool_name TEXT, raw TEXT, provider TEXT, " +
                "created_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX messages_conv ON messages(conv_id, created_at)")
        db.execSQL(
            "CREATE VIRTUAL TABLE chat_fts USING fts4(norm, msg_id, conv_id, notindexed=msg_id, notindexed=conv_id, tokenize=unicode61)",
        )
        db.execSQL(VEC_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(VEC_TABLE)
    }

    // ---- meaning fingerprints (filled in by the on-device model) ----

    /** Recent user/assistant messages that don't have a fingerprint yet: (id, conv, text). */
    fun missingVectors(limit: Int = 300): List<Triple<String, Long, String>> =
        readableDatabase.rawQuery(
            "SELECT id, conv_id, text FROM messages WHERE role != 'TOOL' AND length(text) > 0 " +
                "AND id NOT IN (SELECT msg_id FROM chat_vec) ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getLong(1), c.getString(2))) } }

    fun setVector(msgId: String, convId: Long, vec: ByteArray) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO chat_vec (msg_id, conv_id, vec) VALUES (?, ?, ?)",
            arrayOf<Any>(msgId, convId, vec),
        )
    }

    /** Best matches by meaning, scanning the most recent [scan] fingerprints. */
    fun searchByVector(score: (ByteArray) -> Float, limit: Int, excludeConv: Long?, scan: Int = 5000): List<Pair<Float, ChatHit>> {
        val best = readableDatabase.rawQuery(
            "SELECT v.msg_id, v.conv_id, v.vec FROM chat_vec v ORDER BY v.rowid DESC LIMIT ?",
            arrayOf(scan.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (excludeConv != null && c.getLong(1) == excludeConv) continue
                    add(score(c.getBlob(2)) to c.getString(0))
                }
            }
        }.sortedByDescending { it.first }.take(limit)
        return best.mapNotNull { (s, id) ->
            readableDatabase.rawQuery(
                "SELECT m.role, m.text, m.created_at, c.title FROM messages m JOIN conversations c ON c.id = m.conv_id WHERE m.id = ?",
                arrayOf(id),
            ).use { c ->
                if (!c.moveToFirst()) null else s to ChatHit(
                    conversationTitle = c.getString(3),
                    role = runCatching { Role.valueOf(c.getString(0)) }.getOrDefault(Role.ASSISTANT),
                    text = c.getString(1),
                    createdAt = c.getLong(2),
                )
            }
        }
    }

    fun newConversation(title: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("conversations", null, ContentValues().apply {
            put("title", title.take(60))
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun addMessage(convId: Long, m: ChatMessage) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("messages", null, ContentValues().apply {
                put("id", m.id)
                put("conv_id", convId)
                put("role", m.role.name)
                put("text", m.text)
                put("tool_calls", if (m.toolCalls.isEmpty()) null else encodeCalls(m.toolCalls))
                put("tool_call_id", m.toolCallId)
                put("tool_name", m.toolName)
                put("raw", if (m.raw.isEmpty()) null else JSONObject(m.raw).toString())
                put("provider", m.providerName)
                put("created_at", m.createdAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (m.role != Role.TOOL && m.text.isNotBlank()) {
                db.insert("chat_fts", null, ContentValues().apply {
                    put("norm", ArabicText.normalizeForIndex(m.text))
                    put("msg_id", m.id)
                    put("conv_id", convId)
                })
            }
            db.execSQL("UPDATE conversations SET updated_at = ? WHERE id = ?", arrayOf<Any>(m.createdAt, convId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun messages(convId: Long): List<ChatMessage> =
        readableDatabase.rawQuery(
            "SELECT id, role, text, tool_calls, tool_call_id, tool_name, raw, provider, created_at " +
                "FROM messages WHERE conv_id = ? ORDER BY created_at, rowid",
            arrayOf(convId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChatMessage(
                            id = c.getString(0),
                            role = runCatching { Role.valueOf(c.getString(1)) }.getOrDefault(Role.ASSISTANT),
                            text = c.getString(2).orEmpty(),
                            toolCalls = c.getString(3)?.let { decodeCalls(it) } ?: emptyList(),
                            toolCallId = c.getString(4),
                            toolName = c.getString(5),
                            raw = c.getString(6)?.let { decodeRaw(it) } ?: emptyMap(),
                            providerName = c.getString(7),
                            createdAt = c.getLong(8),
                        ),
                    )
                }
            }
        }

    fun conversations(limit: Int = 300): List<Conversation> =
        readableDatabase.rawQuery(
            "SELECT id, title, updated_at, extracted FROM conversations ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) == 1)) }
        }

    fun markExtracted(convId: Long) {
        writableDatabase.execSQL("UPDATE conversations SET extracted = 1 WHERE id = ?", arrayOf<Any>(convId))
    }

    fun delete(convId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM messages WHERE conv_id = ?", arrayOf<Any>(convId))
            db.execSQL("DELETE FROM chat_fts WHERE conv_id = ?", arrayOf<Any>(convId))
            db.execSQL("DELETE FROM chat_vec WHERE conv_id = ?", arrayOf<Any>(convId))
            db.execSQL("DELETE FROM conversations WHERE id = ?", arrayOf<Any>(convId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Full-text search across all past chats (user and assistant messages). */
    fun search(query: String, limit: Int = 8, excludeConv: Long? = null): List<ChatHit> {
        val terms = ArabicText.terms(query)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "$it*" }
        val rows = readableDatabase.rawQuery(
            "SELECT chat_fts.norm, m.role, m.text, m.created_at, c.title, m.conv_id FROM chat_fts " +
                "JOIN messages m ON m.id = chat_fts.msg_id JOIN conversations c ON c.id = m.conv_id " +
                "WHERE chat_fts MATCH ? ORDER BY m.created_at DESC LIMIT 200",
            arrayOf(match),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (excludeConv != null && c.getLong(5) == excludeConv) continue
                    val words = c.getString(0).orEmpty().split(' ')
                    val score = terms.count { t -> words.any { it.startsWith(t) } }
                    val hit = ChatHit(
                        conversationTitle = c.getString(4),
                        role = runCatching { Role.valueOf(c.getString(1)) }.getOrDefault(Role.ASSISTANT),
                        text = c.getString(2),
                        createdAt = c.getLong(3),
                    )
                    add(score to hit)
                }
            }
        }
        return rows.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    private companion object {
        const val VEC_TABLE = "CREATE TABLE IF NOT EXISTS chat_vec (msg_id TEXT PRIMARY KEY, conv_id INTEGER NOT NULL, vec BLOB NOT NULL)"
    }

    private fun encodeCalls(calls: List<ToolCall>): String {
        val arr = JSONArray()
        calls.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.argsJson)) }
        return arr.toString()
    }

    private fun decodeCalls(s: String): List<ToolCall> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ToolCall(o.getString("id"), o.getString("name"), o.optString("args", "{}"))
        }
    }.getOrDefault(emptyList())

    private fun decodeRaw(s: String): Map<String, String> = runCatching {
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
}

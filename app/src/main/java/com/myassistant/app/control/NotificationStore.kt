package com.myassistant.app.control

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedNotification(
    val id: Long,
    val key: String,
    val pkg: String,
    val app: String,
    /** Conversation / group name (e.g. a WhatsApp group), or email account. */
    val conversation: String,
    /** Sender (messages) or title (other notifications / email sender). */
    val sender: String,
    val text: String,
    val time: Long,
    val isEmail: Boolean,
    val isMessage: Boolean,
    /** Which account/copy of the app: "u<user>" (twin apps run as another user) + "|<self name>" when known. */
    val account: String = "",
)

data class AlertRule(val id: Long, val match: String, val app: String)

/**
 * Every notification the phone shows (messages, emails, alerts), saved only on the phone so the
 * assistant can search, summarise and reply. Kept for 30 days. Blocking — use from IO.
 */
class NotificationStore(context: Context) : SQLiteOpenHelper(context, "notifications.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notifs (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT UNIQUE NOT NULL, key TEXT NOT NULL, " +
                "pkg TEXT NOT NULL, app TEXT NOT NULL, conversation TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, " +
                "time INTEGER NOT NULL, is_email INTEGER NOT NULL, is_message INTEGER NOT NULL, account TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE INDEX notifs_time ON notifs(time)")
        db.execSQL("CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, match TEXT NOT NULL, app TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE notifs ADD COLUMN account TEXT NOT NULL DEFAULT ''")
    }

    /** Returns true if this is new (not seen before). */
    fun save(n: SavedNotification): Boolean {
        val hash = "${n.pkg}|${n.account}|${n.conversation}|${n.sender}|${n.text}|${if (n.isMessage) n.time / 1000 else 0}"
        val id = writableDatabase.insertWithOnConflict("notifs", null, ContentValues().apply {
            put("hash", hash)
            put("key", n.key)
            put("pkg", n.pkg)
            put("app", n.app)
            put("conversation", n.conversation)
            put("sender", n.sender)
            put("text", n.text)
            put("time", n.time)
            put("is_email", if (n.isEmail) 1 else 0)
            put("is_message", if (n.isMessage) 1 else 0)
            put("account", n.account)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (id > 0 && id % 200 == 0L) {
            writableDatabase.execSQL("DELETE FROM notifs WHERE time < ?", arrayOf<Any>(System.currentTimeMillis() - 30L * 86_400_000))
        }
        return id > 0
    }

    fun query(
        sinceMs: Long,
        filter: String = "",
        onlyEmail: Boolean = false,
        onlyMessages: Boolean = false,
        limit: Int = 50,
        pkgs: Collection<String> = emptyList(),
        excludePkgs: Collection<String> = emptyList(),
        account: String? = null,
    ): List<SavedNotification> {
        val where = StringBuilder("time >= ?")
        val args = mutableListOf(sinceMs.toString())
        if (onlyEmail) where.append(" AND is_email = 1")
        if (onlyMessages) where.append(" AND is_message = 1")
        if (pkgs.isNotEmpty()) { where.append(" AND pkg IN (" + pkgs.joinToString(",") { "?" } + ")"); args += pkgs }
        if (excludePkgs.isNotEmpty()) { where.append(" AND pkg NOT IN (" + excludePkgs.joinToString(",") { "?" } + ")"); args += excludePkgs }
        if (account != null) { where.append(" AND account = ?"); args += account }
        if (filter.isNotBlank()) {
            where.append(" AND (app LIKE ? OR conversation LIKE ? OR sender LIKE ? OR text LIKE ?)")
            val f = "%${filter.trim()}%"
            repeat(4) { args += f }
        }
        return readableDatabase.rawQuery(
            "SELECT id, key, pkg, app, conversation, sender, text, time, is_email, is_message, account FROM notifs " +
                "WHERE $where ORDER BY time DESC LIMIT $limit",
            args.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    SavedNotification(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getString(5), c.getString(6), c.getLong(7), c.getInt(8) == 1, c.getInt(9) == 1, c.getString(10),
                    ),
                )
            }
        }
    }

    /** Distinct app accounts seen in messages over the last 30 days: (pkg, app, account, count). */
    fun accounts(): List<Triple<Pair<String, String>, String, Int>> =
        readableDatabase.rawQuery(
            "SELECT pkg, app, account, COUNT(*) FROM notifs WHERE is_message = 1 AND time >= ? GROUP BY pkg, account ORDER BY pkg, account",
            arrayOf((System.currentTimeMillis() - 30L * 86_400_000).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0) to c.getString(1), c.getString(2), c.getInt(3))) } }

    // ---- alert rules ----

    fun addAlert(match: String, app: String): Long =
        writableDatabase.insert("alerts", null, ContentValues().apply { put("match", match.trim()); put("app", app.trim()) })

    fun alerts(): List<AlertRule> =
        readableDatabase.rawQuery("SELECT id, match, app FROM alerts ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(AlertRule(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    fun removeAlert(id: Long) {
        writableDatabase.execSQL("DELETE FROM alerts WHERE id = ?", arrayOf<Any>(id))
    }

    companion object {
        @Volatile private var instance: NotificationStore? = null
        fun get(context: Context): NotificationStore =
            instance ?: synchronized(this) { instance ?: NotificationStore(context.applicationContext).also { instance = it } }
    }
}

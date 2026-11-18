package com.myassistant.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves everything the assistant knows (memory, chats, what it learned, reminders, expenses, places,
 * settings) into one .zip, and restores it. API keys and the Gmail password are NOT included: they are
 * locked to this phone's hardware key, so they must be typed again after restoring on a new phone.
 */
object Backup {
    private val databases = listOf(
        "memory.db", "chats.db", "experience.db", "notifications.db", "activity.db", "reminders.db", "expenses.db", "visits.db", "habits.db", "brainlog.db",
    )
    private val prefs = listOf("settings", "providers", "mail", "report", "health", "expenses", "accounts", "guard", "focus", "updates", "prayer", "reading", "teacher")

    fun export(ctx: Context, target: Uri): Int {
        var count = 0
        ctx.contentResolver.openOutputStream(target)?.use { os ->
            ZipOutputStream(os).use { zip ->
                for (name in databases) {
                    val f = ctx.getDatabasePath(name)
                    if (!f.exists()) continue
                    runCatching {
                        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                        }
                    }
                    zip.putNextEntry(ZipEntry("databases/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
                val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
                for (p in prefs) {
                    val f = File(prefsDir, "$p.xml")
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("shared_prefs/$p.xml"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        } ?: error("Cannot write the file")
        return count
    }

    /** Replaces the current data with the backup. The app must be restarted afterwards. */
    fun restore(ctx: Context, source: Uri): Int {
        val allowedDb = databases.toSet()
        val allowedPrefs = prefs.map { "$it.xml" }.toSet()
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        var count = 0
        ctx.contentResolver.openInputStream(source)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    val target = when {
                        e.name.startsWith("databases/") && name in allowedDb -> ctx.getDatabasePath(name)
                        e.name.startsWith("shared_prefs/") && name in allowedPrefs -> File(prefsDir, name)
                        else -> null
                    }
                    if (target != null) {
                        target.parentFile?.mkdirs()
                        val tmp = File(target.path + ".restore")
                        tmp.outputStream().use { zip.copyTo(it) }
                        File(target.path + "-wal").delete()
                        File(target.path + "-shm").delete()
                        if (!tmp.renameTo(target)) {
                            target.delete(); tmp.renameTo(target)
                        }
                        count++
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot read the file")
        if (count == 0) error("This file is not an assistant backup")
        return count
    }
}

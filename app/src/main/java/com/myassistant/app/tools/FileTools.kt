package com.myassistant.app.tools

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ROOT: File get() = Environment.getExternalStorageDirectory()

private fun canReadFiles(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

private const val NO_FILES = "File access is OFF. Tell the user: → الصلاحيات → الوصول لكل الملفات."

/** Resolves a user path like "Download/x.pdf" or "/sdcard/Download" safely inside shared storage. */
private fun resolve(path: String): File {
    val p = path.trim().removePrefix("/sdcard").removePrefix("/storage/emulated/0").trim('/')
    val f = if (p.isEmpty()) ROOT else File(ROOT, p)
    val canonical = f.canonicalFile
    require(canonical.path.startsWith(ROOT.canonicalPath)) { "Path outside shared storage" }
    return canonical
}

private fun size(n: Long) = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1f MB", n / 1e6)
    n >= 1_000 -> "${n / 1000} KB"
    else -> "$n B"
}

class ListFilesTool : Tool {
    override val name = "list_files"
    override val title = "الملفات"
    override val description =
        "List folders and files in the phone's storage. Path is relative to internal storage, e.g. '' (root), 'Download', 'DCIM/Camera', 'Documents'."
    override val parameters = Schema.obj("path" to Schema.string("Folder path, empty for the root"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!canReadFiles()) return@withContext NO_FILES
        val dir = resolve(args.str("path"))
        val items = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return@withContext "Folder not found: ${args.str("path")}"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sorted = items.sortedWith(compareBy<File>({ !it.isDirectory }, { -it.lastModified() }))
        buildString {
            appendLine("${dir.path.removePrefix(ROOT.path).ifEmpty { "/" }} — ${items.size} items")
            sorted.take(80).forEach {
                if (it.isDirectory) appendLine("${it.name}/")
                else appendLine("${it.name} (${size(it.length())}, ${fmt.format(Date(it.lastModified()))})")
            }
            if (sorted.size > 80) appendLine("…and ${sorted.size - 80} more")
        }
    }
}

class SearchFilesTool : Tool {
    override val name = "search_files"
    override val title = "بحث في الملفات"
    override val description = "Find files on the phone whose name contains the given words (searches all of internal storage)."
    override val parameters = Schema.obj(
        "name" to Schema.string("Part of the file name, e.g. 'invoice' or '.pdf'"),
        "folder" to Schema.string("Optional folder to search in"),
        required = listOf("name"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!canReadFiles()) return@withContext NO_FILES
        val q = args.str("name").lowercase()
        if (q.isEmpty()) return@withContext "Missing name"
        val start = resolve(args.str("folder"))
        val found = start.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name != "Android" }
            .filter { it.isFile && it.name.lowercase().contains(q) }
            .take(40)
            .toList()
        if (found.isEmpty()) "No files found" else found.joinToString("\n") {
            "${it.path.removePrefix(ROOT.path)} (${size(it.length())})"
        }
    }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val title = "قراءة ملف"
    override val description = "Read the text content of a text file on the phone (txt, md, csv, json, xml, html, log, srt...)."
    override val parameters = Schema.obj("path" to Schema.string("File path, e.g. 'Download/notes.txt'"), required = listOf("path"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        if (!canReadFiles()) return@withContext NO_FILES
        val f = resolve(args.str("path"))
        if (!f.isFile) return@withContext "File not found"
        val ext = f.extension.lowercase()
        if (ext !in textTypes) return@withContext "Not a text file ($ext). Use open_file to open it in another app."
        val text = f.readText(Charsets.UTF_8)
        if (text.length > 12_000) text.take(12_000) + "\n…(file truncated)" else text
    }

    private val textTypes = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "srt", "ini", "yaml", "yml", "kt", "java", "py", "js", "ts", "css", "sql")
}

class OpenFileTool : Tool {
    override val name = "open_file"
    override val title = "فتح ملف"
    override val description = "Open a file (PDF, photo, video, document...) in the right app on the phone."
    override val parameters = Schema.obj("path" to Schema.string("File path"), required = listOf("path"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        if (!canReadFiles()) return NO_FILES
        val f = withContext(Dispatchers.IO) { resolve(args.str("path")) }
        if (!f.isFile) return "File not found"
        val uri = FileProvider.getUriForFile(ctx.app, ctx.app.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (ctx.launch(intent)) "Opened ${f.name}" else "No app can open ${f.name}"
    }
}

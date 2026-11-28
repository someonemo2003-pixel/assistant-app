package com.myassistant.app.gtasks

import android.content.Context
import com.myassistant.app.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Google Tasks, straight through Google's Tasks API. Sign-in uses the phone's Google account
 * (Google's own consent screen); Nova never sees a password, and the access token is asked
 * for again each time (Google keeps it), so nothing secret is stored here.
 */
object GoogleTasks {
        private const val API = "https://tasks.googleapis.com/tasks/v1"

    class NotConnected : Exception("Google Tasks مش مربوط")
    class ApiError(msg: String) : Exception(msg)

    data class TaskList(val id: String, val title: String)
    data class Task(val id: String, val listId: String, val title: String, val notes: String, val due: String?, val done: Boolean)

    private val S = com.myassistant.app.google.GoogleAuth.Service.TASKS
    fun connected(ctx: Context) = com.myassistant.app.google.GoogleAuth.connected(ctx, S)
    fun account(ctx: Context) = com.myassistant.app.google.GoogleAuth.account(ctx, S)
    fun disconnect(ctx: Context) = com.myassistant.app.google.GoogleAuth.disconnect(ctx, S)

    private suspend fun token(ctx: Context): String = try {
        com.myassistant.app.google.GoogleAuth.token(ctx, S)
    } catch (_: com.myassistant.app.google.GoogleAuth.NotConnected) {
        throw NotConnected()
    } catch (e: java.io.IOException) {
        throw ApiError(e.message ?: "sign-in failed")
    }

    private suspend fun call(ctx: Context, method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = token(ctx)
        return withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(API + path).header("Authorization", "Bearer $t").method(method, rb).build()
            Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) {
                    // Token revoked or API off: show it plainly.
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${resp.code}")
                    throw ApiError(msg)
                }
                if (!resp.isSuccessful) throw ApiError("HTTP ${resp.code}")
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun lists(ctx: Context): List<TaskList> {
        val items = call(ctx, "GET", "/users/@me/lists?maxResults=100").optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }.map { TaskList(it.getString("id"), it.optString("title")) }
    }

    /** The list whose name matches [name], or the first (default) list. */
    suspend fun pickList(ctx: Context, name: String?): TaskList {
        val all = lists(ctx)
        if (all.isEmpty()) throw ApiError("مفيش قوايم")
        if (name.isNullOrBlank()) return all.first()
        return all.firstOrNull { it.title.equals(name.trim(), true) }
            ?: all.firstOrNull { it.title.contains(name.trim(), true) }
            ?: throw ApiError("مفيش قايمة اسمها $name. القوايم: " + all.joinToString("، ") { it.title })
    }

    suspend fun tasks(ctx: Context, list: TaskList, showDone: Boolean = false): List<Task> {
        val q = "?maxResults=100&showCompleted=$showDone&showHidden=$showDone"
        val items = call(ctx, "GET", "/lists/${enc(list.id)}/tasks$q").optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("title").isNotBlank() }
            .map {
                Task(
                    it.getString("id"), list.id, it.optString("title"), it.optString("notes"),
                    it.optString("due").takeIf { d -> d.isNotBlank() }?.take(10), it.optString("status") == "completed",
                )
            }
    }

    /** [due] is YYYY-MM-DD (Google Tasks keeps only the day). */
    suspend fun add(ctx: Context, list: TaskList, title: String, notes: String?, due: String?): Task {
        val body = JSONObject().put("title", title)
        if (!notes.isNullOrBlank()) body.put("notes", notes)
        if (!due.isNullOrBlank()) body.put("due", "${due}T00:00:00.000Z")
        val o = call(ctx, "POST", "/lists/${enc(list.id)}/tasks", body)
        return Task(o.optString("id"), list.id, title, notes.orEmpty(), due, false)
    }

    suspend fun complete(ctx: Context, task: Task) {
        call(ctx, "PATCH", "/lists/${enc(task.listId)}/tasks/${enc(task.id)}", JSONObject().put("status", "completed"))
    }

    /** Finds an open task by (part of) its title across all lists. */
    suspend fun find(ctx: Context, title: String): List<Task> {
        val q = title.trim()
        return lists(ctx).flatMap { tasks(ctx, it) }.let { all ->
            all.filter { it.title.equals(q, true) }.ifEmpty { all.filter { it.title.contains(q, true) || q.contains(it.title, true) } }
        }
    }
}

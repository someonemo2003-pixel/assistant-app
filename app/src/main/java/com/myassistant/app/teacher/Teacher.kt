package com.myassistant.app.teacher

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myassistant.app.AssistantApp
import com.myassistant.app.core.ChatMessage
import com.myassistant.app.core.Role
import com.myassistant.app.fast.BrainLog
import com.myassistant.app.fast.FastParser
import com.myassistant.app.fast.Templates
import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The nightly teacher: once a night the big brain reviews the day's log — what went to the big brain, what the
 * fast brain got wrong — and teaches the fast brain new phrases (or removes bad ones). Every change is tested
 * against the log first. It changes the fast brain's notebook only, never the app's code or its rules/blocks.
 */
object Teacher {
    private const val WORK = "nightly_teacher"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("teacher", Context.MODE_PRIVATE)

    fun lastReport(ctx: Context): String = prefs(ctx).getString("report", null) ?: "المدرّب لسه مشتغلش."

    /** Every night around 3:30 AM, when there's internet. */
    fun schedule(ctx: Context) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val req = PeriodicWorkRequestBuilder<TeacherWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private const val PROMPT = """You are the nightly teacher of "Nova", an Android assistant. Nova has a FAST BRAIN that answers instantly without AI by
replaying learned examples: phrase → one tool call. A learned phrase also works as a template: if the user later says the same
phrase with ONE word changed and that word appears in the args, the word is swapped ("كلم أحمد"→call{name:أحمد} also serves "كلم محمد").

You get: (1) today's log — requests the big brain handled (with the single tool it used), requests the fast brain handled, and
requests where the user said the fast answer was WRONG; (2) the fast brain's current notebook.

Your job: make the fast brain handle more of the user's real, repeated requests, safely.
- ADD examples for requests the big brain solved with exactly ONE successful call of an allowed tool, when the same kind of request
  is likely to come again (seen twice, or clearly a habit). Write the phrase the way THIS user talks (Egyptian Arabic), short.
  You may add 1–3 common paraphrases the user is likely to say for the same thing.
- REMOVE notebook entries that were corrected by the user or that look wrong / dangerous.
- NEVER teach: anything that sends money, deletes, or reaches a NEW recipient not seen in the log; anything that depends on the
  current screen; answers to questions (only actions).
- Only use tools from the allowed list, with args exactly like the logged call.
Reply with ONLY a JSON object:
{"add":[{"phrase":"...","tool":"...","args":{...},"reply":"short Egyptian Arabic confirmation"}],"remove":[notebook ids],"summary":"one or two short Arabic sentences for the user about what you taught"}"""

    /** One teaching session. Returns the report shown to the user. */
    suspend fun run(ctx: Context): String {
        val g = (ctx.applicationContext as AssistantApp).graph
        val since = prefs(ctx).getLong("last", System.currentTimeMillis() - 86_400_000L)
        val log = withContext(Dispatchers.IO) { BrainLog.get(ctx).since(since) }
        if (log.none { it.kind == "ai" || it.kind == "corrected" }) {
            prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
            return lastReport(ctx)
        }
        val allowed = g.tools.all.filter { it.learnable }.map { it.name }.toSet()
        val notebook = withContext(Dispatchers.IO) { g.experience.all() }.filter { it.source != "user" }.take(200)

        val data = buildString {
            appendLine("ALLOWED TOOLS: " + allowed.joinToString(", "))
            appendLine("\nTODAY'S LOG:")
            log.takeLast(300).forEach { e -> appendLine("[${e.kind}] \"${e.text}\"" + (e.detail?.let { " → $it" } ?: "")) }
            appendLine("\nNOTEBOOK (id | phrase | tool | args):")
            notebook.forEach { appendLine("${it.id} | ${it.phrase} | ${it.tool} | ${it.args.take(200)}") }
        }
        val reply = g.router.complete(
            PROMPT, listOf(ChatMessage(role = Role.USER, text = data.take(60_000))), emptyList(),
            preferred = g.settings.chatBrain.value.ifEmpty { null },
        )
        val json = runCatching {
            val t = reply.text
            JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        }.getOrElse { return "المدرّب: الرد مكانش مفهوم، هيحاول بكرة." }

        // ---- test & apply ----
        val texts = log.map { it }
        var added = 0; var removed = 0; var rejected = 0
        withContext(Dispatchers.IO) {
            val adds = json.optJSONArray("add") ?: JSONArray()
            for (i in 0 until minOf(adds.length(), 25)) {
                val a = adds.optJSONObject(i) ?: continue
                val phrase = a.optString("phrase").trim()
                val tool = a.optString("tool")
                val args = a.optJSONObject("args")?.toString() ?: continue
                if (phrase.isEmpty() || phrase.length > 60 || tool !in allowed) { rejected++; continue }
                if (FastParser.parse(phrase) != null) { rejected++; continue } // a built-in rule already handles it
                // must not hijack anything the user corrected, or a request the big brain solved with a different tool
                val conflict = texts.any { e ->
                    val same = FastParser.phraseKey(e.text) == FastParser.phraseKey(phrase) ||
                        Templates.adapt(phrase, args, e.text, requireChange = true) != null
                    same && (e.kind == "corrected" || (e.kind == "ai" && e.detail?.contains("\"tool\":\"$tool\"") == false))
                }
                if (conflict) { rejected++; continue }
                if (g.experience.learn(phrase, tool, args, a.optString("reply").ifBlank { "تمام ✓" }.take(200), "teacher")) added++
            }
            val rem = json.optJSONArray("remove") ?: JSONArray()
            val ids = notebook.map { it.id }.toSet()
            for (i in 0 until rem.length()) {
                val id = rem.optLong(i, -1)
                if (id in ids) { g.experience.delete(id); removed++ }
            }
        }
        runCatching { g.recall.backfill() }
        val summary = json.optString("summary").trim()
        val report = buildString {
            append("المدرّب الليلي: ضاف $added، وشال $removed")
            if (rejected > 0) append("، ورفض $rejected بعد الاختبار")
            append(".")
            if (summary.isNotEmpty()) append(" ").append(summary)
        }
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).putString("report", report).apply()
        BrainLog.add(ctx, "taught", "teacher", report)
        return report
    }
}

class TeacherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching { Teacher.run(applicationContext); Result.success() }.getOrElse { Result.retry() }
}

class TeacherReportTool : Tool {
    override val name = "teacher_report"
    override val title = "المدرّب"
    override val description =
        "What the nightly teacher taught the fast brain last time (\"اتعلمت ايه امبارح؟\"). With run=true it teaches now."
    override val parameters = Schema.obj("run" to Schema.string("'true' to run a teaching session now"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String =
        if (args.optString("run") == "true") Teacher.run(ctx.app) else Teacher.lastReport(ctx.app)
}

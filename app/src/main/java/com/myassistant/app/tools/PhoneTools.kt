package com.myassistant.app.tools

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val learnable = true
    override val title = "منبه"
    override val description = "Set an alarm on the phone's clock app. Use 24-hour time."
    override val parameters = Schema.obj(
        "hour" to Schema.integer("Hour 0-23"),
        "minute" to Schema.integer("Minute 0-59"),
        "label" to Schema.string("Optional alarm label"),
        required = listOf("hour", "minute"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val hour = args.int("hour") ?: return "Missing hour"
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return "Invalid time"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.str("label").takeIf { it.isNotEmpty() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val time = String.format(Locale.US, "%02d:%02d", hour, minute)
        return if (ctx.launch(intent)) "Alarm set for $time" else "No clock app could set the alarm"
    }
}

class SetTimerTool : Tool {
    override val name = "set_timer"
    override val learnable = true
    override val title = "مؤقت"
    override val description = "Start a countdown timer on the phone."
    override val parameters = Schema.obj(
        "seconds" to Schema.integer("Timer length in seconds"),
        "label" to Schema.string("Optional label"),
        required = listOf("seconds"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val seconds = args.int("seconds") ?: return "Missing seconds"
        if (seconds !in 1..86_400) return "Invalid length"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.str("label").takeIf { it.isNotEmpty() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (ctx.launch(intent)) "Timer started for $seconds seconds" else "No clock app could start a timer"
    }
}

class OpenAppTool : Tool {
    override val name = "open_app"
    override val learnable = true
    override val title = "فتح تطبيق"
    override val description =
        "Open an installed app by name (e.g. WhatsApp, YouTube, Camera, Settings). " +
            "If not found, the result lists the closest app names; retry with one of them."
    override val parameters = Schema.obj(
        "app_name" to Schema.string("App name as shown on the phone, usually in English"),
        required = listOf("app_name"),
    )

    override fun describe(args: JSONObject) = "فتح ${args.str("app_name")}"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val wanted = simplify(args.str("app_name"))
        if (wanted.isEmpty()) return "Missing app_name"
        val apps = withContext(Dispatchers.IO) {
            val pm = ctx.app.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        }
        val ranked = apps
            .map { (label, pkg) -> Triple(label, pkg, score(wanted, simplify(label), pkg.lowercase())) }
            .sortedByDescending { it.third }
        val best = ranked.firstOrNull()
        if (best == null || best.third < 50) {
            val hints = ranked.take(5).joinToString { it.first }
            return "App not found. Closest: $hints"
        }
        val intent = ctx.app.packageManager.getLaunchIntentForPackage(best.second) ?: return "Cannot open ${best.first}"
        return if (ctx.launch(intent)) "Opened ${best.first}" else "Cannot open ${best.first}"
    }

    private fun simplify(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun score(wanted: String, label: String, pkg: String): Int = when {
        label == wanted -> 100
        label.startsWith(wanted) || wanted.startsWith(label) && label.length >= 3 -> 80
        label.contains(wanted) -> 70
        pkg.contains(wanted) -> 60
        else -> 40 - minOf(40, editDistance(wanted, label))
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

class DraftSmsTool : Tool {
    override val name = "draft_sms"
    override val title = "رسالة SMS"
    override val description =
        "Open the messaging app with an SMS ready to send. The user presses send themselves. " +
            "Phone number is optional."
    override val parameters = Schema.obj(
        "phone" to Schema.string("Recipient phone number, digits only, optional"),
        "message" to Schema.string("The SMS text"),
        required = listOf("message"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val phone = args.str("phone").filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
            .putExtra("sms_body", args.str("message"))
        return if (ctx.launch(intent)) "SMS draft opened; the user will press send" else "No SMS app found"
    }
}

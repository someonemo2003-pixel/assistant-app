package com.myassistant.app.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myassistant.app.MainActivity
import com.myassistant.app.admin.DeviceOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands inside Termux (com.termux) through its RUN_COMMAND service and waits for the output.
 * Termux needs `allow-external-apps = true` in ~/.termux/termux.properties.
 * Every job has a hard time limit, and is stopped if the phone gets too hot.
 */
object Termux {
    const val PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val BIN = "/data/data/com.termux/files/usr/bin"
    const val BASH = "$BIN/bash"
    const val HOME = "/data/data/com.termux/files/home"
    private const val MAX_TEMP_C = 46f
    private const val CHANNEL = "terminal"

    data class Result(val out: String, val err: String, val code: Int, val error: String?, val stoppedHot: Boolean = false, val timedOut: Boolean = false) {
        fun text(limit: Int = 6000): String {
            val all = buildString {
                if (error != null) append("Termux error: ").append(error).append('\n')
                if (stoppedHot) append("STOPPED: the phone got too hot.\n")
                if (timedOut) append("STOPPED: time limit reached.\n")
                append("exit code: ").append(code).append('\n')
                if (out.isNotBlank()) append(clean(out)).append('\n')
                if (err.isNotBlank()) append("stderr:\n").append(clean(err))
            }.trim()
            return if (all.length <= limit) all else all.take(limit / 3) + "\n…(cut)…\n" + all.takeLast(limit * 2 / 3)
        }
    }

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Result>>()
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** As device owner Nova gives itself the Termux permission silently. */
    fun ensurePermission(ctx: Context): Boolean {
        if (granted(ctx)) return true
        if (DeviceOwner.isOwner(ctx)) runCatching {
            ctx.getSystemService(DevicePolicyManager::class.java).setPermissionGrantState(
                DeviceOwner.admin(ctx), ctx.packageName, PERMISSION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
        return granted(ctx)
    }

    /** Problem that stops Nova from using Termux, in plain words for the model, or null if ready. */
    fun problem(ctx: Context): String? = when {
        !installed(ctx) -> "Termux is not installed. Tell the user to install Termux from F-Droid or GitHub (not Play Store), then follow the Termux setup steps."
        !ensurePermission(ctx) -> "Nova has no permission to run Termux commands. Device owner must be on, or the user allows 'Run commands in Termux' for Nova in app permissions."
        else -> null
    }

    /**
     * Runs [script] with bash in Termux. Extra [args] reach the script as $1, $2… (no quoting problems).
     * [onTick] is called every few seconds with the elapsed seconds.
     */
    suspend fun run(
        ctx: Context,
        script: String,
        args: List<String> = emptyList(),
        timeoutSec: Int = 60,
        onTick: (Int) -> Unit = {},
    ): Result {
        problem(ctx)?.let { return Result("", "", -1, it) }
        val id = ids.incrementAndGet()
        val tag = "nova-job-$id"
        val done = CompletableDeferred<Result>()
        waiting[id] = done
        val back = Intent(ctx, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id, back, flags)
        // $0 = seconds, $1 = script, rest = script args. The tag lets Nova stop the job.
        val launcher = "T=\"\$0\"; S=\"\$1\"; shift; exec $BIN/timeout -k 5 \"\$T\" $BASH -c \"\$S\" $tag \"\$@\""
        val intent = Intent(ACTION).setClassName(PKG, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", (listOf("-c", launcher, timeoutSec.toString(), script) + args).toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }.recoverCatching { ctx.startService(intent) }
        if (started.isFailure) {
            waiting.remove(id)
            return Result("", "", -1, "Couldn't reach Termux: ${started.exceptionOrNull()?.message}. Open Termux once and check allow-external-apps = true.")
        }
        var hot = false
        val res = coroutineScope {
            val watch = launch {
                var secs = 0
                while (true) {
                    delay(5_000); secs += 5
                    onTick(secs)
                    if (temperature(ctx) >= MAX_TEMP_C) {
                        hot = true
                        stop(ctx, tag)
                        break
                    }
                }
            }
            val r = withTimeoutOrNull((timeoutSec + 40) * 1000L) { done.await() }
            watch.cancel()
            r
        }
        waiting.remove(id)
        return when {
            res == null -> { stop(ctx, tag); Result("", "", -1, null, stoppedHot = hot, timedOut = !hot) }
            else -> res.copy(stoppedHot = hot, timedOut = res.code == 124 || res.code == 137)
        }
    }

    /** Stops a running job (and what it started) by its tag. */
    private fun stop(ctx: Context, tag: String) {
        val intent = Intent(ACTION).setClassName(PKG, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", "$BIN/pkill")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-TERM", "-f", tag))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent) }
    }

    fun temperature(ctx: Context): Float {
        val i = ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) ?: return 0f
        return i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    internal fun deliver(id: Int, r: Result) { waiting[id]?.complete(r) }

    /** Tells the user a long job finished (they may have left the app). */
    fun notifyDone(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "التيرمنال", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(4242, n) }
    }

    private fun clean(s: String) = s.replace(Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]"""), "").replace("\r", "").trim()

    const val EXTRA_ID = "nova_job_id"
}

/** Receives Termux's answer for a job. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Termux.EXTRA_ID, -1)
        val b = intent.getBundleExtra("result")
        val err = b?.getInt("err", -1) ?: -1
        val msg = b?.getString("errmsg")?.takeIf { it.isNotBlank() && err != -1 }
        Termux.deliver(
            id,
            Termux.Result(
                out = b?.getString("stdout").orEmpty(),
                err = b?.getString("stderr").orEmpty(),
                code = b?.getInt("exitCode", -1) ?: -1,
                error = msg,
            ),
        )
    }
}

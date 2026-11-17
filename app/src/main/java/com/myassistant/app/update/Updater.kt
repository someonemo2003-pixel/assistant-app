package com.myassistant.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myassistant.app.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps Nova up to date from the GitHub releases. As device owner the update installs silently;
 * otherwise Android shows its normal "update this app?" screen.
 */
object Updater {
    private const val REPO = "someonemo2003-pixel/assistant-app"
    private const val WORK = "auto_update"
    private const val CHANNEL = "updates"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("auto", true)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto", on).apply()
        schedule(ctx)
    }

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!autoOn(ctx)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** "1.2.1" > "1.2"? */
    internal fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    data class Release(val version: String, val apkUrl: String, val notes: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val j: JSONObject = Http.getJson("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
        val assets = j.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
        Release(j.optString("tag_name").removePrefix("v"), apk.getString("browser_download_url"), j.optString("body"))
    }

    /** Checks and installs if there's a newer version. Returns a short status for the UI. */
    suspend fun checkAndInstall(ctx: Context): String {
        val r = runCatching { latest() }.getOrElse { return "معرفتش أوصل لـ GitHub: ${it.message}" }
            ?: return "مفيش نسخ منشورة"
        val cur = current(ctx)
        if (!newer(r.version, cur)) return "✅ عندك آخر نسخة ($cur)"
        val file = withContext(Dispatchers.IO) {
            val f = File(ctx.cacheDir, "update.apk")
            Http.client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("download ${resp.code}")
                f.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
            }
            f
        }
        withContext(Dispatchers.IO) { install(ctx, file, r.version) }
        return "⬇️ بيتسطب ${r.version}…"
    }

    private fun install(ctx: Context, apk: File, version: String) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("nova.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            prefs(ctx).edit().putString("installing", version).apply()
            val status = PendingIntent.getBroadcast(
                ctx, 77, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            s.commit(status.intentSender)
        }
    }

    const val ACTION_STATUS = "com.myassistant.app.UPDATE_STATUS"

    fun notify(ctx: Context, title: String, text: String, intent: Intent? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "التحديثات", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
        if (intent != null) b.setContentIntent(
            PendingIntent.getActivity(ctx, 78, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        runCatching { NotificationManagerCompat.from(ctx).notify(7070, b.build()) }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Updater.autoOn(applicationContext)) return Result.success()
        runCatching { Updater.checkAndInstall(applicationContext) }
        return Result.success()
    }
}

/** Install results, and "Nova was updated" after the new version starts. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Updater.ACTION_STATUS -> when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    // Not device owner: Android asks the user. Open it, and leave a notification in case it can't open now.
                    runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Updater.notify(ctx, "تحديث Nova جاهز", "دوس عشان تسطّبه", confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> Updater.notify(ctx, "تحديث Nova فشل", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val v = Updater.current(ctx)
                Updater.prefs(ctx).edit().remove("installing").apply()
                Updater.notify(ctx, "✨ Nova اتحدّث", "النسخة $v")
            }
        }
    }
}

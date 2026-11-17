package com.myassistant.app.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myassistant.app.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

object Health {
    private const val LIGHT_WORK = "light_tracking"

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun lightTrackingOn(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE).getBoolean("light", true)

    fun setLightTracking(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit().putBoolean("light", on).apply()
        schedule(ctx)
    }

    /** Every ~15 minutes: save a step-counter sample and (if allowed) where the phone is. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(LIGHT_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Reads the hardware step counter once (it keeps counting even when the app is closed). */
    suspend fun readStepCounter(ctx: Context): Long? {
        if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val result = CompletableDeferred<Long>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { result.complete(e.values[0].toLong()) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return try {
            withTimeoutOrNull(4000) { result.await() }
        } finally {
            sm.unregisterListener(listener)
        }
    }

    suspend fun sampleSteps(ctx: Context) {
        val v = readStepCounter(ctx) ?: return
        withContext(Dispatchers.IO) { ActivityStore.get(ctx).addStepSample(v) }
    }

    /** One current location fix (network/GPS), or null. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context, timeoutMs: Long = 20_000): Location? {
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val d = CompletableDeferred<Location?>()
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { d.complete(it) }
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: lm.getLastKnownLocation(provider)
        }
        return lm.getLastKnownLocation(provider)
    }
}

class SampleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        runCatching { Health.sampleSteps(ctx) }
        runCatching { com.myassistant.app.admin.DeviceOwner.enforce(ctx) }
        runCatching { withContext(Dispatchers.IO) { com.myassistant.app.finance.ExpenseSync.scanSms(ctx) } }
        if (Health.lightTrackingOn(ctx) && !WalkService.running.value &&
            (Build.VERSION.SDK_INT < 29 || Health.granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        ) {
            Health.currentLocation(ctx)?.let { loc -> withContext(Dispatchers.IO) { ActivityStore.get(ctx).addPoint(loc, null) } }
        }
        return Result.success()
    }
}

/**
 * Precise walk tracking ("ابدأ تتبّع المشي"): GPS every few metres, live distance in a notification.
 */
class WalkService : Service(), LocationListener {

    private var walkId = -1L
    private var meters = 0.0
    private var last: Location? = null
    private val store by lazy { ActivityStore.get(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (walkId >= 0) return START_STICKY
        val counter = intent?.getLongExtra(EXTRA_COUNTER, -1L)?.takeIf { it >= 0 }
        walkId = store.startWalk(counter)
        startForegroundCompat(note("بدأ تتبّع المشي…"))
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 4f, this, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 10f, this, Looper.getMainLooper()) }
        _running.value = true
        current = walkId
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        store.addPoint(loc, walkId)
        last?.let { val d = it.distanceTo(loc); if (d in 3f..300f) meters += d }
        last = loc
        store.updateWalkDistance(walkId, meters)
        val km = String.format(java.util.Locale.US, "%.2f", meters / 1000)
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note("مشيت $km كم"))
    }

    override fun onDestroy() {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        if (walkId >= 0) {
            val start = store.walkStartCounter(walkId)
            val end = stopCounter
            val steps = if (start != null && end != null && end >= start) (end - start).toInt() else (meters / 0.75).toInt()
            store.finishWalk(walkId, meters, steps)
        }
        _running.value = false
        current = null
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTE_ID, n, type)
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "تتبّع المشي", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 3, Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("🚶 بيتتبّع مشيك")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "وقّف", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "walk"
        private const val NOTE_ID = 5151
        const val ACTION_STOP = "stop"
        const val EXTRA_COUNTER = "counter"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        @Volatile var current: Long? = null
            private set
        /** Step counter value captured right before stopping (set by [stop]). */
        @Volatile private var stopCounter: Long? = null

        suspend fun start(ctx: Context) {
            val counter = Health.readStepCounter(ctx)
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, WalkService::class.java).putExtra(EXTRA_COUNTER, counter ?: -1L),
            )
        }

        suspend fun stop(ctx: Context) {
            stopCounter = Health.readStepCounter(ctx)
            ctx.startService(Intent(ctx, WalkService::class.java).setAction(ACTION_STOP))
        }
    }
}

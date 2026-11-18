package com.myassistant.app.prayer

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myassistant.app.admin.DeviceOwner
import kotlinx.coroutines.delay

/**
 * The prayer lock: a full screen the user can't leave (device-owner lock task) until they confirm they prayed
 * (after [min] minutes) or [max] minutes pass. Calls and the emergency dialer stay available.
 */
class PrayerLockActivity : ComponentActivity() {

    private lateinit var prayer: Prayer
    private var adhan = 0L
    private var started = 0L
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prayer = Prayer.of(intent.getStringExtra("p") ?: "") ?: run { finish(); return }
        adhan = intent.getLongExtra("adhan", System.currentTimeMillis())
        started = intent.getLongExtra("started", System.currentTimeMillis())
        val minMs = intent.getIntExtra("min", 7) * 60_000L
        val maxMs = intent.getIntExtra("max", 20) * 60_000L

        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(false) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })
        enterLockTask()

        setContent {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    if (now - started >= maxMs) { done(confirmed = false); break }
                    delay(1000)
                }
            }
            val canConfirm = now - started >= minMs
            val leftToButton = ((minMs - (now - started)) / 1000).coerceAtLeast(0)
            Column(
                Modifier.fillMaxSize().background(Color(0xFF05041A)).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("صلاة ${prayer.ar}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = { done(confirmed = true) },
                    enabled = canConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    modifier = Modifier.width(220.dp).height(56.dp),
                ) {
                    Text(if (canConfirm) "صليت" else "%d:%02d".format(leftToButton / 60, leftToButton % 60), fontSize = 20.sp)
                }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { openDialer() }) { Text("مكالمة", color = Color(0xFF9D9BC4)) }
            }
        }
    }

    private fun dialerPkg(): String? = runCatching { getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()

    private fun enterLockTask() {
        if (!DeviceOwner.isOwner(this)) return
        runCatching {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = DeviceOwner.admin(this)
            dpm.setLockTaskPackages(admin, listOfNotNull(packageName, dialerPkg(), "com.android.server.telecom").toTypedArray())
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
            }
            startLockTask()
        }
    }

    private fun openDialer() {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun done(confirmed: Boolean) {
        if (finished) return
        finished = true
        Thread {
            val day = Prayers.dayKey(adhan)
            if (confirmed) Prayers.confirm(applicationContext, prayer)
            else HabitStore.get(applicationContext).mark(day, prayer, PrayerStatus.UNSURE)
        }.start()
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }
        finishAndRemoveTask()
    }

    /** Not device owner: if the user leaves, bring the lock back. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!finished && !DeviceOwner.isOwner(this)) {
            startActivity(Intent(this, PrayerLockActivity::class.java).putExtras(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    companion object {
        fun start(ctx: Context, p: Prayer, adhan: Long, end: Long, min: Int, max: Int) {
            ctx.startActivity(
                Intent(ctx, PrayerLockActivity::class.java)
                    .putExtra("p", p.key).putExtra("adhan", adhan).putExtra("end", end)
                    .putExtra("min", min).putExtra("max", max).putExtra("started", System.currentTimeMillis())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }
}

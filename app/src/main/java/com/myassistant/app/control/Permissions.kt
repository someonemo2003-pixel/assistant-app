package com.myassistant.app.control

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** One permission row in the "الصلاحيات" screen. */
class PermItem(
    val id: String,
    val title: String,
    val why: String,
    /** Normal runtime permissions (granted through the system dialog). */
    val runtime: List<String> = emptyList(),
    /** Special permissions open a settings page instead. */
    val settingsIntent: ((Context) -> Intent)? = null,
    val isGranted: (Context) -> Boolean,
)

object Permissions {

    private fun granted(ctx: Context, perms: List<String>) =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun pkgUri(ctx: Context) = Uri.parse("package:" + ctx.packageName)

    fun screenEnabled(ctx: Context): Boolean {
        if (ScreenService.current != null) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(ctx, ScreenService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    val all: List<PermItem> = buildList {
        add(
            PermItem(
                "screen", "👁️ التحكم في الشاشة",
                "يقرا الشاشة ويدوس ويكتب في أي تطبيق (Accessibility).",
                settingsIntent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                isGranted = ::screenEnabled,
            ),
        )
        add(
            PermItem(
                "notif_read", "🔔 قراءة الإشعارات",
                "يقرا رسايل واتساب والإيميلات وأي إشعار بيوصلك.",
                settingsIntent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                isGranted = { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
            ),
        )
        if (Build.VERSION.SDK_INT >= 30) {
            add(
                PermItem(
                    "files", "📁 الوصول لكل الملفات",
                    "يشوف الفولدرات والملفات ويدوّر فيها ويقراها.",
                    settingsIntent = { Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(it)) },
                    isGranted = { Environment.isExternalStorageManager() },
                ),
            )
        } else {
            add(
                PermItem(
                    "files", "📁 الملفات", "يشوف الملفات ويقراها.",
                    runtime = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    isGranted = { granted(it, listOf(Manifest.permission.READ_EXTERNAL_STORAGE)) },
                ),
            )
        }
        add(
            PermItem(
                "usage", "📊 بيانات الاستخدام",
                "وقت الشاشة وأكتر التطبيقات استخداماً (زي Digital Wellbeing) من غير ما يفتح حاجة.",
                settingsIntent = { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                isGranted = { com.myassistant.app.tools.hasUsageAccess(it) },
            ),
        )
        runtime("mic", "🎙️ الميكروفون", "عشان تكلمه بصوتك بدل الكتابة.", Manifest.permission.RECORD_AUDIO)
        runtime("contacts", "👥 جهات الاتصال", "يلاقي رقم أي حد بالاسم.", Manifest.permission.READ_CONTACTS)
        runtime("calls", "📞 المكالمات", "يتصل (بعد موافقتك) ويقرا سجل المكالمات.", Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        runtime("sms", "💬 الرسايل SMS", "يبعت SMS (بعد موافقتك) ويقرا الرسايل.", Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
        runtime("calendar", "📅 الكالندر", "يقرا مواعيدك ويضيف مواعيد جديدة.", Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        runtime("location", "📍 الموقع", "يعرف إنت فين.", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) {
            runtime("steps", "👣 عدّاد الخطوات", "يعدّ خطواتك بنفسه من غير أي تطبيق تاني.", Manifest.permission.ACTIVITY_RECOGNITION)
            runtime(
                "bg_location", "🗺️ الموقع طول الوقت",
                "عشان يسجّل الأماكن اللي رحتها ويرسملك خريطة. فعّل \"الموقع\" الأول، وبعدين اختار \"السماح طول الوقت\".",
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            runtime("post_notif", "📣 إظهار إشعارات", "يبعتلك تنبيهات وتقارير.", Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(
                PermItem(
                    "exact_alarms", "⏰ تذكيرات في ميعادها بالظبط",
                    "عشان التذكير ييجي في الدقيقة نفسها مش متأخر.",
                    settingsIntent = { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(it)) },
                    isGranted = { com.myassistant.app.reminders.ReminderScheduler.canExact(it) },
                ),
            )
        }
        add(
            PermItem(
                "write_settings", "🔆 تعديل إعدادات النظام",
                "يغيّر الإضاءة وإعدادات بسيطة.",
                settingsIntent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(it)) },
                isGranted = { Settings.System.canWrite(it) },
            ),
        )
        add(
            PermItem(
                "battery", "🔋 الشغل في الخلفية",
                "عشان Honor ما يقفلوش وهو بيشتغل أو بيحمّل.",
                settingsIntent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(it)) },
                isGranted = { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
            ),
        )
    }

    private fun MutableList<PermItem>.runtime(id: String, title: String, why: String, vararg perms: String) {
        val list = perms.toList()
        add(PermItem(id, title, why, runtime = list, isGranted = { granted(it, list) }))
    }

    fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))

    /** Background location must be requested on its own, after normal location — so it's not in the bulk request. */
    fun allRuntime(): Array<String> = all.flatMap { it.runtime }
        .filter { it != Manifest.permission.ACCESS_BACKGROUND_LOCATION }.distinct().toTypedArray()
}

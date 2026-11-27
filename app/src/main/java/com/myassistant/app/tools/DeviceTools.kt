package com.myassistant.app.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------- Calendar ----------------

class ListEventsTool : Tool {
    override val name = "list_events"
    override val title = "المواعيد"
    override val description = "List calendar events from now for the next N days (default 7)."
    override val parameters = Schema.obj("days" to Schema.integer("How many days ahead, default 7"))
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val end = now + (args.int("days") ?: 7).coerceIn(1, 90) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val fmt = SimpleDateFormat("EEE MM-dd HH:mm", Locale.US)
        val out = mutableListOf<String>()
        ctx.app.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val allDay = c.getInt(2) == 1
                val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
                out += (if (allDay) "${fmt.format(Date(c.getLong(1))).substringBeforeLast(' ')} (all day)" else fmt.format(Date(c.getLong(1)))) +
                    " — ${c.getString(0).orEmpty()}$loc"
            }
        }
        if (out.isEmpty()) "No events" else out.joinToString("\n")
    }
}

class AddEventTool : Tool {
    override val name = "add_event"
    override val title = "إضافة موعد"
    override val description = "Add an event to the phone's calendar. Time in the user's local time, format 'yyyy-MM-dd HH:mm'."
    override val parameters = Schema.obj(
        "title" to Schema.string("Event title"),
        "start" to Schema.string("Start, 'yyyy-MM-dd HH:mm'"),
        "minutes" to Schema.integer("Duration in minutes, default 60"),
        "location" to Schema.string("Optional location"),
        required = listOf("title", "start"),
    )
    override val permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val start = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(args.str("start"))!!.time }.getOrNull()
            ?: return@withContext "Bad start time, use yyyy-MM-dd HH:mm"
        val calId = ctx.app.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null, null, null,
        )?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (best == null || c.getInt(1) == 1) best = c.getLong(0)
            }
            best
        } ?: return@withContext "No writable calendar on this phone"
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, args.str("title"))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + (args.int("minutes") ?: 60) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args.str("location").takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        ctx.app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?.let { "Event added: ${args.str("title")} at ${args.str("start")}" } ?: "Could not add event"
    }
}

// ---------------- Location ----------------

class LocationTool : Tool {
    override val name = "get_location"
    override val title = "الموقع"
    override val description = "Get the phone's current location (coordinates and street address if available)."
    override val parameters = Schema.obj()
    override val permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val lm = ctx.app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return@withContext "Location unknown (turn on location/GPS and try again)"
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx.app, Locale("ar")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
        val ageMin = (System.currentTimeMillis() - loc.time) / 60_000
        "lat=${loc.latitude}, lng=${loc.longitude} (±${loc.accuracy.toInt()}m, ${ageMin} min ago)" +
            (address?.let { "\nAddress: $it" } ?: "") +
            "\nMap: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
    }
}

// ---------------- Device ----------------

class DeviceStatusTool : Tool {
    override val name = "device_status"
    override val title = "حالة الموبايل"
    override val description = "Battery level/charging, volume levels, and storage space."
    override val parameters = Schema.obj()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val bm = ctx.app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun vol(s: Int) = "${am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s).coerceAtLeast(1)}%"
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1e9
        val totalGb = stat.totalBytes / 1e9
        return "Battery $level%" + (if (charging) " (charging)" else "") +
            "\nVolume: ring ${vol(AudioManager.STREAM_RING)}, media ${vol(AudioManager.STREAM_MUSIC)}, alarm ${vol(AudioManager.STREAM_ALARM)}" +
            String.format(Locale.US, "\nStorage: %.1f GB free of %.0f GB", freeGb, totalGb)
    }
}

class SetVolumeTool : Tool {
    override val name = "set_volume"
    override val title = "الصوت"
    override val description = "Set a volume level in percent: media, ring, alarm, or notification. 0 = mute."
    override val parameters = Schema.obj(
        "stream" to Schema.string("media | ring | alarm | notification"),
        "percent" to Schema.integer("0-100"),
        required = listOf("stream", "percent"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (args.str("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val pct = (args.int("percent") ?: return "Missing percent").coerceIn(0, 100)
        return try {
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream) * pct / 100, AudioManager.FLAG_SHOW_UI)
            "Volume ${args.str("stream")} set to $pct%"
        } catch (e: SecurityException) {
            "Not allowed (Do Not Disturb may be on)"
        }
    }
}

class FlashlightTool : Tool {
    override val name = "flashlight"
    override val title = "الكشاف"
    override val description = "Turn the flashlight on or off."
    override val parameters = Schema.obj("on" to Schema.string("'true' or 'false'"), required = listOf("on"))
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val cm = ctx.app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "No flashlight"
        val on = args.str("on") != "false"
        return runCatching { cm.setTorchMode(id, on); if (on) "Flashlight on" else "Flashlight off" }.getOrElse { "Flashlight error: ${it.message}" }
    }
}

class BrightnessTool : Tool {
    override val name = "set_brightness"
    override val title = "الإضاءة"
    override val description = "Set screen brightness in percent (turns off auto-brightness)."
    override val parameters = Schema.obj("percent" to Schema.integer("1-100"), required = listOf("percent"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        if (!Settings.System.canWrite(ctx.app)) return "Brightness control is OFF. Tell the user: → الصلاحيات → تعديل إعدادات النظام."
        val pct = (args.int("percent") ?: return "Missing percent").coerceIn(1, 100)
        Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255 * pct / 100)
        return "Brightness set to $pct%"
    }
}

class OpenSettingsTool : Tool {
    override val name = "open_settings"
    override val title = "الإعدادات"
    override val description =
        "Open a phone settings page: wifi, bluetooth, mobile_data, internet, location, display, sound, battery, apps, " +
            "notifications, storage, date, language, airplane, hotspot, nfc, accessibility, or 'main'. " +
            "To actually toggle something, open the page then use read_screen/tap."
    override val parameters = Schema.obj("page" to Schema.string("Which settings page"), required = listOf("page"))
    override val learnable = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val page = args.str("page").lowercase()
        val action = when (page) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet", "mobile_data" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (ctx.launch(Intent(action))) "Opened $page settings" else if (ctx.launch(Intent(Settings.ACTION_SETTINGS))) "Opened main settings" else "Could not open settings"
    }
}

class ListAppsTool : Tool {
    override val name = "list_apps"
    override val title = "التطبيقات"
    override val description = "List the apps installed on the phone (names). Optional filter."
    override val parameters = Schema.obj("filter" to Schema.string("Optional part of the app name"))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val pm = ctx.app.packageManager
        val f = args.str("filter").lowercase()
        val names = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }
            .filter { f.isEmpty() || it.lowercase().contains(f) }
            .distinct().sorted()
        if (names.isEmpty()) "No apps match" else "${names.size} apps: " + names.joinToString(", ")
    }
}

class OpenUrlTool : Tool {
    override val name = "open_link"
    override val title = "فتح رابط"
    override val description = "Open a web link, a Google Maps search/directions, or a web search in the browser."
    override val parameters = Schema.obj(
        "url" to Schema.string("Full URL, or leave empty and use query"),
        "query" to Schema.string("Web search text"),
        "maps" to Schema.string("Place to show/route to in Google Maps"),
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val uri = when {
            args.str("maps").isNotEmpty() -> Uri.parse("geo:0,0?q=" + Uri.encode(args.str("maps")))
            args.str("url").startsWith("http") -> Uri.parse(args.str("url"))
            args.str("query").isNotEmpty() -> Uri.parse("https://www.google.com/search?q=" + Uri.encode(args.str("query")))
            else -> return "Nothing to open"
        }
        return if (ctx.launch(Intent(Intent.ACTION_VIEW, uri))) "Opened" else "No app can open it"
    }
}

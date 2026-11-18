package com.myassistant.app.prayer

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** The five prayers (+ sunrise, which ends Fajr's time). */
enum class Prayer(val key: String, val ar: String) {
    FAJR("fajr", "الفجر"), DHUHR("dhuhr", "الظهر"), ASR("asr", "العصر"), MAGHRIB("maghrib", "المغرب"), ISHA("isha", "العشاء");

    companion object {
        fun of(s: String): Prayer? {
            val t = s.trim().lowercase().removePrefix("ال")
            return entries.firstOrNull { it.key == t || it.ar.removePrefix("ال") == t || it.ar == s.trim() }
                ?: when {
                    t.contains("فجر") || t.contains("صبح") -> FAJR
                    t.contains("ظهر") || t.contains("جمعة") || t.contains("جمعه") -> DHUHR
                    t.contains("عصر") -> ASR
                    t.contains("مغرب") -> MAGHRIB
                    t.contains("عشا") -> ISHA
                    else -> null
                }
        }
    }
}

/** Times for one day, in epoch millis. */
data class DayTimes(val fajr: Long, val sunrise: Long, val dhuhr: Long, val asr: Long, val maghrib: Long, val isha: Long) {
    fun at(p: Prayer) = when (p) {
        Prayer.FAJR -> fajr; Prayer.DHUHR -> dhuhr; Prayer.ASR -> asr; Prayer.MAGHRIB -> maghrib; Prayer.ISHA -> isha
    }

    /** When this prayer's time ends (Fajr → sunrise, Isha → next Fajr ≈ +the night). */
    fun end(p: Prayer, nextFajr: Long) = when (p) {
        Prayer.FAJR -> sunrise; Prayer.DHUHR -> asr; Prayer.ASR -> maghrib; Prayer.MAGHRIB -> isha; Prayer.ISHA -> nextFajr
    }
}

/**
 * Prayer times computed on the phone (no internet), Egyptian General Authority of Survey method:
 * Fajr 19.5°, Isha 17.5°, Asr standard (shadow = 1×).
 */
object PrayerTimes {
    private const val FAJR_ANGLE = 19.5
    private const val ISHA_ANGLE = 17.5

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun fix(a: Double, b: Double) = (a - b * floor(a / b)).let { if (it < 0) it + b else it }

    private fun julian(y: Int, m: Int, d: Int): Double {
        var yy = y; var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    /** Sun declination (deg) and equation of time (hours) at julian date [jd]. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = fix(deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0, 24.0)
        val eqt = q / 15.0 - ra
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return decl to (eqt - 24 * floor(eqt / 24 + 0.5))
    }

    /** Hours from noon until the sun is [angle]° below the horizon. */
    private fun t(angle: Double, lat: Double, decl: Double): Double =
        deg(acos(((-sin(rad(angle)) - sin(rad(decl)) * sin(rad(lat))) / (cos(rad(decl)) * cos(rad(lat)))).coerceIn(-1.0, 1.0))) / 15.0

    private fun asrT(lat: Double, decl: Double): Double {
        val angle = -deg(atan(1.0 / (1.0 + tan(rad(abs(lat - decl))))))
        return t(angle, lat, decl)
    }

    fun forDay(cal: Calendar, lat: Double, lng: Double): DayTimes {
        val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH) + 1; val d = cal.get(Calendar.DAY_OF_MONTH)
        val dayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val tz = TimeZone.getDefault().getOffset(dayStart.timeInMillis + 12 * 3_600_000L) / 3_600_000.0
        val jd = julian(y, m, d) - lng / (15 * 24.0)
        val (decl, eqt) = sun(jd + 0.5)
        val noon = 12 + tz - lng / 15.0 - eqt
        fun ms(h: Double) = dayStart.timeInMillis + (h * 3_600_000).toLong()
        return DayTimes(
            fajr = ms(noon - t(FAJR_ANGLE, lat, decl)),
            sunrise = ms(noon - t(0.833, lat, decl)),
            dhuhr = ms(noon + 1 / 60.0),
            asr = ms(noon + asrT(lat, decl)),
            maghrib = ms(noon + t(0.833, lat, decl)),
            isha = ms(noon + t(ISHA_ANGLE, lat, decl)),
        )
    }
}

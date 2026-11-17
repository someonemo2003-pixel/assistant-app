package com.myassistant.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Shows routes and places in the Google Maps app (or the browser if Maps isn't installed). */
object GoogleMaps {
    private const val MAPS = "com.google.android.apps.maps"

    private fun ll(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    private fun open(ctx: Context, uri: Uri): Boolean {
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(Intent(i).setPackage(MAPS)); true }.getOrElse {
            runCatching { ctx.startActivity(i); true }.getOrDefault(false)
        }
    }

    /**
     * Draws a recorded path as a route: start, end, and up to 8 stops in between
     * (Google Maps takes at most 10 points), picked evenly along the distance walked.
     */
    fun openRoute(ctx: Context, points: List<GeoPoint>, mode: String = "walking"): Boolean {
        val pts = points.filter { it.accuracy <= 100f }.ifEmpty { points }
        if (pts.isEmpty()) return false
        if (pts.size == 1) return openPlace(ctx, pts[0].lat, pts[0].lng)
        // cumulative distance along the path
        val cum = DoubleArray(pts.size)
        val r = FloatArray(1)
        for (i in 1 until pts.size) {
            android.location.Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng, r)
            cum[i] = cum[i - 1] + r[0]
        }
        val total = cum.last()
        val stops = if (total < 1) emptyList() else (1..8).map { k ->
            val target = total * k / 9
            pts[cum.indexOfFirst { it >= target }.coerceAtLeast(0)]
        }.distinctBy { ll(it.lat, it.lng) }.filter { it !== pts.first() && it !== pts.last() }
        val b = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", ll(pts.first().lat, pts.first().lng))
            .appendQueryParameter("destination", ll(pts.last().lat, pts.last().lng))
            .appendQueryParameter("travelmode", mode)
        if (stops.isNotEmpty()) b.appendQueryParameter("waypoints", stops.joinToString("|") { ll(it.lat, it.lng) })
        return open(ctx, b.build())
    }

    fun openPlace(ctx: Context, lat: Double, lng: Double, label: String? = null): Boolean =
        open(ctx, Uri.parse("geo:${ll(lat, lng)}?q=${ll(lat, lng)}" + (label?.let { "(" + Uri.encode(it) + ")" } ?: "")))

    /** Directions from where you are now to [destination] (a name or address); [start] begins turn-by-turn. */
    fun directions(ctx: Context, destination: String, mode: String, start: Boolean): Boolean {
        val m = when (mode) { "walking" -> "w"; "bicycling" -> "b"; "two-wheeler" -> "l"; else -> "d" }
        if (start) return open(ctx, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=$m"))
        return open(ctx, Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", destination)
            .appendQueryParameter("travelmode", if (mode == "transit") "transit" else mode.ifBlank { "driving" })
            .build())
    }
}
